package app.lawnchair.hotseat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.lawnchair.animateToAllApps
import app.lawnchair.launcher
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.profile.WorkspaceProfileId
import app.lawnchair.profile.WorkspaceProfileManager
import app.lawnchair.silencer.SilencerController
import app.lawnchair.ui.icons.BellIcon
import app.lawnchair.ui.icons.BellSlashIcon
import app.lawnchair.ui.icons.FilledBuildingIcon
import app.lawnchair.ui.icons.FilledPersonIcon
import app.lawnchair.wallpaper.WallpaperColorsCompat
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Replaces the default search bar in the hotseat with three controls, clustered closely together
 * and centered rather than stretched edge to edge: a search circle, a single pill housing both
 * Personal and Work (so the profile switch reads as one control), and a Silencer (DND) circle --
 * a placeholder for now, the real Silencer port is a later feature. All three share the same
 * wallpaper-adaptive tint (see [rememberHotseatContrast]).
 */
@Composable
fun DownshiftControlsUi(modifier: Modifier = Modifier) {
    val contrast = rememberHotseatContrast()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchButton(contrast = contrast, modifier = Modifier.fillMaxHeight())
        ProfileSwitch(contrast = contrast, modifier = Modifier.fillMaxHeight())
        SilencerButton(contrast = contrast, modifier = Modifier.fillMaxHeight())
    }
}

/** Wallpaper-adaptive tint for the hotseat controls, so gray tones stay legible on any wallpaper. */
private data class HotseatContrast(
    val background: Color,
    val backgroundSelected: Color,
    val icon: Color,
)

/**
 * Tracks the active profile's wallpaper luminance via Lawnchair's existing
 * [WallpaperManagerCompat] color-extraction pipeline and returns matching tint colors. Purely
 * event-driven: the listener is what updates state, not a one-time check at composition. This
 * also covers the profile-switch case correctly -- a switch restarts the whole process, so this
 * composable's initial read on the next cold start reflects whatever WallpaperManager reports at
 * that moment, and any correction (if the system is still decoding the just-restored bitmap)
 * arrives the same way any other wallpaper change does: through this same listener callback.
 */
@Composable
private fun rememberHotseatContrast(): HotseatContrast {
    val context = LocalContext.current
    val wallpaperManager = remember { WallpaperManagerCompat.INSTANCE.get(context) }
    var isLightWallpaper by remember { mutableStateOf(hasDarkTextHint(wallpaperManager.wallpaperColors)) }

    DisposableEffect(wallpaperManager) {
        val listener = object : WallpaperManagerCompat.OnColorsChangedListener {
            override fun onColorsChanged() {
                isLightWallpaper = hasDarkTextHint(wallpaperManager.wallpaperColors)
            }
        }
        wallpaperManager.addOnChangeListener(listener)
        onDispose { wallpaperManager.removeOnChangeListener(listener) }
    }

    return if (isLightWallpaper) {
        HotseatContrast(
            background = Color(0xFF33343A).copy(alpha = 0.72f),
            backgroundSelected = Color(0xFF33343A).copy(alpha = 0.92f),
            icon = Color(0xFF1C1C1F),
        )
    } else {
        HotseatContrast(
            background = Color(0xFFF2F2F5).copy(alpha = 0.20f),
            backgroundSelected = Color(0xFFF2F2F5).copy(alpha = 0.38f),
            icon = Color(0xFFF2F2F5),
        )
    }
}

private fun hasDarkTextHint(colors: WallpaperColorsCompat?): Boolean {
    val hints = colors?.colorHints ?: 0
    return (hints and WallpaperColorsCompat.HINT_SUPPORTS_DARK_TEXT) != 0
}

// Fixed rather than derived from fillMaxHeight(): the hotseat container's own height
// (qsb_widget_height) resolves dynamically rather than to a stable dimension, and deriving
// button width from an aspectRatio-on-height meant any transient height recalculation (e.g. from
// a new window/popup briefly touching insets) could nudge every button's width and visibly shift
// the whole centered row a few dp. A fixed size can't be affected by that.
private val HotseatButtonSize = 48.dp

