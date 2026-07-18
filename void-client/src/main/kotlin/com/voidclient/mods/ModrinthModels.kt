package com.voidclient.mods

/** Ein Suchtreffer aus /v2/search - reicht fuer die Listendarstellung in der UI. */
data class ModSearchResult(
    val projectId: String,
    val slug: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val downloads: Int,
    val categories: List<String>
)

/** Eine konkrete Version eines Mods - das, was tatsaechlich installiert wird. */
data class ModVersion(
    val versionId: String,
    val projectId: String,
    val versionNumber: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val files: List<ModFile>,
    val dependencies: List<ModDependency>
)

data class ModFile(
    val url: String,
    val filename: String,
    val primary: Boolean,
    val sha1: String
)

data class ModDependency(
    val projectId: String?,
    val versionId: String?,
    val dependencyType: String // "required", "optional", "incompatible", "embedded"
)

sealed class InstallResult {
    data class Success(val installedFiles: List<String>) : InstallResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : InstallResult()
}
