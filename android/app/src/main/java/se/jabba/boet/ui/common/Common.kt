package se.jabba.boet.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.jabba.boet.R
import se.jabba.boet.data.remote.ConnState
import se.jabba.boet.ui.theme.*

// The "Boet" wordmark — the only place the serif appears (The One-Serif Rule).
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text("Boet", style = BoetType.wordmark, color = MossDeep, modifier = modifier)
}

// A circular Moss avatar with the member's initial.
@Composable
fun Avatar(name: String?, size: Int = 36) {
    Surface(color = Moss, shape = CircleShape, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                (name?.firstOrNull() ?: '?').uppercase(),
                color = WarmWhite,
                style = BoetType.title,
            )
        }
    }
}

// Pill primary action button (Moss Deep), optional leading icon.
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MossDeep, contentColor = WarmWhite),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        modifier = modifier,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = BoetType.title, fontWeight = FontWeight.SemiBold)
    }
}

// Category eyebrow header — uppercase Label in Moss Deep.
@Composable
fun CategoryHeader(name: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MossDeep) {
    Text(
        name.uppercase(),
        style = BoetType.label,
        color = color,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

// "Watch on YouTube" link — shown on a MealDB meal preview (MealDetailScreen) and
// carried through to the imported recipe's own detail view (RecipeDetailScreen),
// so the same tappable row/style is used in both places rather than drifting.
@Composable
fun YoutubeLinkRow(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
    ) {
        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MossDeep, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.recipe_youtube_link), style = BoetType.label, color = MossDeep)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MossDeep, modifier = Modifier.size(14.dp))
    }
}

// "View original recipe" link — shown on a recipe imported via URL scrape
// (`RecipeDoc.sourceUrl`, set from the scraped page's own URL), so the user can
// jump back to the source. Same tappable row style as `YoutubeLinkRow`.
@Composable
fun SourceLinkRow(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
    ) {
        Icon(Icons.Default.Language, contentDescription = null, tint = MossDeep, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.recipe_source_link), style = BoetType.label, color = MossDeep)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MossDeep, modifier = Modifier.size(14.dp))
    }
}

// Small status chip pairing icon + text (never colour alone).
@Composable
fun SyncChip(state: ConnState, pending: Int, onClick: (() -> Unit)? = null) {
    val (label, dot) = when {
        state == ConnState.CONNECTED && pending == 0 -> "Synkad" to Moss
        state == ConnState.CONNECTING -> "Synkar…" to Sage
        pending > 0 -> "$pending väntar" to Sage
        else -> "Offline" to CharcoalMuted
    }
    Surface(
        color = Leaf,
        shape = RoundedCornerShape(999.dp),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Charcoal)
        }
    }
}
