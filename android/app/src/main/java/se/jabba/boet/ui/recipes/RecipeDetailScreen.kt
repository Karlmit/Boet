package se.jabba.boet.ui.recipes

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.remote.RecipeDoc
import se.jabba.boet.data.remote.RecipeIngredient
import se.jabba.boet.data.remote.RecipeJson
import se.jabba.boet.ui.common.CategoryHeader
import se.jabba.boet.ui.common.YoutubeLinkRow
import se.jabba.boet.ui.theme.*
import se.jabba.boet.util.resolveImageUrl

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeDetailScreen(
    repo: Repository,
    recipeId: String,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val entity by repo.recipeById(recipeId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var keepAwake by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Keep the screen on while cooking (toggle in the top bar). Mirrors Shopping
    // Mode; cleared when the toggle turns off or the screen leaves composition.
    DisposableEffect(keepAwake) {
        val window = (context as? Activity)?.window
        if (keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val addedText = stringResource(R.string.recipe_added_to_list)
    val noListText = stringResource(R.string.recipe_no_list)

    fun addToList(ing: RecipeIngredient, factor: Double) {
        scope.launch {
            val ok = repo.addIngredientToList(ing.food, addQty(ing, factor))
            snackbarHostState.showSnackbar(if (ok) "$addedText: ${ing.food}" else noListText)
        }
    }

    val doc: RecipeDoc = remember(entity?.data) {
        entity?.data?.let { RecipeJson.decode(it) } ?: RecipeDoc()
    }

    // Serving scaling: amounts scale by current/base. Disabled when the recipe has
    // no base serving count (e.g. a manual recipe with no servings entered).
    val baseServings = doc.servings ?: 0.0
    var servings by remember(doc.servings) { mutableStateOf(if (baseServings > 0) baseServings else 0.0) }
    val factor = if (baseServings > 0 && servings > 0) servings / baseServings else 1.0
    val ingredientsById = remember(doc) { doc.ingredients.associateBy { it.id } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmWhite),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Charcoal)
                    }
                },
                title = {
                    Text(
                        doc.name.ifBlank { stringResource(R.string.recipes_title) },
                        style = BoetType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { keepAwake = !keepAwake }) {
                        Box(
                            modifier = if (keepAwake) Modifier.background(Sage, CircleShape) else Modifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Lightbulb,
                                contentDescription = stringResource(
                                    if (keepAwake) R.string.recipe_keep_awake_on else R.string.recipe_keep_awake_off
                                ),
                                tint = if (keepAwake) MossDeep else CharcoalMuted,
                                modifier = Modifier.padding(6.dp),
                            )
                        }
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.recipe_edit), tint = MossDeep)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // Live AI-parse progress/result. Set only while (or just after) an async
            // parse ran; absent for manual recipes and cleared once truly "done".
            doc.aiStatus?.takeIf { it != "done" }?.let { status ->
                item { AiStatusBanner(status, doc.aiError) }
            }

            item {
                Box(
                    Modifier.fillMaxWidth().height(200.dp).background(Leaf),
                    contentAlignment = Alignment.Center,
                ) {
                    if (doc.image != null) {
                        AsyncImage(
                            model = resolveImageUrl(repo.serverUrl(), doc.image), contentDescription = doc.name,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Default.Restaurant, contentDescription = null, tint = MossDeep, modifier = Modifier.size(48.dp))
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(16.dp))
                    Text(doc.name.ifBlank { stringResource(R.string.recipes_title) }, style = BoetType.headline, color = Charcoal)
                    if (!doc.description.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(doc.description!!, style = BoetType.body, color = CharcoalMuted)
                    }
                    doc.totalTime?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = BoetType.label, color = MossDeep)
                    }
                    entity?.categoryName?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        AmountChip(it)
                    }
                    doc.youtubeUrl?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        YoutubeLinkRow(it)
                    }
                    if (baseServings > 0) {
                        Spacer(Modifier.height(12.dp))
                        ServingsStepper(
                            servings = servings,
                            onChange = { servings = it.coerceIn(1.0, 99.0) },
                        )
                    }
                }
            }

            // Ingredients — amounts scaled to the chosen servings.
            item {
                CategoryHeader(stringResource(R.string.recipe_ingredients), modifier = Modifier.padding(start = 16.dp, top = 20.dp))
            }
            if (doc.ingredients.isEmpty()) {
                item { EmptyHint(stringResource(R.string.recipe_no_ingredients)) }
            } else {
                // Untagged ingredients render first as a plain card; each tag then
                // gets its own card below with a prominent heading, listing every
                // ingredient carrying it — one with several tags (e.g. used in both a
                // marinade and a sauce) appears once per card.
                groupByTags(doc.ingredients) { it.sections }.forEach { (tag, group) ->
                    item {
                        Surface(
                            color = WarmWhite,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Stone),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        ) {
                            Column {
                                if (tag != null) {
                                    Text(
                                        tag, style = BoetType.title, color = MossDeep, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
                                    )
                                    HorizontalDivider(color = Stone)
                                }
                                group.forEachIndexed { idx, ing ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                    ) {
                                        Box(Modifier.size(6.dp).background(Moss, CircleShape))
                                        Spacer(Modifier.width(12.dp))
                                        Text(ingredientLine(ing, factor), style = BoetType.body, color = Charcoal, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { addToList(ing, factor) }) {
                                            Icon(Icons.Default.AddShoppingCart, contentDescription = stringResource(R.string.recipe_add_to_list), tint = MossDeep)
                                        }
                                    }
                                    if (idx < group.lastIndex) {
                                        HorizontalDivider(color = Stone.copy(alpha = 0.5f), modifier = Modifier.padding(start = 14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Steps — with inline amount chips for the ingredients each step uses,
            // so you never scroll back to the ingredient list.
            item {
                CategoryHeader(stringResource(R.string.recipe_steps), modifier = Modifier.padding(start = 16.dp, top = 20.dp))
            }
            if (doc.steps.isEmpty()) {
                item { EmptyHint(stringResource(R.string.recipe_no_steps)) }
            } else {
                itemsIndexed(doc.steps) { idx, step ->
                    // A phase header (e.g. "Gör såsen") from a Mealie/schema.org source
                    // or inferred by the AI — shown above this step, numbering unaffected.
                    step.title?.let {
                        Text(
                            it, style = BoetType.title, color = MossDeep, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = if (idx == 0) 4.dp else 16.dp, bottom = 4.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Surface(color = Moss, shape = CircleShape, modifier = Modifier.size(26.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("${idx + 1}", style = BoetType.label, color = WarmWhite, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.padding(top = 2.dp)) {
                            Text(step.text, style = BoetType.body, color = Charcoal)
                            val used = step.ingredientRefs.mapNotNull { ingredientsById[it] }
                            if (used.isNotEmpty() || step.timerSeconds != null) {
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    used.forEach { AmountChip(chipLabel(it, factor)) }
                                    step.timerSeconds?.takeIf { it > 0 }?.let { StepTimerChip(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServingsStepper(servings: Double, onChange: (Double) -> Unit) {
    Surface(color = Leaf, shape = RoundedCornerShape(999.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { onChange(servings - 1) }) {
                Icon(Icons.Default.Remove, contentDescription = "-", tint = MossDeep)
            }
            Text(stringResource(R.string.recipe_servings_n, fmtNum(servings)), style = BoetType.title, color = Charcoal)
            IconButton(onClick = { onChange(servings + 1) }) {
                Icon(Icons.Default.Add, contentDescription = "+", tint = MossDeep)
            }
        }
    }
}

@Composable
private fun AmountChip(label: String) {
    Surface(color = Sage, shape = RoundedCornerShape(999.dp)) {
        Text(label, style = BoetType.label, color = Charcoal, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

// A tappable per-step countdown. Tap to start; tap again to stop/reset. The timer
// is in-screen only (no background/notification) — enough to cook by in V1.
@Composable
private fun StepTimerChip(totalSeconds: Int) {
    var running by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(totalSeconds) }
    LaunchedEffect(running) {
        if (running) {
            while (remaining > 0) { delay(1000); remaining -= 1 }
            running = false
        }
    }
    val label = if (running || remaining != totalSeconds) {
        "%d:%02d".format(remaining / 60, remaining % 60)
    } else {
        stringResource(R.string.recipe_timer_n, totalSeconds / 60)
    }
    Surface(
        color = if (running) Moss else Leaf,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickable {
            if (running) { running = false; remaining = totalSeconds }
            else { if (remaining <= 0) remaining = totalSeconds; running = true }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Icon(Icons.Default.Timer, contentDescription = null, tint = if (running) WarmWhite else MossDeep, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = BoetType.label, color = if (running) WarmWhite else Charcoal)
        }
    }
}

// Live status for an in-flight (or just-finished) async AI parse — see
// Repository.startAiParse / the server's POST /recipes/parse-async. Shown at the
// top of the detail screen so the app never just looks "stuck" or silently wrong
// while the model runs; updates arrive over the normal recipe WebSocket sync, so
// this recomposes automatically as `doc.aiStatus` changes.
@Composable
private fun AiStatusBanner(status: String, error: String?) {
    val isError = status == "error"
    val isDegraded = status == "degraded"
    val inProgress = !isError && !isDegraded
    val bg = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isDegraded -> Sage
        else -> Leaf
    }
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer else Charcoal
    val label = when (status) {
        "queued" -> stringResource(R.string.recipe_ai_status_queued)
        "fetching" -> stringResource(R.string.recipe_ai_status_fetching)
        "fetching_headless" -> stringResource(R.string.recipe_ai_status_fetching_headless)
        "parsing_cloud" -> stringResource(R.string.recipe_ai_status_parsing_cloud)
        "parsing_local" -> stringResource(R.string.recipe_ai_status_parsing_local)
        "fallback_local" -> stringResource(R.string.recipe_ai_status_fallback_local)
        "linking_steps" -> stringResource(R.string.recipe_ai_status_linking_steps)
        "translating" -> stringResource(R.string.recipe_ai_status_translating)
        "degraded" -> stringResource(R.string.recipe_ai_status_degraded)
        "error" -> error ?: stringResource(R.string.recipe_ai_failed)
        else -> status
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            if (inProgress) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MossDeep)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = BoetType.body, color = fg)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = BoetType.body, color = CharcoalMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

// --- formatting helpers ----------------------------------------------------

// Group items by their tags, preserving first-appearance order of tags. An item
// with several tags appears once per tag it carries (used to cluster recipe
// ingredients under sub-recipe headings like "Marinad"/"Sås", where the same
// ingredient can belong to more than one). Untagged items come first as a single
// null-keyed group (rendered with no header).
internal fun <T> groupByTags(items: List<T>, tagsOf: (T) -> List<String>): List<Pair<String?, List<T>>> {
    val untagged = mutableListOf<T>()
    val byTag = LinkedHashMap<String, MutableList<T>>()
    items.forEach { item ->
        val tags = tagsOf(item).map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty()) untagged.add(item)
        else tags.forEach { tag -> byTag.getOrPut(tag) { mutableListOf() }.add(item) }
    }
    val result = mutableListOf<Pair<String?, List<T>>>()
    if (untagged.isNotEmpty()) result.add(null to untagged)
    byTag.forEach { (tag, group) -> result.add(tag to group) }
    return result
}

// The full ingredient line, amount scaled (e.g. "3,5 dl vetemjöl"). Manual
// recipes without a numeric quantity fall back to their stored display text.
internal fun ingredientLine(ing: RecipeIngredient, factor: Double): String {
    val q = ing.quantity
    return if (q != null) {
        listOf(fmtNum(q * factor), ing.unit.orEmpty(), ing.food).filter { it.isNotBlank() }.joinToString(" ")
    } else {
        ing.display.ifBlank { ing.food }
    }
}

// Compact chip label for a step's referenced ingredient ("vetemjöl · 3,5 dl").
internal fun chipLabel(ing: RecipeIngredient, factor: Double): String {
    val q = ing.quantity ?: return ing.food
    val amount = listOf(fmtNum(q * factor), ing.unit.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
    return if (amount.isBlank()) ing.food else "${ing.food} · $amount"
}

// The quantity string handed to the shopping list when adding an ingredient,
// amount scaled. Measures keep their unit ("3,5 dl"); a bare count > 1 becomes
// "N"; otherwise null (no badge), so add-to-list mirrors normal Boet quantities.
internal fun addQty(ing: RecipeIngredient, factor: Double): String? {
    val q = ing.quantity ?: return null
    val scaled = q * factor
    return when {
        !ing.unit.isNullOrBlank() -> "${fmtNum(scaled)} ${ing.unit}"
        scaled > 1 -> fmtNum(scaled)
        else -> null
    }
}

// Round to 2 decimals, drop a trailing ".0", and use a Swedish decimal comma.
internal fun fmtNum(value: Double): String {
    val r = Math.round(value * 100) / 100.0
    val s = if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    return s.replace('.', ',')
}

// Kept for callers that format a whole serving count.
internal fun formatServings(value: Double): String = fmtNum(value)
