package com.voidclient.ui

import com.voidclient.auth.AuthResult
import com.voidclient.auth.MicrosoftAuthManager
import com.voidclient.instance.Instance
import com.voidclient.instance.InstanceManager
import com.voidclient.launch.GameLauncher
import com.voidclient.launch.MojangVersionManager
import com.voidclient.mods.ModrinthIntegrationManager
import java.io.File

/**
 * Einfacher Service-Locator statt vollem DI-Framework (Koin/Dagger waeren fuer die
 * spaetere Ausbaustufe sinnvoll, fuer das Grundgerüst reicht das hier).
 * Haelt genau eine Instanz jedes Managers, damit z.B. Login-Status ueber alle
 * Views hinweg konsistent bleibt.
 */
object VoidClientServices {
    val authManager = MicrosoftAuthManager()
    val instanceManager = InstanceManager()
    val modManager = ModrinthIntegrationManager()
    val gameLauncher = GameLauncher()
    val versionManager = MojangVersionManager()

    fun launchInstance(instance: Instance) {
        val session = authManager.loadPersistedSession()
            ?: run {
                // TODO: UI-Dialog anzeigen, der zum Login (Device-Code-Flow) auffordert,
                // bevor ueberhaupt versucht wird zu starten.
                println("Kein aktives Konto - bitte zuerst unter Einstellungen anmelden.")
                return
            }

        val activeSession = if (session.isExpired()) {
            when (val refreshed = authManager.refresh(session)) {
                is AuthResult.Success -> refreshed.session
                else -> {
                    println("Session-Refresh fehlgeschlagen, bitte erneut anmelden.")
                    return
                }
            }
        } else session

        Thread {
            try {
                // Laedt das Vanilla-Client-Jar bei Bedarf einmalig herunter (Mojang
                // Version-Manifest) - damit verhaelt sich der Launcher wie ein echter
                // Minecraft-Client und nicht nur wie ein Mod-Installer.
                val clientJar = versionManager.ensureClientJar(instance.gameVersion)
                val process = gameLauncher.launch(instance, activeSession, clientJar)
                instance.lastPlayedEpochMs = System.currentTimeMillis()
                instanceManager.saveInstance(instance)
                process.waitFor()
            } catch (e: Exception) {
                println("Start fehlgeschlagen: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }
}
