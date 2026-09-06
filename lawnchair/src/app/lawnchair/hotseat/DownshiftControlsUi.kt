package app.lawnchair.hotseat

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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

    // Deliberately inverted from what WallpaperColorsCompat's hint would suggest: that hint
    // reflects the wallpaper's overall/global tone, but the hotseat only ever sits over the
    // bottom strip of the screen, whose local brightness doesn't reliably match the wallpaper's
    // global classification (e.g. a wallpaper dark overall but lighter toward the bottom edge,
    // or vice versa). Empirically, picking colors as if the hint meant the opposite tracked the
    // actual bottom-strip brightness far better than trusting it directly.
    // Icon color is fixed, not wallpaper-adaptive -- only the pill/circle backgrounds behind it
    // switch tone.
    return if (isLightWallpaper) {
        HotseatContrast(
            background = Color(0xFFF2F2F5).copy(alpha = 0.20f),
            backgroundSelected = Color(0xFFF2F2F5).copy(alpha = 0.38f),
            icon = Color(0xFFF2F2F5),
        )
    } else {
        HotseatContrast(
            background = Color(0xFF33343A).copy(alpha = 0.72f),
            backgroundSelected = Color(0xFF33343A).copy(alpha = 0.92f),
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

// A custom-drawn track+thumb rather than Material3's Switch, whose fixed internal track/thumb
// ratio (SwitchTokens.TrackHeight/IconHandleHeight, neither public) can't be scaled to make the
// thumb match HotseatButtonSize within the hotseat's ~52dp usable slot height (qsb_widget_height
// minus its vertical padding -- shared with Google's search-bar hotseat mode, so not ours to grow).
// The outer layout box is sized to the LARGER of track/thumb (the thumb) rather than the track:
// a Box passes its own resolved height down to its children as a hard max, so if the track's
// height were the outer box's size, the thumb's .size() request would silently get clamped down
// to fit inside it instead of overflowing above/below as intended -- exactly the bug that made the
// thumb render undersized and flush to one edge instead of centered. Making the track itself an
// inner, center-aligned child (rather than the outer sizing box) sidesteps that entirely.
private val ProfileSwitchTrackHeight = HotseatButtonSize * 0.75f
private val ProfileSwitchTrackWidth = 80.dp * 1.5f * 0.75f
private val ProfileSwitchThumbSize = HotseatButtonSize
private val ProfileSwitchThumbTravel = ProfileSwitchTrackWidth - ProfileSwitchThumbSize
private val ProfileSwitchShape = RoundedCornerShape(percent = 50)

// Active started matched to SearchButton's ic_qsb_search glyph (22dp); inactive started smaller
// (14dp) so it read as secondary. Both are now scaled up from those originals per explicit
// sizing direction -- active 1.5x, inactive 2.0x (the inactive icon has more room to grow into,
// since it isn't fighting a matching-Search-icon constraint).
private val ProfileSwitchActiveIconSize = 22.dp * 1.5f
private val ProfileSwitchInactiveIconSize = 14.dp * 2f

// The active profile's thumb is deliberately NOT wallpaper-adaptive like the rest of the hotseat
// controls: a fixed white circle with the same gray as the track's own pill color for the glyph,
// so it reads the same regardless of wallpaper.
private val ProfileSwitchActiveThumbColor = Color(0xFFF2F2F5)
private val ProfileSwitchActiveIconColor = Color(0xFF33343A)

@Composable
private fun ProfileSwitch(contrast: HotseatContrast, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeProfile by preferenceManager2().activeWorkspaceProfile.getAdapter()
    val manager = remember { WorkspaceProfileManager.getInstance(context) }

    // Drives the confirmation dialog's visibility -- cleared as soon as the user answers it.
    var dialogTarget by remember { mutableStateOf<WorkspaceProfileId?>(null) }

    val checked = activeProfile == WorkspaceProfileId.WORK
    val thumbOffsetX by animateDpAsState(
        targetValue = if (checked) ProfileSwitchThumbTravel else 0.dp,
        label = "profileSwitchThumbOffset",
    )
    // Centered within whichever end of the track the thumb currently leaves uncovered (the
    // ProfileSwitchThumbTravel-wide strip opposite the thumb), so the smaller inactive icon never
    // sits under it.
    val inactiveIconCenterX by animateDpAsState(
        targetValue = if (checked) ProfileSwitchThumbTravel / 2 else ProfileSwitchTrackWidth - ProfileSwitchThumbTravel / 2,
        label = "profileSwitchInactiveIconCenterX",
    )

    // Outer box takes the incoming (fillMaxHeight) modifier AS-IS, uncombined with any fixed
    // .height() of our own -- exactly the HotseatCircleButton pattern above, which centers its
    // 48dp circle via contentAlignment on an untouched fillMaxHeight() box rather than chaining
    // .height(48.dp) onto that same modifier. Chaining our own fixed height directly onto the
    // incoming fillMaxHeight() modifier (the previous version of this code) measurably threw off
    // this control's vertical position relative to Search/Silencer -- confirmed by pixel-measuring
    // screenshots, not guessed -- so the fixed-size content now lives on a separate inner box,
    // matching the working pattern instead of a variant of it.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            // Sized to the thumb (the larger element) so neither it nor the inactive icon get
            // clamped -- see the comment above ProfileSwitchTrackHeight.
            modifier = Modifier
                .height(ProfileSwitchThumbSize)
                .width(ProfileSwitchTrackWidth)
                .clip(ProfileSwitchShape)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = { isWork ->
                        val target = if (isWork) WorkspaceProfileId.WORK else WorkspaceProfileId.PERSONAL
                        if (target != activeProfile) dialogTarget = target
                    },
                ),
        ) {
            Box(
                // The inactive icon is a child of the pill itself (not a sibling positioned with
                // manually-computed offsets against the outer box) so Compose's own alignment
                // centers it against the pill's true, actual bounds directly.
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(ProfileSwitchTrackHeight)
                    .clip(ProfileSwitchShape)
                    .background(contrast.background, ProfileSwitchShape),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = inactiveIconCenterX - ProfileSwitchInactiveIconSize / 2)
                        .size(ProfileSwitchInactiveIconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    val inactiveColor = contrast.icon
                    if (checked) {
                        FilledPersonIcon(size = ProfileSwitchInactiveIconSize, color = inactiveColor)
                    } else {
                        FilledBuildingIcon(size = ProfileSwitchInactiveIconSize, color = inactiveColor)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .offset(x = thumbOffsetX)
                    .size(ProfileSwitchThumbSize)
                    .clip(CircleShape)
                    .background(ProfileSwitchActiveThumbColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    FilledBuildingIcon(size = ProfileSwitchActiveIconSize, color = ProfileSwitchActiveIconColor)
                } else {
                    FilledPersonIcon(size = ProfileSwitchActiveIconSize, color = ProfileSwitchActiveIconColor)
                }
            }
        }
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
