package se.jabba.boet.ui.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.ui.graphics.vector.ImageVector

// A small glanceable icon per default grocery category; custom categories fall
// back to a neutral label tag.
fun categoryIcon(name: String): ImageVector = when (name.lowercase().trim()) {
    "frukt & grönt" -> Icons.Default.Eco
    "bröd" -> Icons.Default.BakeryDining
    "mejeri" -> Icons.Default.Egg
    "kött & fisk" -> Icons.Default.SetMeal
    "frys" -> Icons.Default.AcUnit
    "torrvaror" -> Icons.Default.Grain
    "snacks" -> Icons.Default.Cookie
    "hushåll" -> Icons.Default.CleaningServices
    "övrigt" -> Icons.Default.MoreHoriz
    else -> Icons.Default.Label
}
