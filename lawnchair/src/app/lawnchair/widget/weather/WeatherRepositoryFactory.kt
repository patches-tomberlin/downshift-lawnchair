package app.lawnchair.widget.weather

import app.lawnchair.widget.DownshiftWeatherProvider

object WeatherRepositoryFactory {
    fun create(provider: DownshiftWeatherProvider): WeatherRepository = when (provider) {
        DownshiftWeatherProvider.OPEN_METEO -> OpenMeteoProvider()
        DownshiftWeatherProvider.MET_NORWAY -> MetNorwayProvider()
        DownshiftWeatherProvider.NWS -> NwsProvider()
    }
}
