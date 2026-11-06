package com.chesslens.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chesslens.app.ui.ChessLensApp
import com.chesslens.app.ui.theme.ChessLensTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ChessLensTheme { ChessLensApp() }
        }
    }
}
