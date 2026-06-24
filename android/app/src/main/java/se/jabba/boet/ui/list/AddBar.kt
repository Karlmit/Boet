package se.jabba.boet.ui.list

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import se.jabba.boet.R
import se.jabba.boet.ui.theme.*
import se.jabba.boet.ui.voice.VoiceRecognizer
import se.jabba.boet.ui.voice.parseSpokenItems

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AddBar(
    language: String,
    onAdd: (String) -> Unit,
    onSpoken: (List<String>) -> Unit,
    onShopping: () -> Unit,
    onRecipe: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val recognizer = remember { VoiceRecognizer(context) }

    var continuous by remember { mutableStateOf(false) }

    fun startVoice(cont: Boolean) {
        listening = true
        continuous = cont
        partial = ""
        recognizer.start(language, cont, object : VoiceRecognizer.Callbacks {
            override fun onPartial(t: String) { partial = t }
            override fun onResult(t: String) { onSpoken(parseSpokenItems(t)) }
            override fun onError(code: Int) {}
            override fun onEnd() { listening = false; continuous = false; partial = "" }
        })
    }

    var pendingContinuous by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoice(pendingContinuous) }

    fun submit() {
        if (text.isNotBlank()) { onAdd(text); text = "" }
    }

    Surface(color = WarmWhite, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onShopping) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = MossDeep, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.shopping_mode), color = MossDeep, style = BoetType.title)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRecipe) {
                    Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = MossDeep, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.recipe_to_list), color = MossDeep, style = BoetType.title)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.add_item_hint), color = CharcoalMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Moss,
                        unfocusedBorderColor = Stone,
                        focusedContainerColor = WarmWhite,
                        unfocusedContainerColor = WarmWhite,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // Mic button — tap = single add, long-press = continuous session.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (listening) Moss else Leaf)
                        .combinedClickable(
                            onClick = {
                                if (listening) recognizer.stop()
                                else { pendingContinuous = false; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                            },
                            onLongClick = {
                                if (!listening) { pendingContinuous = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                            },
                        ),
                ) {
                    Icon(
                        if (listening && continuous) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(R.string.add_with_voice),
                        tint = if (listening) WarmWhite else MossDeep,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(color = MossDeep, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                    IconButton(onClick = { submit() }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add), tint = WarmWhite)
                    }
                }
            }
            if (listening) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (partial.isNotBlank()) partial else stringResource(R.string.listening),
                    style = BoetType.body, color = MossDeep,
                )
            }
        }
    }
}
