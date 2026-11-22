package com.writeright.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** WordPop visual identity: mint, thick ink borders, solid shadows. */
object Brand {
    val Mint = Color(0xFF2FE0B5)
    val MintSoft = Color(0xFFDDF7EF)
    val Ink = Color(0xFF10211C)
    val Paper = Color(0xFFFFFFFF)
    val Cream = Color(0xFFF2FFFA)
    val Yellow = Color(0xFFFFD84D)
    val YellowSoft = Color(0xFFFFF4C2)
    val Coral = Color(0xFFD92D3A)
    val CoralSoft = Color(0xFFFFE5E3)
    val CoralDeep = Color(0xFF7A1018)
    val GreenInk = Color(0xFF0A7F61)
    val Muted = Color(0xFF46605A)
    val RuleLine = Color(0xFFCFE9E0)
}

/** A box with a thick ink border and a solid offset shadow. */
fun Modifier.brandBox(
    bg: Color,
    radius: Dp,
    shadow: Dp = 3.dp,
    border: Dp = 3.dp,
): Modifier = this
    .drawBehind {
        if (shadow.value > 0f) {
            drawRoundRect(
                color = Brand.Ink,
                topLeft = Offset(shadow.toPx(), shadow.toPx()),
                size = size,
                cornerRadius = CornerRadius(radius.toPx()),
            )
        }
    }
    .background(bg, RoundedCornerShape(radius))
    .border(border, Brand.Ink, RoundedCornerShape(radius))

@Composable
fun WriteRightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand.Ink,
            onPrimary = Brand.Mint,
            secondary = Brand.Mint,
            background = Brand.Paper,
            surface = Brand.Cream,
            onSurface = Brand.Ink,
            onBackground = Brand.Ink,
        ),
        content = content,
    )
}
