package se.jabba.boet.ui.list

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.ui.theme.*

// "Kassen" — the shopping bag you always carry. A floating pill with the live
// remaining count, docked at the bottom of every screen except the list itself,
// onboarding and Shopping Mode (hosted by BoetNavHost over the NavHost). One tap
// pulls the Matkasse up as a half-height sheet OVER the current screen — a recipe
// stays visible behind it for comparison — with working checkboxes and a quick-add
// field. One swipe/back/scrim-tap and you're exactly where you were.
@Composable
fun MatkasseQuickAccess(
    repo: Repository,
    listId: String,
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Its own ListViewModel instance (activity-scoped, unlike the home screen's
    // nav-entry-scoped one) — both observe the same Room flows, so state is
    // always in sync with the real list.
    val vm: ListViewModel = viewModel(key = "list-$listId", factory = ListViewModel.factory(repo, listId))
    val state by vm.state.collectAsState()
    val favoriteKeys by vm.favoriteKeys.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }

    MatkassePill(
        remaining = state.remaining,
        onClick = { open = true },
        modifier = modifier,
    )

    if (open) {
        MatkassePeekSheet(
            state = state,
            favoriteKeys = favoriteKeys,
            onToggle = vm::toggle,
            onAdd = vm::addItems,
            onOpenFull = { open = false; onOpenFull() },
            onDismiss = { open = false },
        )
    }
}

// The floating handle: basket icon + live remaining count. Same press-dip +
// haptic motion language as BannerShoppingButton / CircleIconButton.
@Composable
private fun MatkassePill(remaining: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "pressKassen")
    Surface(
        color = MossDeep,
        shape = RoundedCornerShape(999.dp),
        shadowElevation = 4.dp,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Default.ShoppingBasket,
                contentDescription = stringResource(R.string.matkasse_peek_cd),
                tint = WarmWhite,
                modifier = Modifier.size(18.dp),
            )
            if (remaining > 0) {
                Spacer(Modifier.width(8.dp))
                Text("$remaining", style = BoetType.title, color = WarmWhite)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatkassePeekSheet(
    state: ListUiState,
    favoriteKeys: Set<String>,
    onToggle: (ItemEntity) -> Unit,
    onAdd: (String) -> Unit,
    onOpenFull: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Partially-expanded first (about half the screen): the screen underneath —
    // typically a recipe — stays readable while comparing. Drag up for the full list.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WarmWhite,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Stone) },
    ) {
        Column(Modifier.fillMaxHeight().imePadding()) {
            // Header: list name + remaining count, and the full-jump affordance.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.list?.name ?: "…", style = BoetType.headline, color = Charcoal)
                    Text(stringResource(R.string.items_left, state.remaining), style = BoetType.body, color = MossDeep)
                }
                TextButton(onClick = { close(onOpenFull) }) {
                    Text(stringResource(R.string.matkasse_open_full), style = BoetType.title, color = MossDeep)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MossDeep, modifier = Modifier.size(16.dp))
                }
            }

            // Quick add — above the items so it's reachable in the half-open state.
            PeekAddRow(onAdd = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

            if (state.remaining == 0) {
                Text(
                    stringResource(R.string.matkasse_empty),
                    style = BoetType.body, color = CharcoalMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                for (section in state.sections) {
                    if (section.items.isEmpty()) continue
                    item(key = "peek-h-${section.id}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp),
                        ) {
                            Icon(categoryIcon(section.icon, section.name), contentDescription = null, tint = MossDeep, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(section.name.uppercase(), style = BoetType.label, color = MossDeep)
                        }
                    }
                    item(key = "peek-c-${section.id}") {
                        GroupedCard {
                            section.items.forEachIndexed { i, item ->
                                if (i > 0) GroupDivider()
                                CompactItemRow(
                                    item = item,
                                    isFavorite = favoriteKeys.contains(item.name.trim().lowercase()),
                                    onToggle = { onToggle(item) },
                                    // The peek is for glancing and ticking, not editing —
                                    // tapping the row toggles too, like Shopping Mode.
                                    onClick = { onToggle(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Slim add field + Moss circle button. Deliberately simpler than the home AddBar
// (no mic, no favorites) — quick top-ups while comparing, nothing more.
@Composable
private fun PeekAddRow(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    fun submit() {
        if (text.isNotBlank()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onAdd(text); text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.add_item_hint), color = CharcoalMuted) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Moss,
                unfocusedBorderColor = Stone,
                focusedContainerColor = WarmWhite,
                unfocusedContainerColor = WarmWhite,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MossDeep,
            shape = CircleShape,
            modifier = Modifier.size(44.dp).clickable { submit() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add), tint = WarmWhite, modifier = Modifier.size(22.dp))
            }
        }
    }
}
