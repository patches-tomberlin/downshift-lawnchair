package app.lawnchair.widget.weather

/**
 * STUB -- not implemented yet. MET Norway's Locationforecast API
 * (https://api.met.no/weatherapi/locationforecast/2.0/compact) is free and needs no API key,
 * but their Terms of Service (https://api.met.no/doc/TermsOfService) REQUIRE every request to
 * send a custom `User-Agent` header identifying the calling application, e.g.
 * "DownShift/1.0 github.com/<repo-or-contact>" -- requests without one get rate-limited or
 * blocked outright. Remember to add that header (via HttpURLConnection.setRequestProperty or
 * equivalent) when implementing the real fetch below.
 *
 * Also still needed: MET Norway reports conditions as `symbol_code` strings (e.g.
 * "clearsky_day", "cloudy", "rain", "partlycloudy_night"), not WMO numeric codes -- a mapping
 * function from those strings to the WMO codes the existing icon lookup expects has to be
 * written before this can return real data.
 */
class MetNorwayProvider : WeatherRepository {
    override suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherData {
        throw IllegalStateException("MET Norway weather provider is not implemented yet")
    }
}
