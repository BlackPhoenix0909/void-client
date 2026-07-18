package com.voidclient.ui

import com.voidclient.instance.InstanceManager
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/**
 * Zeigt alle vorhandenen Profile ("Vanilla+", "Tech-Modpack", ...) mit Play-Button.
 * Der eigentliche Start (Auth-Check -> GameLauncher.launch) wird ueber
 * VoidClientServices verdrahtet (siehe ui/VoidClientServices.kt), damit Controller
 * nicht direkt Manager-Instanzen selbst konstruieren.
 */
class HomeController {

    @FXML private lateinit var instanceList: VBox

    private val instanceManager = InstanceManager()

    @FXML
    fun initialize() {
        renderInstances()
    }

    private fun renderInstances() {
        instanceList.children.clear()
        val instances = instanceManager.listInstances()

        if (instances.isEmpty()) {
            instanceList.children.add(Label("Noch keine Profile angelegt. Erstelle eins unter Einstellungen."))
            return
        }

        instances.forEach { instance ->
            val nameLabel = Label(instance.displayName).apply { styleClass.add("instance-name") }
            val metaLabel = Label("${instance.gameVersion} · Fabric ${instance.fabricLoaderVersion} · ${instance.installedMods.size} Mods")
                .apply { styleClass.add("instance-meta") }
            val info = VBox(2.0, nameLabel, metaLabel)

            val playButton = Button("Spielen").apply {
                styleClass.add("btn-primary")
                setOnAction { VoidClientServices.launchInstance(instance) }
            }

            val row = HBox(16.0, info, playButton).apply {
                styleClass.add("instance-row")
                alignment = javafx.geometry.Pos.CENTER_LEFT
                javafx.scene.layout.HBox.setHgrow(info, javafx.scene.layout.Priority.ALWAYS)
            }
            instanceList.children.add(row)
        }
    }
}
