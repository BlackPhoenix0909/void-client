package com.voidclient.ui

import javafx.animation.FadeTransition
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.animation.AnimationTimer
import javafx.util.Duration
import kotlin.random.Random

/**
 * Steuert die App-Shell: linke Navigation + rechter Inhaltsbereich (StackPane),
 * in den die einzelnen Views (Home/Mods/Settings) mit Fade-Transition geladen werden -
 * die im Lastenheft geforderten "Fade-In/Fade-Out Effekte beim Wechsel der Menüs".
 */
class MainController {

    @FXML private lateinit var contentPane: StackPane
    @FXML private lateinit var splashCanvas: Canvas
    @FXML private lateinit var splashOverlay: StackPane

    private var currentView: Node? = null

    @FXML
    fun initialize() {
        showView("/fxml/home.fxml")
    }

    @FXML fun onHomeClicked() = showView("/fxml/home.fxml")
    @FXML fun onModsClicked() = showView("/fxml/mods.fxml")
    @FXML fun onSettingsClicked() = showView("/fxml/settings.fxml")

    private fun showView(fxmlPath: String) {
        val loader = FXMLLoader(javaClass.getResource(fxmlPath))
        val newView = loader.load<Node>()
        newView.opacity = 0.0

        if (currentView != null) {
            val fadeOut = FadeTransition(Duration.millis(180.0), currentView)
            fadeOut.toValue = 0.0
            fadeOut.setOnFinished {
                contentPane.children.remove(currentView)
                contentPane.children.add(newView)
                fadeIn(newView)
            }
            fadeOut.play()
        } else {
            contentPane.children.add(newView)
            fadeIn(newView)
        }
        currentView = newView
    }

    private fun fadeIn(node: Node) {
        val fadeIn = FadeTransition(Duration.millis(220.0), node)
        fadeIn.fromValue = 0.0
        fadeIn.toValue = 1.0
        fadeIn.play()
    }

    // -----------------------------------------------------------------
    // Start-Animation: Partikel formen sich zum Void-Logo (siehe Lastenheft 3.)
    // Technisch simpler Ansatz: Canvas + AnimationTimer, jedes Partikel bewegt
    // sich per Lerp von einer Zufallsposition zu einem Zielpunkt auf einer
    // vordefinierten Punktwolke, die die Form des Logos nachbildet.
    // -----------------------------------------------------------------
    fun playSplashSequence() {
        val gc = splashCanvas.graphicsContext2D
        val width = splashCanvas.width
        val height = splashCanvas.height
        val targets = buildLogoPoints(140, width, height)
        val particles = targets.map {
            Particle(
                x = Random.nextDouble(0.0, width),
                y = Random.nextDouble(0.0, height),
                targetX = it.first,
                targetY = it.second
            )
        }

        var frame = 0
        val timer = object : AnimationTimer() {
            override fun handle(now: Long) {
                gc.setFill(Color.rgb(10, 10, 10, 0.35))
                gc.fillRect(0.0, 0.0, width, height)
                particles.forEach { p ->
                    p.x += (p.targetX - p.x) * 0.06
                    p.y += (p.targetY - p.y) * 0.06
                    gc.fill = Color.web("#b800ff")
                    gc.fillOval(p.x, p.y, 3.0, 3.0)
                }
                frame++
                if (frame > 140) {
                    stop()
                    val fadeOut = FadeTransition(Duration.millis(600.0), splashOverlay)
                    fadeOut.toValue = 0.0
                    fadeOut.setOnFinished { splashOverlay.isVisible = false }
                    fadeOut.play()
                }
            }
        }
        timer.start()
    }

    /** Simple V-Form als Punktwolke - fuer ein echtes Logo durch SVG-Pfad-Sampling ersetzen. */
    private fun buildLogoPoints(count: Int, width: Double, height: Double): List<Pair<Double, Double>> {
        val cx = width / 2
        val cy = height / 2
        return (0 until count).map { i ->
            val t = i.toDouble() / count
            if (t < 0.5) cx - 70 + t * 2 * 70 to cy - 80 + t * 2 * 160
            else cx + (t - 0.5) * 2 * 70 to cy + 80 - (t - 0.5) * 2 * 160
        }
    }

    private data class Particle(var x: Double, var y: Double, val targetX: Double, val targetY: Double)
}
