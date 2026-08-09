package dev.po4yka.lenswake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.po4yka.lenswake.ui.LenswakeApp
import dev.po4yka.lenswake.ui.theme.LenswakeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LenswakeTheme {
                LenswakeApp()
            }
        }
    }
}
