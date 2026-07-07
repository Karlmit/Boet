package se.jabba.boet.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import se.jabba.boet.BoetApp
import se.jabba.boet.MainActivity
import se.jabba.boet.R
import se.jabba.boet.data.local.Prefs
import se.jabba.boet.ui.theme.BoetTheme
import se.jabba.boet.ui.theme.BoetType
import se.jabba.boet.ui.theme.Charcoal
import se.jabba.boet.ui.theme.CharcoalMuted
import se.jabba.boet.ui.theme.Moss
import se.jabba.boet.ui.theme.MossDeep
import se.jabba.boet.ui.theme.Stone
import se.jabba.boet.ui.theme.WarmWhite
import java.util.Locale

// Quick add from the home-screen widget: a lightweight translucent overlay with
// just a text field — no full app launch. Adds go through the normal Repository
// path (optimistic Room write + outbox), so they categorize on-device and sync
// exactly like an in-app add, online or offline.
class WidgetAddActivity : ComponentActivity() {

    // Same locale handling as MainActivity, so the overlay follows the app language.
    override fun attachBaseContext(newBase: Context) {
        val lang = runCatching { runBlocking { Prefs(newBase).settings.first().language } }
            .getOrDefault("sv")
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale(lang))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BoetApp

        // Before onboarding there's no identity/list to add to — open the app instead.
        val onboarded = runCatching { runBlocking { app.prefs.settings.first().identity } }
            .getOrNull() != null
        if (!onboarded) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContent {
            BoetTheme {
                var addedCount by remember { mutableIntStateOf(0) }
                QuickAddOverlay(
                    addedCount = addedCount,
                    onAdd = { text ->
                        val names = text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                        if (names.isNotEmpty()) {
                            addedCount += names.size
                            lifecycleScope.launch {
                                val listId = app.repository.groceryListId()
                                if (listId != null) {
                                    app.repository.addItems(listId, names.map { it to null })
                                    MatkasseWidget.update(applicationContext)
                                }
                            }
                        }
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // A transient overlay: leaving it (home button, tapping elsewhere) closes it.
        if (!isFinishing) finish()
    }
}

@Composable
private fun QuickAddOverlay(
    addedCount: Int,
    onAdd: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }

    // Dimmed scrim; a tap outside the card dismisses.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = WarmWhite,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                // Swallow taps on the card so they don't reach the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.widget_quick_add), style = BoetType.title, color = Charcoal)
                Spacer(Modifier.height(12.dp))
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
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Running confirmation so adding several in a row feels acknowledged.
                    Text(
                        text = if (addedCount > 0) stringResource(R.string.voice_added_count, addedCount)
                        else stringResource(R.string.widget_add_more_hint),
                        style = BoetType.label,
                        color = if (addedCount > 0) MossDeep else CharcoalMuted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.close), color = MossDeep)
                    }
                    Button(
                        onClick = { submit() },
                        enabled = text.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MossDeep, contentColor = WarmWhite),
                    ) {
                        Text(stringResource(R.string.add))
                    }
                }
            }
        }
    }
}
