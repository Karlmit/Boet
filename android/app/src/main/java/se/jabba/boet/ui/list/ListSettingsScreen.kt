package se.jabba.boet.ui.list

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import se.jabba.boet.util.compressImageToBase64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListSettingsScreen(
    repo: Repository,
    listId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by repo.listById(listId).collectAsState(initial = null)

    var blur by remember(list?.bgBlur) { mutableStateOf((list?.bgBlur ?: 0).toFloat()) }
    var overlay by remember(list?.bgOverlay) { mutableStateOf((list?.bgOverlay ?: 0).toFloat()) }
    var uploading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            uploading = true
            scope.launch {
                val encoded = compressImageToBase64(context, uri)
                if (encoded != null) repo.uploadBackground(listId, encoded.first, encoded.second)
                uploading = false
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
                title = { Text(stringResource(R.string.background_image), style = BoetType.headline) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            PrimaryButton(
                text = stringResource(R.string.background_image),
                icon = Icons.Default.Image,
                enabled = !uploading,
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (uploading) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = MossDeep, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_syncing), color = CharcoalMuted, style = BoetType.body)
                }
            }
            if (list?.bgImageUrl != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    scope.launch { list?.let { repo.updateListBackground(it, null, 0, 0) } }
                }) { Text(stringResource(R.string.delete), color = Charcoal) }
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.blur).uppercase(), style = BoetType.label, color = MossDeep)
            Slider(
                value = blur, onValueChange = { blur = it }, valueRange = 0f..100f,
                onValueChangeFinished = { scope.launch { list?.let { repo.updateListDisplay(it, blur.toInt(), overlay.toInt()) } } },
                colors = SliderDefaults.colors(thumbColor = MossDeep, activeTrackColor = Moss),
            )

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.overlay).uppercase(), style = BoetType.label, color = MossDeep)
            Slider(
                value = overlay, onValueChange = { overlay = it }, valueRange = 0f..100f,
                onValueChangeFinished = { scope.launch { list?.let { repo.updateListDisplay(it, blur.toInt(), overlay.toInt()) } } },
                colors = SliderDefaults.colors(thumbColor = MossDeep, activeTrackColor = Moss),
            )
        }
    }
}
