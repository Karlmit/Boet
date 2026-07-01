package se.jabba.boet.ui.recipes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
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

// Recipes hub: a grid of the household's recipes, reached from the drawer.
// Shopping stays the home screen; this is a parallel destination — it hosts the
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
    val scope = rememberCoroutineScope()
    var addMenuOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Recipes carry an optional free-text category (set in the editor); derive
    // the filter's option list from whatever's actually in use rather than a
    // fixed list, and clear a filter if its category no longer exists.
    val categories = remember(recipes) {
        recipes.mapNotNull { it.categoryName?.trim()?.takeIf { c -> c.isNotBlank() } }.distinct().sorted()
    }
    LaunchedEffect(categories) {
        if (selectedCategory != null && selectedCategory !in categories) selectedCategory = null
    }
    val filteredRecipes = remember(recipes, selectedCategory) {
        selectedCategory?.let { cat -> recipes.filter { it.categoryName == cat } } ?: recipes
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
                        IconButton(onClick = { categoryMenuOpen = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.recipes_filter_category),
                                tint = if (selectedCategory != null) MossDeep else Charcoal,
                            )
                        }
                        DropdownMenu(
                            expanded = categoryMenuOpen,
                            onDismissRequest = { categoryMenuOpen = false },
                            containerColor = WarmWhite,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recipes_filter_all), color = Charcoal) },
                                trailingIcon = {
                                    if (selectedCategory == null) Icon(Icons.Default.Check, contentDescription = null, tint = Moss)
                                },
                                onClick = { selectedCategory = null; categoryMenuOpen = false },
                            )
                            if (categories.isNotEmpty()) {
                                HorizontalDivider(color = Stone)
                                categories.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat, color = Charcoal) },
                                        trailingIcon = {
                                            if (selectedCategory == cat) Icon(Icons.Default.Check, contentDescription = null, tint = Moss)
                                        },
                                        onClick = { selectedCategory = cat; categoryMenuOpen = false },
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
        if (filteredRecipes.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (selectedCategory != null) R.string.recipes_filter_empty else R.string.recipes_empty),
                    style = BoetType.body, color = CharcoalMuted,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filteredRecipes, key = { it.id }) { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        serverUrl = repo.serverUrl(),
                        onClick = { onOpenRecipe(recipe.id) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun RecipeCard(recipe: RecipeEntity, serverUrl: String, onClick: () -> Unit) {
    // Cheap peek at aiStatus for the in-progress/error badge — the grid otherwise
    // only needs the denormalized name/image columns, but this field is small.
    val aiStatus = remember(recipe.data) { RecipeJson.decode(recipe.data).aiStatus }
    Surface(
        color = WarmWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Stone),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
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
