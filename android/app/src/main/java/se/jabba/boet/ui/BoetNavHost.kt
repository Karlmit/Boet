package se.jabba.boet.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import se.jabba.boet.BoetApp
import se.jabba.boet.data.local.Settings
import se.jabba.boet.ui.list.CategoryManageScreen
import se.jabba.boet.ui.list.ListScreen
import se.jabba.boet.ui.list.ListSettingsScreen
import se.jabba.boet.ui.lists.ListsScreen
import se.jabba.boet.ui.onboarding.OnboardingScreen
import se.jabba.boet.ui.settings.SettingsScreen
import se.jabba.boet.ui.shopping.ShoppingScreen
import se.jabba.boet.update.UpdatePrompt
import kotlinx.coroutines.launch

@Composable
fun BoetNavHost(app: BoetApp, settings: Settings) {
    // Localization is applied at the Activity level (MainActivity.attachBaseContext)
    // so dialogs and bottom sheets — which render in separate windows — are localized
    // too. Nothing locale-related needs to happen here.
    run {
        val repo = app.repository
        val nav = rememberNavController()
        val scope = rememberCoroutineScope()

        // The currently shown list (defaults to the first list once loaded).
        var selectedListId by rememberSaveable { mutableStateOf<String?>(null) }
        val activeLists by repo.activeLists().collectAsState(initial = emptyList())

        LaunchedEffect(activeLists) {
            if (selectedListId == null || activeLists.none { it.id == selectedListId }) {
                selectedListId = activeLists.firstOrNull()?.id
            }
        }
        LaunchedEffect(Unit) { repo.bootstrap() }

        val start = if (settings.identity == null) "onboarding" else "home"

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
                        onOpenLists = { nav.navigate("lists") },
                        onOpenSettings = { nav.navigate("settings") },
                        onOpenShopping = { nav.navigate("shopping/$listId") },
                        onOpenCategories = { nav.navigate("categories/$listId") },
                        onOpenListSettings = { nav.navigate("listsettings/$listId") },
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

            composable("shopping/{listId}") { entry ->
                val id = entry.arguments?.getString("listId") ?: return@composable
                ShoppingScreen(
                    repo = repo,
                    listId = id,
                    serverUrl = settings.serverUrl,
                    prefs = app.prefs,
                    initialHideCompleted = settings.shoppingHideCompleted,
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

        // Offer a self-update once the user is past onboarding.
        if (settings.identity != null) UpdatePrompt()
    }
}
