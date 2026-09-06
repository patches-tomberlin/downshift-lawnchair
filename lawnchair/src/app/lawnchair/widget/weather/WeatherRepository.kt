package app.lawnchair.widget.weather

/**
 * Temperature plus a WMO (World Meteorological Organization) weather-interpretation code --
 * every provider implementation is responsible for mapping its own condition codes to this
 * WMO scheme internally, so the widget's existing icon lookup (built around WMO codes) keeps
 * working unchanged regardless of which provider is selected.
 */
data class WeatherData(
    val temperatureFahrenheit: Double,
    val wmoWeatherCode: Int,
)

/** A source of current-weather data for a single lat/lon point. */
interface WeatherRepository {
    suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherData
}
