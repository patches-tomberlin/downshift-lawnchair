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

    private fun setWidgetContent() {
        composeView.setContent {
            LawnchairTheme {
                ProvideLifecycleState {
                    DownshiftHeaderWidgetUi()
                }
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setWidgetContent()

        // This widget's content depends on an async bind flow (HeadlessWidgetsManager) that can
        // launch a separate Activity (BlankActivity) mid-flight, which detaches this ComposeView
        // from its window. DisposeOnDetachedFromWindow then tears down the composition -- so on
        // reattach we must call setContent() again to establish a fresh composition (which
        // re-reads current bind state) rather than leaving the slot permanently blank.
        composeView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                requestLayout()
                setWidgetContent()
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
