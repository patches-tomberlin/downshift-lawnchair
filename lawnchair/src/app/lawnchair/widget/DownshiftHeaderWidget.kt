package app.lawnchair.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.widget.weather.WeatherRepositoryFactory
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen header widget: clock/date (via TextClock, no code needed) plus a current-weather
 * icon and temperature. Weather fetching lives behind the WeatherRepository interface
 * (app.lawnchair.widget.weather) -- the user picks a provider in Settings, WeatherRepositoryFactory
 * instantiates the matching implementation, and each implementation maps its own condition codes
 * to the WMO codes getWeatherIconResource() below already understands, so this class never needs
 * to know which provider is active. `updatePeriodMillis="0"` in downshift_header_info.xml means
 * it only refreshes on placement/re-add, not on a timer -- intentional for this first version.
 *
 * Appearance/behavior settings (font color/weight, date format, weather visibility/provider,
 * tap-action app overrides) live in PreferenceManager2 and are read fresh on every updateWidget()
 * call; Settings pushes a live redraw via requestUpdate() whenever one of them changes.
 */
class DownshiftHeaderWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Fires whenever the user drags the widget's resize handles on the home screen.
        // RemoteViews' XML autoSizeTextType doesn't reliably react to that live resize, so
        // text size is instead recomputed here and pushed with a plain setTextViewTextSize().
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val prefs = PreferenceManager2.getInstance(context)
        val fontWeight = prefs.downshiftWidgetFontWeight.firstCached(prefs)
        val fontColorArgb = prefs.downshiftWidgetFontColorArgb.firstCached(prefs)
        val dateFormat = prefs.downshiftWidgetDateFormat.firstCached(prefs)
        val showWeather = prefs.downshiftWidgetShowWeather.firstCached(prefs)

        val views = RemoteViews(context.packageName, fontWeight.layoutResId)
        applyTextSizes(appWidgetManager, appWidgetId, views)
        applyFontColor(views, fontColorArgb)
        applyDateFormat(views, dateFormat)
        applyWeatherVisibility(views, showWeather)
        setClickPendingIntents(context, views, prefs)
        appWidgetManager.updateAppWidget(appWidgetId, views)

        if (!showWeather) return

        val weatherProvider = prefs.downshiftWidgetWeatherProvider.firstCached(prefs)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val repository = WeatherRepositoryFactory.create(weatherProvider)
                val weather = repository.fetchWeather(latitude = HARDCODED_LATITUDE, longitude = HARDCODED_LONGITUDE)

                views.setTextViewText(R.id.weather_temp, "${weather.temperatureFahrenheit.toInt()}°")
                views.setImageViewResource(R.id.weather_icon, getWeatherIconResource(weather.wmoWeatherCode))

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e("DownshiftHeaderWidget", "Error fetching weather from $weatherProvider", e)
            }
        }
    }

    private fun getWeatherIconResource(conditionCode: Int): Int {
        return when (conditionCode) {
            0 -> R.drawable.ic_weather_day_sunny
            1, 2, 3 -> R.drawable.ic_weather_cloudy
            45, 48 -> R.drawable.ic_weather_cloudy
            51, 53, 55 -> R.drawable.ic_weather_rain
            61, 63, 65 -> R.drawable.ic_weather_rain
            71, 73, 75 -> R.drawable.ic_weather_snow
            95, 96, 99 -> R.drawable.ic_weather_thunderstorm
            else -> R.drawable.ic_weather_placeholder
        }
    }

    private fun applyFontColor(views: RemoteViews, colorArgb: Int) {
        views.setTextColor(R.id.clock_time_main, colorArgb)
        views.setTextColor(R.id.clock_time_ampm, colorArgb)
        views.setTextColor(R.id.clock_date, colorArgb)
        views.setTextColor(R.id.weather_temp, colorArgb)
    }

    /**
     * Sets both format12Hour and format24Hour to the same pattern -- a date format doesn't
     * depend on the clock's 12/24-hour convention, but TextClock falls back to its own default
     * date pattern (ignoring format12Hour entirely) on a device set to 24-hour time unless
     * format24Hour is also set. Applies via RemoteViews' generic CharSequence-arg reflection
     * setter, which TextClock's setFormat12Hour/setFormat24Hour(CharSequence) matches.
     */
    private fun applyDateFormat(views: RemoteViews, dateFormat: DownshiftWidgetDateFormat) {
        views.setCharSequence(R.id.clock_date, "setFormat12Hour", dateFormat.pattern)
        views.setCharSequence(R.id.clock_date, "setFormat24Hour", dateFormat.pattern)
    }

    private fun applyWeatherVisibility(views: RemoteViews, showWeather: Boolean) {
        val visibility = if (showWeather) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.weather_icon, visibility)
        views.setViewVisibility(R.id.weather_temp, visibility)
    }

    /**
     * Time -> system alarm clock; date -> default calendar app (the universal
     * CalendarContract "jump to today" URI works regardless of which app is default,
     * no component name needed); weather -> whatever app is registered under the
     * platform's CATEGORY_APP_WEATHER role, falling back to a browser search when
     * no such app is installed. Lawnchair already holds QUERY_ALL_PACKAGES (needed for
     * its core job of listing every installed app), so the resolveActivity() checks below
     * see every installed package with no extra manifest declarations required.
     *
     * Any of the three targets can be pinned to a specific app via Settings (setPackage()
     * on the intent) -- if that saved package is no longer installed, falls back to the
     * normal resolution/fallback behavior below rather than firing a dead intent.
     */
    private fun setClickPendingIntents(context: Context, views: RemoteViews, prefs: PreferenceManager2) {
        val clockPackage = installedPackageOrNull(context, prefs.downshiftWidgetClockPackage.firstCached(prefs))
        val calendarPackage = installedPackageOrNull(context, prefs.downshiftWidgetCalendarPackage.firstCached(prefs))
        val weatherPackage = installedPackageOrNull(context, prefs.downshiftWidgetWeatherPackage.firstCached(prefs))

        val alarmPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_ALARM,
            resolveAlarmIntent().apply { clockPackage?.let(::setPackage) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.clock_time_main, alarmPendingIntent)
        views.setOnClickPendingIntent(R.id.clock_time_ampm, alarmPendingIntent)

        val calendarUri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(System.currentTimeMillis().toString())
            .build()
        val calendarPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_CALENDAR,
            Intent(Intent.ACTION_VIEW, calendarUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { calendarPackage?.let(::setPackage) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.clock_date, calendarPendingIntent)

        val weatherPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_WEATHER,
            resolveWeatherIntent(context, weatherPackage),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.weather_icon, weatherPendingIntent)
        views.setOnClickPendingIntent(R.id.weather_temp, weatherPendingIntent)
    }

    private fun installedPackageOrNull(context: Context, packageName: String): String? {
        if (packageName.isEmpty()) return null
        return runCatching { context.packageManager.getApplicationInfo(packageName, 0) }
            .map { packageName }
            .getOrNull()
    }

    /**
     * A bare implicit intent, not Intent.createChooser(). createChooser() routes through the
     * platform sharesheet (Direct Share/Nearby Share/etc.), which is built for ACTION_SEND-style
     * sharing and reports zero targets for unrelated actions like SHOW_ALARMS regardless of what
     * actually resolves (confirmed via logcat: "ChooserListAdapter: getDisplayResolveInfoCount()
     * == 0" even though queryIntentActivities() found Samsung's Clock app just fine). A plain
     * implicit intent gets Android's native disambiguation for free: it launches directly when
     * exactly one app matches, or shows the classic system "Open with" picker when several do.
     */
    private fun resolveAlarmIntent(): Intent {
        return Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun applyTextSizes(appWidgetManager: AppWidgetManager, appWidgetId: Int, views: RemoteViews) {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, BASE_WIDTH_DP)
        val scale = (widthDp.toFloat() / BASE_WIDTH_DP).coerceIn(0.4f, 3f)

        // Main time size lowered from 48sp/48sp max: at the widget's 2x2 default size, a
        // 2-digit hour (e.g. "11:45") plus the AM/PM label no longer fit the row at 48sp and
        // clipped the "M" off the edge.
        views.setTextViewTextSize(R.id.clock_time_main, TypedValue.COMPLEX_UNIT_SP, (40f * scale).coerceIn(20f, 40f))
        views.setTextViewTextSize(R.id.clock_time_ampm, TypedValue.COMPLEX_UNIT_SP, (18f * scale).coerceIn(10f, 20f))
        views.setTextViewTextSize(R.id.clock_date, TypedValue.COMPLEX_UNIT_SP, (18f * scale).coerceIn(10f, 18f))
        views.setTextViewTextSize(R.id.weather_temp, TypedValue.COMPLEX_UNIT_SP, (16f * scale).coerceIn(10f, 16f))
    }

    private fun resolveWeatherIntent(context: Context, weatherPackage: String?): Intent {
        if (weatherPackage != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(weatherPackage)
            if (intent != null) return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val weatherAppIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_WEATHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (weatherAppIntent.resolveActivity(context.packageManager) != null) {
            return weatherAppIntent
        }

        // Many OEM weather apps (e.g. Samsung's) don't register CATEGORY_APP_WEATHER, but the
        // Google app almost always is installed -- same fallback component the Smartspace
        // weather card already uses (SmartspaceWidgetReader.kt).
        val googleWeatherIntent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(GSA_PACKAGE, GSA_WEATHER_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (googleWeatherIntent.resolveActivity(context.packageManager) != null) {
            return googleWeatherIntent
        }

        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private const val REQUEST_CODE_ALARM = 4301
        private const val REQUEST_CODE_CALENDAR = 4302
        private const val REQUEST_CODE_WEATHER = 4303
        private const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val GSA_WEATHER_ACTIVITY = "com.google.android.apps.search.weather.WeatherExportedActivity"

        // Reference width (dp) the base text sizes below are authored for -- matches the
        // widget's 2x2 default placement footprint. Text scales proportionally from here.
        private const val BASE_WIDTH_DP = 110

        // No location picker UI yet -- same fixed coordinates this widget has always used.
        private const val HARDCODED_LATITUDE = 34.99
        private const val HARDCODED_LONGITUDE = -81.24

        /**
         * Pushes a live redraw of every placed instance of this widget. Not a broadcast --
         * ACTION_APPWIDGET_UPDATE is a protected system broadcast (confirmed via adb: even
         * `am broadcast` from shell is rejected with "Permission Denial: not allowed to send
         * broadcast ... from unknown caller"). A normal app can't send it, from adb or
         * in-process. The actual supported mechanism is a direct AppWidgetManager call, which
         * is a Binder call to AppWidgetService, not a broadcast, and needs no special
         * permission for an app updating its own widgets.
         */
        fun requestUpdate(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, DownshiftHeaderWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) return
            DownshiftHeaderWidget().onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }
}
