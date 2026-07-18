package com.voidclient

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.stage.StageStyle

class VoidClientApp : Application() {
    override fun start(primaryStage: Stage) {
        val loader = FXMLLoader(javaClass.getResource("/fxml/main.fxml"))
        val root = loader.load<javafx.scene.Parent>()

        val scene = Scene(root, 1100.0, 700.0)
        scene.stylesheets.add(javaClass.getResource("/css/void-theme.css")!!.toExternalForm())

        primaryStage.title = "Void Client"
        primaryStage.initStyle(StageStyle.DECORATED) // TRANSPARENT waere fuer eigenes Fenster-Chrome noetig
        primaryStage.scene = scene
        primaryStage.minWidth = 900.0
        primaryStage.minHeight = 600.0
        primaryStage.show()

        val controller = loader.getController<com.voidclient.ui.MainController>()
        controller.playSplashSequence()
    }
}

fun main() {
    Application.launch(VoidClientApp::class.java)
}
