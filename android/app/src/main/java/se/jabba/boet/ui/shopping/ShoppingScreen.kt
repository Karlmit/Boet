package se.jabba.boet.ui.shopping

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.ui.common.CategoryHeader
import se.jabba.boet.ui.list.ItemRow
import se.jabba.boet.ui.list.ListViewModel
import se.jabba.boet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    repo: Repository,
    listId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ListViewModel = viewModel(key = "list-$listId", factory = ListViewModel.factory(repo, listId))
    val state by vm.state.collectAsState()
    var hideCompleted by remember { mutableStateOf(false) }
    var dismissedDone by remember { mutableStateOf(false) }

    // Keep the screen awake while shopping.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo.realtime.sendPresence("shopping", listId)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            repo.realtime.sendPresence("viewing", listId)
        }
    }

    BoetTheme(forceDark = true) {
        Scaffold(
            containerColor = NightBase,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = NightBase, titleContentColor = WarmWhite),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = WarmWhite)
                        }
                    },
                    title = {
                        Column {
                            Text(state.list?.name ?: "", style = BoetType.title, color = WarmWhite)
                            Text(stringResource(R.string.items_left, state.remaining), style = BoetType.body, color = Sage)
                        }
                    },
                    actions = {
                        FilterChip(
                            selected = hideCompleted,
                            onClick = { hideCompleted = !hideCompleted },
                            label = { Text(stringResource(R.string.hide_completed), color = WarmWhite) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MossDeep,
                                containerColor = NightSurface,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Shopping completion suggestion (never auto-removes).
                if (!dismissedDone && state.total > 0 && state.remaining == 0) {
                    DoneSuggestion(
                        onRemove = { vm.clearChecked(); dismissedDone = true },
                        onKeep = { dismissedDone = true },
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    for (section in state.sections) {
                        val visible = if (hideCompleted) section.items.filter { !it.checked } else section.items
                        if (visible.isEmpty()) continue   // collapse empty categories
                        item(key = "sh-h-${section.id}") {
                            CategoryHeader(section.name, color = Sage, modifier = Modifier.padding(top = 16.dp))
                        }
                        items(visible, key = { it.id }) { item ->
                            ItemRow(item = item, onToggle = { vm.toggle(item) }, onClick = { vm.toggle(item) }, large = true)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DoneSuggestion(onRemove: () -> Unit, onKeep: () -> Unit) {
    Surface(color = NightSurface, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.shopping_done), style = BoetType.title, color = WarmWhite)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(onClick = onKeep) { Text(stringResource(R.string.keep_list), color = Sage) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onRemove,
                    colors = ButtonDefaults.buttonColors(containerColor = MossDeep, contentColor = WarmWhite),
                ) { Text(stringResource(R.string.remove_completed)) }
            }
        }
    }
}
