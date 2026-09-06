package app.lawnchair.hotseat

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState

/**
 * Hosts [DownshiftControlsUi] in the hotseat's QSB slot, the same way [app.lawnchair.qsb.LawnQsbLayout]
 * hosts the search bar -- a thin View shim around a single [ComposeView], inflated by
 * [com.android.launcher3.Hotseat] whenever [HotseatMode.layoutResourceId] points at this class.
 */
class DownshiftControlsLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val composeView = ComposeView(context)

    override fun onFinishInflate() {
        super.onFinishInflate()

        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LawnchairTheme {
                    ProvideLifecycleState {
                        DownshiftControlsUi()
                    }
                }
            }
        }

        // Same fix as LawnQsbLayout: stop Compose content from disappearing when this view
        // reattaches (e.g. returning from another activity).
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
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }
}
