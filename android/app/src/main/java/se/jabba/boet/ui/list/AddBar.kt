package se.jabba.boet.ui.list

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBar(
    language: String,
    onAdd: (String) -> Unit,
    onOpenVoice: () -> Unit,
    onShowFavorites: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    // Voice needs RECORD_AUDIO; request it, then open the full-screen session.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onOpenVoice() }

    fun submit() {
        if (text.isNotBlank()) { onAdd(text); text = "" }
    }

    // The bottom bar is now focused purely on adding: a prominent voice pill and a
    // text field. Shopping Mode moved up to the banner; auto-sort is automatic.
    Surface(color = WarmWhite, shadowElevation = 8.dp, modifier = Modifier.imePadding().navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            VoiceButton(onTap = { micPermission.launch(Manifest.permission.RECORD_AUDIO) })
            Spacer(Modifier.height(8.dp))
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
                // Tap with text → add it; tap while empty → open the favorites
                // quick-add sheet so saved items are one tap away.
                Surface(color = MossDeep, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                    IconButton(onClick = { if (text.isBlank()) onShowFavorites() else submit() }) {
                        Icon(
                            if (text.isBlank()) Icons.Default.Star else Icons.Default.Add,
                            contentDescription = if (text.isBlank()) stringResource(R.string.favorites) else stringResource(R.string.add),
                            tint = WarmWhite,
                        )
                    }
                }
            }
        }
    }
}

// Prominent full-width voice entry. It's pressed only occasionally, so it earns a
// little delight (emilkowalski): a soft Moss ripple emanates from the tap point and
// the pill dips on press — feedback that makes the button feel special and alive.
@Composable
private fun VoiceButton(onTap: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var center by remember { mutableStateOf(Offset.Zero) }
    val ripple = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "voiceScale")

    Surface(
        color = Leaf,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .drawWithContent {
                    drawContent()
                    if (ripple.value > 0f) {
                        drawCircle(
                            color = MossDeep.copy(alpha = (1f - ripple.value) * 0.22f),
                            radius = size.maxDimension * ripple.value,
                            center = center,
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { pos ->
                            center = pos
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                ripple.snapTo(0f)
                                ripple.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
                            }
                            onTap()
                        },
                    )
                }
                .padding(vertical = 13.dp),
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, tint = MossDeep, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_with_voice), color = MossDeep, style = BoetType.title, fontWeight = FontWeight.SemiBold)
        }
    }
}
