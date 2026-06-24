package se.jabba.boet.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.jabba.boet.R
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.ui.common.PrimaryButton
import se.jabba.boet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditSheet(
    item: ItemEntity,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?) -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var qty by remember { mutableStateOf(item.quantity ?: "") }
    var note by remember { mutableStateOf(item.note ?: "") }
    var fav by remember { mutableStateOf(item.favorite) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WarmWhite) {
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
            field(qty, { qty = it }, stringResource(R.string.quantity))
            Spacer(Modifier.height(12.dp))
            field(note, { note = it }, stringResource(R.string.note))
            Spacer(Modifier.height(20.dp))
            Row {
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Charcoal),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete))
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.save), onClick = {
                    onSave(name.trim(), qty.trim().ifBlank { null }, note.trim().ifBlank { null })
                })
            }
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
