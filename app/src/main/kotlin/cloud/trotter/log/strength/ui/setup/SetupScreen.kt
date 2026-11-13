package cloud.trotter.log.strength.ui.setup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import cloud.trotter.log.strength.run.LocationAccess
import cloud.trotter.log.strength.run.LocationAnswer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.progression.EffortScale
import androidx.compose.material3.TextButton
import cloud.trotter.log.strength.domain.standards.RestCategory
import cloud.trotter.log.strength.domain.standards.RestPolicy
import cloud.trotter.log.strength.domain.theme.ThemePreference
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.domain.units.DistanceUnit
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.DialogAction
import cloud.trotter.log.strength.ui.components.RadioChoiceDialog
import cloud.trotter.log.strength.ui.components.SheetCommitButton
import cloud.trotter.log.strength.ui.theme.AppTheme
import cloud.trotter.log.strength.ui.theme.Border
import cloud.trotter.log.strength.ui.theme.DoneButtonLabel
import cloud.trotter.log.strength.ui.theme.Error
import cloud.trotter.log.strength.ui.theme.ReadableWidth
import cloud.trotter.log.strength.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    state: SetupUiState,
    actions: SetupActions,
    openRestTimer: Boolean = false,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var blocked by rememberSaveable { mutableStateOf(false) }
    var wanted by rememberSaveable { mutableStateOf(false) }
    val openSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val answer = LocationAnswer.from(grants, activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
        } == true)
        actions.onRefreshLocationAccess()
        when (answer) {
            LocationAnswer.PRECISE -> { blocked = false; wanted = false; actions.onGpsDistanceChange(true) }
            LocationAnswer.APPROXIMATE, LocationAnswer.DENIED -> actions.onGpsDistanceChange(false)
            LocationAnswer.BLOCKED -> { blocked = true; wanted = true; actions.onGpsDistanceChange(false); openSettings() }
        }
    }
    LifecycleResumeEffect(Unit) {
        actions.onRefreshLocationAccess()
        onPauseOrDispose {}
    }
    LaunchedEffect(state.gpsAccess, wanted) {
        if (wanted && state.gpsAccess == LocationAccess.PRECISE) {
            actions.onGpsDistanceChange(true)
            wanted = false
            blocked = false
        }
    }
    var showRestResetConfirm by rememberSaveable { mutableStateOf(false) }
    var showDistanceUnitDialog by rememberSaveable { mutableStateOf(false) }
    var showEffortScaleDialog by rememberSaveable { mutableStateOf(false) }
    val effortScales = listOf(
        EffortScale.WORDS to stringResource(R.string.settings_effort_words),
        EffortScale.REPS_LEFT to stringResource(R.string.settings_effort_reps_left),
    )
    val distanceUnits = mapOf(
        DistanceUnit.MI to R.string.settings_distance_miles,
        DistanceUnit.KM to R.string.settings_distance_kilometers,
    )
    val rows = buildList {
        addAll(listOf("units", "distance", "effort_scale"))
        if (state.gpsAccess != LocationAccess.UNAVAILABLE) addAll(listOf("gps_distance", "save_route"))
        addAll(listOf("theme", "keep_screen_on", "warm_up", "cool_down", "backup", "rest_timer", "custom_exercise", "licenses"))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(openRestTimer) {
        if (openRestTimer) listState.scrollToItem(rows.indexOf("rest_timer"))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.setup_back_description))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = ReadableWidth).fillMaxSize(),
                // The shortcut must align the section even on a tall screen.
                contentPadding = PaddingValues(bottom = if (openRestTimer) maxHeight else 24.dp),
            ) {
                items(rows, key = { it }) { row ->
                    when (row) {
                        "effort_scale" -> ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_effort_scale)) },
                            supportingContent = { Text(effortScales.first { it.first == state.effortScale }.second) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable { showEffortScaleDialog = true },
                        )
                        "units" -> ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_units)) },
                            supportingContent = {
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth(), space = 0.dp) {
                                    WeightUnit.entries.forEachIndexed { index, unit ->
                                        SegmentedButton(
                                            selected = state.unit == unit,
                                            onClick = { actions.onUnitToggle(unit) },
                                            shape = SegmentedButtonDefaults.itemShape(index, WeightUnit.entries.size),
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            icon = {},
                                        ) { Text(unit.name.lowercase()) }
                                    }
                                }
                            },
                        )
                        "distance" -> ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_distance)) },
                            supportingContent = { Text(stringResource(distanceUnits.getValue(state.distanceUnit))) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable { showDistanceUnitDialog = true },
                        )
                        "gps_distance" -> ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_gps_distance)) },
                            supportingContent = { Text(stringResource(when {
                                blocked -> R.string.settings_gps_blocked
                                !state.gpsDistance && state.gpsAccess == LocationAccess.APPROXIMATE -> R.string.settings_gps_needs_precise
                                else -> R.string.settings_gps_distance_detail
                            })) },
                            trailingContent = { Switch(state.gpsDistance, onCheckedChange = null) },
                            modifier = Modifier.fillMaxWidth().toggleable(value = state.gpsDistance, role = Role.Switch) { on ->
                                when {
                                    !on -> actions.onGpsDistanceChange(false)
                                    state.gpsAccess == LocationAccess.PRECISE || state.gpsAccess == LocationAccess.LOCATION_OFF -> actions.onGpsDistanceChange(true)
                                    blocked -> { wanted = true; openSettings() }
                                    else -> permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            },
                        )
                        "save_route" -> {
                            val colors = ListItemDefaults.colors()
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.settings_save_route)) },
                                supportingContent = { Text(stringResource(R.string.settings_save_route_detail)) },
                                trailingContent = { Switch(state.saveRoute, onCheckedChange = null, enabled = state.gpsDistance) },
                                modifier = Modifier.fillMaxWidth().toggleable(value = state.saveRoute,
                                    enabled = state.gpsDistance, role = Role.Switch, onValueChange = actions.onSaveRouteChange),
                                colors = if (state.gpsDistance) colors else colors.copy(
                                    headlineColor = colors.disabledHeadlineColor,
                                    supportingTextColor = colors.disabledHeadlineColor,
                                ),
                            )
                        }
                        "theme" -> ListItem(
                            headlineContent = { Text(stringResource(R.string.setup_theme_label)) },
                            supportingContent = {
                                val themes = listOf(
                                    ThemePreference.SYSTEM to R.string.setup_theme_system_title,
                                    ThemePreference.DARK to R.string.setup_theme_dark_title,
                                    ThemePreference.LIGHT to R.string.setup_theme_light_title,
                                )
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().heightIn(min = 48.dp), space = 0.dp) {
                                    themes.forEachIndexed { index, (theme, label) ->
                                        SegmentedButton(
                                            selected = state.themePreference == theme,
                                            onClick = { actions.onThemePreferenceChange(theme) },
                                            shape = SegmentedButtonDefaults.itemShape(index, themes.size),
                                            icon = {},
                                        ) { Text(stringResource(label)) }
                                    }
                                }
                            },
                        )
                        "keep_screen_on" -> {
                            val label = stringResource(R.string.setup_keep_screen_on)
                            ListItem(
                                headlineContent = { Text(label) },
                                trailingContent = { Switch(state.keepScreenOn, onCheckedChange = null) },
                                modifier = Modifier.fillMaxWidth().toggleable(value = state.keepScreenOn,
                                    role = Role.Switch, onValueChange = actions.onKeepScreenOnChange),
                            )
                        }
                        "warm_up", "cool_down" -> {
                            val warm = row == "warm_up"
                            val checked = if (warm) state.warmUp else state.coolDown
                            ListItem(
                                headlineContent = { Text(stringResource(if (warm) R.string.settings_warm_up else R.string.settings_cool_down)) },
                                supportingContent = { Text(stringResource(if (warm) R.string.settings_warm_up_detail else R.string.settings_cool_down_detail)) },
                                trailingContent = { Switch(checked, onCheckedChange = null) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked,
                                    role = Role.Switch, onValueChange = if (warm) actions.onWarmUpChange else actions.onCoolDownChange),
                            )
                        }
                        "rest_timer" -> Column(Modifier.padding(horizontal = 16.dp)) {
                            SectionHeader(stringResource(R.string.setup_rest_timer_section))
                            RestTimerSection(
                                state.restTimerEnabled, state.phoneRestTimerEnabled, state.restCategories, actions,
                                onResetDefaults = { showRestResetConfirm = true },
                            )
                        }
                        "custom_exercise" -> SettingsLink(stringResource(R.string.settings_custom_exercise), actions.onCreateCustomExercise)
                        "backup" -> SettingsLink(stringResource(R.string.settings_backup), actions.onOpenBackup)
                        "licenses" -> SettingsLink(stringResource(R.string.settings_licenses), actions.onOpenLicenses)
                    }
                }
            }
        }
    }
    if (showEffortScaleDialog) {
        RadioChoiceDialog(
            title = stringResource(R.string.settings_effort_scale), detail = null,
            choices = effortScales, selected = state.effortScale,
            onPick = actions.onEffortScaleChange, onDismiss = { showEffortScaleDialog = false },
        )
    }
    if (showDistanceUnitDialog) {
        AppAlertDialog(
            onDismissRequest = { showDistanceUnitDialog = false },
            title = { Text(stringResource(R.string.settings_distance)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    distanceUnits.forEach { (unit, label) ->
                        ListItem(
                            headlineContent = { Text(stringResource(label)) },
                            leadingContent = { RadioButton(selected = state.distanceUnit == unit, onClick = null) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = state.distanceUnit == unit,
                                role = Role.RadioButton,
                                onClick = {
                                    actions.onDistanceUnitChange(unit)
                                    showDistanceUnitDialog = false
                                },
                            ),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                DialogAction(stringResource(R.string.setup_cancel_button), TextSecondary) {
                    showDistanceUnitDialog = false
                }
            },
        )
    }
    if (showRestResetConfirm) {
        RestDefaultsConfirmDialog(
            onConfirm = {
                showRestResetConfirm = false
                actions.onRestOverridesReset()
            },
            onDismiss = { showRestResetConfirm = false },
        )
    }
}

@Composable
private fun SettingsLink(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun SectionHeader(label: String) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.size(8.dp))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.size(6.dp))
        HorizontalDivider(thickness = 1.dp, color = Border)
    }
}

