package se.jabba.boet.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.remote.MealDetail
import se.jabba.boet.ui.common.CategoryHeader
import se.jabba.boet.ui.common.YoutubeLinkRow
import se.jabba.boet.ui.theme.*

// Read-only view of a single TheMealDB meal — the "before you've imported it"
// counterpart to RecipeDetailScreen. Its one job beyond reading is the prominent
// import button; everything else (ingredients, steps, tags) is presented but not
// editable, since there's nothing to edit until it becomes a real Boet recipe.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealDetailScreen(
    repo: Repository,
    mealId: String,
    onImported: (String) -> Unit,
    onBack: () -> Unit,
) {
    var meal by remember(mealId) { mutableStateOf(DiscoverMealCache.get(mealId)) }
    var loading by remember(mealId) { mutableStateOf(meal == null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val importFailedText = stringResource(R.string.recipe_discover_import_failed)

    LaunchedEffect(mealId) {
        if (meal == null) {
            meal = repo.discoverMeal(mealId)
            loading = false
        }
    }

    fun import() {
        if (importing) return
        importing = true
        scope.launch {
            val id = repo.importMeal(mealId)
            importing = false
            if (id != null) onImported(id) else snackbarHostState.showSnackbar(importFailedText)
        }
    }

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
                        meal?.name?.ifBlank { null } ?: stringResource(R.string.recipes_title),
                        style = BoetType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        val m = meal
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MossDeep)
            }
            m == null -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.recipe_discover_empty), style = BoetType.body, color = CharcoalMuted)
            }
            else -> MealDetailBody(
                meal = m,
                importing = importing,
                onImport = ::import,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun MealDetailBody(
    meal: MealDetail,
    importing: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = remember(meal.instructions) { splitMealInstructions(meal.instructions) }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(200.dp).background(Leaf), contentAlignment = Alignment.Center) {
                if (meal.thumb != null) {
                    AsyncImage(model = meal.thumb, contentDescription = meal.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Restaurant, contentDescription = null, tint = MossDeep, modifier = Modifier.size(48.dp))
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(16.dp))
                Text(meal.name, style = BoetType.headline, color = Charcoal)
                if (meal.category != null || meal.area != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        meal.category?.let { Pill(it, Sage) }
                        meal.area?.let { Pill(it, Leaf) }
                    }
                }
                if (meal.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        meal.tags.forEach { Text("#$it", style = BoetType.label, color = CharcoalMuted) }
                    }
                }
                meal.youtube?.let { url ->
                    Spacer(Modifier.height(10.dp))
                    YoutubeLinkRow(url)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onImport,
                    enabled = !importing,
                    colors = ButtonDefaults.buttonColors(containerColor = MossDeep, contentColor = WarmWhite),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = WarmWhite)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(R.string.recipe_discover_add))
                }
            }
        }

        item { CategoryHeader(stringResource(R.string.recipe_ingredients), modifier = Modifier.padding(start = 16.dp, top = 20.dp)) }
        itemsIndexed(meal.ingredients) { idx, ing ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(6.dp).background(Moss, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(
                    listOf(ing.measure, ing.food).filter { it.isNotBlank() }.joinToString(" "),
                    style = BoetType.body, color = Charcoal,
                )
            }
        }

        item { CategoryHeader(stringResource(R.string.recipe_steps), modifier = Modifier.padding(start = 16.dp, top = 20.dp)) }
        itemsIndexed(steps) { idx, text ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Surface(color = Moss, shape = RoundedCornerShape(999.dp), modifier = Modifier.size(26.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${idx + 1}", style = BoetType.label, color = WarmWhite)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(text, style = BoetType.body, color = Charcoal, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun Pill(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = RoundedCornerShape(999.dp)) {
        Text(label, style = BoetType.label, color = Charcoal, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
