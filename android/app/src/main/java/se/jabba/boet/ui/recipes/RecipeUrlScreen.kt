package se.jabba.boet.ui.recipes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.ui.common.PrimaryButton
import se.jabba.boet.ui.theme.*

// "Import from link": paste a URL, kick off an async server-side scrape+parse,
// and jump straight to the recipe detail screen — a placeholder appears there
// immediately and fills in live as the server works through it (fetch, maybe a
// headless-render fallback, then the same AI structuring as the paste-text
// path), same shape as RecipeAiScreen's paste flow.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeUrlScreen(
    repo: Repository,
    onParsed: (String) -> Unit, // navigates to the (still-parsing) recipe's detail screen
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun fetch() {
        val input = url.trim()
        if (input.isEmpty()) return
        scope.launch {
            busy = true; error = null
            val id = repo.startUrlScrape(input)
            busy = false
            if (id == null) {
                error = context.getString(R.string.recipe_url_failed)
            } else {
                onParsed(id)
            }
        }
    }

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
                title = { Text(stringResource(R.string.recipe_url_title), style = BoetType.headline) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.recipe_url_hint), style = BoetType.body, color = CharcoalMuted)
            Spacer(Modifier.height(12.dp))
            val placeholderText = stringResource(R.string.recipe_url_placeholder)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(placeholderText, color = CharcoalMuted) },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, style = BoetType.body, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(color = MossDeep)
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.recipe_url_parse),
                        onClick = { fetch() },
                        enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
