package se.jabba.boet.ui.list

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.data.remote.ConnState
import se.jabba.boet.ui.common.*
import se.jabba.boet.ui.theme.*
import se.jabba.boet.ui.voice.VoiceRecognizer
import se.jabba.boet.ui.voice.parseSpokenItems

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    repo: Repository,
    listId: String,
    identity: String?,
    language: String,
    serverUrl: String,
    onOpenLists: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenShopping: () -> Unit,
    onOpenRecipe: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenListSettings: () -> Unit,
    onSelectList: (String) -> Unit,
) {
    val vm: ListViewModel = viewModel(
        key = "list-$listId",
        factory = ListViewModel.factory(repo, listId),
    )
    val state by vm.state.collectAsState()
    val presence by repo.presence.collectAsState()
    val conn by repo.realtime.state.collectAsState()
    val pending by repo.pendingCount().collectAsState(initial = 0)
    val allLists by repo.activeLists().collectAsState(initial = emptyList())

    var editing by remember { mutableStateOf<ItemEntity?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Mark presence as "viewing" while this screen is composed.
    LaunchedEffect(listId) {
        repo.bootstrap()
        repo.realtime.sendPresence("viewing", listId)
    }

    val otherPresence = presence.firstOrNull { it.name != identity }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ListsDrawer(
                lists = allLists,
                currentId = listId,
                onSelect = { scope.launch { drawerState.close() }; onSelectList(it) },
                onManage = { scope.launch { drawerState.close() }; onOpenLists() },
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = WarmWhite) {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmWhite),
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.lists_title), tint = Charcoal)
                            }
                        },
                        title = { Wordmark() },
                        actions = {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Mer", tint = Charcoal)
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Sortera kategorier") },
                                        onClick = { menuOpen = false; onOpenCategories() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.background_image)) },
                                        onClick = { menuOpen = false; onOpenListSettings() },
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(state.list?.name ?: "…", style = BoetType.headline)
                            val presenceText = otherPresence?.let {
                                if (it.status == "shopping") stringResource(R.string.presence_shopping, it.name ?: "")
                                else stringResource(R.string.presence_viewing, it.name ?: "")
                            }
                            if (presenceText != null) {
                                Text(presenceText, style = BoetType.body, color = MossDeep)
                            }
                        }
                        SyncChip(conn, pending)
                    }
                }
            }
        },
        bottomBar = {
            AddBar(
                language = language,
                onAdd = vm::addItems,
                onSpoken = vm::addSpokenItems,
                onShopping = onOpenShopping,
                onRecipe = onOpenRecipe,
            )
        },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        // Shared per-list background image with blur + dark overlay for readability.
        val bg = state.list?.bgImageUrl
        if (bg != null) {
            val blur = ((state.list?.bgBlur ?: 0) / 100f * 24f).dp
            AsyncImage(
                model = serverUrl.trimEnd('/') + bg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(blur),
            )
            Box(
                Modifier.matchParentSize().background(
                    androidx.compose.ui.graphics.Color.Black.copy(alpha = (state.list?.bgOverlay ?: 0) / 100f * 0.7f)
                )
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            for (section in state.sections) {
                if (section.items.isEmpty()) continue
                item(key = "h-${section.id}") {
                    CategoryHeader(section.name, modifier = Modifier.padding(top = 12.dp))
                }
                items(section.items, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        onToggle = { vm.toggle(item) },
                        onClick = { editing = item },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
      }
    }
    } // ModalNavigationDrawer

    editing?.let { item ->
        ItemEditSheet(
            item = item,
            onDismiss = { editing = null },
            onSave = { name, qty, note -> vm.edit(item, name, qty, note); editing = null },
            onDelete = { vm.delete(item); editing = null },
            onFavorite = { vm.toggleFavorite(item) },
        )
    }
}

// Slide-in navigation drawer: the household's lists with a settings cog at the
// lower-left.
@Composable
private fun ListsDrawer(
    lists: List<se.jabba.boet.data.local.ListEntity>,
    currentId: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = WarmWhite) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(24.dp))
            Wordmark(Modifier.padding(start = 16.dp, bottom = 4.dp))
            CategoryHeader(stringResource(R.string.lists_title), modifier = Modifier.padding(start = 16.dp))
            Spacer(Modifier.height(4.dp))

            lists.forEach { list ->
                NavigationDrawerItem(
                    label = { Text(list.name, style = BoetType.title) },
                    selected = list.id == currentId,
                    onClick = { onSelect(list.id) },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = Leaf,
                        unselectedContainerColor = WarmWhite,
                        selectedTextColor = Charcoal,
                        unselectedTextColor = Charcoal,
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }

            NavigationDrawerItem(
                label = { Text(stringResource(R.string.manage_lists), style = BoetType.body, color = MossDeep) },
                icon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MossDeep) },
                selected = false,
                onClick = onManage,
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = WarmWhite),
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )

            Spacer(Modifier.weight(1f))

            // Settings cog, lower-left.
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings), tint = MossDeep)
                }
                Text(stringResource(R.string.settings), style = BoetType.body, color = MossDeep)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun ItemRow(item: ItemEntity, onToggle: () -> Unit, onClick: () -> Unit, large: Boolean = false) {
    val rowColor = if (large) NightSurface else WarmWhite
    val textColor = when {
        item.checked && large -> Stone
        item.checked -> CharcoalMuted
        large -> WarmWhite
        else -> Charcoal
    }
    Surface(
        color = rowColor,
        shape = RoundedCornerShape(14.dp),
        border = if (large) null else androidx.compose.foundation.BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (large) 18.dp else 14.dp),
        ) {
            BoetCheckbox(checked = item.checked, onToggle = onToggle, large = large)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = if (large) BoetType.shopping else BoetType.title,
                    color = textColor,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                )
                if (!item.note.isNullOrBlank()) {
                    Text(item.note, style = BoetType.body, color = if (large) Stone else CharcoalMuted)
                }
            }
            if (!item.quantity.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                QuantityBadge(item.quantity)
            }
            if (item.favorite) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.favorite), tint = Sage, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun BoetCheckbox(checked: Boolean, onToggle: () -> Unit, large: Boolean = false) {
    val size = if (large) 36 else 24
    Surface(
        color = if (checked) Moss else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = if (checked) null else androidx.compose.foundation.BorderStroke(2.dp, Stone),
        modifier = Modifier.size(size.dp).clickable(onClick = onToggle),
    ) {
        if (checked) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, tint = WarmWhite, modifier = Modifier.size((size - 8).dp))
            }
        }
    }
}

@Composable
fun QuantityBadge(text: String) {
    Surface(color = Stone, shape = RoundedCornerShape(8.dp)) {
        Text(
            "×$text",
            style = BoetType.body,
            color = Charcoal,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
