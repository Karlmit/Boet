package se.jabba.boet.ui.recipes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.ListEntity
import se.jabba.boet.data.local.RecipeEntity
import se.jabba.boet.data.remote.RecipeJson
import se.jabba.boet.ui.list.ListsDrawer
import se.jabba.boet.ui.theme.*
import se.jabba.boet.util.resolveImageUrl

private data class RecipeGroup(val id: String?, val name: String, val recipes: List<RecipeEntity>)

// Recipes hub: the household's recipes grouped into cards by one of two axes —
// Type of food or Country (mode toggle in the filter menu, default Type) — one
// card per category value, alphabetical, recipes within a card alphabetical.
// Reached from the drawer; shopping stays the home screen, so this hosts the
// SAME drawer (hamburger, not a back arrow) as the shopping list, so switching
// between lists/recipes/discover never requires backing all the way out first.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    repo: Repository,
    lists: List<ListEntity>,
    currentListId: String?,
    onOpenRecipe: (String) -> Unit,
    onCreate: () -> Unit,
    onAiCreate: () -> Unit,
    onUrlCreate: () -> Unit,
    onSelectList: (String) -> Unit,
    onManageLists: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val recipes by repo.recipes().collectAsState(initial = emptyList())
    val typeOptions by repo.recipeCategories("type").collectAsState(initial = emptyList())
    val countryOptions by repo.recipeCategories("country").collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var addMenuOpen by remember { mutableStateOf(false) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    // "type" | "country" — which axis groups the cards below and which
    // catalogue the filter list underneath shows.
    var filterMode by rememberSaveable { mutableStateOf("type") }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val activeOptions = if (filterMode == "type") typeOptions else countryOptions
    val uncategorizedLabel = stringResource(R.string.recipes_uncategorized)

    // A specific filter selection only makes sense within the mode it was
    // picked in — dropping it on a mode switch avoids a stale filter silently
    // hiding everything in the new mode.
    LaunchedEffect(filterMode) { selectedCategoryId = null }

    val visibleRecipes = remember(recipes, selectedCategoryId, filterMode) {
        if (selectedCategoryId == null) recipes
        else recipes.filter { (if (filterMode == "type") it.typeCategoryId else it.countryCategoryId) == selectedCategoryId }
    }
    val groups = remember(visibleRecipes, activeOptions, filterMode, uncategorizedLabel) {
        val byId = activeOptions.associateBy { it.id }
        val grouped = visibleRecipes.groupBy { if (filterMode == "type") it.typeCategoryId else it.countryCategoryId }
        val named = grouped.entries.mapNotNull { (catId, list) ->
            if (catId == null) null
            else byId[catId]?.let { RecipeGroup(catId, it.name, list.sortedBy { r -> r.name.lowercase() }) }
        }.sortedBy { it.name.lowercase() }
        val uncategorized = grouped[null].orEmpty()
        if (uncategorized.isEmpty()) named
        else named + RecipeGroup(null, uncategorizedLabel, uncategorized.sortedBy { it.name.lowercase() })
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
                onRecipes = { scope.launch { drawerState.close() } },
                onDiscover = { scope.launch { drawerState.close() }; onOpenDiscover() },
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
                title = { Text(stringResource(R.string.recipes_title), style = BoetType.headline) },
                actions = {
                    Box {
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.recipes_filter_category),
                                tint = if (selectedCategoryId != null) MossDeep else Charcoal,
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuOpen,
                            onDismissRequest = { filterMenuOpen = false },
                            containerColor = WarmWhite,
                        ) {
                            // Mode toggle — switching axis stays open so the user can
                            // immediately pick a specific value in the new mode.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recipe_type), color = if (filterMode == "type") MossDeep else Charcoal) },
                                trailingIcon = { if (filterMode == "type") Icon(Icons.Default.Check, contentDescription = null, tint = Moss) },
                                onClick = { filterMode = "type" },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recipe_country), color = if (filterMode == "country") MossDeep else Charcoal) },
                                trailingIcon = { if (filterMode == "country") Icon(Icons.Default.Check, contentDescription = null, tint = Moss) },
                                onClick = { filterMode = "country" },
                            )
                            HorizontalDivider(color = Stone)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recipes_filter_all), color = Charcoal) },
                                trailingIcon = {
                                    if (selectedCategoryId == null) Icon(Icons.Default.Check, contentDescription = null, tint = Moss)
                                },
                                onClick = { selectedCategoryId = null; filterMenuOpen = false },
                            )
                            if (activeOptions.isNotEmpty()) {
                                HorizontalDivider(color = Stone)
                                activeOptions.sortedBy { it.name.lowercase() }.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat.name, color = Charcoal) },
                                        trailingIcon = {
                                            if (selectedCategoryId == cat.id) Icon(Icons.Default.Check, contentDescription = null, tint = Moss)
                                        },
                                        onClick = { selectedCategoryId = cat.id; filterMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { addMenuOpen = true },
                    containerColor = MossDeep, contentColor = WarmWhite,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.recipe_new))
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                    containerColor = WarmWhite,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recipe_create_ai), color = Charcoal) },
                        onClick = { addMenuOpen = false; onAiCreate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recipe_create_url), color = Charcoal) },
                        onClick = { addMenuOpen = false; onUrlCreate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recipe_create_manual), color = Charcoal) },
                        onClick = { addMenuOpen = false; onCreate() },
                    )
                }
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (selectedCategoryId != null) R.string.recipes_filter_empty else R.string.recipes_empty),
                    style = BoetType.body, color = CharcoalMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(groups, key = { it.id ?: " uncategorized" }) { group ->
                    RecipeGroupCard(group = group, serverUrl = repo.serverUrl(), onOpenRecipe = onOpenRecipe)
                }
            }
        }
    }
    }
}

@Composable
private fun RecipeGroupCard(group: RecipeGroup, serverUrl: String, onOpenRecipe: (String) -> Unit) {
    Surface(
        color = WarmWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                group.name, style = BoetType.title, color = MossDeep, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            group.recipes.chunked(2).forEach { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    pair.forEach { recipe ->
                        RecipeCard(
                            recipe = recipe,
                            serverUrl = serverUrl,
                            onClick = { onOpenRecipe(recipe.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: RecipeEntity, serverUrl: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Cheap peek at aiStatus for the in-progress/error badge — the grid otherwise
    // only needs the denormalized name/image columns, but this field is small.
    val aiStatus = remember(recipe.data) { RecipeJson.decode(recipe.data).aiStatus }
    Surface(
        color = WarmWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Stone),
        modifier = modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(120.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Leaf),
                contentAlignment = Alignment.Center,
            ) {
                if (recipe.image != null) {
                    AsyncImage(
                        model = resolveImageUrl(serverUrl, recipe.image),
                        contentDescription = recipe.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Default.Restaurant, contentDescription = null, tint = MossDeep)
                }
                if (aiStatus == "error") {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(999.dp), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = stringResource(R.string.recipe_ai_status_error_badge), tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(6.dp).size(16.dp))
                    }
                } else if (aiStatus != null && aiStatus != "done" && aiStatus != "degraded") {
                    Surface(color = WarmWhite, shape = RoundedCornerShape(999.dp), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                        CircularProgressIndicator(modifier = Modifier.padding(6.dp).size(16.dp), strokeWidth = 2.dp, color = MossDeep)
                    }
                }
            }
            Text(
                recipe.name.ifBlank { stringResource(R.string.recipes_title) },
                style = BoetType.title, color = Charcoal,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }
}
