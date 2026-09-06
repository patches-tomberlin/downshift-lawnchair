package app.lawnchair.widget.weather

import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Open-Meteo (https://open-meteo.com) -- free, no API key or custom headers required. Its own
 * `weathercode` field already follows the WMO weather-interpretation code standard, so no
 * mapping step is needed before handing it to the widget's existing WMO-based icon lookup.
 */
class OpenMeteoProvider : WeatherRepository {
    override suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherData =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current_weather=true&temperature_unit=fahrenheit"
            val jsonString = URL(url).readText()
            val current = JSONObject(jsonString).getJSONObject("current_weather")

            WeatherData(
                temperatureFahrenheit = current.getDouble("temperature"),
                wmoWeatherCode = current.getInt("weathercode"),
            )
        }
}
