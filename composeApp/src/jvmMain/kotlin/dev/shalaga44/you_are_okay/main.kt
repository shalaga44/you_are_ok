package dev.shalaga44.you_are_okay

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "YouAreOkay",
    ) {
        App()
    }
}