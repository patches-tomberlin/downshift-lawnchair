package app.lawnchair.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState

/**
 * Hosts [DownshiftHeaderWidgetUi] in the workspace's reserved first-row Smartspace slot -- the
 * same thin View-shim-around-a-[ComposeView] pattern as
 * [app.lawnchair.hotseat.DownshiftControlsLayout], inflated whenever [SmartspaceMode]
 * (`app.lawnchair.smartspace.model.DownshiftHeader`) points at this layout.
 */
class DownshiftHeaderHostLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val composeView = ComposeView(context)

    override fun onFinishInflate() {
        super.onFinishInflate()

        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LawnchairTheme {
                    ProvideLifecycleState {
                        DownshiftHeaderWidgetUi()
                    }
                }
            }
        }

        // Same fix as LawnQsbLayout/DownshiftControlsLayout: stop Compose content from
        // disappearing when this view reattaches (e.g. returning from another activity).
        composeView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                requestLayout()
                composeView.disposeComposition()
            }
            override fun onViewDetachedFromWindow(v: View) {
                composeView.disposeComposition()
            }
        })

        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }
}
