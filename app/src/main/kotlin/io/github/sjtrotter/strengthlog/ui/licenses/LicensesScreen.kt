package io.github.sjtrotter.strengthlog.ui.licenses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sjtrotter.strengthlog.ui.components.AppCard
import io.github.sjtrotter.strengthlog.ui.theme.AppTheme
import io.github.sjtrotter.strengthlog.ui.theme.Background
import io.github.sjtrotter.strengthlog.ui.theme.Border
import io.github.sjtrotter.strengthlog.ui.theme.Surface2
import io.github.sjtrotter.strengthlog.ui.theme.TabLetter
import io.github.sjtrotter.strengthlog.ui.theme.TextFaint
import io.github.sjtrotter.strengthlog.ui.theme.TextPrimary
import io.github.sjtrotter.strengthlog.ui.theme.TextSecondary

/** One license/notice text, read from `assets/licenses/` by the route (LicensesRoute in AppNavHost). */
data class LicenseEntry(val title: String, val body: String)

/**
 * Static OSS-licenses screen (M6 #23, the "Oswald/Barlow OFL isn't packaged"
 * ledger debt): renders whatever [entries] the route loaded from
 * `assets/licenses/` so the SIL OFL text for the bundled Barlow Condensed
 * font — and the Apache-2.0 notice for the other bundled libraries — actually
 * ship inside the APK instead of living repo-only. Stateless like every other
 * screen here: no view-model, since there's nothing to mutate.
 */
@Composable
fun LicensesScreen(entries: List<LicenseEntry>, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            LicensesHeader(onBack)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                entries.forEach { entry ->
                    item { LicenseCard(entry) }
                }
                item { Spacer(Modifier.size(8.dp)) }
            }
        }
    }
}

@Composable
private fun LicensesHeader(onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackChevron(onBack)
            Text("OSS LICENSES", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

@Composable
private fun BackChevron(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = TextSecondary, style = TabLetter.copy(fontSize = 20.sp))
    }
}

@Composable
private fun LicenseCard(entry: LicenseEntry) {
    AppCard {
        Text(entry.title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(10.dp))
        Text(entry.body, color = TextFaint, style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(showBackground = true, heightDp = 900, backgroundColor = 0xFF0D0D0F)
@Composable
private fun LicensesScreenPreview() {
    AppTheme {
        LicensesScreen(
            entries = listOf(
                LicenseEntry("Barlow Condensed (SIL OFL 1.1)", "Copyright 2017 The Barlow Project Authors\n\nLicensed under the SIL Open Font License, Version 1.1…"),
                LicenseEntry("Apache License 2.0", "AndroidX, Jetpack Compose, Kotlin, Dagger/Hilt…"),
            ),
            onBack = {},
        )
    }
}
