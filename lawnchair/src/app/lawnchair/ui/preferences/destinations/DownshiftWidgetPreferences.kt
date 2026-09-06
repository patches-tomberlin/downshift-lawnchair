package app.lawnchair.ui.preferences.destinations

import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.AdvancedColorPicker
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.AppItemPlaceholder
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.navigation.DownshiftWidgetPickCalendarApp
import app.lawnchair.ui.preferences.navigation.DownshiftWidgetPickClockApp
import app.lawnchair.ui.preferences.navigation.DownshiftWidgetPickWeatherApp
import app.lawnchair.util.App
import app.lawnchair.util.appsState
import app.lawnchair.widget.DownshiftWeatherProvider
import app.lawnchair.widget.DownshiftWidgetDateFormat
import app.lawnchair.widget.DownshiftWidgetFontWeight
import com.android.launcher3.R
import com.patrykmichalik.opto.core.setBlocking

@Composable
fun DownshiftWidgetPreferences() {
    val navController = LocalNavController.current
    val prefs2 = preferenceManager2()

    val fontColorArgbAdapter = prefs2.downshiftWidgetFontColorArgb.getAdapter()
    val fontWeightAdapter = prefs2.downshiftWidgetFontWeight.getAdapter()
    val dateFormatAdapter = prefs2.downshiftWidgetDateFormat.getAdapter()
    val showWeatherAdapter = prefs2.downshiftWidgetShowWeather.getAdapter()
    val weatherProviderAdapter = prefs2.downshiftWidgetWeatherProvider.getAdapter()

    val clockPackage = prefs2.downshiftWidgetClockPackage.getAdapter().state.value
    val calendarPackage = prefs2.downshiftWidgetCalendarPackage.getAdapter().state.value
    val weatherPackage = prefs2.downshiftWidgetWeatherPackage.getAdapter().state.value

    PreferenceLayout(
        label = stringResource(id = R.string.downshift_widget_label),
    ) {
        PreferenceGroup(heading = stringResource(id = R.string.downshift_widget_appearance_heading)) {
            Text(
                text = stringResource(id = R.string.downshift_widget_font_color_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            AdvancedColorPicker(
                initialColorArgb = fontColorArgbAdapter.state.value,
                onColorChangeFinished = { fontColorArgbAdapter.onChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
            ListPreference(
                adapter = fontWeightAdapter,
                entries = remember {
                    DownshiftWidgetFontWeight.entries.map { option ->
                        ListPreferenceEntry(option) { stringResource(id = option.nameResId) }
                    }
                },
                label = stringResource(id = R.string.downshift_widget_font_weight_label),
            )
            ListPreference(
                adapter = dateFormatAdapter,
                entries = remember {
                    DownshiftWidgetDateFormat.entries.map { option ->
                        ListPreferenceEntry(option) { stringResource(id = option.nameResId) }
                    }
                },
                label = stringResource(id = R.string.downshift_widget_date_format_label),
            )
            SwitchPreference(
                adapter = showWeatherAdapter,
                label = stringResource(id = R.string.downshift_widget_show_weather_label),
            )
            ListPreference(
                adapter = weatherProviderAdapter,
                entries = remember {
                    DownshiftWeatherProvider.entries.map { option ->
                        ListPreferenceEntry(option) { stringResource(id = option.nameResId) }
                    }
                },
                label = stringResource(id = R.string.downshift_widget_weather_provider_label),
            )
        }

        PreferenceGroup(heading = stringResource(id = R.string.downshift_widget_tap_actions_heading)) {
            ClickablePreference(
                label = stringResource(id = R.string.downshift_widget_clock_app_label),
                subtitle = rememberInstalledAppLabelOrDefault(clockPackage),
                onClick = { navController.navigate(DownshiftWidgetPickClockApp) },
            )
            ClickablePreference(
                label = stringResource(id = R.string.downshift_widget_calendar_app_label),
                subtitle = rememberInstalledAppLabelOrDefault(calendarPackage),
                onClick = { navController.navigate(DownshiftWidgetPickCalendarApp) },
            )
            ClickablePreference(
                label = stringResource(id = R.string.downshift_widget_weather_app_label),
                subtitle = rememberInstalledAppLabelOrDefault(weatherPackage),
                onClick = { navController.navigate(DownshiftWidgetPickWeatherApp) },
            )
        }
    }
}

@Composable
private fun rememberInstalledAppLabelOrDefault(packageName: String): String {
    val context = LocalContext.current
    val defaultLabel = stringResource(id = R.string.downshift_widget_app_default)
    return remember(packageName) {
        if (packageName.isEmpty()) {
            defaultLabel
        } else {
            runCatching {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            }.getOrDefault(defaultLabel)
        }
    }
}

/** Which of the widget's three tap targets an app-picker screen is choosing an app for. */
enum class DownshiftWidgetTapTarget {
    CLOCK,
    CALENDAR,
    WEATHER,
}

@Composable
fun DownshiftWidgetAppPicker(target: DownshiftWidgetTapTarget) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val prefs2 = preferenceManager2()
    val apps by appsState()
    val state = rememberLazyListState()

    // Android has no API exposing Play Store category metadata for installed apps, and the
    // OS's own ApplicationInfo.category taxonomy (game/audio/video/social/news/maps/...) has
    // no "weather" bucket -- there's no on-device way to ask "what category is this app". So
    // weather apps are found by a name heuristic instead (label containing "weather" or
    // "forecast"), with a manual "show all installed apps" escape hatch for anything that
    // heuristic misses (e.g. a weather app with a purely branded name).
    var showAllApps by remember { mutableStateOf(false) }

    val allowedPackages = remember(target) {
        when (target) {
            DownshiftWidgetTapTarget.WEATHER -> null
            DownshiftWidgetTapTarget.CLOCK -> context.packageManager
                .queryIntentActivities(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
                .map { it.activityInfo.packageName }
                .toSet()
            DownshiftWidgetTapTarget.CALENDAR -> {
                val probe = Intent(
                    Intent.ACTION_VIEW,
                    CalendarContract.CONTENT_URI.buildUpon()
                        .appendPath("time")
                        .appendPath(System.currentTimeMillis().toString())
                        .build(),
                )
                context.packageManager.queryIntentActivities(probe, 0)
                    .map { it.activityInfo.packageName }
                    .toSet()
            }
        }
    }

    val filteredApps = remember(apps, allowedPackages, showAllApps) {
        val packages = allowedPackages
        when {
            packages != null -> apps.filter { it.key.componentName.packageName in packages }
            showAllApps -> apps
            else -> apps.filter { app ->
                val label = app.label.lowercase()
                "weather" in label || "forecast" in label || "radar" in label
            }
        }
    }

    fun onSelectApp(app: App) {
        val packageName = app.key.componentName.packageName
        when (target) {
            DownshiftWidgetTapTarget.CLOCK -> prefs2.downshiftWidgetClockPackage.setBlocking(packageName)
            DownshiftWidgetTapTarget.CALENDAR -> prefs2.downshiftWidgetCalendarPackage.setBlocking(packageName)
            DownshiftWidgetTapTarget.WEATHER -> prefs2.downshiftWidgetWeatherPackage.setBlocking(packageName)
        }
        navController.popBackStack()
    }

    val screenLabel = stringResource(
        id = when (target) {
            DownshiftWidgetTapTarget.CLOCK -> R.string.downshift_widget_clock_app_label
            DownshiftWidgetTapTarget.CALENDAR -> R.string.downshift_widget_calendar_app_label
            DownshiftWidgetTapTarget.WEATHER -> R.string.downshift_widget_weather_app_label
        },
    )

    PreferenceScaffold(
        label = screenLabel,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) {
        // Keyed off whether the RAW app list has loaded, not the filtered result -- a
        // name-filtered empty list (no app matched "weather"/"forecast") is a valid, real
        // state, not a loading state, and must still render so the "show all" escape hatch
        // stays reachable.
        Crossfade(targetState = apps.isNotEmpty(), label = "") { present ->
            if (present) {
                PreferenceLazyColumn(it, state = state) {
                    if (target == DownshiftWidgetTapTarget.WEATHER && !showAllApps) {
                        item {
                            PreferenceGroup {
                                ClickablePreference(
                                    label = stringResource(id = R.string.downshift_widget_show_all_apps_label),
                                    onClick = { showAllApps = true },
                                )
                            }
                        }
                        if (filteredApps.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(id = R.string.downshift_widget_no_weather_apps_found),
                                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                                )
                            }
                        }
                    }
                    preferenceGroupItems(
                        items = filteredApps,
                        isFirstChild = target != DownshiftWidgetTapTarget.WEATHER,
                    ) { _, app ->
                        AppItem(app = app, onClick = ::onSelectApp)
                    }
                }
            } else {
                PreferenceLazyColumn(it, enabled = false) {
                    preferenceGroupItems(
                        count = 20,
                        isFirstChild = true,
                    ) {
                        AppItemPlaceholder()
                    }
                }
            }
        }
    }
}
