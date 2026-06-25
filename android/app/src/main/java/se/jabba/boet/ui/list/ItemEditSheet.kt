package se.jabba.boet.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.jabba.boet.R
import se.jabba.boet.data.local.CategoryEntity
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.ui.theme.*

// The edit sheet autosaves: the quantity stepper persists on every tap, the
// category persists the moment you pick it, and the name/note persist when the
// sheet is closed. There is no Save button.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemEditSheet(
    item: ItemEntity,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?) -> Unit,
    onQuantityChange: (Int) -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    // Quantity is a simple count adjusted with the stepper; 1 is the default and
    // is stored as "no quantity" (no ×N badge in the list).
    var qty by remember { mutableStateOf(item.quantity?.toIntOrNull()?.coerceAtLeast(1) ?: 1) }
    var note by remember { mutableStateOf(item.note ?: "") }
    var fav by remember { mutableStateOf(item.favorite) }
    // Selected category persists immediately on tap (autosave), so a correction
    // syncs to the other device and teaches the household KB right away.
    var categoryId by remember { mutableStateOf(item.categoryId) }

    // Persist name/note (keeps the existing name if blanked), then close.
    fun saveAndDismiss() {
        onSave(name.trim().ifBlank { item.name }, if (qty > 1) qty.toString() else null, note.trim().ifBlank { null })
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { saveAndDismiss() }, containerColor = WarmWhite) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.add), style = BoetType.headline, modifier = Modifier.weight(1f))
                IconButton(onClick = { fav = !fav; onFavorite() }) {
                    Icon(
                        if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(R.string.favorite),
                        tint = if (fav) Sage else CharcoalMuted,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            field(name, { name = it }, stringResource(R.string.add_item_hint))
            Spacer(Modifier.height(12.dp))
            // Stepper saves immediately so the ×N badge updates live behind the sheet.
            QuantityStepper(qty, onChange = { qty = it; onQuantityChange(it) })
            Spacer(Modifier.height(12.dp))
            field(note, { note = it }, stringResource(R.string.note))
            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.category), style = BoetType.label, color = MossDeep)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { cat ->
                        CategoryChip(cat.name, selected = cat.id == categoryId) {
                            if (cat.id != categoryId) { categoryId = cat.id; onMove(cat.id) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = onDelete,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Charcoal),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.delete))
            }
        }
    }
}

// A selectable category pill. Tonal Leaf when unselected, solid Moss when chosen.
@Composable
private fun CategoryChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) Moss else Leaf,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            name,
            style = BoetType.body,
            color = if (selected) WarmWhite else MossDeep,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// Quantity as a +/- stepper rather than free text. Floors at 1; 1 reads as the
// plain item with no quantity badge.
@Composable
private fun QuantityStepper(value: Int, onChange: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = WarmWhite,
        border = androidx.compose.foundation.BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.quantity), style = BoetType.title, color = Charcoal, modifier = Modifier.weight(1f))
            StepButton(Icons.Default.Remove, enabled = value > 1) { if (value > 1) onChange(value - 1) }
            Text(
                value.toString(),
                style = BoetType.headline,
                color = Charcoal,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 48.dp),
            )
            StepButton(Icons.Default.Add, enabled = true) { onChange(value + 1) }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (enabled) Leaf else Stone,
        modifier = Modifier.size(40.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (enabled) MossDeep else CharcoalMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun field(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(label, color = CharcoalMuted) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Moss,
            unfocusedBorderColor = Stone,
            focusedContainerColor = WarmWhite,
            unfocusedContainerColor = WarmWhite,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
