package io.github.sjtrotter.strengthlog.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.github.sjtrotter.strengthlog.wear.theme.Background
import io.github.sjtrotter.strengthlog.wear.theme.TextPrimary
import io.github.sjtrotter.strengthlog.wear.theme.TextSecondary

/**
 * Shown when the watch has a real (non-null) snapshot but the phone hasn't
 * generated a program yet — the day's exercise list is empty (design digest
 * §3). This is deliberately the *only* signal used: a null snapshot means
 * "haven't heard from the phone at all" ([LoadingScreen]), while an empty one
 * means "heard from the phone, and there's genuinely nothing to show yet".
 *
 * Non-goal, on purpose: there is no setup flow here and never will be — the
 * setup wizard is phone-only by design (spec §9: the watch is glanceable
 * in-set logging, never a form on the wrist).
 */
@Composable
fun EmptyScreen() {
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Text("no program yet", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                text = "answer the setup wizard on your phone — the day appears here",
                color = TextSecondary,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
