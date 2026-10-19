package com.scanpdf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.scanpdf.app.ui.CameraScreen
import com.scanpdf.app.ui.EditScreen
import com.scanpdf.app.ui.theme.ScanPdfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScanPdfTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Camera : Screen
    data class Edit(val startIndex: Int) : Screen
}

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }

    when (val current = screen) {
        is Screen.Camera -> CameraScreen(
            onOpenEditor = { index -> screen = Screen.Edit(index) }
        )

        is Screen.Edit -> EditScreen(
            startIndex = current.startIndex,
            onBack = { screen = Screen.Camera }
        )
    }
}
