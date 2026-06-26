package se.jabba.boet.ui.list

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.CategoryEntity
import se.jabba.boet.ui.theme.*

// Reorder, rename, add and remove categories. Reordering teaches the per-list
// store layout (spec: "Store Layout Learning").
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryManageScreen(
    repo: Repository,
    listId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val remote by repo.categories(listId).collectAsState(initial = emptyList())

    // Local working copy so up/down feels instant; committed to the repo on change.
    var order by remember(remote.map { it.id }) { mutableStateOf(remote) }
    var newName by remember { mutableStateOf("") }
    var newIcon by remember { mutableStateOf("label") }
    var renaming by remember { mutableStateOf<CategoryEntity?>(null) }
    var iconEditing by remember { mutableStateOf<CategoryEntity?>(null) }

    fun commit(newOrder: List<CategoryEntity>) {
        order = newOrder
        scope.launch { repo.reorderCategories(listId, newOrder.map { it.id }) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmWhite),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Charcoal)
                    }
                },
                title = { Text("Sortera kategorier", style = BoetType.headline) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Add a category.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                IconPickerButton(
                    icon = newIcon,
                    name = newName.ifBlank { "Ny kategori" },
                    onClick = { iconEditing = CategoryEntity(id = "", listId = listId, name = newName.ifBlank { "Ny kategori" }, icon = newIcon) },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        if (newIcon == "label") newIcon = defaultCategoryIconKey(it)
                    },
                    placeholder = { Text("Ny kategori", color = CharcoalMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            val name = newName.trim()
                            scope.launch { repo.addCategory(listId, name, newIcon) }
                            newName = ""
                            newIcon = "label"
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MossDeep, contentColor = WarmWhite),
                ) { Icon(Icons.Default.Add, contentDescription = "Lägg till kategori") }
            }

            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                itemsIndexed(order, key = { _, c -> c.id }) { index, cat ->
                    Surface(
                        color = WarmWhite,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Stone),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.DragHandle, contentDescription = null, tint = CharcoalMuted)
                            Spacer(Modifier.width(12.dp))
                            IconPickerButton(
                                icon = cat.icon,
                                name = cat.name,
                                onClick = { iconEditing = cat },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(cat.name, style = BoetType.title, color = Charcoal, modifier = Modifier.weight(1f))

                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    val m = order.toMutableList()
                                    m.add(index - 1, m.removeAt(index)); commit(m)
                                },
                            ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Flytta upp", tint = if (index > 0) MossDeep else Stone) }

                            IconButton(
                                enabled = index < order.lastIndex,
                                onClick = {
                                    val m = order.toMutableList()
                                    m.add(index + 1, m.removeAt(index)); commit(m)
                                },
                            ) { Icon(Icons.Default.ArrowDownward, contentDescription = "Flytta ner", tint = if (index < order.lastIndex) MossDeep else Stone) }

                            IconButton(onClick = { renaming = cat }) {
                                Text("Aa", style = BoetType.label, color = MossDeep)
                            }
                            IconButton(onClick = { scope.launch { repo.deleteCategory(cat) } }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = CharcoalMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    renaming?.let { cat ->
        var text by remember { mutableStateOf(cat.name) }
        AlertDialog(
            containerColor = WarmWhite,
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename), style = BoetType.headline) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) scope.launch { repo.renameCategory(cat, text.trim()) }
                    renaming = null
                }) { Text(stringResource(R.string.save), color = MossDeep) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel), color = Charcoal) } },
        )
    }

    iconEditing?.let { cat ->
        IconPickerDialog(
            title = if (cat.id.isBlank()) "Ikon för ny kategori" else cat.name,
            selected = cat.icon ?: defaultCategoryIconKey(cat.name),
            onPick = { icon ->
                if (cat.id.isBlank()) newIcon = icon
                else scope.launch { repo.setCategoryIcon(cat, icon) }
                iconEditing = null
            },
            onDismiss = { iconEditing = null },
        )
    }
}

@Composable
private fun IconPickerButton(
    icon: String?,
    name: String,
    onClick: () -> Unit,
) {
    OutlinedIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = MossDeep),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stone),
    ) {
        Icon(categoryIcon(icon, name), contentDescription = "Välj ikon")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPickerDialog(
    title: String,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = WarmWhite,
        onDismissRequest = onDismiss,
        title = { Text(title, style = BoetType.headline) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CATEGORY_ICON_OPTIONS.forEach { option ->
                    val isSelected = option.key == selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { onPick(option.key) },
                        label = { Text(option.label) },
                        leadingIcon = {
                            Icon(
                                option.image,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Leaf,
                            selectedLabelColor = Charcoal,
                            selectedLeadingIconColor = MossDeep,
                            labelColor = Charcoal,
                            iconColor = MossDeep,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Stone,
                            selectedBorderColor = Moss,
                        ),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Charcoal) }
        },
    )
}
