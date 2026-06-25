package se.jabba.boet.ui.list

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.jabba.boet.R
import se.jabba.boet.data.local.FavoriteEntity
import se.jabba.boet.ui.theme.*

// Quick-add favorites sheet. Opened from the empty + button on the add bar.
// Favorites are grouped by category; tapping one adds it to the current list and
// the sheet stays open (with a running "added" tally) so several can be added in
// a row. Closes via the X or by swiping down.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesSheet(
    ui: FavoritesUiState,
    onAdd: (FavoriteEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // How many times each favorite has been added during this sheet session.
    val addedCounts = remember { mutableStateMapOf<String, Int>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WarmWhite,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, contentDescription = null, tint = Sage, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.favorites), style = BoetType.headline, color = Charcoal)
                    Text(stringResource(R.string.favorites_subtitle), style = BoetType.body, color = CharcoalMuted)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = Charcoal)
                }
            }
            Spacer(Modifier.height(4.dp))

            when {
                ui.loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Moss) }

                ui.sections.isEmpty() -> Text(
                    stringResource(R.string.favorites_empty),
                    style = BoetType.body,
                    color = CharcoalMuted,
                    modifier = Modifier.padding(vertical = 40.dp),
                )

                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                    for (section in ui.sections) {
                        item(key = "fav-cat-${section.category}") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                            ) {
                                Icon(categoryIcon(section.category), contentDescription = null, tint = MossDeep, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(section.category.uppercase(), style = BoetType.label, color = MossDeep)
                            }
                        }
                        items(section.items, key = { it.id }) { fav ->
                            FavoriteRow(
                                fav = fav,
                                addedCount = addedCounts[fav.id] ?: 0,
                                onAdd = {
                                    addedCounts[fav.id] = (addedCounts[fav.id] ?: 0) + 1
                                    onAdd(fav)
                                },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(fav: FavoriteEntity, addedCount: Int, onAdd: () -> Unit) {
    val added = addedCount > 0
    Surface(
        color = WarmWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (added) Sage else Stone),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onAdd),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(fav.name, style = BoetType.title, color = Charcoal, modifier = Modifier.weight(1f))
            if (added) {
                Text(
                    stringResource(R.string.favorites_added, addedCount),
                    style = BoetType.body,
                    color = MossDeep,
                )
                Spacer(Modifier.width(8.dp))
            }
            Surface(
                color = if (added) Leaf else MossDeep,
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
            ) {
                IconButton(onClick = onAdd) {
                    Icon(
                        if (added) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = stringResource(R.string.add),
                        tint = if (added) MossDeep else WarmWhite,
                    )
                }
            }
        }
    }
}
