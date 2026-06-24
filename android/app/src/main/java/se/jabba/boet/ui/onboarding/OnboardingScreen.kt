package se.jabba.boet.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.jabba.boet.R
import se.jabba.boet.ui.common.PrimaryButton
import se.jabba.boet.ui.common.Wordmark
import se.jabba.boet.ui.theme.BoetType
import se.jabba.boet.ui.theme.CharcoalMuted

@Composable
fun OnboardingScreen(onPick: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(160.dp),
            )
            Spacer(Modifier.height(8.dp))
            Wordmark()
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_tagline),
                style = BoetType.body,
                color = CharcoalMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            Text(stringResource(R.string.onboarding_who), style = BoetType.headline)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Kalle", onClick = { onPick("Kalle") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Klara", onClick = { onPick("Klara") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
