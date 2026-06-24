package se.jabba.boet.ui.list

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
    var completedExpanded by remember { mutableStateOf(false) }
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
        gesturesEnabled = drawerState.isOpen, // open only via the hamburger; no edge-swipe
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
                    val presenceText = otherPresence?.let {
                        if (it.status == "shopping") stringResource(R.string.presence_shopping, it.name ?: "")
                        else stringResource(R.string.presence_viewing, it.name ?: "")
                    }
                    ListHeaderBand(
                        listName = state.list?.name ?: "…",
                        presence = presenceText,
                        bgImageUrl = state.list?.bgImageUrl,
                        serverUrl = serverUrl,
                        blur = state.list?.bgBlur ?: 0,
                        overlay = state.list?.bgOverlay ?: 0,
                        conn = conn,
                        pending = pending,
                        onEditBackground = onOpenListSettings,
                    )
                }
            }
        },
        bottomBar = {
            AddBar(
                language = language,
                onAdd = vm::addItems,
                onSpoken = vm::addSpokenItems,
                onShopping = onOpenShopping,
                onAutoSort = { vm.autoSort() },
            )
        },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
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
                    SwipeToDeleteRow(onDelete = { vm.delete(item) }) {
                        ItemRow(
                            item = item,
                            onToggle = { vm.toggle(item) },
                            onClick = { editing = item },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // Completed items live in a collapsed-by-default section at the bottom.
            if (state.completed.isNotEmpty()) {
                item(key = "completed-header") {
                    CompletedHeader(
                        count = state.completed.size,
                        expanded = completedExpanded,
                        onToggle = { completedExpanded = !completedExpanded },
                    )
                }
                if (completedExpanded) {
                    items(state.completed, key = { it.id }) { item ->
                        SwipeToDeleteRow(onDelete = { vm.delete(item) }) {
                            ItemRow(
                                item = item,
                                onToggle = { vm.toggle(item) },
                                onClick = { editing = item },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
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

// Swipe-left to delete (Microsoft To-Do style): the row tracks the finger and
// reveals a red area with a white trash icon. Deletion is position-based — you
// must deliberately drag past the halfway point; a quick flick that doesn't get
// there springs back. On commit the row slides off and collapses so the delete
// reads clearly rather than vanishing instantly.
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(true) }
    val commitThreshold = 0.5f

    AnimatedVisibility(
        visible = visible,
        exit = shrinkVertically(animationSpec = tween(240)) + fadeOut(animationSpec = tween(180)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) },
        ) {
            val progress = (-offsetX / width).coerceIn(0f, 1f)
            // Red reveal — the trash icon fades and scales in as you drag.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DangerRed)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = Color.White,
                    modifier = Modifier.graphicsLayer {
                        alpha = progress
                        val s = 0.7f + 0.3f * progress
                        scaleX = s; scaleY = s
                    },
                )
            }
            // Foreground row — follows the finger (leftward only).
            Box(
                Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            offsetX = (offsetX + delta).coerceIn(-width, 0f)
                        },
                        onDragStopped = {
                            scope.launch {
                                if (-offsetX >= width * commitThreshold) {
                                    animate(offsetX, -width, animationSpec = tween(200)) { v, _ -> offsetX = v }
                                    visible = false
                                    delay(240)
                                    onDelete()
                                } else {
                                    animate(offsetX, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> offsetX = v }
                                }
                            }
                        },
                    ),
            ) { content() }
        }
    }
}

// Header band at the top of the list. Shows the per-list background image (blur +
// dark overlay) with the list name + presence overlaid; falls back to a Leaf fill.
@Composable
private fun ListHeaderBand(
    listName: String,
    presence: String?,
    bgImageUrl: String?,
    serverUrl: String,
    blur: Int,
    overlay: Int,
    conn: ConnState,
    pending: Int,
    onEditBackground: () -> Unit,
) {
    val hasImage = bgImageUrl != null
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(104.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (hasImage) Charcoal else Leaf),
    ) {
        if (hasImage) {
            AsyncImage(
                model = serverUrl.trimEnd('/') + bgImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur((blur / 100f * 16f).dp),
            )
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.2f + overlay / 100f * 0.5f)))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { SyncChip(conn, pending) }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(listName, style = BoetType.headline, color = if (hasImage) WarmWhite else Charcoal)
            if (presence != null) {
                Text(presence, style = BoetType.body, color = if (hasImage) Stone else MossDeep)
            }
        }
        IconButton(onClick = onEditBackground, modifier = Modifier.align(Alignment.BottomEnd)) {
            Icon(
                Icons.Default.Image,
                contentDescription = stringResource(R.string.background_image),
                tint = if (hasImage) WarmWhite else MossDeep,
            )
        }
    }
}

// Collapsible "Klara" (completed) section header with a count and chevron.
@Composable
fun CompletedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, dark: Boolean = false) {
    val tint = if (dark) Sage else MossDeep
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = tint,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "${stringResource(R.string.completed).uppercase()} · $count",
            style = BoetType.label,
            color = tint,
        )
    }
}

@Composable
fun ItemRow(item: ItemEntity, onToggle: () -> Unit, onClick: () -> Unit, large: Boolean = false) {
    // Shopping-mode rows are transparent so the full-screen background shows through.
    val rowColor = if (large) Color.Transparent else WarmWhite
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
