package com.remindero.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SpaceBlack = Color(0xFF070514)
val SpacePurple = Color(0xFF1B1038)
val Cyan = Color(0xFF00E5FF)
val Magenta = Color(0xFFFF2BD6)
val Danger = Color(0xFFFF3B5C)

@Composable
fun RemindEroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan,
            onPrimary = Color.Black,
            secondary = Magenta,
            background = SpaceBlack,
            surface = SpacePurple,
            onBackground = Color.White,
            onSurface = Color.White,
            error = Danger
        ),
        content = content
    )
}
