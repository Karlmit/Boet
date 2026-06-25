package se.jabba.boet.ui.shopping

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.data.local.Prefs
import se.jabba.boet.ui.common.CategoryHeader
import se.jabba.boet.ui.list.BoetCheckbox
import se.jabba.boet.ui.list.CompletedHeader
import se.jabba.boet.ui.list.QuantityBadge
import se.jabba.boet.ui.list.ListViewModel
import se.jabba.boet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    repo: Repository,
    listId: String,
    serverUrl: String,
    prefs: Prefs,
    initialHideCompleted: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: ListViewModel = viewModel(key = "list-$listId", factory = ListViewModel.factory(repo, listId))
    val state by vm.state.collectAsState()
    // "Dölj klara" — remembers the user's last choice (persisted in Prefs) so the
    // list opens the way they last viewed it.
    var hideCompleted by remember { mutableStateOf(initialHideCompleted) }
    var completedExpanded by remember { mutableStateOf(false) }
    var dismissedDone by remember { mutableStateOf(false) }

    // Keep the screen awake while shopping, broadcast presence, and enable pocket
    // detection: a proximity wake lock turns the screen off when covered — exactly
    // how phone calls behave — so the phone can ride in a pocket mid-trip.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo.realtime.sendPresence("shopping", listId)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val proximityLock = if (pm != null &&
            pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
        ) {
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "boet:shopping")
                .also { runCatching { it.acquire() } }
        } else null

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            repo.realtime.sendPresence("viewing", listId)
            runCatching { if (proximityLock?.isHeld == true) proximityLock.release() }
        }
    }

    BoetTheme(forceDark = true) {
      Box(Modifier.fillMaxSize().background(NightBase)) {
        // Full-screen background image (blur + dark overlay) for Shopping Mode.
        val bg = state.list?.bgImageUrl
        if (bg != null) {
            AsyncImage(
                model = serverUrl.trimEnd('/') + bg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(((state.list?.bgBlur ?: 0) / 100f * 24f).dp),
            )
            Box(
                Modifier.matchParentSize().background(
                    Color.Black.copy(alpha = 0.35f + (state.list?.bgOverlay ?: 0) / 100f * 0.45f)
                )
            )
        }
        Scaffold(
            containerColor = Color.Transparent,
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
                            onClick = {
                                hideCompleted = !hideCompleted
                                scope.launch { prefs.setShoppingHideCompleted(hideCompleted) }
                            },
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    // Pick the section source based on the toggle: by default checked
                    // items stay in place (struck-through, dimmed); "Dölj klara" hides
                    // them and surfaces the separate Klara list at the bottom instead.
                    val sections = if (hideCompleted) state.sections else state.shoppingSections
                    for (section in sections) {
                        if (section.items.isEmpty()) continue   // collapse empty categories
                        item(key = "sh-h-${section.id}") {
                            CategoryHeader(section.name, color = Sage, modifier = Modifier.padding(top = 12.dp))
                        }
                        items(section.items, key = { it.id }) { item ->
                            ShoppingItemRow(item = item, onToggle = { vm.toggle(item) })
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // Completed list: only when "Dölj klara" is on (otherwise checked
                    // items already show in place above).
                    if (hideCompleted && state.completed.isNotEmpty()) {
                        item(key = "sh-completed-h") {
                            CompletedHeader(
                                count = state.completed.size,
                                expanded = completedExpanded,
                                onToggle = { completedExpanded = !completedExpanded },
                                dark = true,
                            )
                        }
                        if (completedExpanded) {
                            items(state.completed.take(10), key = { it.id }) { item ->
                                ShoppingItemRow(item = item, onToggle = { vm.toggle(item) })
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
      }
    }
}

// Shopping-mode row: transparent so the background shows through, large tap target.
// Tapping anywhere toggles the item with a haptic and a subtle press-dip (emil), and
// checked items recede in place — struck through and dimmed rather than disappearing.
@Composable
private fun ShoppingItemRow(item: ItemEntity, onToggle: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "rowPress")
    val dim by animateFloatAsState(if (item.checked) 0.45f else 1f, tween(200), label = "rowDim")

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = dim }
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            BoetCheckbox(checked = item.checked, onToggle = onToggle, large = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = BoetType.shopping,
                    color = if (item.checked) Stone else WarmWhite,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                )
                if (!item.note.isNullOrBlank()) {
                    Text(item.note, style = BoetType.body, color = Stone)
                }
            }
            if (!item.quantity.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                QuantityBadge(item.quantity)
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
