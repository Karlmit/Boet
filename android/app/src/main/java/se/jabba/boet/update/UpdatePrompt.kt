package se.jabba.boet.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import se.jabba.boet.R
import se.jabba.boet.ui.theme.Charcoal
import se.jabba.boet.ui.theme.MossDeep
import se.jabba.boet.ui.theme.WarmWhite

// Checks for a newer GitHub release once on launch and, if found, offers to
// download + install it. Silent when there's nothing new or the check fails.
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { info = UpdateChecker.check(context) }

    val update = info ?: return
    AlertDialog(
        onDismissRequest = { if (!downloading) info = null },
        containerColor = WarmWhite,
        title = { Text(stringResource(R.string.update_available, update.versionName)) },
        text = {
            val notes = if (update.notes.isBlank()) "" else "\n\n${update.notes}"
            Text(stringResource(R.string.update_message) + notes, color = Charcoal)
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (!UpdateChecker.ensureCanInstall(context)) return@TextButton
                    scope.launch {
                        downloading = true
                        val ok = runCatching { UpdateChecker.downloadAndInstall(context, update) }.isSuccess
                        downloading = false
                        if (ok) info = null
                    }
                },
            ) {
                Text(
                    stringResource(if (downloading) R.string.update_downloading else R.string.update_now),
                    color = MossDeep,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = { info = null }) {
                Text(stringResource(R.string.update_later), color = Charcoal)
            }
        },
    )
}
