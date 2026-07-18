package com.voidclient.auth

/** Wird waehrend des Device-Code-Flows an die UI gegeben, damit sie Code + URL anzeigen kann. */
data class DeviceCodeInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int
)

/** Ergebnis eines erfolgreichen Logins - das, was der GameLauncher zum Start braucht. */
data class MinecraftSession(
    val minecraftAccessToken: String,
    val uuid: String,
    val username: String,
    val msRefreshToken: String,
    val expiresAtEpochMs: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtEpochMs
}

sealed class AuthResult {
    data class Success(val session: MinecraftSession) : AuthResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : AuthResult()
    /** Kein Minecraft-Besitz auf dem Account (z.B. Game-Pass-Sonderfaelle, kein Kauf). */
    data object NoMinecraftEntitlement : AuthResult()
}
