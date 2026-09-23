package com.example.feedreader

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.compose.multiselectable.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

import org.jetbrains.kotlin.android.*

class MainActivity : AppCompatActivity() {
    private val _themeMode = mutableStateOf<ThemeMode>(ThemeMode.TWITTER) // Default: Twitter list style
    val themeMode: ThemeMode = _themeMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FeedScreen(
                themeMode = themeMode,
                onThemeChange = { themeMode.value = it }
            )
        }
    }
}

// Theme enum for the two UI styles
sealed class ThemeMode {
    object TWITTER : ThemeMode() // List view style (Twitter-like)
    object XIAOHONGSHU : ThemeMode() // Card grid style (Xiaohongshu-like)
}
