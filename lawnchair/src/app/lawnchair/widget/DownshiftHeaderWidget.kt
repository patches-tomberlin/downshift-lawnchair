package app.lawnchair.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.android.launcher3.R
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Home-screen header widget: clock/date (via TextClock, no code needed) plus a current-weather
 * icon and temperature fetched from Open-Meteo. Deliberately self-contained -- its own networking
 * and WMO weather-code-to-icon mapping, no shared app architecture, per how it was handed off.
 * `updatePeriodMillis="0"` in downshift_header_info.xml means it only refreshes on
 * placement/re-add, not on a timer -- intentional for this first version.
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

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_downshift_header)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=34.99&longitude=-81.24" +
                        "&current_weather=true&temperature_unit=fahrenheit"
                    URL(url).readText()
                }

                val weatherData = JSONObject(jsonString).getJSONObject("current_weather")
                val temperature = weatherData.getDouble("temperature")
                val weatherCode = weatherData.getInt("weathercode")

                val tempString = "${temperature.toInt()}°"
                val iconRes = getWeatherIconResource(weatherCode)

                views.setTextViewText(R.id.weather_temp, tempString)
                views.setImageViewResource(R.id.weather_icon, iconRes)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e("DownshiftHeaderWidget", "Error fetching weather", e)
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
}
