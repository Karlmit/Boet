package se.jabba.boet.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.ListEntity
import se.jabba.boet.data.local.Prefs
import se.jabba.boet.data.remote.MealCategory
import se.jabba.boet.data.remote.MealDetail
import se.jabba.boet.data.remote.MealIngredientRef
import se.jabba.boet.data.remote.MealSummary
import se.jabba.boet.ui.common.CategoryHeader
import se.jabba.boet.ui.list.ListsDrawer
import se.jabba.boet.ui.theme.*

// Discover: browse/search TheMealDB and import a meal into the household's own
// recipe book. Two states live side by side rather than as separate nav routes —
// Browse (the default hub: featured pick, a reshufflable ten, category/area
// chips) and Results (whatever the user just searched/filtered for) — so
// clearing a search is instant and never loses the browse scroll position.
// Hosts the SAME drawer (hamburger, not a back arrow) as the shopping list and
// the recipe book, so switching between them never requires backing out first.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    repo: Repository,
    prefs: Prefs,
    lists: List<ListEntity>,
    currentListId: String?,
    onOpenMeal: (String) -> Unit,
    onSelectList: (String) -> Unit,
    onManageLists: () -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Random-10/categories/areas are read from the process-scoped DiscoverBrowseState
    // (not just `remember`) so they survive navigating to a meal and back — see that
    // object's comment. Featured ("Dagens slump") is additionally persisted to disk
    // via Prefs so it's a genuine once-a-day pick, not "random on every visit".
    var featured by remember { mutableStateOf<MealDetail?>(null) }
    var randomTen by remember { mutableStateOf(DiscoverBrowseState.randomTen ?: emptyList()) }
    var categories by remember { mutableStateOf(DiscoverBrowseState.categories ?: emptyList()) }
    var areas by remember { mutableStateOf(DiscoverBrowseState.areas ?: emptyList()) }
    var browseLoading by remember { mutableStateOf(true) }
    var featuredGen by remember { mutableIntStateOf(0) }
    var tenGen by remember { mutableIntStateOf(0) }

    var query by remember { mutableStateOf("") }
    var ingredientPickerOpen by remember { mutableStateOf(false) }
    var ingredientQuery by remember { mutableStateOf("") }
    var selectedIngredients by remember { mutableStateOf(listOf<String>()) }
    var allIngredients by remember { mutableStateOf<List<MealIngredientRef>>(emptyList()) }

    var resultsLabel by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<MealSummary>>(emptyList()) }
    var resultsLoading by remember { mutableStateOf(false) }

    fun runSearch(label: String, loader: suspend () -> List<MealSummary>) {
        scope.launch {
            resultsLabel = label
            resultsLoading = true
            results = loader()
            resultsLoading = false
        }
    }

    LaunchedEffect(Unit) {
        val today = java.time.LocalDate.now().toString()
        val stored = prefs.dailyMeal()
        featured = if (stored != null && stored.date == today) {
            DiscoverMealCache.get(stored.mealId) ?: repo.discoverMeal(stored.mealId)?.also { DiscoverMealCache.put(it) }
        } else {
            repo.discoverRandom()?.also {
                DiscoverMealCache.put(it)
                prefs.setDailyMeal(it.id, today)
            }
        }

        if (DiscoverBrowseState.randomTen == null) {
            randomTen = repo.discoverRandomSelection().also {
                DiscoverMealCache.putAll(it)
                DiscoverBrowseState.randomTen = it
            }
        }
        if (DiscoverBrowseState.categories == null) {
            categories = repo.discoverCategories().also { DiscoverBrowseState.categories = it }
        }
        if (DiscoverBrowseState.areas == null) {
            areas = repo.discoverAreas().also { DiscoverBrowseState.areas = it }
        }
        browseLoading = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen, // open only via the hamburger; no edge-swipe
        drawerContent = {
            ListsDrawer(
                lists = lists,
                currentId = currentListId ?: "",
                onSelect = { scope.launch { drawerState.close() }; onSelectList(it) },
                onManage = { scope.launch { drawerState.close() }; onManageLists() },
                onRecipes = { scope.launch { drawerState.close() }; onOpenRecipes() },
                onDiscover = { scope.launch { drawerState.close() } },
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmWhite),
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.lists_title), tint = Charcoal)
                    }
                },
                title = { Text(stringResource(R.string.recipe_discover_title), style = BoetType.headline) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        if (query.isNotBlank()) runSearch("“$query”") { repo.discoverSearchByName(query) }
                    },
                )
            }

            item {
                IngredientSearchPanel(
                    open = ingredientPickerOpen,
                    onToggle = {
                        ingredientPickerOpen = !ingredientPickerOpen
                        if (ingredientPickerOpen && allIngredients.isEmpty()) {
                            scope.launch { allIngredients = repo.discoverIngredients() }
                        }
                    },
                    ingredientQuery = ingredientQuery,
                    onIngredientQueryChange = { ingredientQuery = it },
                    suggestions = if (ingredientQuery.isBlank()) emptyList() else {
                        allIngredients.filter { it.name.contains(ingredientQuery, ignoreCase = true) }
                            .filter { it.name !in selectedIngredients }.take(6)
                    },
                    selected = selectedIngredients,
                    onAdd = { selectedIngredients = selectedIngredients + it; ingredientQuery = "" },
                    onRemove = { name -> selectedIngredients = selectedIngredients.filter { it != name } },
                    onSubmit = {
                        val chosen = selectedIngredients
                        runSearch(chosen.joinToString(", ")) {
                            repo.discoverFilterByIngredients(chosen.map(::ingredientApiToken))
                        }
                    },
                )
            }

            val label = resultsLabel
            if (label != null) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.recipe_discover_results_for, label),
                            style = BoetType.title, color = Charcoal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            resultsLabel = null; query = ""; selectedIngredients = emptyList(); ingredientPickerOpen = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recipe_discover_clear), tint = CharcoalMuted)
                        }
                    }
                }
                if (resultsLoading) {
                    item { CenteredProgress() }
                } else if (results.isEmpty()) {
                    item { EmptyHint(stringResource(R.string.recipe_discover_empty)) }
                } else {
                    item { MealGrid(results, onOpenMeal) }
                }
            } else {
                if (browseLoading) {
                    item { CenteredProgress() }
                } else {
                    featured?.let { meal ->
                        item {
                            FeaturedCard(
                                meal = meal,
                                onClick = { onOpenMeal(meal.id) },
                                onShuffle = {
                                    scope.launch {
                                        featuredGen++
                                        val today = java.time.LocalDate.now().toString()
                                        featured = repo.discoverRandom()?.also {
                                            DiscoverMealCache.put(it)
                                            prefs.setDailyMeal(it.id, today)
                                        }
                                    }
                                },
                            )
                        }
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.recipe_discover_random10_title), style = BoetType.title,
                                color = Charcoal, modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    tenGen++
                                    randomTen = repo.discoverRandomSelection().also {
                                        DiscoverMealCache.putAll(it)
                                        DiscoverBrowseState.randomTen = it
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Shuffle, contentDescription = stringResource(R.string.recipe_discover_shuffle), tint = MossDeep)
                            }
                        }
                    }
                    item {
                        AnimatedContent(
                            targetState = tenGen,
                            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                            label = "random-ten",
                        ) {
                            MealGrid(randomTen.map { MealSummary(it.id, it.name, it.thumb) }, onOpenMeal)
                        }
                    }

                    if (categories.isNotEmpty()) {
                        item { CategoryHeader(stringResource(R.string.recipe_discover_categories), modifier = Modifier.padding(start = 16.dp, top = 20.dp)) }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(categories, key = { it.name }) { c ->
                                    CategoryChip(c, onClick = {
                                        runSearch(c.name) { repo.discoverFilterByCategory(c.name) }
                                    })
                                }
                            }
                        }
                    }

                    if (areas.isNotEmpty()) {
                        item { CategoryHeader(stringResource(R.string.recipe_discover_areas), modifier = Modifier.padding(start = 16.dp, top = 20.dp)) }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(areas, key = { it }) { a ->
                                    TextChip(a, onClick = { runSearch(a) { repo.discoverFilterByArea(a) } })
                                }
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
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    Surface(
        color = WarmWhite,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 6.dp)) {
            Icon(Icons.Default.Search, contentDescription = null, tint = CharcoalMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.recipe_discover_search_hint), style = BoetType.body, color = CharcoalMuted) },
                textStyle = BoetType.body,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = WarmWhite, unfocusedContainerColor = WarmWhite, disabledContainerColor = WarmWhite,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange(""); onSearch() }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recipe_discover_clear), tint = CharcoalMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IngredientSearchPanel(
    open: Boolean,
    onToggle: () -> Unit,
    ingredientQuery: String,
    onIngredientQueryChange: (String) -> Unit,
    suggestions: List<MealIngredientRef>,
    selected: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Surface(
            color = if (open) Sage else Leaf,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.clickable(onClick = onToggle),
        ) {
            Text(
                stringResource(R.string.recipe_discover_search_ingredients),
                style = BoetType.label, color = Charcoal,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            if (selected.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    selected.forEach { name ->
                        Surface(color = Moss, shape = RoundedCornerShape(999.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(name, style = BoetType.label, color = WarmWhite)
                                IconButton(onClick = { onRemove(name) }, modifier = Modifier.size(20.dp).padding(start = 2.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recipe_discover_clear), tint = WarmWhite, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = ingredientQuery,
                onValueChange = onIngredientQueryChange,
                placeholder = { Text(stringResource(R.string.recipe_discover_ingredient_hint), style = BoetType.body) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = WarmWhite, unfocusedContainerColor = WarmWhite),
                modifier = Modifier.fillMaxWidth(),
            )
            suggestions.forEach { ing ->
                Text(
                    ing.name, style = BoetType.body, color = Charcoal,
                    modifier = Modifier.fillMaxWidth().clickable { onAdd(ing.name) }.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSubmit,
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MossDeep, contentColor = WarmWhite),
            ) {
                Text(stringResource(R.string.recipe_discover_search_btn))
            }
        }
    }
}

@Composable
private fun FeaturedCard(meal: MealDetail, onClick: () -> Unit, onShuffle: () -> Unit) {
    Surface(
        color = WarmWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp).clickable(onClick = onClick),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Leaf)) {
                if (meal.thumb != null) {
                    AsyncImage(model = meal.thumb, contentDescription = meal.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Restaurant, contentDescription = null, tint = MossDeep, modifier = Modifier.align(Alignment.Center).size(40.dp))
                }
                Surface(
                    color = WarmWhite.copy(alpha = 0.92f), shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clickable(onClick = onShuffle),
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = stringResource(R.string.recipe_discover_shuffle), tint = MossDeep, modifier = Modifier.padding(8.dp).size(18.dp))
                }
                Surface(
                    color = Moss, shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Text(stringResource(R.string.recipe_discover_featured), style = BoetType.label, color = WarmWhite, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Column(Modifier.padding(14.dp)) {
                Text(meal.name, style = BoetType.title, color = Charcoal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meal.category != null || meal.area != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        meal.category?.let { TextChip(it, onClick = null) }
                        meal.area?.let { TextChip(it, onClick = null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealGrid(items: List<MealSummary>, onClick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { meal ->
                    MealCard(meal, onClick = { onClick(meal.id) }, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MealCard(meal: MealSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = WarmWhite, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Stone),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(Leaf)) {
                if (meal.thumb != null) {
                    AsyncImage(model = meal.thumb, contentDescription = meal.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Restaurant, contentDescription = null, tint = MossDeep, modifier = Modifier.align(Alignment.Center))
                }
            }
            Text(
                meal.name, style = BoetType.body, color = Charcoal, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun CategoryChip(category: MealCategory, onClick: () -> Unit) {
    Surface(color = Leaf, shape = RoundedCornerShape(999.dp), modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            if (category.thumb != null) {
                AsyncImage(
                    model = category.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(24.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(category.name, style = BoetType.label, color = Charcoal)
        }
    }
}

@Composable
private fun TextChip(label: String, onClick: (() -> Unit)?) {
    Surface(
        color = Leaf, shape = RoundedCornerShape(999.dp),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(label, style = BoetType.label, color = Charcoal, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MossDeep)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = BoetType.body, color = CharcoalMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}
