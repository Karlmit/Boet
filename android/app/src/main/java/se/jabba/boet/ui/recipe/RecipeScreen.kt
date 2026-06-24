package se.jabba.boet.ui.recipe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.remote.RecipeSuggestion
import se.jabba.boet.ui.common.PrimaryButton
import se.jabba.boet.ui.list.BoetCheckbox
import se.jabba.boet.ui.list.QuantityBadge
import se.jabba.boet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeScreen(
    repo: Repository,
    listId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<RecipeSuggestion>>(emptyList()) }
    val selected = remember { mutableStateMapOf<Int, Boolean>() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmWhite),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Charcoal)
                    }
                },
                title = { Text(stringResource(R.string.recipe_to_list), style = BoetType.headline) },
            )
        },
        bottomBar = {
            if (suggestions.isNotEmpty()) {
                val count = selected.count { it.value }
                Surface(color = WarmWhite, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        PrimaryButton(
                            stringResource(R.string.add_selected, count),
                            enabled = count > 0,
                            onClick = {
                                val picked = suggestions.filterIndexed { i, _ -> selected[i] == true }
                                scope.launch {
                                    repo.addItems(listId, picked.map { it.name to it.quantity })
                                    onBack()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.recipe_hint), color = CharcoalMuted) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                stringResource(R.string.generate_suggestions),
                enabled = text.isNotBlank() && !loading,
                onClick = {
                    loading = true
                    scope.launch {
                        suggestions = repo.parseRecipe(text)
                        selected.clear()
                        suggestions.indices.forEach { selected[it] = true }
                        loading = false
                    }
                },
            )
            if (loading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = MossDeep)
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(suggestions) { i, s ->
                    Surface(
                        color = Leaf,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { selected[i] = !(selected[i] ?: false) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            BoetCheckbox(checked = selected[i] ?: false, onToggle = { selected[i] = !(selected[i] ?: false) })
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = BoetType.title, color = Charcoal)
                                if (!s.category.isNullOrBlank()) {
                                    Text(s.category, style = BoetType.body, color = MossDeep)
                                }
                            }
                            if (!s.quantity.isNullOrBlank()) QuantityBadge(s.quantity)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
