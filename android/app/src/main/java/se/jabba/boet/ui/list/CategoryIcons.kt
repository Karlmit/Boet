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

data class CategoryIconOption(
    val key: String,
    val label: String,
    val image: ImageVector,
)

val CATEGORY_ICON_OPTIONS = listOf(
    CategoryIconOption("leaf", "Grönt", Icons.Default.Eco),
    CategoryIconOption("bread", "Bröd", Icons.Default.BakeryDining),
    CategoryIconOption("dairy", "Mejeri", Icons.Default.Egg),
    CategoryIconOption("protein", "Kött", Icons.Default.SetMeal),
    CategoryIconOption("frozen", "Frys", Icons.Default.AcUnit),
    CategoryIconOption("pantry", "Torrt", Icons.Default.Grain),
    CategoryIconOption("snacks", "Snacks", Icons.Default.Cookie),
    CategoryIconOption("home", "Hem", Icons.Default.CleaningServices),
    CategoryIconOption("other", "Övrigt", Icons.Default.MoreHoriz),
    CategoryIconOption("label", "Egen", Icons.Default.Label),
)

fun defaultCategoryIconKey(name: String): String = when (name.lowercase().trim()) {
    "frukt & grönt" -> "leaf"
    "bröd" -> "bread"
    "mejeri" -> "dairy"
    "kött & fisk" -> "protein"
    "frys" -> "frozen"
    "torrvaror" -> "pantry"
    "snacks" -> "snacks"
    "hushåll" -> "home"
    "övrigt" -> "other"
    else -> "label"
}

// A small glanceable icon per default grocery category; custom categories fall
// back to a neutral label tag.
fun categoryIcon(icon: String?, name: String): ImageVector {
    val key = icon?.takeIf { it.isNotBlank() } ?: defaultCategoryIconKey(name)
    return CATEGORY_ICON_OPTIONS.firstOrNull { it.key == key }?.image ?: Icons.Default.Label
}

fun categoryIcon(name: String): ImageVector = categoryIcon(null, name)
