package se.jabba.boet.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import se.jabba.boet.BoetApp
import se.jabba.boet.R
import se.jabba.boet.data.local.Settings
import se.jabba.boet.ui.list.CategoryManageScreen
import se.jabba.boet.ui.list.ListScreen
import se.jabba.boet.ui.list.ListSettingsScreen
import se.jabba.boet.ui.list.MatkasseQuickAccess
import se.jabba.boet.ui.lists.ListsScreen
import se.jabba.boet.ui.onboarding.OnboardingScreen
import se.jabba.boet.ui.discover.DiscoverScreen
import se.jabba.boet.ui.discover.MealDetailScreen
import se.jabba.boet.ui.recipes.RecipeAiScreen
import se.jabba.boet.ui.recipes.RecipeDetailScreen
import se.jabba.boet.ui.recipes.RecipeEditorScreen
import se.jabba.boet.ui.recipes.RecipeUrlScreen
import se.jabba.boet.ui.recipes.RecipesScreen
import se.jabba.boet.ui.settings.SettingsScreen
import se.jabba.boet.ui.shopping.ShoppingScreen
import se.jabba.boet.update.UpdatePrompt
import se.jabba.boet.util.InstagramUrl
import kotlinx.coroutines.launch

// Routes where the floating Matkasse pill is NOT shown: the list itself (home),
// onboarding, and Shopping Mode — everywhere else the household's grocery list
// is one tap away (and one swipe back), even from deep screens like a recipe.
private fun showMatkassePill(route: String?): Boolean =
    route != null && route != "onboarding" && route != "home" && !route.startsWith("shopping/")

