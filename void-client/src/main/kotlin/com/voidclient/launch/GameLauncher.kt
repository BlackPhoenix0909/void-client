package com.voidclient.launch

import com.google.gson.JsonParser
import com.voidclient.auth.MinecraftSession
import com.voidclient.instance.Instance
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Schritt zwischen Launcher-UI und laufendem Spiel: laedt das Fabric-Loader-Profil
 * (Fabric-Meta-API), stellt sicher, dass alle Libraries lokal vorhanden sind, baut
 * den Classpath zusammen und startet Minecraft als eigenstaendigen Subprozess -
 * genau wie Prism/MultiMC/Lunar es tun. Der Launcher-Prozess selbst (JavaFX) bleibt
 * dabei unangetastet weiterlaufen.
 */
class GameLauncher(
    private val librariesDir: File = File(System.getProperty("user.home"), ".voidclient/libraries"),
    private val versionsDir: File = File(System.getProperty("user.home"), ".voidclient/versions")
) {
    private val http = HttpClient.newHttpClient()

    /** Fabric-Meta liefert das komplette Launch-Profil (Main-Class, Libraries, Argumente). */
    private fun fetchFabricProfile(gameVersion: String, loaderVersion: String): FabricProfile {
        val url = "https://meta.fabricmc.net/v2/versions/loader/$gameVersion/$loaderVersion/profile/json"
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Fabric-Profil nicht gefunden fuer $gameVersion/$loaderVersion" }

        val json = JsonParser.parseString(response.body()).asJsonObject
        val mainClass = json["mainClass"].asString
        val libs = json.getAsJsonArray("libraries").map { it.asJsonObject }.map { lib ->
            val name = lib["name"].asString // group:artifact:version
            val repoUrl = lib["url"]?.asString ?: "https://maven.fabricmc.net/"
            LibraryRef(name, repoUrl)
        }
        return FabricProfile(mainClass, libs)
    }

    private fun libraryToPath(coord: String): String {
        val (group, artifact, version) = coord.split(":")
        return "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar"
    }

    private fun ensureLibrary(lib: LibraryRef): File {
        val relativePath = libraryToPath(lib.mavenCoordinate)
        val target = librariesDir.resolve(relativePath)
        if (!target.exists()) {
            target.parentFile.mkdirs()
            val url = lib.repoBaseUrl.trimEnd('/') + "/" + relativePath
            val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
            check(response.statusCode() == 200) { "Library-Download fehlgeschlagen: $url" }
        }
        return target
    }

    /**
     * Startet die Instanz. Gibt den laufenden Prozess zurueck, damit die UI z.B.
     * Log-Output live in ein Konsolen-Panel streamen oder auf Beenden reagieren kann.
     */
    fun launch(instance: Instance, session: MinecraftSession, vanillaClientJar: File): Process {
        val profile = fetchFabricProfile(instance.gameVersion, instance.fabricLoaderVersion)
        val libraryFiles = profile.libraries.map { ensureLibrary(it) }

        val classpath = (libraryFiles + vanillaClientJar)
            .joinToString(File.pathSeparator) { it.absolutePath }

        val instanceDir = File(System.getProperty("user.home"), ".voidclient/instances/${instance.id}")

        val command = mutableListOf(
            "java",
            "-Xmx4G"
        )
        command += instance.extraJvmArgs
        command += listOf(
            "-cp", classpath,
            profile.mainClass,
            "--username", session.username,
            "--uuid", session.uuid,
            "--accessToken", session.minecraftAccessToken,
            "--version", instance.displayName,
            "--gameDir", instanceDir.absolutePath,
            "--assetsDir", File(System.getProperty("user.home"), ".voidclient/assets").absolutePath
        )

        val builder = ProcessBuilder(command)
            .directory(instanceDir)
            .redirectErrorStream(true)

        return builder.start()
        // Hinweis: Asset-Index-Download, Native-Libraries-Extraktion (LWJGL-Natives) und
        // vollstaendige Vanilla-Jar-Beschaffung ueber die Mojang Version-Manifest-API
        // sind fuer die Blaupause bewusst ausgeklammert - das ist der Teil, den z.B.
        // die "minecraft-launcher-lib"-Referenzimplementierungen im Detail zeigen.
    }

    private data class FabricProfile(val mainClass: String, val libraries: List<LibraryRef>)
    private data class LibraryRef(val mavenCoordinate: String, val repoBaseUrl: String)
}
