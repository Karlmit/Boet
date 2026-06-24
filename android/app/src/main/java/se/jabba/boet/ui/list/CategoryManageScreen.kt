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
@OptIn(ExperimentalMaterial3Api::class)
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
    var renaming by remember { mutableStateOf<CategoryEntity?>(null) }

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
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Ny kategori", color = CharcoalMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (newName.isNotBlank()) { scope.launch { repo.addCategory(listId, newName.trim()) }; newName = "" }
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
}
