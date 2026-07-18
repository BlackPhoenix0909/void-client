package com.voidclient.instance

/**
 * Ein "Profil" im Sinne des Lastenhefts (z.B. "Vanilla+", "Tech-Modpack", "Admin-Tools").
 * Jede Instanz hat ihr eigenes Verzeichnis mit eigenem mods/-Ordner, eigener
 * Fabric-/MC-Version und eigenen installierten Mods.
 */
data class Instance(
    val id: String,
    var displayName: String,
    var gameVersion: String,
    var fabricLoaderVersion: String,
    var iconKey: String = "default",
    var installedMods: MutableList<InstalledMod> = mutableListOf(),
    var extraJvmArgs: MutableList<String> = mutableListOf(),
    var lastPlayedEpochMs: Long = 0L
)

data class InstalledMod(
    val projectId: String,
    val versionId: String,
    val fileName: String,
    val slug: String
)
