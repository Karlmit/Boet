package se.jabba.boet.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.ListEntity
import se.jabba.boet.ui.common.PrimaryButton
import se.jabba.boet.ui.common.Wordmark
import se.jabba.boet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    repo: Repository,
    onOpenList: (String) -> Unit,
    onBack: () -> Unit,
) {
    val lists by repo.allLists().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showNew by remember { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.lists_title), style = BoetType.headline) },
                actions = { Wordmark(Modifier.padding(end = 16.dp)) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNew = true }, containerColor = MossDeep, contentColor = WarmWhite) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_list))
            }
        },
    ) { padding ->
        val active = lists.filter { !it.archived }
        val archived = lists.filter { it.archived }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            items(active, key = { it.id }) { list ->
                ListCard(list, onOpenList) { scope.launch { repo.setArchived(list, true) } }
                Spacer(Modifier.height(10.dp))
            }
            if (archived.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.archived_lists).uppercase(),
                        style = BoetType.label, color = MossDeep,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                items(archived, key = { it.id }) { list ->
                    ListCard(list, onOpenList, archived = true) { scope.launch { repo.setArchived(list, false) } }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    if (showNew) {
        NewListDialog(
            onDismiss = { showNew = false },
            onCreate = { name, kind, prompt ->
                scope.launch {
                    val id = repo.createList(name, kind, prompt)
                    repo.bootstrap()
                    showNew = false
                    onOpenList(id)
                }
            },
        )
    }
}

@Composable
private fun ListCard(list: ListEntity, onOpen: (String) -> Unit, archived: Boolean = false, onToggleArchive: () -> Unit) {
    Surface(
        color = if (archived) Stone else WarmWhite,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth().clickable { onOpen(list.id) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(list.name, style = BoetType.title, color = Charcoal)
                Text(
                    if (list.kind == "grocery") "Matlista" else "Lista",
                    style = BoetType.body, color = CharcoalMuted,
                )
            }
            IconButton(onClick = onToggleArchive) {
                Icon(
                    if (archived) Icons.Default.Unarchive else Icons.Default.Archive,
                    contentDescription = if (archived) stringResource(R.string.restore) else stringResource(R.string.archive),
                    tint = MossDeep,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewListDialog(onDismiss: () -> Unit, onCreate: (String, String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var grocery by remember { mutableStateOf(true) }
    var prompt by remember { mutableStateOf("") }

    AlertDialog(
        containerColor = WarmWhite,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_list), style = BoetType.headline) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("Namn", color = CharcoalMuted) },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = grocery, onClick = { grocery = true }, label = { Text("Matlista") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Moss, selectedLabelColor = WarmWhite))
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !grocery, onClick = { grocery = false }, label = { Text("Annan") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Moss, selectedLabelColor = WarmWhite))
                }
                if (!grocery) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it },
                        placeholder = { Text("Beskriv sortering, t.ex. dokument, kläder, toalett…", color = CharcoalMuted) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(stringResource(R.string.add), enabled = name.isNotBlank(), onClick = {
                onCreate(name.trim(), if (grocery) "grocery" else "custom", prompt.trim().ifBlank { null })
            })
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Charcoal) } },
    )
}
