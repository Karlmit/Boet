package se.jabba.boet.ui.recipes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.data.Repository
import se.jabba.boet.data.remote.RecipeDoc
import se.jabba.boet.data.remote.RecipeIngredient
import se.jabba.boet.data.remote.RecipeJson
import se.jabba.boet.data.remote.RecipeStep
import se.jabba.boet.ui.common.CategoryHeader
import se.jabba.boet.ui.theme.*
import se.jabba.boet.util.compressImageToBase64
import java.util.UUID

// Common Swedish recipe units offered as quick picks in the unit field; any other
// value can still be typed freely (the AI or a Mealie import may produce one we
// don't list here).
private val COMMON_UNITS = listOf(
    "g", "kg", "ml", "dl", "l", "msk", "tsk", "st",
    "burk", "paket", "förp", "klyfta", "knippe", "kruka", "nypa", "skiva",
)

// One editable ingredient as three independent fields (quantity/unit/food) —
// replacing an earlier single freeform line that round-tripped `display`, which
// made every ingredient look like unstructured text regardless of how well the
// AI had actually parsed it. `section` optionally groups this ingredient under a
// sub-recipe heading (e.g. "Marinad", "Sås"); see groupBySection.
private class IngRow(
    val id: String,
    quantity: String,
    unit: String,
    food: String,
    val note: String?,
    section: String?,
) {
    var quantity by mutableStateOf(quantity)
    var unit by mutableStateOf(unit)
    var food by mutableStateOf(food)
    var section by mutableStateOf(section)
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

// Rebuild the human-readable ingredient line from the three edited fields —
// mirrors the server's composeDisplay (recipe-ai.js) so manually edited
// ingredients render identically to AI-parsed ones.
private fun composeDisplayLine(quantity: Double?, unit: String?, food: String): String =
    listOfNotNull(quantity?.let(::formatServings), unit?.trim()?.ifBlank { null }, food.trim().ifBlank { null })
        .joinToString(" ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(
    repo: Repository,
    recipeId: String?,                 // null = create new
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val existing by (if (recipeId != null) repo.recipeById(recipeId) else kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = null)

    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var servings by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var image by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadingImage by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
            image = doc.image
            ingredients.clear()
            doc.ingredients.forEach {
                ingredients.add(IngRow(it.id, it.quantity?.let(::formatServings) ?: "", it.unit.orEmpty(), it.food, it.note, it.section))
            }
            steps.clear()
            doc.steps.forEach { steps.add(StepRow(it.id, it.text, it.ingredientRefs, it.timerSeconds)) }
            seeded = true
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                uploadingImage = true
                val encoded = compressImageToBase64(context, uri)
                val url = encoded?.let { repo.uploadRecipeImage(it.first, it.second) }
                uploadingImage = false
                if (url != null) image = url
            }
        }
    }

    fun save() {
        val doc = RecipeDoc(
            name = name.trim(),
            description = description.trim().ifBlank { null },
            image = image,
            servings = servings.trim().replace(',', '.').toDoubleOrNull(),
            sourceUrl = existing?.let { RecipeJson.decode(it.data).sourceUrl },
            ingredients = ingredients.mapNotNull { row ->
                val food = row.food.trim()
                if (food.isEmpty()) return@mapNotNull null
                val qty = row.quantity.trim().replace(',', '.').toDoubleOrNull()
                val unit = row.unit.trim().ifBlank { null }
                RecipeIngredient(
                    id = row.id, quantity = qty, unit = unit, food = food,
                    display = composeDisplayLine(qty, unit, food), note = row.note,
                    section = row.section?.trim()?.ifBlank { null },
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
                    // Delete only makes sense once a recipe actually exists, and lives
                    // here (edit mode) rather than on the normal detail view, so it can
                    // never be tapped by accident while just browsing a recipe.
                    if (recipeId != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = CharcoalMuted)
                        }
                    }
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
                ImagePicker(image = image, uploading = uploadingImage, onPick = { pickImage.launch("image/*") })
                Spacer(Modifier.height(16.dp))
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
                IngredientRow(row, onRemove = { ingredients.remove(row) })
            }
            item {
                AddButton(stringResource(R.string.recipe_add_ingredient)) {
                    ingredients.add(IngRow(UUID.randomUUID().toString(), "", "", "", null, null))
                }
            }

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

    if (confirmDelete) {
        AlertDialog(
            containerColor = WarmWhite,
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.recipe_delete_q), style = BoetType.headline, color = MaterialTheme.colorScheme.error) },
            text = { Text(stringResource(R.string.recipe_delete_warning), style = BoetType.body, color = Charcoal) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    recipeId?.let { id -> scope.launch { repo.deleteRecipe(id); onDeleted() } }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel), color = Charcoal) } },
        )
    }
}

@Composable
private fun ImagePicker(image: String?, uploading: Boolean, onPick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(14.dp)).background(Leaf).clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            uploading -> CircularProgressIndicator(color = MossDeep)
            image != null -> AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Image, contentDescription = null, tint = MossDeep, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.recipe_add_photo), style = BoetType.body, color = MossDeep)
            }
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

// One ingredient's quantity/unit/food fields plus its optional section tag.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientRow(row: IngRow, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.quantity, onValueChange = { row.quantity = it },
                placeholder = { Text("1", color = CharcoalMuted) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                modifier = Modifier.width(72.dp),
            )
            Spacer(Modifier.width(6.dp))
            UnitField(value = row.unit, onValue = { row.unit = it }, modifier = Modifier.width(104.dp))
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = row.food, onValueChange = { row.food = it },
                placeholder = { Text(stringResource(R.string.recipe_ingredient_food_hint), color = CharcoalMuted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.delete), tint = CharcoalMuted)
            }
        }
        OutlinedTextField(
            value = row.section.orEmpty(),
            onValueChange = { row.section = it },
            placeholder = { Text(stringResource(R.string.recipe_ingredient_section_hint), color = CharcoalMuted) },
            singleLine = true,
            textStyle = BoetType.label,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

// Unit text field with a dropdown of common Swedish recipe units; any value can
// still be typed freely (an AI/Mealie import may produce one we don't list).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value, onValueChange = onValue,
            placeholder = { Text(stringResource(R.string.recipe_ingredient_unit_hint), color = CharcoalMuted) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = CharcoalMuted)
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = WarmWhite) {
            COMMON_UNITS.forEach { u ->
                DropdownMenuItem(text = { Text(u, color = Charcoal) }, onClick = { onValue(u); menu = false })
            }
        }
    }
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
