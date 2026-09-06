package app.lawnchair.widget

import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.android.launcher3.R

/**
 * Font weight option. RemoteViews can't apply a Typeface object or a TypefaceSpan reliably to
 * the widget's TextClock elements (a TextClock overwrites its own text -- including any span --
 * on every tick), so weight is instead chosen by picking which pre-built layout XML variant to
 * inflate; each variant differs only in its fontFamily attributes.
 */
enum class DownshiftWidgetFontWeight(
    @LayoutRes val layoutResId: Int,
    @StringRes val nameResId: Int,
) {
    THIN(
        layoutResId = R.layout.widget_downshift_header_thin,
        nameResId = R.string.downshift_widget_font_weight_thin,
    ),
    LIGHT(
        layoutResId = R.layout.widget_downshift_header,
        nameResId = R.string.downshift_widget_font_weight_light,
    ),
    REGULAR(
        layoutResId = R.layout.widget_downshift_header_regular,
        nameResId = R.string.downshift_widget_font_weight_regular,
    ),
    ;

    companion object {
        fun fromString(value: String): DownshiftWidgetFontWeight = entries.find { it.name == value } ?: LIGHT
    }
}

/** Date format option for the widget's date row. */
enum class DownshiftWidgetDateFormat(val pattern: String, @StringRes val nameResId: Int) {
    DAY_FIRST(pattern = "EEE, MMM d", nameResId = R.string.downshift_widget_date_format_day_first),
    DATE_FIRST(pattern = "MMM d, EEE", nameResId = R.string.downshift_widget_date_format_date_first),
    ;

    companion object {
        fun fromString(value: String): DownshiftWidgetDateFormat = entries.find { it.name == value } ?: DAY_FIRST
    }
}

/** Which free, no-API-key weather source the widget fetches current conditions from. */
enum class DownshiftWeatherProvider(@StringRes val nameResId: Int) {
    OPEN_METEO(nameResId = R.string.downshift_widget_weather_provider_open_meteo),
    MET_NORWAY(nameResId = R.string.downshift_widget_weather_provider_met_norway),
    NWS(nameResId = R.string.downshift_widget_weather_provider_nws),
    ;

    companion object {
        fun fromString(value: String): DownshiftWeatherProvider = entries.find { it.name == value } ?: OPEN_METEO
    }
}
