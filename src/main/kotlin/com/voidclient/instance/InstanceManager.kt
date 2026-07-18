package com.voidclient.instance

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Verwaltet alle lokalen Profile ("Instanzen"). Jede Instanz lebt in einem eigenen
 * Verzeichnis unter ~/.voidclient/instances/<id>/ mit eigenem mods/-Ordner, sodass
 * Mods aus Profil "Tech-Modpack" niemals mit "Vanilla+" kollidieren.
 *
 * Layout:
 *   ~/.voidclient/instances/<id>/instance.json   <- Metadaten (siehe Instance.kt)
 *   ~/.voidclient/instances/<id>/mods/           <- installierte .jar-Dateien
 *   ~/.voidclient/instances/<id>/config/         <- Mod-Configs
 *   ~/.voidclient/instances/<id>/saves/          <- Welten (nur falls Singleplayer genutzt)
 */
class InstanceManager(
    private val rootDir: Path = Path.of(System.getProperty("user.home"), ".voidclient", "instances")
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    init {
        rootDir.toFile().mkdirs()
    }

    fun listInstances(): List<Instance> {
        val dir = rootDir.toFile()
        val subDirs = dir.listFiles { f -> f.isDirectory } ?: emptyArray()
        return subDirs.mapNotNull { d -> loadInstance(d.name) }
    }

    fun loadInstance(id: String): Instance? {
        val file = metadataFile(id)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), Instance::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun createInstance(
        displayName: String,
        gameVersion: String,
        fabricLoaderVersion: String,
        withCoreMods: Boolean = true
    ): Instance {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val instance = Instance(
            id = id,
            displayName = displayName,
            gameVersion = gameVersion,
            fabricLoaderVersion = fabricLoaderVersion
        )
        modsDir(id).mkdirs()
        configDir(id).mkdirs()
        savesDir(id).mkdirs()
        saveInstance(instance)
        return instance
        // Hinweis: "withCoreMods" (Sodium/Iris automatisch mitinstallieren) wird vom
        // aufrufenden Code ueber ModrinthIntegrationManager.installMod(...) erledigt,
        // damit die Download-Logik an einer zentralen Stelle bleibt.
    }

    fun saveInstance(instance: Instance) {
        metadataFile(instance.id).writeText(gson.toJson(instance))
    }

    fun deleteInstance(id: String) {
        instanceDir(id).deleteRecursively()
    }

    fun instanceDir(id: String): File = rootDir.resolve(id).toFile()
    fun modsDir(id: String): File = instanceDir(id).resolve("mods")
    fun configDir(id: String): File = instanceDir(id).resolve("config")
    fun savesDir(id: String): File = instanceDir(id).resolve("saves")
    private fun metadataFile(id: String): File = instanceDir(id).resolve("instance.json")
}
