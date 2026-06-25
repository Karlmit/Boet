@file:OptIn(ExperimentalTextApi::class)

package se.jabba.boet.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import se.jabba.boet.R

// Manrope is a variable font; derive weights via the wght axis (API 26+).
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(
    manrope(400),
    manrope(500),
    manrope(600),
    manrope(700),
)

// Cormorant Garamond — reserved for the "Boet" wordmark only (The One-Serif Rule).
val Cormorant = FontFamily(
    Font(R.font.cormorant_garamond_medium, weight = FontWeight.Medium),
)

object BoetType {
    val wordmark = TextStyle(
        fontFamily = Cormorant, fontWeight = FontWeight.Medium,
        fontSize = 34.sp, lineHeight = 34.sp,
    )
    val headline = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.01).em,
    )
    val title = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 23.sp,
    )
    val body = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    )
    val label = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em,
    )
    // Shopping Mode type — intentionally ~20% smaller than before (28→22sp) so more
    // of the list fits on screen and rows stay compact while shopping.
    val shopping = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em,
    )
}
