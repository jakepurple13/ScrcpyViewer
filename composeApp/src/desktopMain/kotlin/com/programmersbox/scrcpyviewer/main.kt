package com.programmersbox.scrcpyviewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val adbRepo: AdbRepo = remember { AdbRepo() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "ScrcpyViewer",
    ) {
        App(adbRepo)
    }

    adbRepo.logsFromScrcpy.entries.forEach {
        Window(
            onCloseRequest = { adbRepo.logsFromScrcpy.remove(it.key) },
            title = it.key.deviceInfo,
        ) {
            MaterialTheme(darkColorScheme()) {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState(0))
                    ) {
                        it.value.forEach {
                            Text(it)
                        }
                    }
                }
            }
        }
    }
}