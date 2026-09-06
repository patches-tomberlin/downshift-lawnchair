package app.lawnchair.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.lawnchair.HeadlessWidgetsManager
import kotlinx.coroutines.flow.catch

private const val PREF_KEY = "downshift_header_widget_id"

/**
 * Binds and displays our own [DownshiftHeaderWidget] via [HeadlessWidgetsManager] -- the same
 * "headless AppWidgetHost" mechanism [app.lawnchair.nexuslauncher.SmartspaceQsb] already uses to
 * host Google's real Smartspace widget in this same reserved slot. Binding our own widget needs
 * no user permission prompt: `bindAppWidgetIdIfAllowed` auto-allows a provider that belongs to the
 * calling app's own package.
 */
@Composable
fun DownshiftHeaderWidgetUi(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val providerInfo = remember {
        AppWidgetManager.getInstance(context)
            .getInstalledProvidersForPackage(context.packageName, null)
            .firstOrNull { it.provider.className == DownshiftHeaderWidget::class.java.name }
    } ?: return

    val hostView by produceState<AppWidgetHostView?>(initialValue = null, providerInfo) {
        HeadlessWidgetsManager.INSTANCE.get(context)
            .subscribeUpdates(providerInfo, PREF_KEY)
            .catch { /* fail-soft: leave the slot empty rather than crash the home screen */ }
            .collect { value = it }
    }
    val view = hostView ?: return

    AndroidView(factory = { view }, modifier = modifier.wrapContentHeight())
}