@Composable
private fun RestTimerSection(
    enabled: Boolean,
    phoneEnabled: Boolean,
    categories: List<RestCategoryUiState>,
    actions: SetupActions,
    onResetDefaults: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RuledGroup {
            ListItem(
                headlineContent = { Text(stringResource(R.string.setup_rest_timer_toggle)) },
                trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = actions.onRestTimerEnabledChange),
            )
            HorizontalDivider(color = Border)
            ListItem(
                headlineContent = { Text(stringResource(R.string.setup_phone_rest_timer_toggle)) },
                trailingContent = { Switch(checked = phoneEnabled, onCheckedChange = null) },
                modifier = Modifier.fillMaxWidth().toggleable(value = phoneEnabled, role = Role.Switch, onValueChange = actions.onPhoneRestTimerEnabledChange),
            )
        }
        if (enabled || phoneEnabled) {
            RuledGroup {
                categories.forEachIndexed { index, row ->
                    RestCategoryRow(row, onChange = { seconds -> actions.onRestOverrideChange(row.category, seconds) })
                    if (index != categories.lastIndex) HorizontalDivider(color = Border)
                }
            }
            ResetRestDefaultsRow(onResetDefaults)
        }
    }
}

@Composable
private fun RestCategoryRow(row: RestCategoryUiState, onChange: (Int) -> Unit) {
    val label = restCategoryLabel(row.category)
    var editing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Text(stringResource(R.string.form_rest_duration, row.seconds / 60, row.seconds % 60)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { editing = true },
    )
    if (editing) {
        RestDurationSheet(label, row.seconds, onSave = {
            onChange(it)
            editing = false
        }, onDismiss = { editing = false })
    }
}