@Composable
fun BoetNavHost(
    app: BoetApp,
    settings: Settings,
    pendingSharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
) {
    // Localization is applied at the Activity level (MainActivity.attachBaseContext)
    // so dialogs and bottom sheets — which render in separate windows — are localized
    // too. Nothing locale-related needs to happen here.
    val repo = app.repository
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The currently shown list (defaults to the first list once loaded).
    var selectedListId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeLists by repo.activeLists().collectAsState(initial = emptyList())

    LaunchedEffect(activeLists) {
        if (selectedListId == null || activeLists.none { it.id == selectedListId }) {
            selectedListId = activeLists.firstOrNull()?.id
        }
    }
    LaunchedEffect(Unit) { repo.bootstrap() }

    // A Reel shared into Boet via the Android share sheet (MainActivity's
    // ACTION_SEND intent filter). Gated on settings.identity != null (i.e.
    // post-onboarding) since a share landing mid-onboarding has nowhere
    // sane to navigate to yet — dropped in that unlikely edge case. Reuses
    // the exact same import call the "Parse URL" screen uses
    // (Repository.startUrlScrape branches on the URL shape), so a share
    // gets the identical placeholder/live-status/review experience.
    LaunchedEffect(pendingSharedText, settings.identity) {
        val text = pendingSharedText ?: return@LaunchedEffect
        if (settings.identity == null) return@LaunchedEffect
        val reelUrl = InstagramUrl.extractReelUrl(text)
        if (reelUrl == null) {
            Toast.makeText(context, context.getString(R.string.recipe_instagram_share_no_url), Toast.LENGTH_LONG).show()
            onSharedTextConsumed()
            return@LaunchedEffect
        }
        val id = repo.startUrlScrape(reelUrl)
        if (id == null) {
            Toast.makeText(context, context.getString(R.string.recipe_url_failed), Toast.LENGTH_LONG).show()
        } else {
            // Anchor at "home" rather than the other import flows' "recipes"
            // — a share can arrive from anywhere in the app, not just the
            // Recipes screen, so this always leaves a sane home -> recipe/$id
            // back stack regardless of where the user was when they shared.
            nav.navigate("recipe/$id") { popUpTo("home") { inclusive = false }; launchSingleTop = true }
        }
        onSharedTextConsumed()
    }

    // Shared drawer actions for the screens that host their own drawer instance
    // (home/ListScreen, RecipesScreen, DiscoverScreen) — launchSingleTop means
    // re-selecting the screen you're already on just closes the drawer instead
    // of pushing a duplicate entry onto the back stack.
    val onSelectListFromDrawer: (String) -> Unit = { id -> selectedListId = id; nav.popBackStack("home", false) }
    val onManageListsFromDrawer: () -> Unit = { nav.navigate("lists") }
    val onOpenRecipesFromDrawer: () -> Unit = { nav.navigate("recipes") { launchSingleTop = true } }
    val onOpenDiscoverFromDrawer: () -> Unit = { nav.navigate("recipe/discover") { launchSingleTop = true } }
    val onOpenSettingsFromDrawer: () -> Unit = { nav.navigate("settings") }

    val start = if (settings.identity == null) "onboarding" else "home"

    // True while a recipe's keep-awake (lightbulb) toggle is on — the user is
    // cooking, so the floating Matkasse pill gets out of the way.
    var cookingKeepAwake by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = start) {
            composable("onboarding") {
                OnboardingScreen(onPick = { name ->
                    scope.launch { app.prefs.setIdentity(name) }
                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                })
            }

            composable("home") {
                val listId = selectedListId
                if (listId == null) {
                    // No list yet — send the user to the lists hub to create one.
                    ListsScreen(
                        repo = repo,
                        onOpenList = { selectedListId = it },
                        onBack = { },
                    )
                } else {
                    ListScreen(
                        repo = repo,
                        listId = listId,
                        identity = settings.identity,
                        language = settings.language,
                        serverUrl = settings.serverUrl,
                        onOpenLists = onManageListsFromDrawer,
                        onOpenSettings = onOpenSettingsFromDrawer,
                        onOpenShopping = { nav.navigate("shopping/$listId") },
                        onOpenCategories = { nav.navigate("categories/$listId") },
                        onOpenListSettings = { nav.navigate("listsettings/$listId") },
                        onOpenRecipes = onOpenRecipesFromDrawer,
                        onOpenDiscover = onOpenDiscoverFromDrawer,
                        onSelectList = { selectedListId = it },
                    )
                }
            }

            composable("lists") {
                ListsScreen(
                    repo = repo,
                    onOpenList = { id -> selectedListId = id; nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("settings") {
                SettingsScreen(prefs = app.prefs, settings = settings, onBack = { nav.popBackStack() })
            }

            composable("recipes") {
                RecipesScreen(
                    repo = repo,
                    lists = activeLists,
                    currentListId = selectedListId,
                    onOpenRecipe = { id -> nav.navigate("recipe/$id") },
                    onCreate = { nav.navigate("recipe/new") },
                    onAiCreate = { nav.navigate("recipe/ai") },
                    onUrlCreate = { nav.navigate("recipe/url") },
                    onSelectList = onSelectListFromDrawer,
                    onManageLists = onManageListsFromDrawer,
                    onOpenDiscover = onOpenDiscoverFromDrawer,
                    onOpenSettings = onOpenSettingsFromDrawer,
                )
            }

            composable("recipe/discover") {
                DiscoverScreen(
                    repo = repo,
                    prefs = app.prefs,
                    lists = activeLists,
                    currentListId = selectedListId,
                    onOpenMeal = { id -> nav.navigate("recipe/discover/meal/$id") },
                    onSelectList = onSelectListFromDrawer,
                    onManageLists = onManageListsFromDrawer,
                    onOpenRecipes = onOpenRecipesFromDrawer,
                    onOpenSettings = onOpenSettingsFromDrawer,
                )
            }

            composable("recipe/discover/meal/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                MealDetailScreen(
                    repo = repo,
                    mealId = id,
                    // Land on the (still-parsing) recipe's own detail screen right away,
                    // same as an AI paste/photo import — backing out from there returns
                    // here, so the user can keep browsing rather than losing Discover.
                    onImported = { recipeId -> nav.navigate("recipe/$recipeId") },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("recipe/ai") {
                RecipeAiScreen(
                    repo = repo,
                    // Land on the (still-parsing) recipe's detail screen right away —
                    // it shows live status and fills in as the server finishes, dropping
                    // the AI screen from the back stack.
                    onParsed = { id -> nav.navigate("recipe/$id") { popUpTo("recipes") } },
                    onManual = { nav.navigate("recipe/new") { popUpTo("recipes") } },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("recipe/url") {
                RecipeUrlScreen(
                    repo = repo,
                    onParsed = { id -> nav.navigate("recipe/$id") { popUpTo("recipes") } },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("recipe/new") {
                RecipeEditorScreen(
                    repo = repo,
                    recipeId = null,
                    // Land on the new recipe's detail; drop the editor from the back stack.
                    onSaved = { id -> nav.navigate("recipe/$id") { popUpTo("recipes") } },
                    onDeleted = { nav.popBackStack("recipes", false) },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("recipe/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                RecipeDetailScreen(
                    repo = repo,
                    recipeId = id,
                    onEdit = { nav.navigate("recipe/$id/edit") },
                    onBack = { nav.popBackStack() },
                    onKeepAwakeChanged = { cookingKeepAwake = it },
                )
            }

            composable("recipe/{id}/edit") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                RecipeEditorScreen(
                    repo = repo,
                    recipeId = id,
                    onSaved = { nav.popBackStack() },
                    // Deleting from the editor: the detail screen below it on the back
                    // stack no longer has a recipe to show, so pop all the way to the list.
                    onDeleted = { nav.popBackStack("recipes", false) },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("shopping/{listId}") { entry ->
                val id = entry.arguments?.getString("listId") ?: return@composable
                ShoppingScreen(
                    repo = repo,
                    listId = id,
                    serverUrl = settings.serverUrl,
                    prefs = app.prefs,
                    initialHideCompleted = settings.shoppingHideCompleted,
                    autoCompleteThreshold = settings.autoCompleteThreshold,
                    onBack = { nav.popBackStack() },
                )
            }

            composable("categories/{listId}") { entry ->
                val id = entry.arguments?.getString("listId") ?: return@composable
                CategoryManageScreen(repo = repo, listId = id, onBack = { nav.popBackStack() })
            }

            composable("listsettings/{listId}") { entry ->
                val id = entry.arguments?.getString("listId") ?: return@composable
                ListSettingsScreen(repo = repo, listId = id, serverUrl = settings.serverUrl, onBack = { nav.popBackStack() })
            }
        }

        // "Kassen" — the household's grocery list, one tap away from anywhere.
        // Prefers the selected list when it's a grocery one; falls back to the
        // first grocery list (Matkasse is auto-created for every household).
        val backStackEntry by nav.currentBackStackEntryAsState()
        val route = backStackEntry?.destination?.route
        val peekListId = (activeLists.firstOrNull { it.id == selectedListId && it.kind == "grocery" }
            ?: activeLists.firstOrNull { it.kind == "grocery" })?.id
        if (peekListId != null) {
            // Animated (not a plain if) because keep-awake toggles it while the
            // screen is showing — a snap in/out would be jarring mid-recipe.
            AnimatedVisibility(
                visible = showMatkassePill(route) && !cookingKeepAwake,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
            ) {
                MatkasseQuickAccess(
                    repo = repo,
                    listId = peekListId,
                    onOpenFull = { selectedListId = peekListId; nav.popBackStack("home", false) },
                )
            }
        }
    }

    // Offer a self-update once the user is past onboarding.
    if (settings.identity != null) UpdatePrompt()
}
