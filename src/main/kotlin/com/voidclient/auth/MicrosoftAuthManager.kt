package com.voidclient.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpRequest.BodyPublishers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Implementiert den kompletten Login-Flow, den jeder Drittanbieter-Launcher braucht:
 *
 *   1. Microsoft OAuth2 Device-Code-Flow (kein eingebetteter Login-Screen noetig,
 *      Nutzer bestaetigt im Browser -> vermeidet Passwort-Handling im Launcher)
 *   2. Tausch des MS-Access-Tokens gegen ein Xbox-Live-Token (XBL)
 *   3. Tausch des XBL-Tokens gegen ein XSTS-Token
 *   4. Login bei den Minecraft Services mit dem XSTS-Token -> Minecraft-Access-Token
 *   5. Abruf des Minecraft-Profils (Username/UUID) + Besitz-Check
 *
 * Referenz: https://minecraft.wiki/w/Microsoft_authentication (Client-ID muss selbst
 * in Azure AD App Registrations angelegt werden - hier als Platzhalter CLIENT_ID).
 */
class MicrosoftAuthManager(
    private val clientId: String = "00000000-0000-0000-0000-000000000000", // TODO: eigene Azure-App-ID eintragen
    private val sessionStorePath: Path = Path.of(System.getProperty("user.home"), ".voidclient", "session.json")
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // ---------------------------------------------------------------------
    // Schritt 1: Device-Code anfordern
    // ---------------------------------------------------------------------
    fun requestDeviceCode(): DeviceCodeInfo {
        val body = "client_id=$clientId&scope=" +
            java.net.URLEncoder.encode("XboxLive.signin offline_access", "UTF-8")

        val request = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Device-Code-Anfrage fehlgeschlagen: HTTP ${response.statusCode()} ${response.body()}" }

        val json = JsonParser.parseString(response.body()).asJsonObject
        return DeviceCodeInfo(
            deviceCode = json["device_code"].asString,
            userCode = json["user_code"].asString,
            verificationUri = json["verification_uri"].asString,
            expiresInSeconds = json["expires_in"].asInt,
            pollIntervalSeconds = json["interval"].asInt
        )
    }

    // ---------------------------------------------------------------------
    // Schritt 1b: Auf Bestaetigung im Browser pollen (asynchron, blockiert nicht die UI)
    // ---------------------------------------------------------------------
    fun pollForToken(deviceCode: DeviceCodeInfo, onStatus: (String) -> Unit): CompletableFuture<AuthResult> {
        return CompletableFuture.supplyAsync {
            val deadline = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L
            var interval = deviceCode.pollIntervalSeconds.coerceAtLeast(2)

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(interval * 1000L)

                val body = "grant_type=urn:ietf:params:oauth:grant-type:device_code" +
                    "&client_id=$clientId&device_code=${deviceCode.deviceCode}"

                val request = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(body))
                    .build()

                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                val json = JsonParser.parseString(response.body()).asJsonObject

                when {
                    response.statusCode() == 200 -> {
                        onStatus("Microsoft-Login erfolgreich, verifiziere Xbox/Minecraft...")
                        return@supplyAsync completeLoginChain(json["access_token"].asString, json["refresh_token"].asString)
                    }
                    json.has("error") && json["error"].asString == "authorization_pending" -> {
                        onStatus("Warte auf Bestaetigung im Browser...")
                    }
                    json.has("error") && json["error"].asString == "slow_down" -> {
                        interval += 5
                    }
                    else -> {
                        return@supplyAsync AuthResult.Failure("Microsoft-Login fehlgeschlagen: ${json.get("error_description")?.asString}")
                    }
                }
            }
            AuthResult.Failure("Device-Code abgelaufen, bitte Login erneut starten.")
        }
    }

    // ---------------------------------------------------------------------
    // Schritte 2-5: XBL -> XSTS -> Minecraft-Login -> Profil
    // ---------------------------------------------------------------------
    private fun completeLoginChain(msAccessToken: String, msRefreshToken: String): AuthResult {
        try {
            val xblToken = authenticateXboxLive(msAccessToken)
            val (xstsToken, userHash) = authenticateXsts(xblToken)
            val mcAccessToken = loginWithXbox(xstsToken, userHash)

            if (!ownsMinecraft(mcAccessToken)) {
                return AuthResult.NoMinecraftEntitlement
            }

            val (uuid, username) = fetchProfile(mcAccessToken)
            val session = MinecraftSession(
                minecraftAccessToken = mcAccessToken,
                uuid = uuid,
                username = username,
                msRefreshToken = msRefreshToken,
                expiresAtEpochMs = System.currentTimeMillis() + 24 * 60 * 60 * 1000L // MC-Tokens sind ~24h gueltig
            )
            persistSession(session)
            return AuthResult.Success(session)
        } catch (e: Exception) {
            return AuthResult.Failure("Login-Kette fehlgeschlagen: ${e.message}", e)
        }
    }

    private fun authenticateXboxLive(msAccessToken: String): String {
        val payload = """
            {
              "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": "d=$msAccessToken"
              },
              "RelyingParty": "http://auth.xboxlive.com",
              "TokenType": "JWT"
            }
        """.trimIndent()

        val response = postJson("https://user.auth.xboxlive.com/user/authenticate", payload)
        return response["Token"].asString
    }

    /** @return Pair(xstsToken, userHash) */
    private fun authenticateXsts(xblToken: String): Pair<String, String> {
        val payload = """
            {
              "Properties": {
                "SandboxId": "RETAIL",
                "UserTokens": ["$xblToken"]
              },
              "RelyingParty": "rp://api.minecraftservices.com/",
              "TokenType": "JWT"
            }
        """.trimIndent()

        val response = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", payload)
        val userHash = response.getAsJsonObject("DisplayClaims")
            .getAsJsonArray("xui")[0].asJsonObject["uhs"].asString
        return response["Token"].asString to userHash
    }

    private fun loginWithXbox(xstsToken: String, userHash: String): String {
        val payload = """{"identityToken": "XBL3.0 x=$userHash;$xstsToken"}"""
        val response = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", payload)
        return response["access_token"].asString
    }

    private fun ownsMinecraft(mcAccessToken: String): Boolean {
        val request = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/entitlements/mcstore"))
            .header("Authorization", "Bearer $mcAccessToken")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return false
        val items = JsonParser.parseString(response.body()).asJsonObject.getAsJsonArray("items")
        return items != null && items.size() > 0
    }

    /** @return Pair(uuid, username) */
    private fun fetchProfile(mcAccessToken: String): Pair<String, String> {
        val request = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
            .header("Authorization", "Bearer $mcAccessToken")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Profil-Abruf fehlgeschlagen: HTTP ${response.statusCode()}" }
        val json = JsonParser.parseString(response.body()).asJsonObject
        return json["id"].asString to json["name"].asString
    }

    private fun postJson(url: String, jsonBody: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(BodyPublishers.ofString(jsonBody))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "POST $url fehlgeschlagen: HTTP ${response.statusCode()} ${response.body()}" }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    // ---------------------------------------------------------------------
    // Session-Persistenz (lokal, damit nicht bei jedem Start neu eingeloggt werden muss)
    // ---------------------------------------------------------------------
    private fun persistSession(session: MinecraftSession) {
        sessionStorePath.parent.toFile().mkdirs()
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        Files.writeString(sessionStorePath, gson.toJson(session))
        // HINWEIS: Fuer eine echte Auslieferung sollte dieses File verschluesselt
        // oder ueber den OS-Keystore (z.B. java.security / DPAPI-Wrapper) gesichert werden,
        // statt Access-/Refresh-Token im Klartext auf der Platte zu speichern.
    }

    fun loadPersistedSession(): MinecraftSession? {
        if (!Files.exists(sessionStorePath)) return null
        return try {
            val json = Files.readString(sessionStorePath)
            com.google.gson.Gson().fromJson(json, MinecraftSession::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /** Erneuert eine abgelaufene Session per Refresh-Token, ohne erneuten Device-Code-Flow. */
    fun refresh(oldSession: MinecraftSession): AuthResult {
        val body = "grant_type=refresh_token&client_id=$clientId&refresh_token=${oldSession.msRefreshToken}"
        val request = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return AuthResult.Failure("Token-Refresh fehlgeschlagen: HTTP ${response.statusCode()}")
        val json = JsonParser.parseString(response.body()).asJsonObject
        return completeLoginChain(json["access_token"].asString, json["refresh_token"].asString)
    }
}
