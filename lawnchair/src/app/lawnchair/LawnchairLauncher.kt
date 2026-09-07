/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Pair
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import android.window.SplashScreen
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairApp.Companion.showQuickstepWarningIfNecessary
import app.lawnchair.compat.LawnchairQuickstepCompat
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.wallpaper.service.WallpaperService
import app.lawnchair.gestures.GestureController
import app.lawnchair.gestures.VerticalSwipeTouchController
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.gestures.ui.LawnchairShortcutActivity
import app.lawnchair.nexuslauncher.OverlayCallbackImpl
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.root.RootHelperManager
import app.lawnchair.root.RootNotAvailableException
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.ui.popup.LauncherOptionsPopup
import app.lawnchair.ui.popup.LawnchairShortcut
import app.lawnchair.util.getThemedIconPacksInstalled
import app.lawnchair.util.unsafeLazy
import app.lawnchair.views.LawnchairFloatingSurfaceView
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.BaseActivity
import com.android.launcher3.BubbleTextView
import com.android.launcher3.GestureNavContract
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS_PREDICTION
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_WIDGETS_PREDICTION
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.PredictedContainerInfo
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.shortcuts.DeepShortcutView
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.statemanager.StateManager.StateHandler
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.uioverrides.states.AllAppsState
import com.android.launcher3.uioverrides.states.BackgroundAppState
import com.android.launcher3.uioverrides.states.OverviewState
import com.android.launcher3.util.ActivityOptionsWrapper
import com.android.launcher3.util.Executors
import com.android.launcher3.util.IntSet
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.SystemUiController.UI_STATE_BASE_WINDOW
import com.android.launcher3.util.Themes
import com.android.launcher3.util.TouchController
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.OptionsPopupView
import com.android.launcher3.views.OptionsPopupView.OptionItem
import com.android.launcher3.widget.LauncherWidgetHolder
import com.android.launcher3.widget.RoundedCornerEnforcement
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.shared.system.QuickStepContract
import com.kieronquinn.app.smartspacer.sdk.client.SmartspacerClient
import com.patrykmichalik.opto.core.onEach
import dev.kdrag0n.monet.theme.ColorScheme
import java.util.stream.Stream
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class LawnchairLauncher : QuickstepLauncher() {
    private val defaultOverlay by unsafeLazy { OverlayCallbackImpl(this) }
    private val prefs by unsafeLazy { PreferenceManager.getInstance(this) }
    private val preferenceManager2 by unsafeLazy { PreferenceManager2.getInstance(this) }
    private val insetsController: WindowInsetsControllerCompat by lazy {
        val window = launcher.window
            ?: throw Exception("WindowInsetsControllerCompat not available.")
        WindowInsetsControllerCompat(window, rootView)
    }
    private val themeProvider by unsafeLazy { ThemeProvider.INSTANCE.get(this) }
    private val noStatusBarStateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            if (toState is OverviewState) {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        override fun onStateTransitionComplete(finalState: LauncherState) {
            if (finalState !is OverviewState) {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    private val rememberPositionStateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            if (toState is AllAppsState) {
                mAppsView.activeRecyclerView.restoreScrollPosition()
            }
        }
        override fun onStateTransitionComplete(finalState: LauncherState) {}
    }
    private val statusBarClockListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            when (toState) {
                is BackgroundAppState,
                is OverviewState,
                is AllAppsState,
                -> {
                    LawnchairApp.instance.restoreClockInStatusBar()
                }

                else -> {
                    workspace.updateStatusbarClock()
                }
            }
        }
        override fun onStateTransitionComplete(finalState: LauncherState) {}
    }
    private val clearSearchStateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionComplete(finalState: LauncherState) {
            if (finalState == LauncherState.NORMAL && mAppsView != null && mAppsView.isSearching) {
                mAppsView?.post {
                    mAppsView.reset(false, true)
                }
            }
        }
    }

    private lateinit var colorScheme: ColorScheme
    private var hasBackGesture = false

    val gestureController by unsafeLazy { GestureController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        layoutInflater.factory2 = LawnchairLayoutFactory(this)
        super.onCreate(savedInstanceState)

        showColdStartScrim()

        prefs.launcherTheme.subscribeChanges(this, ::updateTheme)
        prefs.feedProvider.subscribeChanges(this, defaultOverlay::reconnect)
        preferenceManager2.enableFeed.get().distinctUntilChanged().onEach { enable ->
            defaultOverlay.setEnableFeed(enable)
        }.launchIn(scope = lifecycleScope)
        launcher.stateManager.addStateListener(clearSearchStateListener)

        if (prefs.autoLaunchRoot.get()) {
            lifecycleScope.launch {
                try {
                    RootHelperManager.INSTANCE.get(this@LawnchairLauncher)
                } catch (_: RootNotAvailableException) {
                }
            }
        }

        preferenceManager2.showStatusBar.get().distinctUntilChanged().onEach {
            with(insetsController) {
                if (it) {
                    show(WindowInsetsCompat.Type.statusBars())
                } else {
                    hide(WindowInsetsCompat.Type.statusBars())
                }
            }
            with(launcher.stateManager) {
                if (it) {
                    removeStateListener(noStatusBarStateListener)
                } else {
                    addStateListener(noStatusBarStateListener)
                }
            }
        }.launchIn(scope = lifecycleScope)

        preferenceManager2.statusBarClock.get().distinctUntilChanged().onEach {
            with(launcher.stateManager) {
                if (it) {
                    addStateListener(statusBarClockListener)
                } else {
                    removeStateListener(statusBarClockListener)
                    // Make sure status bar clock is restored when the preference is toggled off
                    LawnchairApp.instance.restoreClockInStatusBar()
                }
            }
        }.launchIn(scope = lifecycleScope)
        preferenceManager2.rememberPosition.get().onEach {
            with(launcher.stateManager) {
                if (it) {
                    addStateListener(rememberPositionStateListener)
                } else {
                    removeStateListener(rememberPositionStateListener)
                }
            }
        }.launchIn(scope = lifecycleScope)

        prefs.overrideWindowCornerRadius.subscribeValues(this) {
            QuickStepContract.sHasCustomCornerRadius = it
        }
        prefs.windowCornerRadius.subscribeValues(this) {
            QuickStepContract.sCustomCornerRadius = it.toFloat()
        }
        preferenceManager2.roundedWidgets.onEach(launchIn = lifecycleScope) {
            RoundedCornerEnforcement.sRoundedCornerEnabled = it
        }
        val isWorkspaceDarkText = Themes.getAttrBoolean(this, R.attr.isWorkspaceDarkText)
        preferenceManager2.darkStatusBar.onEach(launchIn = lifecycleScope) { darkStatusBar ->
            systemUiController?.updateUiState(UI_STATE_BASE_WINDOW, isWorkspaceDarkText || darkStatusBar)
        }
        preferenceManager2.backPressGestureHandler.onEach(launchIn = lifecycleScope) { handler ->
            hasBackGesture = handler !is GestureHandlerConfig.NoOp
        }

        LauncherOptionsPopup.restoreMissingPopupOptions(launcher)
        LauncherOptionsPopup.migrateLegacyPreferences(launcher)

        // Handle update from version 12 Alpha 4 to version 12 Alpha 5.
        if (
            prefs.themedIcons.get() &&
            packageManager.getThemedIconPacksInstalled(this).isEmpty()
        ) {
            prefs.themedIcons.set(newValue = false)
        }

        colorScheme = themeProvider.colorScheme

        showQuickstepWarningIfNecessary()

        reloadIconsIfNeeded()

        AppDatabase.INSTANCE.get(this).checkpointSync()
    }

    // Set only while a cold-start black scrim is being shown over the DragLayer -- from the
    // moment onCreate renders it (see showColdStartScrim) until finishBindingItems fades it away.
    // Null the rest of the time.
    private var profileSwitchScrim: ColorDrawable? = null

    /**
     * Renders a full-opacity black scrim over the DragLayer immediately, on every cold start --
     * not just a profile switch. Originally this only ran when the restart intent carried a flag
     * set by [app.lawnchair.profile.WorkspaceProfileManager.switchTo], but that flag turned out to
     * be unreliable: killing a HOME app's process makes Android's own ActivityManagerService
     * auto-relaunch it
     * immediately, and *that* relaunch is what actually creates this Activity (confirmed via
     * logging -- the intent it delivers carries only the system's own EXTRA_START_REASON, never
     * anything we attached to our own restart Intent, which a separately-scheduled PendingIntent
     * loses the race to). There is no reliable way to pass a flag across a killed process's
     * restart without it, so this scrim now covers every cold start unconditionally -- which also
     * means zero cross-process state of any kind: no snapshot, no disk flag, nothing. A profile
     * switch fades the outgoing screen to the same solid black via [playProfileSwitchFadeOut]
     * before killing the process (see [app.lawnchair.hotseat.DownshiftControlsUi]), so for that
     * specific case this hands off seamlessly across the dead-process gap; for a normal cold
     * start (tapping the app icon after a swipe-kill, etc.) it's a brief, deliberate fade-in
     * rather than an abrupt pop of content, which reads as intentional either way. Removed by
     * [finishBindingItems] below, once the workspace has actually finished binding --
     * [PROFILE_SWITCH_SCRIM_MAX_HOLD_MS] here is only a safety net in case that callback never
     * fires, so this can never get stuck showing a frozen black screen forever.
     */
    private fun showColdStartScrim() {
        val scrim = ensureProfileSwitchScrim()
        scrim.alpha = 255
        dragLayer.invalidate()
        Log.d(PROFILE_SWITCH_LOG_TAG, "scrim shown at ${SystemClock.uptimeMillis()}")
        dragLayer.postDelayed(::fadeInAfterProfileSwitch, PROFILE_SWITCH_SCRIM_MAX_HOLD_MS)
    }

    /**
     * Fades a black scrim in over the DragLayer -- called from Compose right before a profile
     * switch, so the outgoing screen goes to solid black under our own control instead of
     * whatever the OS shows once the process actually dies a moment later.
     */
    suspend fun playProfileSwitchFadeOut(): Unit = suspendCancellableCoroutine { cont ->
        val scrim = ensureProfileSwitchScrim()
        scrim.alpha = 0
        dragLayer.invalidate()
        val fadeOut = ValueAnimator.ofInt(0, 255)
        fadeOut.duration = PROFILE_SWITCH_SCRIM_FADE_OUT_MS
        fadeOut.addUpdateListener { animator ->
            scrim.alpha = animator.animatedValue as Int
            dragLayer.invalidate()
        }
        fadeOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (cont.isActive) cont.resume(Unit, null)
            }
        })
        fadeOut.start()
    }

    /** Undoes [playProfileSwitchFadeOut] -- used only when the switch itself fails afterward. */
    fun cancelProfileSwitchFadeOut() {
        val drawable = profileSwitchScrim ?: return
        profileSwitchScrim = null
        dragLayer.overlay.remove(drawable)
        dragLayer.invalidate()
    }

    private fun ensureProfileSwitchScrim(): ColorDrawable {
        profileSwitchScrim?.let { return it }
        // Display metrics rather than dragLayer's own (possibly not yet laid out) width/height --
        // this can be set up as the very first thing in onCreate, before any layout pass has run.
        val metrics = resources.displayMetrics
        val drawable = ColorDrawable(Color.BLACK).apply {
            setBounds(0, 0, metrics.widthPixels, metrics.heightPixels)
            alpha = 0
        }
        dragLayer.overlay.add(drawable)
        profileSwitchScrim = drawable
        return drawable
    }

    override fun finishBindingItems(pagesBoundFirst: IntSet) {
        super.finishBindingItems(pagesBoundFirst)
        if (profileSwitchScrim == null) return
        Log.d(PROFILE_SWITCH_LOG_TAG, "finishBindingItems fired at ${SystemClock.uptimeMillis()}, fading in")
        // A couple of frame hops so the just-bound views have actually been laid out and drawn
        // at least once underneath the scrim before it starts fading away.
        dragLayer.post { dragLayer.post(::fadeInAfterProfileSwitch) }
    }

    private fun fadeInAfterProfileSwitch() {
        val drawable = profileSwitchScrim ?: return
        profileSwitchScrim = null
        Log.d(PROFILE_SWITCH_LOG_TAG, "fade-in starting at ${SystemClock.uptimeMillis()}")

        val fadeIn = ValueAnimator.ofInt(drawable.alpha, 0)
        fadeIn.duration = PROFILE_SWITCH_SCRIM_FADE_IN_MS
        fadeIn.addUpdateListener { animator ->
            drawable.alpha = animator.animatedValue as Int
            dragLayer.invalidate()
        }
        fadeIn.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                dragLayer.overlay.remove(drawable)
                Log.d(PROFILE_SWITCH_LOG_TAG, "fade-in complete at ${SystemClock.uptimeMillis()}")
            }
        })
        fadeIn.start()
    }

    override fun onNewIntent(intent: Intent?) {
        if (intent != null && intent.action == LawnchairShortcutActivity.START_ACTION) {
            val handlerString = intent.getStringExtra(LawnchairShortcutActivity.EXTRA_HANDLER)
            val config = handlerString?.let { GestureHandlerConfig.fromString(it) }
            if (config != null && config.isExternallyInvokable()) {
                gestureController.handle(config)
            }
        }

        super.onNewIntent(intent)
    }

    override fun collectStateHandlers(out: MutableList<StateHandler<LauncherState>>) {
        super.collectStateHandlers(out)
        out.add(SearchBarStateHandler(this))
    }

    override fun getAllAppsItemLongClickListener(): View.OnLongClickListener {
        return View.OnLongClickListener { view ->
            if (view is FolderIcon && view.mInfo.id != ItemInfo.NO_ID) {
                LawnchairShortcut.showAppDrawerFolderPopup(this, view)
            } else {
                super.getAllAppsItemLongClickListener().onLongClick(view)
            }
        }
    }

    override fun getSupportedShortcuts(container: Int): Stream<SystemShortcut.Factory<*>> = Stream.concat(
        super.getSupportedShortcuts(container),
        Stream.concat(
            Stream.of(LawnchairShortcut.UNINSTALL, LawnchairShortcut.CUSTOMIZE, LawnchairShortcut.OPEN_IN_STORE),
            if (LawnchairApp.isRecentsEnabled) Stream.of(LawnchairShortcut.PAUSE_APPS) else Stream.empty(),
        ),
    )

    fun updateTheme() {
        if (themeProvider.colorScheme != colorScheme) {
            recreate()
        } else {
            mWallpaperThemeManager.updateTheme()
        }
    }

    override fun onStateBack() {
        val searchInput = mAppsView?.searchUiManager?.editText
        val isSearching = mAppsView?.isSearching == true || searchInput?.hasFocus() == true
        if (isSearching) {
            mAppsView?.searchUiManager?.resetSearch()
            allAppsController.animateAllAppsToNoScale()
        } else {
            super.onStateBack()
        }
    }

    override fun createTouchControllers(): Array<TouchController> {
        val verticalSwipeController = VerticalSwipeTouchController(this, gestureController)
        return arrayOf<TouchController>(verticalSwipeController) + super.createTouchControllers()
    }

    override fun handleHomeTap() {
        gestureController.onHomePressed()
    }

    fun bindItems(items: List<ItemInfo>, forceAnimateIcons: Boolean) {
        // pE-TODO(QPR1): Note: null is modelWriter + bindItems override something
        val inflatedItems = items.map { i ->
            Pair.create(
                i,
                itemInflater?.inflateItem(
                    i,
                    null,
                ),
            )
        }.toList()
        bindInflatedItems(inflatedItems, if (forceAnimateIcons) AnimatorSet() else null)
    }

    override fun handleGestureContract(intent: Intent) {
        if (!LawnchairApp.isRecentsEnabled && prefs.enableGnc.get()) {
            val gnc = GestureNavContract.fromIntent(intent)
            if (gnc != null) {
                AbstractFloatingView.closeOpenViews(
                    this,
                    false,
                    AbstractFloatingView.TYPE_ICON_SURFACE,
                )
                LawnchairFloatingSurfaceView.show(this, gnc)
            }
        }
    }

    override fun onUiChangedWhileSleeping() {
        if (Utilities.ATLEAST_S) {
            super.onUiChangedWhileSleeping()
        }
    }

    override fun showDefaultOptions(x: Float, y: Float) {
        val showWallpaperCarousel = "+carousel" in preferenceManager2.launcherPopupOrder.firstCached()

        if (showWallpaperCarousel) {
            show<LawnchairLauncher>(
                this,
                getPopupTarget(x, y),
                OptionsPopupView.getOptions(this),
            )
        } else {
            super.showDefaultOptions(x, y)
        }
    }

    private fun <T> show(
        activityContext: ActivityContext?,
        targetRect: RectF,
        items: List<OptionItem>,
        shouldAddArrow: Boolean = false,
        width: Int = 0,
    ): OptionsPopupView<T>? where T : Context?, T : ActivityContext? {
        if (activityContext == null) return null

        val isEmpty = WallpaperService.INSTANCE.get(this).getTopWallpapers().isEmpty()
        val layout = if (isEmpty) R.layout.longpress_options_menu else R.layout.wallpaper_options_popup

        val popup = activityContext.layoutInflater.inflate(layout, activityContext.dragLayer, false) as OptionsPopupView<T>
        popup.setTargetRect(targetRect)
        popup.setShouldAddArrow(shouldAddArrow)

        for (item in items) {
            val deepLayout = if (isEmpty) R.layout.system_shortcut else R.layout.wallpaper_options_popup_item

            val view = popup.inflateAndAdd<DeepShortcutView>(deepLayout, popup)
            if (width > 0) view.layoutParams.width = width
            view.iconView.setBackgroundDrawable(item.icon)
            view.bubbleText.text = item.label
            view.setOnClickListener(popup)
            view.setOnLongClickListener(popup)
            popup.mItemMap[view] = item
        }

        popup.show()
        return popup
    }

    fun createAppWidgetHolder(): LauncherWidgetHolder {
        val holder = LauncherWidgetHolder.newInstance(this)
        holder.setAppWidgetRemovedCallback { appWidgetId ->
            workspace.removeWidget(appWidgetId)
        }
        return holder
    }

    override fun makeDefaultActivityOptions(splashScreenStyle: Int): ActivityOptionsWrapper {
        val callbacks = RunnableList()
        val options = if (Utilities.ATLEAST_Q) {
            LawnchairQuickstepCompat.activityOptionsCompat.makeCustomAnimation(
                this,
                0,
                0,
                Executors.MAIN_EXECUTOR.handler,
                null,
            ) {
                callbacks.executeAllAndDestroy()
            }
        } else {
            ActivityOptions.makeBasic()
        }
        if (Utilities.ATLEAST_T) {
            options.splashScreenStyle = splashScreenStyle
        }

        Utilities.allowBGLaunch(options)
        return ActivityOptionsWrapper(options, callbacks)
    }

    override fun getActivityLaunchOptions(v: View?, item: ItemInfo?): ActivityOptionsWrapper {
        return runCatching {
            super.getActivityLaunchOptions(v, item)
        }.getOrElse {
            getActivityLaunchOptionsDefault(v)
        }
    }

    private fun getActivityLaunchOptionsDefault(v: View?): ActivityOptionsWrapper {
        var left = 0
        var top = 0
        var width = v!!.measuredWidth
        var height = v.measuredHeight
        if (v is BubbleTextView) {
            // Launch from center of icon, not entire view
            val icon: Drawable? = v.icon
            if (icon != null) {
                val bounds = icon.bounds
                left = (width - bounds.width()) / 2
                top = v.paddingTop
                width = bounds.width()
                height = bounds.height()
            }
        }
        val options = Utilities.allowBGLaunch(
            ActivityOptions.makeClipRevealAnimation(
                v,
                left,
                top,
                width,
                height,
            ),
        )
        if (Utilities.ATLEAST_T) {
            options.splashScreenStyle = SplashScreen.SPLASH_SCREEN_STYLE_ICON
        }
        options.launchDisplayId = if (v.display != null) v.display.displayId else Display.DEFAULT_DISPLAY
        val callback = RunnableList()
        return ActivityOptionsWrapper(options, callback)
    }

    override fun onResume() {
        super.onResume()
        restartIfPending()
        refreshPredictionContainersFromModel()

        dragLayer.viewTreeObserver.addOnDrawListener(
            object : ViewTreeObserver.OnDrawListener {
                private var handled = false

                override fun onDraw() {
                    if (handled) {
                        return
                    }
                    handled = true

                    dragLayer.post {
                        dragLayer.viewTreeObserver.removeOnDrawListener(this)
                        // Drop stuck All Apps RenderEffect on icons after returning home.
                        depthController.clearStuckBlurOnResumeIfHome()
                    }
                }
            },
        )
    }

    override fun onStateSetEnd(state: LauncherState) {
        super.onStateSetEnd(state)
        refreshPredictionContainersFromModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only actually closes if required, safe to call if not enabled
        SmartspacerClient.close()
        profileSwitchScrim = null
    }

    override fun getDefaultOverlay(): LauncherOverlayManager = defaultOverlay

    fun recreateIfNotScheduled() {
        if (sRestartFlags == 0) {
            recreate()
        }
    }

    private fun restartIfPending() {
        when {
            sRestartFlags and FLAG_RESTART != 0 -> lawnchairApp.restart(false)

            sRestartFlags and FLAG_RECREATE != 0 -> {
                sRestartFlags = 0
                recreate()
            }
        }
    }

    private fun refreshPredictionContainersFromModel() {
        LauncherAppState.getInstance(this).model.loadAsync { dataModel ->
            if (dataModel == null || isDestroyed) return@loadAsync

            val predictedContainers = synchronized(dataModel) {
                listOf(
                    dataModel.itemsIdMap[CONTAINER_ALL_APPS_PREDICTION] as? PredictedContainerInfo,
                    dataModel.itemsIdMap[CONTAINER_HOTSEAT_PREDICTION] as? PredictedContainerInfo,
                    dataModel.itemsIdMap[CONTAINER_WIDGETS_PREDICTION] as? PredictedContainerInfo,
                ).filterNotNull()
            }

            Executors.MAIN_EXECUTOR.execute {
                if (isDestroyed) return@execute
                predictedContainers.forEach(::bindPredictedContainerInfo)
            }
        }
    }

    /**
     * Reloads app icons if there is an active icon pack & [PreferenceManager2.alwaysReloadIcons] is enabled.
     */
    private fun reloadIconsIfNeeded() {
        if (
            preferenceManager2.alwaysReloadIcons.firstCached()
        ) {
            LauncherAppState.getInstance(this).model.reloadIfActive()
        }
    }

    companion object {
        private const val FLAG_RECREATE = 1 shl 0
        private const val FLAG_RESTART = 1 shl 1

        private const val PROFILE_SWITCH_SCRIM_FADE_OUT_MS = 200L
        private const val PROFILE_SWITCH_SCRIM_FADE_IN_MS = 250L

        // Safety-net cap on how long the profile-switch black scrim (see
        // showProfileSwitchScrimIfPending) can stay on screen if finishBindingItems never fires
        // -- not the normal removal path, which is driven by that callback instead.
        private const val PROFILE_SWITCH_SCRIM_MAX_HOLD_MS = 4000L
        private const val PROFILE_SWITCH_LOG_TAG = "ProfileSwitchTransition"

        var sRestartFlags = 0

        val instance get() = LawnchairApp.launcher
    }
}

val Context.launcher: LawnchairLauncher
    get() = BaseActivity.fromContext(this)

val Context.launcherNullable: LawnchairLauncher? get() = try {
    launcher
} catch (_: IllegalArgumentException) {
    null
}
