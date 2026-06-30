package se.jabba.boet.ui.recipes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.remote.RecipeDoc
import se.jabba.boet.data.remote.RecipeIngredient
import se.jabba.boet.data.remote.RecipeJson
import se.jabba.boet.data.remote.RecipeStep
import se.jabba.boet.ui.common.CategoryHeader
import se.jabba.boet.ui.theme.*
import java.util.UUID

// One editable ingredient line. The freeform `line` round-trips the document's
// `display`; original structured fields (quantity/unit/food/note) are carried
// through untouched so editing an AI-parsed recipe doesn't strip them.
private class IngRow(
    val id: String,
    line: String,
    val origFood: String?,
    val quantity: Double?,
    val unit: String?,
    val note: String?,
) {
    var line by mutableStateOf(line)
}

// One editable step. `text` and `timer` are editable; ingredient refs are preserved.
private class StepRow(
    val id: String,
    text: String,
    val refs: List<String>,
    timer: Int?,
) {
    var text by mutableStateOf(text)
    var timer by mutableStateOf(timer)   // seconds, or null for no timer
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(
    repo: Repository,
    recipeId: String?,                 // null = create new
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val existing by (if (recipeId != null) repo.recipeById(recipeId) else kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = null)

    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var servings by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    val ingredients = remember { mutableStateListOf<IngRow>() }
    val steps = remember { mutableStateListOf<StepRow>() }
    var seeded by rememberSaveable { mutableStateOf(recipeId == null) }

    // Seed editor state once from the loaded recipe (edit mode).
    LaunchedEffect(existing) {
        val e = existing
        if (!seeded && e != null) {
            val doc = RecipeJson.decode(e.data)
            name = doc.name
            description = doc.description.orEmpty()
            servings = doc.servings?.let { formatServings(it) } ?: ""
            category = e.categoryName.orEmpty()
            ingredients.clear()
            doc.ingredients.forEach {
                ingredients.add(IngRow(it.id, it.display.ifBlank { it.food }, it.food, it.quantity, it.unit, it.note))
            }
            steps.clear()
            doc.steps.forEach { steps.add(StepRow(it.id, it.text, it.ingredientRefs, it.timerSeconds)) }
            seeded = true
        }
    }

    fun save() {
        val doc = RecipeDoc(
            name = name.trim(),
            description = description.trim().ifBlank { null },
            image = (existing?.let { RecipeJson.decode(it.data).image }),  // preserve any existing image
            servings = servings.trim().replace(',', '.').toDoubleOrNull(),
            sourceUrl = existing?.let { RecipeJson.decode(it.data).sourceUrl },
            ingredients = ingredients.mapNotNull { row ->
                val line = row.line.trim()
                if (line.isEmpty()) return@mapNotNull null
                RecipeIngredient(
                    id = row.id, quantity = row.quantity, unit = row.unit,
                    food = row.origFood ?: line, display = line, note = row.note,
                )
            },
            steps = steps.mapNotNull { row ->
                val t = row.text.trim()
                if (t.isEmpty()) return@mapNotNull null
                RecipeStep(id = row.id, text = t, ingredientRefs = row.refs, timerSeconds = row.timer)
            },
        )
        scope.launch {
            val id = repo.saveRecipe(doc, id = recipeId, categoryName = category.trim().ifBlank { null })
            onSaved(id)
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
                title = {
                    Text(
                        stringResource(if (recipeId == null) R.string.recipe_new else R.string.recipe_edit),
                        style = BoetType.headline,
                    )
                },
                actions = {
                    IconButton(onClick = { save() }, enabled = name.isNotBlank()) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save),
                            tint = if (name.isNotBlank()) MossDeep else CharcoalMuted)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Field(name, { name = it }, stringResource(R.string.recipe_name))
                Spacer(Modifier.height(12.dp))
                Field(description, { description = it }, stringResource(R.string.recipe_description), singleLine = false)
                Spacer(Modifier.height(12.dp))
                Field(servings, { servings = it }, stringResource(R.string.recipe_servings), keyboard = KeyboardType.Number)
                Spacer(Modifier.height(12.dp))
                Field(category, { category = it }, stringResource(R.string.recipe_category))
            }

            item { CategoryHeader(stringResource(R.string.recipe_ingredients), modifier = Modifier.padding(top = 16.dp)) }
            items(ingredients, key = { it.id }) { row ->
                EditableRow(
                    value = row.line,
                    onValue = { row.line = it },
                    placeholder = stringResource(R.string.recipe_ingredient_hint),
                    onRemove = { ingredients.remove(row) },
                )
            }
            item { AddButton(stringResource(R.string.recipe_add_ingredient)) { ingredients.add(IngRow(UUID.randomUUID().toString(), "", null, null, null, null)) } }

            item { CategoryHeader(stringResource(R.string.recipe_steps), modifier = Modifier.padding(top = 16.dp)) }
            itemsIndexed(steps, key = { _, it -> it.id }) { idx, row ->
                Column(Modifier.padding(top = 4.dp)) {
                    EditableRow(
                        value = row.text,
                        onValue = { row.text = it },
                        placeholder = "${idx + 1}. " + stringResource(R.string.recipe_step_hint),
                        singleLine = false,
                        onRemove = { steps.remove(row) },
                    )
                    TimerButton(seconds = row.timer, onSet = { row.timer = it })
                }
            }
            item { AddButton(stringResource(R.string.recipe_add_step)) { steps.add(StepRow(UUID.randomUUID().toString(), "", emptyList(), null)) } }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value, onValueChange = onValue,
        label = { Text(label, color = CharcoalMuted) },
        singleLine = singleLine,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableRow(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onValue,
            placeholder = { Text(placeholder, color = CharcoalMuted) },
            singleLine = singleLine,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.delete), tint = CharcoalMuted)
        }
    }
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
        Icon(Icons.Default.Add, contentDescription = null, tint = MossDeep)
        Spacer(Modifier.width(8.dp))
        Text(text, style = BoetType.body, color = MossDeep)
    }
}

// Per-step timer: shows "Lägg till timer" or "⏱ N min"; a dialog sets/clears it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerButton(seconds: Int?, onSet: (Int?) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    TextButton(onClick = { dialog = true }, modifier = Modifier.padding(start = 12.dp)) {
        Icon(Icons.Default.Timer, contentDescription = null, tint = MossDeep, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (seconds != null && seconds > 0) stringResource(R.string.recipe_timer_n, seconds / 60)
            else stringResource(R.string.recipe_add_timer),
            style = BoetType.body, color = MossDeep,
        )
    }
    if (dialog) {
        var minutes by remember { mutableStateOf(seconds?.let { (it / 60).toString() } ?: "") }
        AlertDialog(
            containerColor = WarmWhite,
            onDismissRequest = { dialog = false },
            title = { Text(stringResource(R.string.recipe_timer_title), style = BoetType.headline) },
            text = {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { v -> minutes = v.filter { it.isDigit() } },
                    label = { Text(stringResource(R.string.recipe_timer_minutes), color = CharcoalMuted) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = minutes.toIntOrNull()
                    onSet(if (m != null && m > 0) m * 60 else null)
                    dialog = false
                }) { Text(stringResource(R.string.save), color = MossDeep) }
            },
            dismissButton = {
                TextButton(onClick = { onSet(null); dialog = false }) {
                    Text(stringResource(R.string.recipe_timer_clear), color = Charcoal)
                }
            },
        )
    }
}