/** The rest a minutes-and-seconds draft names, or null when either part is blank, out of range, or the total is over the cap. */
internal fun restDurationSeconds(minutes: String, seconds: String): Int? {
    val minuteValue = minutes.trim().toIntOrNull() ?: return null
    val secondValue = seconds.trim().toIntOrNull() ?: return null
    if (minuteValue !in 0..RestPolicy.MAX_REST_SECONDS / 60 || secondValue !in 0..59) return null
    return (minuteValue * 60 + secondValue).takeIf { it <= RestPolicy.MAX_REST_SECONDS }
}

/** Rest is edited in the app's standard sheet: two fields and one pinned Save, like the other entry sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestDurationSheet(label: String, initialSeconds: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit,
                               onUseDefault: (() -> Unit)? = null) {
    var minutes by rememberSaveable { mutableStateOf((initialSeconds / 60).toString()) }
    var seconds by rememberSaveable { mutableStateOf((initialSeconds % 60).toString()) }
    val total = restDurationSeconds(minutes, seconds)
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = { Text(label, style = MaterialTheme.typography.titleLarge) },
        footer = {
            SheetCommitButton(
                label = stringResource(R.string.form_save),
                onClick = { total?.let(onSave) },
                enabled = total != null,
                cancelLabel = stringResource(R.string.setup_cancel_button),
                onCancel = onDismiss,
            )
        },
        body = {
            if (onUseDefault != null) TextButton(onClick = onUseDefault) {
                Text(stringResource(R.string.lift_options_use_default))
            }
            OutlinedTextField(
                value = minutes, onValueChange = { minutes = it },
                label = { Text(stringResource(R.string.form_minutes_label)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = seconds, onValueChange = { seconds = it },
                label = { Text(stringResource(R.string.form_seconds_label)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                supportingText = if (total == null) {
                    { Text(stringResource(R.string.form_rest_range, RestPolicy.MAX_REST_SECONDS)) }
                } else null,
                isError = total == null,
            )
        },
    )
}

/**
 * Setup's ruled settings group mirrors Today's editorial list; M3 has list
 * items but no hairline-bounded settings section with this compact rhythm.
 */
