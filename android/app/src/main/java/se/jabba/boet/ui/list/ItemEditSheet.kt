package se.jabba.boet.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.jabba.boet.R
import se.jabba.boet.ai.UNITS
import se.jabba.boet.ai.composeQuantity
import se.jabba.boet.ai.formatNumber
import se.jabba.boet.ai.parseQuantity
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
    onQuantityChange: (String?) -> Unit,
    onMove: (String) -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    // Quantity is a number plus an optional unit. With no unit it's a plain count
    // (stepper, 1 = "no quantity" / no badge). With a unit (kg, g, …) it's a
    // measure whose number can be typed. Seed from the stored string so a measure
    // isn't silently wiped on open.
    val initialAmount = remember(item.id) { parseQuantity(item.quantity) }
    var qtyValue by remember { mutableStateOf(initialAmount.value) }
    var qtyUnit by remember { mutableStateOf(initialAmount.unit) }
    var qtyText by remember { mutableStateOf(formatNumber(initialAmount.value)) }
    var note by remember { mutableStateOf(item.note ?: "") }
    var fav by remember { mutableStateOf(item.favorite) }
    // Selected category persists immediately on tap (autosave), so a correction
    // syncs to the other device and teaches the household KB right away.
    var categoryId by remember { mutableStateOf(item.categoryId) }

    // Persist name/note (keeps the existing name if blanked), then close.
    fun saveAndDismiss() {
        onSave(name.trim().ifBlank { item.name }, composeQuantity(qtyValue, qtyUnit), note.trim().ifBlank { null })
        onDismiss()
    }

    // Push the composed quantity live (autosave) so the badge updates behind the sheet.
    fun emitQuantity() = onQuantityChange(composeQuantity(qtyValue, qtyUnit))

    fun step(delta: Int) {
        qtyValue = (qtyValue + delta).coerceAtLeast(1.0)
        if (qtyUnit == null) qtyValue = qtyValue.toLong().toDouble() // counts stay whole
        qtyText = formatNumber(qtyValue)
        emitQuantity()
    }

    fun selectUnit(u: String?) {
        qtyUnit = u
        if (u == null) qtyValue = qtyValue.toLong().coerceAtLeast(1L).toDouble()
        qtyText = formatNumber(qtyValue)
        emitQuantity()
    }

    fun typeAmount(t: String) {
        qtyText = t
        t.replace(',', '.').toDoubleOrNull()?.let { if (it > 0) { qtyValue = it; emitQuantity() } }
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
            // Saves immediately so the badge updates live behind the sheet.
            QuantityControl(
                value = qtyValue,
                unit = qtyUnit,
                text = qtyText,
                onStep = ::step,
                onTextChange = ::typeAmount,
                onUnitChange = ::selectUnit,
            )
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

// Quantity as a +/- stepper plus a unit-chip row. With no unit ("st") it's a
// plain count exactly as before (floors at 1; 1 reads as no badge). Pick a unit
// (kg, g, …) and the number becomes a tappable field for precise amounts (250 g).
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantityControl(
    value: Double,
    unit: String?,
    text: String,
    onStep: (Int) -> Unit,
    onTextChange: (String) -> Unit,
    onUnitChange: (String?) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = WarmWhite,
        border = androidx.compose.foundation.BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.quantity), style = BoetType.title, color = Charcoal, modifier = Modifier.weight(1f))
                StepButton(Icons.Default.Remove, enabled = value > 1) { if (value > 1) onStep(-1) }
                if (unit == null) {
                    Text(
                        formatNumber(value),
                        style = BoetType.headline,
                        color = Charcoal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 48.dp),
                    )
                } else {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = BoetType.headline.copy(color = Charcoal, textAlign = TextAlign.Center),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.widthIn(min = 48.dp).width(64.dp),
                    )
                }
                StepButton(Icons.Default.Add, enabled = true) { onStep(+1) }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategoryChip(stringResource(R.string.unit_none), selected = unit == null) { onUnitChange(null) }
                UNITS.forEach { u ->
                    CategoryChip(u, selected = unit == u) { onUnitChange(u) }
                }
            }
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
