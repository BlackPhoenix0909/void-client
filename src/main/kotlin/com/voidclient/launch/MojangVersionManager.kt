package com.voidclient.launch

import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Schliesst die Luecke aus dem ersten Grundgerüst: ohne das Vanilla-Client-Jar
 * ist der Launcher kein "Minecraft-Client", sondern nur ein Mod-Manager.
 * Nutzt Mojangs oeffentliches Version-Manifest (keine Auth noetig fuer den Download
 * selbst, nur zum Start des Spiels ist ein gueltiges MinecraftSession-Token noetig).
 */
class MojangVersionManager(
    private val versionsDir: File = File(System.getProperty("user.home"), ".voidclient/versions")
) {
    private val http = HttpClient.newHttpClient()
    private val manifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

    /**
     * Liefert das Client-Jar fuer eine Version, laedt es bei Bedarf herunter.
     * Idempotent: liegt die Datei schon lokal, wird nicht erneut geladen.
     */
    fun ensureClientJar(gameVersion: String): File {
        val targetDir = versionsDir.resolve(gameVersion).apply { mkdirs() }
        val targetJar = targetDir.resolve("client.jar")
        if (targetJar.exists() && targetJar.length() > 0) return targetJar

        val versionMetaUrl = resolveVersionMetaUrl(gameVersion)
            ?: error("Version '$gameVersion' nicht im Mojang-Manifest gefunden.")

        val versionJson = getJson(versionMetaUrl)
        val clientDownload = versionJson
            .getAsJsonObject("downloads")
            .getAsJsonObject("client")
        val url = clientDownload["url"].asString
        // Empfehlenswert: clientDownload["sha1"] gegen die heruntergeladene Datei pruefen.

        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofFile(targetJar.toPath()))
        check(response.statusCode() == 200) { "Client-Jar-Download fehlgeschlagen (HTTP ${response.statusCode()}): $url" }

        // Zusaetzlich hinterlegen wir das rohe Versions-JSON - GameLauncher/Asset-Handling
        // koennen es spaeter fuer Asset-Index & Argument-Templates weiterverwenden.
        targetDir.resolve("version.json").writeText(versionJson.toString())

        return targetJar
    }

    private fun resolveVersionMetaUrl(gameVersion: String): String? {
        val manifest = getJson(manifestUrl)
        val versions = manifest.getAsJsonArray("versions")
        for (v in versions) {
            val obj = v.asJsonObject
            if (obj["id"].asString == gameVersion) return obj["url"].asString
        }
        return null
    }

    private fun getJson(url: String): com.google.gson.JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "GET $url fehlgeschlagen: HTTP ${response.statusCode()}" }
        return JsonParser.parseString(response.body()).asJsonObject
    }
}