@Composable
private fun HotseatCircleButton(
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(HotseatButtonSize)
                .clip(CircleShape)
                .background(background, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun SearchButton(contrast: HotseatContrast, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    HotseatCircleButton(
        background = contrast.background,
        onClick = {
            val launcher = context.launcher
            // Identical to the "App Search" gesture (OpenAppSearchGestureHandler): open the
            // built-in All Apps search directly, not whatever external provider is configured.
            val searchUiManager = launcher.appsView.searchUiManager
            searchUiManager.setDirectFocus(true)
            searchUiManager.editText?.showKeyboard()
            scope.launch { launcher.animateToAllApps() }
        },
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_qsb_search),
            contentDescription = stringResource(id = R.string.label_search),
            tint = contrast.icon,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Scales a child up (or down) to a target height, preserving its aspect ratio. Plain
 * [Modifier.scale] only affects drawing, not the measured layout size -- the Row would keep
 * reserving the child's *unscaled* footprint while it visually renders larger, silently eating
 * into [Arrangement.spacedBy]'s gap to its neighbors. This measures the child at its natural size,
 * derives the scale factor from that actual measurement (rather than an assumed constant, since
 * Material3's Switch doesn't expose its intrinsic track size as a public constant), and reports
 * the scaled size as its own layout size -- so the Row's spacing is measured against the true
 * visual footprint, not the pre-scale one.
 */
private fun Modifier.scaleToHeight(targetHeight: Dp): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val scale = targetHeight.roundToPx().toFloat() / placeable.height
    val width = (placeable.width * scale).roundToInt()
    val height = (placeable.height * scale).roundToInt()
    layout(width, height) {
        placeable.placeRelativeWithLayer(
            x = ((width - placeable.width) / 2f).roundToInt(),
            y = ((height - placeable.height) / 2f).roundToInt(),
        ) {
            scaleX = scale
            scaleY = scale
        }
    }
}

@Composable
private fun ProfileSwitch(contrast: HotseatContrast, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeProfile by preferenceManager2().activeWorkspaceProfile.getAdapter()
    val manager = remember { WorkspaceProfileManager.getInstance(context) }

    // Drives the confirmation dialog's visibility -- cleared as soon as the user answers it.
    var dialogTarget by remember { mutableStateOf<WorkspaceProfileId?>(null) }

    val checked = activeProfile == WorkspaceProfileId.WORK

    Box(
        modifier = modifier.height(HotseatButtonSize),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = { isWork ->
                val target = if (isWork) WorkspaceProfileId.WORK else WorkspaceProfileId.PERSONAL
                if (target != activeProfile) dialogTarget = target
            },
            modifier = Modifier.scaleToHeight(HotseatButtonSize),
            thumbContent = {
                if (checked) {
                    FilledBuildingIcon(size = SwitchDefaults.IconSize, color = contrast.icon)
                } else {
                    FilledPersonIcon(size = SwitchDefaults.IconSize, color = contrast.icon)
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = contrast.backgroundSelected,
                uncheckedThumbColor = contrast.backgroundSelected,
                checkedTrackColor = contrast.background,
                uncheckedTrackColor = contrast.background,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }

    val target = dialogTarget
    if (target != null) {
        val targetLabel = stringResource(
            id = if (target == WorkspaceProfileId.WORK) R.string.profile_work else R.string.profile_personal,
        )
        AlertDialog(
            onDismissRequest = { dialogTarget = null },
            title = { Text(text = stringResource(id = R.string.profile_switch_confirm_title, targetLabel)) },
            text = { Text(text = stringResource(id = R.string.profile_switch_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    dialogTarget = null
                    scope.launch {
                        val success = manager.switchTo(target)
                        if (!success) {
                            Toast.makeText(context, R.string.profile_switch_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogTarget = null }) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
        )
    }
}

private enum class SilencerDialogMode { NONE, START, ACTIVE }

@Composable
private fun SilencerButton(contrast: HotseatContrast, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { SilencerController.getInstance(context) }

    // Bumped after every action so isActive()/activeUntilMillis() get re-read -- a plain state
    // flag, not a ticker, since DND's own filter state doesn't change on a timer we need to watch
    // continuously (only the countdown text below does, and that has its own lifecycle-aware one).
    var refreshKey by remember { mutableStateOf(0) }
    val isActive = remember(refreshKey) { controller.isActive() }
    val untilMillis = remember(refreshKey) { controller.activeUntilMillis() }
    var dialogMode by remember { mutableStateOf(SilencerDialogMode.NONE) }

    val countdownText = rememberSilencerCountdownText(
        untilMillis = untilMillis,
        isTimedActive = isActive == true && untilMillis > 0,
    )

    val onClick: () -> Unit = {
        when {
            dialogMode != SilencerDialogMode.NONE -> dialogMode = SilencerDialogMode.NONE
            !controller.isPolicyAccessGranted() -> context.startActivity(controller.policyAccessSettingsIntent())
            isActive == true -> dialogMode = SilencerDialogMode.ACTIVE
            else -> dialogMode = SilencerDialogMode.START
        }
    }
    val bellIcon: @Composable () -> Unit = {
        if (isActive == true) {
            BellSlashIcon(size = 28.dp, color = contrast.icon)
        } else {
            BellIcon(size = 28.dp, color = contrast.icon)
        }
    }
    if (countdownText.isEmpty()) {
        HotseatCircleButton(
            background = contrast.background,
            onClick = onClick,
            modifier = modifier,
        ) {
            bellIcon()
        }
    } else {
        val shape = RoundedCornerShape(percent = 50)
        Row(
            modifier = modifier
                .height(HotseatButtonSize)
                .clip(shape)
                .background(contrast.background, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            bellIcon()
            Text(text = countdownText, color = contrast.icon, style = MaterialTheme.typography.labelLarge)
        }
    }

    if (dialogMode == SilencerDialogMode.START) {
        SilencerTimePickerDialog(
            onDismissRequest = { dialogMode = SilencerDialogMode.NONE },
            onPickTime = { targetMillis ->
                controller.startTimedSessionUntil(targetMillis)
                refreshKey++
                dialogMode = SilencerDialogMode.NONE
            },
            onPickIndefinite = {
                controller.startIndefiniteSession()
                refreshKey++
                dialogMode = SilencerDialogMode.NONE
            },
        )
    }

    if (dialogMode == SilencerDialogMode.ACTIVE) {
        SilencerMenuPopup(onDismissRequest = { dialogMode = SilencerDialogMode.NONE }) {
            SilencerMenuRow(
                label = stringResource(id = R.string.silencer_turn_off),
                icon = { BellSlashIcon(size = MenuRowIconSize, color = MaterialTheme.colorScheme.onSurface) },
                onClick = {
                    controller.cancelSession()
                    refreshKey++
                    dialogMode = SilencerDialogMode.NONE
                },
            )
            if (untilMillis > 0) {
                SilencerMenuRow(stringResource(id = R.string.silencer_extend_15m)) {
                    controller.extend(15 * 60_000L)
                    refreshKey++
                    dialogMode = SilencerDialogMode.NONE
                }
            }
        }
    }
}

/**
 * Replaces the old fixed 15m/30m/1h presets with a duration picker built from the same M3
 * [TimePicker] dial/text-input component -- reinterpreted here as "hours : minutes from now"
 * rather than a clock time, so it looks and behaves like the familiar time picker while actually
 * picking a duration. Always forced to 24-hour mode with no AM/PM toggle, since that framing only
 * makes sense for an actual time of day, not a span of time. "Until I turn it off" is retained as
 * a one-tap bypass that skips the dial entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SilencerTimePickerDialog(
    onDismissRequest: () -> Unit,
    onPickTime: (targetMillis: Long) -> Unit,
    onPickIndefinite: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = 0,
        initialMinute = 30,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(id = R.string.silencer_start_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = timePickerState)
                TextButton(
                    onClick = onPickIndefinite,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(id = R.string.silencer_preset_until_off))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPickTime(resolveDurationTargetMillis(timePickerState.hour, timePickerState.minute)) },
            ) {
                Text(text = stringResource(id = R.string.silencer_set_time))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
        },
    )
}

private fun resolveDurationTargetMillis(hours: Int, minutes: Int): Long {
    val durationMillis = (hours * 60L + minutes) * 60_000L
    return System.currentTimeMillis() + durationMillis
}

/**
 * Live "MM:SS"/"H:MM:SS" remaining-time text for a timed Silencer session, ticking once a second
 * only while the launcher is actually on screen -- wrapped in `repeatOnLifecycle(STARTED)` so it
 * halts the instant this process is backgrounded rather than ticking for as long as the process
 * happens to live. (A previous launcher's DND countdown ran a bare `while(true) { delay(N) }`
 * with no lifecycle gate at all and drained battery for hours in the background; this is the fix
 * for that exact mistake, applied up front rather than after the fact.)
 */
@Composable
private fun rememberSilencerCountdownText(untilMillis: Long, isTimedActive: Boolean): String {
    var remainingText by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(untilMillis, isTimedActive, lifecycleOwner) {
        if (!isTimedActive) {
            remainingText = ""
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val remainingMs = untilMillis - System.currentTimeMillis()
                if (remainingMs <= 0) {
                    remainingText = ""
                    break
                }
                val totalSeconds = remainingMs / 1000
                val h = totalSeconds / 3600
                val m = (totalSeconds % 3600) / 60
                val s = totalSeconds % 60
                remainingText = if (h > 0) {
                    "%d:%02d:%02d".format(h, m, s)
                } else {
                    "%d:%02d".format(m, s)
                }
                delay(1000)
            }
        }
    }
    return remainingText
}

// Dimensions lifted directly from the home-screen long-press menu's own row layout
// (res/layout/system_shortcut_content.xml + res/values/dimens.xml: bg_popup_item_width/height,
// system_shortcut_icon_size, system_shortcut_margin_start), so this menu reads as the same
// control family rather than a generic dialog.
private val MenuCardWidth = 216.dp
private val MenuRowHeight = 52.dp
private val MenuRowIconSize = 20.dp
private val MenuRowIconMarginStart = 16.dp
private val MenuRowTextPaddingStart = 54.dp
private val MenuCardCornerRadius = 16.dp

/**
 * A menu styled like the home-screen long-press menu: a rounded card of icon+label rows,
 * positioned near the bottom-right of the screen (close to the hotseat controls, without
 * precisely anchoring to the bell's own live-measured position).
 *
 * Uses [Dialog] rather than [Popup] deliberately: the [Popup]-based version of this menu (an
 * earlier iteration) reliably shifted the whole hotseat controls row a few dp to the left for as
 * long as the menu stayed open, snapping back only once it closed -- reproducible, not a one-frame
 * animation artifact (confirmed via matched before/after screenshots). That didn't happen with
 * this launcher's own long-press menu, or with an earlier centered-[Dialog] version of this same
 * menu, which points at something about [Popup]'s specific window flags interacting badly with
 * this OEM build's hotseat positioning -- not something fixable via [PopupProperties] alone (tried
 * `excludeFromSystemGesture`, no effect). [Dialog] sidesteps it entirely.
 */
@Composable
private fun SilencerMenuPopup(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Centered, standard Dialog sizing/positioning for now -- the custom full-screen-positioned
    // version broke tap-outside-to-dismiss (its content Box covered the whole window, so there
    // was no "outside" left to tap) and blocked touches through to the rest of the hotseat.
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .width(MenuCardWidth)
                .clip(RoundedCornerShape(MenuCardCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SilencerMenuRow(
    label: String,
    icon: @Composable () -> Unit = { BellIcon(size = MenuRowIconSize, color = MaterialTheme.colorScheme.onSurface) },
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MenuRowHeight)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .padding(start = MenuRowIconMarginStart)
                .size(MenuRowIconSize)
                .align(Alignment.CenterStart),
        ) {
            icon()
        }
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = MenuRowTextPaddingStart, end = 14.dp)
                .align(Alignment.CenterStart),
        )
    }
}
