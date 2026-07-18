package com.voidclient.ui

import com.voidclient.mods.InstallResult
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import kotlin.concurrent.thread

/**
 * Schritt B der Anforderung, sichtbar in der UI: Suchfeld -> Modrinth-Suche ->
 * Ergebniskarten mit "Installieren"-Button, der installMod() gegen die aktuell
 * ausgewaehlte Instanz aufruft.
 */
class ModsController {

    @FXML private lateinit var searchField: TextField
    @FXML private lateinit var searchButton: Button
    @FXML private lateinit var resultsPane: FlowPane
    @FXML private lateinit var statusLabel: Label

    private val modManager = VoidClientServices.modManager
    private val instanceManager = VoidClientServices.instanceManager

    // Fuer die Blaupause: fest gewaehlte aktive Instanz (in der echten UI kommt das
    // aus einem Dropdown/Profil-Switcher im Home-View).
    private val activeGameVersion = "1.21.1"

    @FXML
    fun initialize() {
        searchButton.setOnAction { runSearch() }
        searchField.setOnAction { runSearch() }
    }

    private fun runSearch() {
        val query = searchField.text.orEmpty()
        statusLabel.text = "Suche nach \"$query\" ..."
        resultsPane.children.clear()

        thread(isDaemon = true) {
            val results = try {
                modManager.searchMods(query, activeGameVersion)
            } catch (e: Exception) {
                Platform.runLater { statusLabel.text = "Suche fehlgeschlagen: ${e.message}" }
                return@thread
            }

            Platform.runLater {
                statusLabel.text = "${results.size} Treffer"
                results.forEach { result ->
                    val title = Label(result.title).apply { styleClass.add("mod-title") }
                    val desc = Label(result.description).apply {
                        styleClass.add("mod-desc")
                        isWrapText = true
                        maxWidth = 200.0
                    }
                    val installBtn = Button("Installieren").apply {
                        styleClass.add("btn-install")
                        setOnAction { installMod(result.projectId, result.slug) }
                    }
                    val card = VBox(6.0, title, desc, installBtn).apply {
                        styleClass.add("mod-card")
                        prefWidth = 220.0
                    }
                    resultsPane.children.add(card)
                }
            }
        }
    }

    private fun installMod(projectId: String, slugForLog: String) {
        val instances = instanceManager.listInstances()
        val targetInstance = instances.firstOrNull() ?: run {
            statusLabel.text = "Kein Profil vorhanden - erst unter Einstellungen anlegen."
            return
        }

        statusLabel.text = "Installiere $slugForLog in '${targetInstance.displayName}' ..."
        thread(isDaemon = true) {
            val version = modManager.getLatestVersion(projectId, activeGameVersion)
            if (version == null) {
                Platform.runLater { statusLabel.text = "Keine passende Version fuer ${targetInstance.gameVersion} gefunden." }
                return@thread
            }

            val result = modManager.installMod(
                version,
                instanceManager.modsDir(targetInstance.id),
                activeGameVersion
            )

            Platform.runLater {
                when (result) {
                    is InstallResult.Success -> {
                        statusLabel.text = "Installiert: ${result.installedFiles.joinToString(", ")}"
                        targetInstance.installedMods.add(
                            com.voidclient.instance.InstalledMod(
                                projectId = version.projectId,
                                versionId = version.versionId,
                                fileName = result.installedFiles.firstOrNull() ?: "",
                                slug = slugForLog
                            )
                        )
                        instanceManager.saveInstance(targetInstance)
                    }
                    is InstallResult.Failure -> statusLabel.text = "Fehler: ${result.reason}"
                }
            }
        }
    }
}
