package app.lawnchair.widget

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.lawnchair.BlankActivity
import app.lawnchair.HeadlessWidgetsManager
import com.android.launcher3.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val PREF_KEY = "downshift_header_widget_id"
private const val MAX_PROVIDER_QUERY_ATTEMPTS = 5
private const val PROVIDER_QUERY_RETRY_DELAY_MS = 300L

/**
 * Binds and displays our own [DownshiftHeaderWidget] via [HeadlessWidgetsManager] -- the same
 * "headless AppWidgetHost" mechanism [app.lawnchair.nexuslauncher.SmartspaceQsb] already uses to
 * host Google's real Smartspace widget in this same reserved slot.
 *
 * `AppWidgetManager.bindAppWidgetIdIfAllowed()` does NOT auto-allow same-package providers
 * despite what its name suggests -- confirmed empirically (logged `false` return, and
 * `dumpsys package` showed `BIND_APPWIDGET: granted=false`, a signature/system permission this
 * app doesn't hold and can't self-grant). The actual same-package auto-approval lives in the
 * separate `ACTION_APPWIDGET_BIND` *intent* flow, resolved invisibly (no dialog) by the system
 * when the requesting app matches the provider's own package -- the exact mechanism
 * [app.lawnchair.smartspace.provider.SmartspaceWidgetReader] already relies on for Google's
 * widget via [HeadlessWidgetsManager.Widget.getBindIntent]/[BlankActivity.startBlankActivityForResult].
 *
 * That bind is attempted automatically first. If it doesn't complete (observed on a fresh
 * install: an async bind attempt can race against this ComposeView's own attach/dispose
 * lifecycle and get cancelled before finishing), a small tappable placeholder is shown instead
 * of leaving the slot silently blank -- one tap retries the same bind synchronously with a real
 * user gesture, which doesn't have that race.
 */
@Composable
fun DownshiftHeaderWidgetUi(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Right after a fresh install, AppWidgetManager may not have indexed this app's own widget
    // provider yet -- a brief, one-time race specific to the very first launch. Bounded retry
    // rather than an indefinite poll -- this only ever needs to recover from a startup race, not
    // an ongoing condition.
    val providerInfo by produceState<AppWidgetProviderInfo?>(initialValue = null) {
        repeat(MAX_PROVIDER_QUERY_ATTEMPTS) { attempt ->
            val found = AppWidgetManager.getInstance(context)
                .getInstalledProvidersForPackage(context.packageName, null)
                .firstOrNull { it.provider.className == DownshiftHeaderWidget::class.java.name }
            if (found != null) {
                value = found
                return@produceState
            }
            if (attempt < MAX_PROVIDER_QUERY_ATTEMPTS - 1) {
                delay(PROVIDER_QUERY_RETRY_DELAY_MS)
            }
        }
    }
    val provider = providerInfo ?: return

    val widgetsManager = remember { HeadlessWidgetsManager.INSTANCE.get(context) }
    val widget = remember(provider) { widgetsManager.getWidget(provider, PREF_KEY) }
    var isBound by remember(provider) { mutableStateOf(widget.isBound) }
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    // Automatic attempt, once, when this provider first resolves.
    produceState(initialValue = Unit, provider) {
        if (!widget.isBound && activity != null) {
            runCatching { BlankActivity.startBlankActivityForResult(activity, widget.getBindIntent()) }
            isBound = widget.isBound
        }
    }

    if (!isBound) {
        Text(
            text = stringResource(id = R.string.downshift_widget_tap_to_setup),
            color = Color.White,
            modifier = modifier
                .fillMaxWidth()
                .clickable(enabled = activity != null) {
                    scope.launch {
                        if (activity != null) {
                            runCatching { BlankActivity.startBlankActivityForResult(activity, widget.getBindIntent()) }
                            isBound = widget.isBound
                        }
                    }
                }
                .padding(16.dp),
        )
        return
    }

    val hostView by produceState<AppWidgetHostView?>(initialValue = null, provider) {
        widgetsManager.subscribeUpdates(provider, PREF_KEY)
            .catch { /* fail-soft: leave the slot empty rather than crash the home screen */ }
            .collect { value = it }
    }
    val view = hostView ?: return

    AndroidView(factory = { view }, modifier = modifier.fillMaxWidth().wrapContentHeight())
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
