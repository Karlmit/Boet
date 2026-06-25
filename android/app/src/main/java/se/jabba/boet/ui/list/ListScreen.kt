package se.jabba.boet.ui.list

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
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
import se.jabba.boet.ui.voice.VoiceSessionSheet

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
    var voiceOpen by remember { mutableStateOf(false) }
    var favoritesOpen by remember { mutableStateOf(false) }
    val favorites by vm.favorites.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var completedExpanded by remember { mutableStateOf(false) }
    // Per-category collapse state; absent = expanded (default).
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Mark presence as "viewing" while this screen is composed.
    LaunchedEffect(listId) {
        repo.bootstrap()
        repo.realtime.sendPresence("viewing", listId)
    }

    // Banner presence line: only when the partner is actively shopping.
    val shoppingPartner = presence.firstOrNull { it.name != identity && it.status == "shopping" }

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
                    val presenceText = shoppingPartner?.let {
                        stringResource(R.string.presence_shopping, it.name ?: "")
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
                        onShopping = onOpenShopping,
                    )
                }
            }
        },
        bottomBar = {
            AddBar(
                language = language,
                onAdd = vm::addItems,
                onOpenVoice = { voiceOpen = true },
                onShowFavorites = { favoritesOpen = true; vm.loadFavorites() },
            )
        },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            for (section in state.sections) {
                if (section.items.isEmpty()) continue
                val key = section.id ?: "orphan"
                val expanded = collapsed[key] != true
                item(key = "cat-$key") {
                    CategoryGroup(
                        name = section.name,
                        items = section.items,
                        expanded = expanded,
                        onToggleExpanded = { collapsed[key] = expanded },
                        onItemToggle = { vm.toggle(it) },
                        onItemClick = { editing = it },
                        onItemDelete = { vm.delete(it) },
                        onReorder = { vm.reorderItems(it) },
                    )
                }
            }

            // Completed items live in a collapsed-by-default group at the bottom.
            if (state.completed.isNotEmpty()) {
                item(key = "completed") {
                    Column(Modifier.padding(top = 14.dp)) {
                        CompletedHeader(
                            count = state.completed.size,
                            expanded = completedExpanded,
                            onToggle = { completedExpanded = !completedExpanded },
                        )
                        AnimatedVisibility(visible = completedExpanded) {
                            GroupedCard {
                                state.completed.forEachIndexed { i, item ->
                                    if (i > 0) GroupDivider()
                                    CompactItemRow(
                                        item = item,
                                        onToggle = { vm.toggle(item) },
                                        onClick = { editing = item },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
      }
    }
    } // ModalNavigationDrawer

    if (voiceOpen) {
        VoiceSessionSheet(
            language = language,
            clean = { transcript -> vm.cleanSpoken(transcript) },
            onConfirm = { items -> vm.addVoiceItems(items) },
            onDismiss = { voiceOpen = false },
        )
    }

    if (favoritesOpen) {
        FavoritesSheet(
            ui = favorites,
            onAdd = { vm.addFavorite(it) },
            onDismiss = { favoritesOpen = false },
        )
    }

    editing?.let { item ->
        ItemEditSheet(
            item = item,
            categories = state.categories,
            onDismiss = { editing = null },
            onSave = { name, qty, note -> vm.edit(item, name, qty, note) },
            onQuantityChange = { vm.setQuantity(item, it) },
            onMove = { vm.move(item, it) },
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
    onShopping: () -> Unit,
) {
    val hasImage = bgImageUrl != null
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(96.dp)
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
        // Top-right stack: the sync pill, with the Shopping entry point right beneath
        // it (moved here off the add bar so the bottom stays focused on adding).
        Column(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SyncChip(conn, pending)
            Spacer(Modifier.height(8.dp))
            BannerShoppingButton(onClick = onShopping)
        }
        // Title raised toward the top so the presence line sits beneath it.
        Column(Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = 16.dp)) {
            Text(listName, style = BoetType.headline, color = if (hasImage) WarmWhite else Charcoal)
            if (presence != null) {
                Text(presence, style = BoetType.body, color = if (hasImage) Stone else MossDeep)
            }
        }
    }
}

// Compact "Handla" pill that lives in the top banner under the sync chip. Scales
// down on press (emilkowalski) so the entry into Shopping Mode feels responsive.
@Composable
private fun BannerShoppingButton(onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "pressShop")
    Surface(
        color = MossDeep,
        shape = RoundedCornerShape(999.dp),
        shadowElevation = 2.dp,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = WarmWhite, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.shopping_mode), style = BoetType.title, color = WarmWhite)
        }
    }
}

// One soft-cornered card holding a category's items, hairline-separated.
@Composable
private fun GroupedCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = WarmWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(color = Stone, thickness = 1.dp, modifier = Modifier.padding(start = 52.dp))
}

