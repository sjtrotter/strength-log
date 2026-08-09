package cloud.trotter.log.strength.ui.licenses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.pressable
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Background
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.Surface2
import cloud.trotter.log.strength.ui.theme.TabLetter
import cloud.trotter.log.strength.ui.theme.TextFaint
import cloud.trotter.log.strength.ui.theme.TextPrimary
import cloud.trotter.log.strength.ui.theme.TextSecondary
import cloud.trotter.log.strength.ui.theme.readableWidth

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
        Column(readableWidth()) {
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
        HorizontalDivider(thickness = 1.dp, color = Border)
    }
}

@Composable
private fun BackChevron(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(40.dp, 40.dp)
            .background(Surface2, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .pressable(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                onClickLabel = "Back",
                role = Role.Button,
            )
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "‹",
            color = TextSecondary,
            style = TabLetter.copy(fontSize = 20.sp),
            modifier = Modifier.clearAndSetSemantics {},
        )
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
