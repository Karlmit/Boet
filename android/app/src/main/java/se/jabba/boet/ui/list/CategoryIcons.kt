package se.jabba.boet.ui.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Plumbing
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.ui.graphics.vector.ImageVector

data class CategoryIconOption(
    val key: String,
    val label: String,
    val image: ImageVector,
)

data class CategoryIconGroup(
    val title: String,
    val options: List<CategoryIconOption>,
)

val CATEGORY_ICON_GROUPS = listOf(
    CategoryIconGroup(
        "Mat & dryck",
        listOf(
            CategoryIconOption("leaf", "Grönt", Icons.Default.Eco),
            CategoryIconOption("bread", "Bröd", Icons.Default.BakeryDining),
            CategoryIconOption("dairy", "Mejeri", Icons.Default.Egg),
            CategoryIconOption("protein", "Kött", Icons.Default.SetMeal),
            CategoryIconOption("frozen", "Frys", Icons.Default.AcUnit),
            CategoryIconOption("pantry", "Torrt", Icons.Default.Grain),
            CategoryIconOption("snacks", "Snacks", Icons.Default.Cookie),
            CategoryIconOption("beverages", "Dryck", Icons.Default.LocalDrink),
            CategoryIconOption("coffee", "Kaffe", Icons.Default.Coffee),
            CategoryIconOption("restaurant", "Mat", Icons.Default.Restaurant),
        ),
    ),
    CategoryIconGroup(
        "Hem & ärenden",
        listOf(
            CategoryIconOption("home", "Hushåll", Icons.Default.CleaningServices),
            CategoryIconOption("furniture", "Möbler", Icons.Default.Chair),
            CategoryIconOption("living", "Vardagsrum", Icons.Default.Weekend),
            CategoryIconOption("kitchen", "Kök", Icons.Default.Kitchen),
            CategoryIconOption("plants", "Växter", Icons.Default.LocalFlorist),
            CategoryIconOption("pets", "Husdjur", Icons.Default.Pets),
            CategoryIconOption("pharmacy", "Apotek", Icons.Default.Medication),
            CategoryIconOption("shopping", "Inköp", Icons.Default.ShoppingCart),
        ),
    ),
    CategoryIconGroup(
        "Bygg & teknik",
        listOf(
            CategoryIconOption("hardware", "Järnhandel", Icons.Default.Build),
            CategoryIconOption("tools", "Verktyg", Icons.Default.Build),
            CategoryIconOption("plumbing", "VVS", Icons.Default.Plumbing),
            CategoryIconOption("electrical", "El", Icons.Default.ElectricalServices),
            CategoryIconOption("electronics", "Elektronik", Icons.Default.Devices),
            CategoryIconOption("lighting", "Belysning", Icons.Default.Lightbulb),
            CategoryIconOption("car", "Bil", Icons.Default.DirectionsCar),
            CategoryIconOption("games", "Spel", Icons.Default.SportsEsports),
        ),
    ),
    CategoryIconGroup(
        "Annat",
        listOf(
            CategoryIconOption("other", "Övrigt", Icons.Default.MoreHoriz),
            CategoryIconOption("label", "Egen", Icons.Default.Label),
        ),
    ),
)

val CATEGORY_ICON_OPTIONS = CATEGORY_ICON_GROUPS.flatMap { it.options }

fun defaultCategoryIconKey(name: String): String = when (name.lowercase().trim()) {
    "frukt & grönt" -> "leaf"
    "bröd" -> "bread"
    "mejeri" -> "dairy"
    "kött & fisk" -> "protein"
    "frys" -> "frozen"
    "torrvaror" -> "pantry"
    "snacks" -> "snacks"
    "dryck", "drycker", "dricka", "läsk", "öl & vin" -> "beverages"
    "kaffe", "te" -> "coffee"
    "hushåll" -> "home"
    "möbler", "ikea" -> "furniture"
    "järnhandel", "bygg", "byggvaror" -> "hardware"
    "verktyg" -> "tools"
    "el", "elektronik", "teknik" -> "electronics"
    "belysning", "lampor" -> "lighting"
    "vvs", "rör" -> "plumbing"
    "kök" -> "kitchen"
    "apotek", "medicin" -> "pharmacy"
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
