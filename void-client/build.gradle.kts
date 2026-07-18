import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.google.gson.JsonParser

plugins {
    kotlin("jvm") version "2.0.0"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.voidclient"
version = "0.1.0"

repositories {
    mavenCentral()
    // Fabric-Meta / Fabric-Maven fuer Loader- & Library-Aufloesung zur Laufzeit
    maven("https://maven.fabricmc.net/")
    // HINWEIS: Modrinth betreibt KEIN offizielles Maven-Repository fuer Mod-Jars.
    // Mods (Sodium, Iris, etc.) werden bewusst NICHT als Gradle-Dependency eingebunden,
    // sondern zur Laufzeit ueber die REST-API (api.modrinth.com) pro Instanz heruntergeladen.
    // Das ist der einzige robuste Weg, weil Modrinth-Projekt-Versionen sich staendig aendern
    // und nicht als stabile Maven-Koordinaten (group:artifact:version) veroeffentlicht werden.
}

javafx {
    version = "21.0.2"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics")
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    // java.net.http.HttpClient (JDK-Bordmittel) wird fuer alle REST-Aufrufe genutzt,
    // daher keine zusaetzliche HTTP-Client-Bibliothek noetig.
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.voidclient.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Optionaler Dev-Komfort-Task: laedt die zum Zeitpunkt des Builds aktuellsten
 * Sodium- & Iris-Jars fuer eine Ziel-Minecraft-Version in build/coremods/,
 * damit sie als Default-Bundle in neue Instanzen kopiert werden koennen.
 * Nutzt dieselbe Modrinth-API wie der Launcher selbst zur Laufzeit
 * (siehe ModrinthIntegrationManager) - hier nur als Gradle-Task nachgebaut,
 * damit "gradle stageCoreMods" ohne laufenden Launcher funktioniert.
 */
tasks.register("stageCoreMods") {
    group = "void-client"
    description = "Laedt aktuelle Sodium- & Iris-Jars fuer eine Zielversion nach build/coremods/"

    doLast {
        val gameVersion = (project.findProperty("mcVersion") as String?) ?: "1.21.1"
        val loader = "fabric"
        val outDir = layout.buildDirectory.dir("coremods").get().asFile
        outDir.mkdirs()

        val client = HttpClient.newHttpClient()

        listOf("sodium", "iris").forEach { slug ->
            val versionsUrl =
                "https://api.modrinth.com/v2/project/$slug/version?loaders=[\"$loader\"]&game_versions=[\"$gameVersion\"]"
            val request = HttpRequest.newBuilder(URI.create(versionsUrl))
                .header("User-Agent", "void-client/0.1.0 (build-script)")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                logger.warn("Konnte Versionen fuer '$slug' ($gameVersion) nicht laden: HTTP ${response.statusCode()}")
                return@forEach
            }

            val versions = JsonParser.parseString(response.body()).asJsonArray
            if (versions.isEmpty) {
                logger.warn("Keine passende Version von '$slug' fuer $gameVersion gefunden.")
                return@forEach
            }

            val latest = versions[0].asJsonObject
            val primaryFile = latest.getAsJsonArray("files").firstOrNull { it.asJsonObject.get("primary").asBoolean }
                ?: latest.getAsJsonArray("files").first()
            val fileObj = primaryFile.asJsonObject
            val url = fileObj.get("url").asString
            val filename = fileObj.get("filename").asString

            val target = outDir.resolve(filename)
            client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(target.toPath())
            )
            logger.lifecycle("Gestaged: $filename -> ${target.absolutePath}")
        }
    }
}
