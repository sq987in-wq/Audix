package com.candela.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFFF4EFE6)
private val Gold = Color(0xFFC49A4A)
private val Bg = Color(0xFF0A0908)
private val Card = Color(0xFF1A1714)

private val Scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Bg,
    background = Bg,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    secondary = Gold,
    onSecondary = Bg,
    error = Color(0xFFC45C3E),
)

@Composable
fun CandelaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
