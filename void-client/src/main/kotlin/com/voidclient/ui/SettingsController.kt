package com.voidclient.ui

import com.voidclient.auth.AuthResult
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import kotlin.concurrent.thread

/**
 * Zeigt den Device-Code-Login: Nutzer bekommt Code + URL angezeigt, die UI
 * pollt im Hintergrund, bis der Login im Browser bestaetigt wurde.
 */
class SettingsController {

    @FXML private lateinit var accountStatusLabel: Label
    @FXML private lateinit var loginButton: Button

    private val authManager = VoidClientServices.authManager

    @FXML
    fun initialize() {
        val existing = authManager.loadPersistedSession()
        accountStatusLabel.text = if (existing != null) {
            "Angemeldet als ${existing.username}"
        } else {
            "Nicht angemeldet"
        }
    }

    @FXML
    fun onLoginClicked() {
        loginButton.isDisable = true
        accountStatusLabel.text = "Starte Login..."

        thread(isDaemon = true) {
            val deviceCode = authManager.requestDeviceCode()

            Platform.runLater {
                accountStatusLabel.text =
                    "Öffne ${deviceCode.verificationUri} im Browser und gib den Code ein: ${deviceCode.userCode}"
            }

            authManager.pollForToken(deviceCode) { status ->
                Platform.runLater { accountStatusLabel.text = status }
            }.thenAccept { result ->
                Platform.runLater {
                    loginButton.isDisable = false
                    when (result) {
                        is AuthResult.Success -> accountStatusLabel.text = "Angemeldet als ${result.session.username}"
                        is AuthResult.NoMinecraftEntitlement -> accountStatusLabel.text = "Dieses Konto besitzt kein Minecraft."
                        is AuthResult.Failure -> accountStatusLabel.text = "Login fehlgeschlagen: ${result.reason}"
                    }
                }
            }
        }
    }
}