@Composable
private fun RuledGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = Border)
        Column(Modifier.padding(vertical = 6.dp), content = content)
        HorizontalDivider(thickness = 1.dp, color = Border)
    }
}

@Composable
private fun restCategoryLabel(category: RestCategory): String = when (category) {
    RestCategory.RAMP -> stringResource(R.string.setup_rest_category_warm_up)
    RestCategory.TOP -> stringResource(R.string.setup_rest_category_top_set)
    RestCategory.BACKOFF -> stringResource(R.string.setup_rest_category_back_off)
    RestCategory.WORK -> stringResource(R.string.setup_rest_category_accessory)
    RestCategory.LIGHT -> stringResource(R.string.setup_rest_category_bodyweight)
}


@Composable
private fun ResetRestDefaultsRow(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, TextSecondary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        Text(stringResource(R.string.setup_reset_defaults_button), style = DoneButtonLabel,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Confirms the destructive reset of all per-category rest overrides. */
@Composable
private fun RestDefaultsConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_rest_reset_confirm_title)) },
        text = { Text(stringResource(R.string.setup_rest_reset_confirm_message)) },
        confirmButton = { DialogAction(stringResource(R.string.setup_rest_reset_confirm_button), Error, onConfirm) },
        dismissButton = { DialogAction(stringResource(R.string.setup_cancel_button), TextSecondary, onDismiss) },
    )
}

@Preview(showBackground = true, heightDp = 1700, backgroundColor = 0xFF0D0D0F)
@Composable
private fun SetupScreenPreview() {
    SetupScreenPreviewContent(ThemePreference.DARK)
}

@Preview(showBackground = true, heightDp = 1700, backgroundColor = 0xFFF1EFEA)
@Composable
private fun SetupScreenLightPreview() {
    SetupScreenPreviewContent(ThemePreference.LIGHT)
}

@Composable
private fun SetupScreenPreviewContent(theme: ThemePreference) {
    AppTheme(preference = theme) {
        SetupScreen(
            state = SetupStateBuilder.buildUiState(
                unit = WeightUnit.LB,
            ),
            actions = SetupActions(
                onUnitToggle = {}, onRestTimerEnabledChange = {}, onRestOverrideChange = { _, _ -> },
                onThemePreferenceChange = {}, onDistanceUnitChange = {},
                onSaveRouteChange = {}, onGpsDistanceChange = {}, onRefreshLocationAccess = {},
                onRestOverridesReset = {}, onCreateCustomExercise = {}, onOpenBackup = {},
                onOpenLicenses = {}, onBack = {},
            ),
        )
    }
}
