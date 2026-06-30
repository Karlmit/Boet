package se.jabba.boet.ui.recipes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.ui.common.PrimaryButton
import se.jabba.boet.ui.theme.*

// "Add recipe with AI": paste text (or pull text from a photo via on-device OCR),
// send it to the server parser, then hand the structured draft to the editor for
// review. Nothing is saved until the parse succeeds; failures fall back to manual.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeAiScreen(
    repo: Repository,
    onParsed: (String) -> Unit,     // navigates to the editor for the saved draft
    onManual: () -> Unit,           // fall back to the blank manual editor
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true; error = null
                val ocr = recognizeText(context, uri)
                busy = false
                if (ocr.isBlank()) error = context.getString(R.string.recipe_ocr_empty)
                else text = if (text.isBlank()) ocr else "$text\n$ocr"
            }
        }
    }

    fun parse() {
        val input = text.trim()
        if (input.isEmpty()) return
        scope.launch {
            busy = true; error = null
            val doc = repo.aiParseRecipe(input)
            if (doc == null) {
                busy = false
                error = context.getString(R.string.recipe_ai_failed)
            } else {
                // Save the draft so the editor can load it by id like any recipe.
                val id = repo.saveRecipe(doc)
                busy = false
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
                title = { Text(stringResource(R.string.recipe_ai_title), style = BoetType.headline) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.recipe_ai_hint), style = BoetType.body, color = CharcoalMuted)
            Spacer(Modifier.height(12.dp))
            val placeholderText = stringResource(R.string.recipe_ai_placeholder)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholderText, color = CharcoalMuted) },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { pickImage.launch("image/*") },
                enabled = !busy,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp), tint = MossDeep)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.recipe_ai_from_photo), color = MossDeep)
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, style = BoetType.body, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onManual) { Text(stringResource(R.string.recipe_write_manually), color = MossDeep) }
            }

            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(color = MossDeep)
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.recipe_ai_parse),
                        onClick = { parse() },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
