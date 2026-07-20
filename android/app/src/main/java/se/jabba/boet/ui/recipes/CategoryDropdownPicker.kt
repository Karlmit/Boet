package se.jabba.boet.ui.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.local.RecipeCategoryEntity
import se.jabba.boet.ui.theme.*

// Shared "pick an existing recipe category, or add a new one" dropdown for one
// axis (kind "type" or "country") — the menu content and create-dialog are
// identical everywhere a category gets assigned; only the trigger differs:
// CategoryFieldPicker (a form field, RecipeEditorScreen) and CategoryChipPicker
// (a chip, RecipeDetailScreen). onCreateNew round-trips through the server
// (Repository.createRecipeCategory) so the returned id is always real, never
// client-minted, matching how the catalogue is meant to converge across devices.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFieldPicker(
    label: String,
    options: List<RecipeCategoryEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onCreateNew: suspend (String) -> RecipeCategoryEntity?,
) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.id == selectedId }?.name.orEmpty()

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = CharcoalMuted) },
            trailingIcon = {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = CharcoalMuted)
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
            modifier = Modifier.fillMaxWidth(),
        )
        CategoryDropdownMenu(
            expanded = menu,
            options = options,
            selectedId = selectedId,
            allowClear = selectedId != null,
            onDismiss = { menu = false },
            onSelect = { onSelect(it); menu = false },
            onCreateNewClick = { menu = false; createDialog = true },
        )
    }

    if (createDialog) {
        CategoryCreateDialog(
            title = label,
            onDismiss = { createDialog = false },
            onCreate = { name ->
                createDialog = false
                scope.launch { onCreateNew(name)?.let { onSelect(it.id) } }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryChipPicker(
    label: String,
    options: List<RecipeCategoryEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onCreateNew: suspend (String) -> RecipeCategoryEntity?,
) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.id == selectedId }?.name

    Box {
        Surface(
            color = if (selectedName != null) Sage else Leaf,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.clickable { menu = true },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(selectedName ?: label, style = BoetType.label, color = Charcoal)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = CharcoalMuted, modifier = Modifier.size(16.dp))
            }
        }
        CategoryDropdownMenu(
            expanded = menu,
            options = options,
            selectedId = selectedId,
            allowClear = selectedId != null,
            onDismiss = { menu = false },
            onSelect = { onSelect(it); menu = false },
            onCreateNewClick = { menu = false; createDialog = true },
        )
    }

    if (createDialog) {
        CategoryCreateDialog(
            title = label,
            onDismiss = { createDialog = false },
            onCreate = { name ->
                createDialog = false
                scope.launch { onCreateNew(name)?.let { onSelect(it.id) } }
            },
        )
    }
}

@Composable
private fun CategoryDropdownMenu(
    expanded: Boolean,
    options: List<RecipeCategoryEntity>,
    selectedId: String?,
    allowClear: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    onCreateNewClick: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = WarmWhite) {
        if (allowClear) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recipe_category_none), color = CharcoalMuted) },
                onClick = { onSelect(null) },
            )
            HorizontalDivider(color = Stone)
        }
        options.sortedBy { it.name.lowercase() }.forEach { opt ->
            DropdownMenuItem(
                text = { Text(opt.name, color = Charcoal) },
                trailingIcon = { if (opt.id == selectedId) Icon(Icons.Default.Check, contentDescription = null, tint = Moss) },
                onClick = { onSelect(opt.id) },
            )
        }
        HorizontalDivider(color = Stone)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.recipe_category_new), color = MossDeep) },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MossDeep) },
            onClick = onCreateNewClick,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryCreateDialog(title: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        containerColor = WarmWhite,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recipe_category_new_title, title), style = BoetType.headline) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onCreate(text.trim()) }) { Text(stringResource(R.string.save), color = MossDeep) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Charcoal) } },
    )
}
