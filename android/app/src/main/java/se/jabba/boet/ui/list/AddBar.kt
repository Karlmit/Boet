package se.jabba.boet.ui.list

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
    var focused by remember { mutableStateOf(false) }

    // Voice needs RECORD_AUDIO; request it, then open the full-screen session.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onOpenVoice() }

    fun submit() {
        if (text.isNotBlank()) { onAdd(text); text = "" }
    }

    // A single compact row: text field, a small mic circle, and the add/favorites
    // button. The mic collapses away when the field is focused so the text box gets
    // the full width exactly when the user needs the room to type.
    Surface(color = WarmWhite, shadowElevation = 8.dp, modifier = Modifier.imePadding().navigationBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
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
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
            )
            // Mic sits just left of the add button; hidden while typing.
            AnimatedVisibility(
                visible = !focused,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    CircleIconButton(
                        onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        container = Leaf,
                        tint = MossDeep,
                        icon = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.add_with_voice),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Tap with text → add it; tap while empty → open the favorites quick-add
            // sheet so saved items are one tap away.
            CircleIconButton(
                onClick = { if (text.isBlank()) onShowFavorites() else submit() },
                container = MossDeep,
                tint = WarmWhite,
                icon = if (text.isBlank()) Icons.Default.Star else Icons.Default.Add,
                contentDescription = if (text.isBlank()) stringResource(R.string.favorites) else stringResource(R.string.add),
            )
        }
    }
}

// 52dp circular action button with a press-dip + haptic (emilkowalski tap feedback).
@Composable
private fun CircleIconButton(
    onClick: () -> Unit,
    container: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "circlePress")
    Surface(
        color = container,
        shape = CircleShape,
        modifier = Modifier
            .size(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}