// A category: glanceable icon + name + collapse chevron, above one grouped card.
// Items reorder by long-pressing the trailing drag handle.
@Composable
private fun CategoryGroup(
    name: String,
    items: List<ItemEntity>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onItemToggle: (ItemEntity) -> Unit,
    onItemClick: (ItemEntity) -> Unit,
    onItemDelete: (ItemEntity) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Icon(categoryIcon(name), contentDescription = null, tint = MossDeep, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(name.uppercase(), style = BoetType.label, color = MossDeep, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Dölj" else "Visa",
                tint = MossDeep,
                modifier = Modifier.size(22.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            // Key on the full item content (not just ids) so quantity/note/check
            // edits re-render live; a drag only calls onReorder on release, so the
            // underlying `items` is stable for the duration of a drag.
            var order by remember(items) { mutableStateOf(items) }
            var draggingId by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            val rowHeights = remember { mutableStateMapOf<String, Int>() }

            GroupedCard {
                order.forEachIndexed { index, item ->
                    val isDragging = item.id == draggingId
                    if (index > 0) GroupDivider()
                    Box(
                        Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            .onSizeChanged { rowHeights[item.id] = it.height },
                    ) {
                      SwipeToDeleteRow(onDelete = { onItemDelete(item) }) {
                        CompactItemRow(
                            item = item,
                            onToggle = { onItemToggle(item) },
                            onClick = { onItemClick(item) },
                            dragHandle = {
                                // Big, immediate grab target: drag starts the moment
                                // you move the handle (no long-press wait), with a
                                // haptic tick on grab so it's easy and obvious to sort.
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .pointerInput(item.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggingId = item.id; dragOffset = 0f
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDragEnd = { draggingId = null; dragOffset = 0f; onReorder(order.map { it.id }) },
                                                onDragCancel = { draggingId = null; dragOffset = 0f },
                                                onDrag = { change, drag ->
                                                    change.consume()
                                                    dragOffset += drag.y
                                                    val curIdx = order.indexOfFirst { it.id == draggingId }
                                                    val rowH = (rowHeights[item.id] ?: 1).toFloat().coerceAtLeast(1f)
                                                    if (curIdx in 0..order.lastIndex) {
                                                        if (dragOffset > rowH / 2 && curIdx < order.lastIndex) {
                                                            order = order.toMutableList().apply { add(curIdx + 1, removeAt(curIdx)) }
                                                            dragOffset -= rowH
                                                        } else if (dragOffset < -rowH / 2 && curIdx > 0) {
                                                            order = order.toMutableList().apply { add(curIdx - 1, removeAt(curIdx)) }
                                                            dragOffset += rowH
                                                        }
                                                    }
                                                },
                                            )
                                        },
                                ) {
                                    Icon(
                                        Icons.Default.DragHandle,
                                        contentDescription = "Sortera",
                                        tint = if (isDragging) MossDeep else CharcoalMuted,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            },
                        )
                      }
                    }
                }
            }
        }
    }
}

// Compact list row used inside grouped cards: checkbox · name · qty · drag handle.
@Composable
private fun CompactItemRow(
    item: ItemEntity,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(WarmWhite)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
    ) {
        BoetCheckbox(checked = item.checked, onToggle = onToggle)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = BoetType.title,
                color = if (item.checked) CharcoalMuted else Charcoal,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
            )
            if (!item.note.isNullOrBlank()) {
                Text(item.note, style = BoetType.body, color = CharcoalMuted)
            }
        }
        if (!item.quantity.isNullOrBlank()) {
            QuantityBadge(item.quantity)
            Spacer(Modifier.width(6.dp))
        }
        if (item.favorite) {
            Icon(Icons.Default.Star, contentDescription = stringResource(R.string.favorite), tint = Sage, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (dragHandle != null) dragHandle() else Spacer(Modifier.width(8.dp))
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

// Checkbox with deliberate, satisfying feedback (emilkowalski): it scales down on
// press so it feels heard, the fill springs in with a subtle pop when it flips on,
// the checkmark scales/fades in (never from scale 0), and a haptic confirms the tap.
@Composable
fun BoetCheckbox(checked: Boolean, onToggle: () -> Unit, large: Boolean = false) {
    val size = if (large) 34 else 26
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh), label = "press",
    )
    // Overshoots past 1 then settles — the "pop" that makes checking feel purposeful.
    val checkScale by animateFloatAsState(
        if (checked) 1f else 0.8f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow), label = "check",
    )
    val fill by animateColorAsState(if (checked) Moss else Color.Transparent, tween(160), label = "fill")
    val borderColor by animateColorAsState(if (checked) Moss else Stone, tween(160), label = "border")

    Box(
        modifier = Modifier
            .size(size.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .border(BorderStroke(2.dp, borderColor), RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Check, contentDescription = null, tint = WarmWhite,
            modifier = Modifier
                .size((size - 8).dp)
                .graphicsLayer {
                    alpha = ((checkScale - 0.8f) / 0.2f).coerceIn(0f, 1f)
                    scaleX = checkScale; scaleY = checkScale
                },
        )
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
