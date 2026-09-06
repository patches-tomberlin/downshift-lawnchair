package app.lawnchair.widget.weather

/**
 * STUB -- not implemented yet. The US National Weather Service API (https://api.weather.gov) is
 * free and needs no API key, but their docs REQUIRE a custom `User-Agent` header identifying the
 * application plus a contact method (email or website) -- requests without one may be throttled
 * or rejected. Remember to add that header when implementing the real fetch below.
 *
 * Also still needed: NWS has no direct "give me weather for this lat/lon" endpoint -- it's a
 * two-step flow: first GET /points/{lat},{lon} to resolve the forecast grid endpoint for that
 * location, then GET that endpoint for the actual forecast. And NWS reports conditions as
 * short-forecast text/icon URLs, not WMO numeric codes -- a mapping function from those to the
 * WMO codes the existing icon lookup expects has to be written before this can return real data.
 */
class NwsProvider : WeatherRepository {
    override suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherData {
        throw IllegalStateException("National Weather Service provider is not implemented yet")
    }
}
