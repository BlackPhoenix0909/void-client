package com.voidclient.mods

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Schritt B des Lastenhefts: Zugriff auf die Modrinth-API zum Suchen und Installieren
 * von Mods/Shaderpacks. Modrinth-API-Dokumentation: https://docs.modrinth.com/
 *
 * WICHTIG: Modrinth verlangt einen aussagekraeftigen User-Agent-Header mit Kontaktinfo,
 * sonst kann es zu Rate-Limiting/Ablehnung kommen (siehe Modrinth API-Richtlinien).
 */
class ModrinthIntegrationManager(
    private val userAgent: String = "void-client/0.1.0 (https://example.invalid/void-client)"
) {
    private val baseUrl = "https://api.modrinth.com/v2"
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // ---------------------------------------------------------------------
    // Suche
    // ---------------------------------------------------------------------

    /**
     * Durchsucht Modrinth nach Mods/Shaderpacks fuer eine bestimmte Spielversion.
     * facets steuert serverseitige Filter, siehe https://docs.modrinth.com/api/operations/searchprojects/
     */
    fun searchMods(
        query: String,
        gameVersion: String,
        loader: String = "fabric",
        projectType: String = "mod", // oder "shader"
        limit: Int = 20
    ): List<ModSearchResult> {
        val facets = buildString {
            append("[")
            append("[\"project_type:$projectType\"],")
            append("[\"versions:$gameVersion\"],")
            append("[\"categories:$loader\"]")
            append("]")
        }

        val url = "$baseUrl/search" +
            "?query=${URLEncoder.encode(query, "UTF-8")}" +
            "&facets=${URLEncoder.encode(facets, "UTF-8")}" +
            "&limit=$limit"

        val json = getJson(url)
        val hits = json.getAsJsonArray("hits") ?: JsonArray()

        return hits.map { el ->
            val o = el.asJsonObject
            ModSearchResult(
                projectId = o["project_id"].asString,
                slug = o["slug"].asString,
                title = o["title"].asString,
                description = o["description"].asString,
                iconUrl = o["icon_url"]?.takeIf { !it.isJsonNull }?.asString,
                downloads = o["downloads"].asInt,
                categories = o.getAsJsonArray("categories")?.map { it.asString } ?: emptyList()
            )
        }
    }

    // ---------------------------------------------------------------------
    // Versionen eines Projekts abrufen (noetig, um konkrete .jar-URL zu bekommen)
    // ---------------------------------------------------------------------

    fun getVersions(projectIdOrSlug: String, gameVersion: String, loader: String = "fabric"): List<ModVersion> {
        val loadersParam = URLEncoder.encode("[\"$loader\"]", "UTF-8")
        val versionsParam = URLEncoder.encode("[\"$gameVersion\"]", "UTF-8")
        val url = "$baseUrl/project/$projectIdOrSlug/version?loaders=$loadersParam&game_versions=$versionsParam"

        val json = getJsonArray(url)
        return json.map { parseVersion(it.asJsonObject) }
    }

    fun getLatestVersion(projectIdOrSlug: String, gameVersion: String, loader: String = "fabric"): ModVersion? =
        getVersions(projectIdOrSlug, gameVersion, loader).firstOrNull()

    private fun parseVersion(o: JsonObject): ModVersion {
        val files = o.getAsJsonArray("files").map { f ->
            val fo = f.asJsonObject
            ModFile(
                url = fo["url"].asString,
                filename = fo["filename"].asString,
                primary = fo["primary"].asBoolean,
                sha1 = fo.getAsJsonObject("hashes")?.get("sha1")?.asString ?: ""
            )
        }
        val deps = (o.getAsJsonArray("dependencies") ?: JsonArray()).map { d ->
            val dobj = d.asJsonObject
            ModDependency(
                projectId = dobj["project_id"]?.takeIf { !it.isJsonNull }?.asString,
                versionId = dobj["version_id"]?.takeIf { !it.isJsonNull }?.asString,
                dependencyType = dobj["dependency_type"].asString
            )
        }
        return ModVersion(
            versionId = o["id"].asString,
            projectId = o["project_id"].asString,
            versionNumber = o["version_number"].asString,
            gameVersions = o.getAsJsonArray("game_versions").map { it.asString },
            loaders = o.getAsJsonArray("loaders").map { it.asString },
            files = files,
            dependencies = deps
        )
    }

    // ---------------------------------------------------------------------
    // Installation (Schritt B, Kernmethode) - inkl. rekursiver Aufloesung
    // von "required"-Abhaengigkeiten (z.B. Iris braucht ggf. eine Fabric-API-Lib).
    // ---------------------------------------------------------------------

    fun installMod(
        modVersion: ModVersion,
        targetModsDir: File,
        gameVersion: String,
        loader: String = "fabric",
        alreadyInstalledProjectIds: MutableSet<String> = mutableSetOf()
    ): InstallResult {
        val installed = mutableListOf<String>()
        try {
            targetModsDir.mkdirs()
            downloadPrimaryFile(modVersion, targetModsDir)?.let { installed.add(it) }
            alreadyInstalledProjectIds.add(modVersion.projectId)

            // Abhaengigkeiten auflösen: nur "required", und nur wenn nicht schon installiert.
            modVersion.dependencies
                .filter { it.dependencyType == "required" }
                .forEach { dep ->
                    val depProjectId = dep.projectId
                    if (depProjectId != null && depProjectId !in alreadyInstalledProjectIds) {
                        val depVersion = if (dep.versionId != null) {
                            getVersionById(dep.versionId)
                        } else {
                            getLatestVersion(depProjectId, gameVersion, loader)
                        }
                        if (depVersion != null) {
                            val depResult = installMod(
                                depVersion, targetModsDir, gameVersion, loader, alreadyInstalledProjectIds
                            )
                            if (depResult is InstallResult.Success) installed.addAll(depResult.installedFiles)
                        }
                    }
                }

            return InstallResult.Success(installed)
        } catch (e: Exception) {
            return InstallResult.Failure("Installation von ${modVersion.projectId} fehlgeschlagen: ${e.message}", e)
        }
    }

    private fun downloadPrimaryFile(modVersion: ModVersion, targetDir: File): String? {
        val file = modVersion.files.firstOrNull { it.primary } ?: modVersion.files.firstOrNull() ?: return null
        val targetFile = targetDir.resolve(file.filename)

        val request = HttpRequest.newBuilder(URI.create(file.url))
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofFile(targetFile.toPath()))
        check(response.statusCode() == 200) { "Download fehlgeschlagen (HTTP ${response.statusCode()}): ${file.url}" }

        // Empfehlenswert: SHA1 gegen file.sha1 verifizieren, bevor die Datei als gueltig gilt.
        return file.filename
    }

    private fun getVersionById(versionId: String): ModVersion {
        val json = getJson("$baseUrl/version/$versionId")
        return parseVersion(json)
    }

    // ---------------------------------------------------------------------
    // HTTP-Hilfsfunktionen
    // ---------------------------------------------------------------------

    private fun getJson(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "GET $url fehlgeschlagen: HTTP ${response.statusCode()} ${response.body()}" }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun getJsonArray(url: String): JsonArray {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "GET $url fehlgeschlagen: HTTP ${response.statusCode()} ${response.body()}" }
        return JsonParser.parseString(response.body()).asJsonArray
    }
}
