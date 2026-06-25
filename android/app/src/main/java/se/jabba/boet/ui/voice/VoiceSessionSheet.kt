package se.jabba.boet.ui.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.ai.VoiceItem
import se.jabba.boet.ui.theme.*

private enum class Phase { Listening, Processing, Review }

// Full-screen continuous voice flow. While LISTENING it just accumulates the raw
// transcript (nothing is added yet). On Stop the on-device LLM cleans it into tidy
// grocery items (fixes mis-hearings, parses quantities, drops non-groceries) and we
// move to REVIEW, where the user approves all — or checks a subset — before adding.
@Composable
fun VoiceSessionSheet(
    language: String,
    // When the device is synced to the server, cleaning runs through the household
    // LLM on the *whole* transcript, so we show the full running voice-to-text string
    // (what actually gets sent) instead of per-utterance bullets.
    serverSynced: Boolean,
    clean: suspend (List<String>) -> List<VoiceItem>,
    onConfirm: (List<VoiceItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { VoiceRecognizer(context) }

    var phase by remember { mutableStateOf(Phase.Listening) }
    var partial by remember { mutableStateOf("") }
    val transcript = remember { mutableStateListOf<String>() }   // raw utterances heard
    var items by remember { mutableStateOf<List<VoiceItem>>(emptyList()) }
    val checked = remember { mutableStateMapOf<Int, Boolean>() }

    fun process() {
        recognizer.stop()
        partial = ""
        phase = Phase.Processing
        scope.launch {
            items = clean(transcript.toList())
            checked.clear()
            phase = Phase.Review
        }
    }

    DisposableEffect(Unit) {
        recognizer.start(language, continuous = true, object : VoiceRecognizer.Callbacks {
            override fun onPartial(t: String) { partial = t }
            override fun onResult(t: String) { transcript.add(t.trim()); partial = "" }
            override fun onError(code: Int) {}
            override fun onEnd() {}
        })
        onDispose { recognizer.stop() }
    }

    Dialog(
        onDismissRequest = { recognizer.stop(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(NightBase)) {
            IconButton(
                onClick = { recognizer.stop(); onDismiss() },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = Stone)
            }

            when (phase) {
                Phase.Listening -> ListeningView(partial, transcript, serverSynced, onStop = { process() })
                Phase.Processing -> ProcessingView()
                Phase.Review -> ReviewView(
                    items = items,
                    checked = checked,
                    onToggle = { i -> checked[i] = !(checked[i] ?: false) },
                    onConfirm = { selected -> onConfirm(selected); onDismiss() },
                    onCancel = { onDismiss() },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ListeningView(
    partial: String,
    transcript: List<String>,
    serverSynced: Boolean,
    onStop: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        PulsingMic()
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.listening), style = BoetType.headline, color = WarmWhite)
        Spacer(Modifier.height(16.dp))
        if (serverSynced) {
            // The LLM cleans the whole transcript on Stop, so the split into separate
            // utterances is irrelevant — show the full running string instead, so the
            // user can confirm their words were picked up before sending.
            LiveTranscript(transcript, partial, Modifier.weight(1f).fillMaxWidth())
        } else {
            Text(
                if (partial.isNotBlank()) "”$partial”" else stringResource(R.string.voice_continuous_hint),
                style = BoetType.body, color = Sage,
            )
            Spacer(Modifier.height(24.dp))
            if (transcript.isNotEmpty()) {
                Text(
                    stringResource(R.string.voice_heard_count, transcript.size),
                    style = BoetType.label, color = Stone, modifier = Modifier.align(Alignment.Start),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(transcript.reversed()) { line ->
                        Text("• $line", style = BoetType.body, color = Stone)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        StopButton(onStop)
        Spacer(Modifier.height(28.dp))
    }
}

// The whole running voice-to-text string: finalized utterances in warm white with
// the in-flight partial dimmed at the tail, auto-scrolled to the newest words. This
// is the exact transcript handed to the cleaning LLM.
@Composable
private fun LiveTranscript(transcript: List<String>, partial: String, modifier: Modifier = Modifier) {
    val finalized = transcript.joinToString(" ").trim()
    val scroll = rememberScrollState()
    val text = buildAnnotatedString {
        append(finalized)
        if (partial.isNotBlank()) {
            if (finalized.isNotEmpty()) append(" ")
            withStyle(SpanStyle(color = Sage)) { append(partial) }
        }
    }
    LaunchedEffect(text.length) { scroll.animateScrollTo(scroll.maxValue) }
    Box(modifier.verticalScroll(scroll)) {
        if (text.isEmpty()) {
            Text(stringResource(R.string.voice_freeform_hint), style = BoetType.body, color = Stone)
        } else {
            Text(text, style = BoetType.title, color = WarmWhite)
        }
    }
}

@Composable
private fun BoxScope.ProcessingView() {
    Column(
        Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Sage)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.voice_cleaning), style = BoetType.title, color = WarmWhite)
    }
}

@Composable
private fun BoxScope.ReviewView(
    items: List<VoiceItem>,
    checked: Map<Int, Boolean>,
    onToggle: (Int) -> Unit,
    onConfirm: (List<VoiceItem>) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text(stringResource(R.string.voice_review_title), style = BoetType.headline, color = WarmWhite, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(12.dp))

        if (items.isEmpty()) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.voice_none), style = BoetType.body, color = Stone)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onCancel,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Moss, contentColor = WarmWhite),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.close), style = BoetType.title) }
            Spacer(Modifier.height(28.dp))
            return
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(items) { i, item ->
                val isChecked = checked[i] == true
                Surface(
                    color = NightSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onToggle(i) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            if (isChecked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isChecked) Sage else Stone,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(item.name, style = BoetType.title, color = WarmWhite, modifier = Modifier.weight(1f))
                        val qty = item.quantity?.trim()
                        if (!qty.isNullOrBlank()) {
                            val label = if (qty.toIntOrNull() != null) "×$qty" else qty
                            Surface(color = Moss, shape = RoundedCornerShape(8.dp)) {
                                Text(label, style = BoetType.body, color = WarmWhite,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val anyChecked = (0 until items.size).any { checked[it] == true }
        val selected = if (anyChecked) items.filterIndexed { i, _ -> checked[i] == true } else items
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Stone),
                modifier = Modifier.height(56.dp),
            ) { Text(stringResource(R.string.cancel), style = BoetType.title) }
            Button(
                onClick = { onConfirm(selected) },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Moss, contentColor = WarmWhite),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(
                    if (anyChecked) stringResource(R.string.add_selected, selected.size) else stringResource(R.string.accept_all),
                    style = BoetType.title, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    Button(
        onClick = onStop,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Moss, contentColor = WarmWhite),
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
        Icon(Icons.Default.Stop, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.voice_stop), style = BoetType.title, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PulsingMic() {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(120.dp).scale(pulse).clip(CircleShape).background(Moss.copy(alpha = 0.25f)))
        Box(Modifier.size(88.dp).clip(CircleShape).background(Moss), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Mic, contentDescription = null, tint = WarmWhite, modifier = Modifier.size(40.dp))
        }
    }
}
