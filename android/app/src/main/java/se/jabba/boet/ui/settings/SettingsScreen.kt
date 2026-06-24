package se.jabba.boet.ui.settings

import androidx.compose.foundation.layout.*
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
import se.jabba.boet.data.local.Prefs
import se.jabba.boet.data.local.Settings
import se.jabba.boet.ui.common.Avatar
import se.jabba.boet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    settings: Settings,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }

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
                title = { Text(stringResource(R.string.settings), style = BoetType.headline) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            // Identity
            SectionLabel(stringResource(R.string.identity))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(settings.identity)
                Spacer(Modifier.width(12.dp))
                listOf("Kalle", "Klara").forEach { who ->
                    FilterChip(
                        selected = settings.identity == who,
                        onClick = { scope.launch { prefs.setIdentity(who) } },
                        label = { Text(who) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Moss, selectedLabelColor = WarmWhite),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.language))
            Row {
                LangChip("sv", stringResource(R.string.lang_swedish), settings.language) { scope.launch { prefs.setLanguage(it) } }
                Spacer(Modifier.width(8.dp))
                LangChip("en", stringResource(R.string.lang_english), settings.language) { scope.launch { prefs.setLanguage(it) } }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.server_url))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Moss, unfocusedBorderColor = Stone),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { scope.launch { prefs.setServerUrl(serverUrl) } }) {
                Text(stringResource(R.string.save), color = MossDeep)
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.notifications))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.notifications,
                    onCheckedChange = { scope.launch { prefs.setNotifications(it) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = WarmWhite, checkedTrackColor = MossDeep),
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.notifications), style = BoetType.body, color = Charcoal)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = BoetType.label, color = MossDeep, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun LangChip(code: String, label: String, current: String, onPick: (String) -> Unit) {
    FilterChip(
        selected = current == code,
        onClick = { onPick(code) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Moss, selectedLabelColor = WarmWhite),
    )
}
