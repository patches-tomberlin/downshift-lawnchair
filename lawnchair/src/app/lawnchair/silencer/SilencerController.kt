package app.lawnchair.silencer

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.lawnchair.util.requireSystemService
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import javax.inject.Inject

/**
 * "Silencer": a Do Not Disturb toggle, ported from the paused custom-Compose Downshift app's
 * `DndController`/`LauncherViewModel` DND logic. Uses the platform's notification-policy API
 * directly (`NotificationManager.setInterruptionFilter`) rather than Zen `AutomaticZenRule`s, same
 * as the original -- simpler, and this device's DND behavior is fully covered by the interruption
 * filter alone. `ACCESS_NOTIFICATION_POLICY` can't be requested through the normal runtime
 * permission dialog; the only way to grant it is the system's own settings screen, which
 * [policyAccessSettingsIntent] sends the user to.
 *
 * Session state (timed end time / indefinite flag) is stored in its own plain [SharedPreferences]
 * file, deliberately NOT routed through [app.lawnchair.preferences2.PreferenceManager2]'s
 * DataStore. That shared file has reactive `onEach { reloadHelper.restart()/recreate() }`
 * listeners on unrelated preferences (hotseatMode, isHotseatEnabled, hotseatQsbProvider) that,
 * once observed, fire on *any* write to the underlying DataStore -- including keys they don't
 * care about -- and recreate the whole Launcher Activity. That tore down this exact popup
 * mid-interaction (dismissed the Compose Popup, reset all remembered state) every time a session
 * was started/stopped, which looked like "nothing happens" or "state doesn't match" depending on
 * timing. Silencer's session state has no other reader that needs Compose/Flow reactivity, so a
 * plain SharedPreferences file sidesteps that whole mechanism rather than working around it.
 *
 * [isActive] is driven entirely by this stored state, not by re-reading
 * [NotificationManager.getCurrentInterruptionFilter] -- that call goes through system_server
 * asynchronously, so reading it back immediately after our own
 * [NotificationManager.setInterruptionFilter] call can still return the pre-change value for a
 * moment.
 */
@LauncherAppSingleton
class SilencerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager: NotificationManager = context.requireSystemService()
    private val alarmManager: AlarmManager = context.requireSystemService()
    private val sessionPrefs = context.getSharedPreferences("silencer_session", Context.MODE_PRIVATE)

    fun isPolicyAccessGranted(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    fun policyAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Null if policy access isn't granted. Otherwise app-tracked state -- see the class doc. */
    fun isActive(): Boolean? {
        if (!isPolicyAccessGranted()) return null
        return activeUntilMillis() > 0 || sessionPrefs.getBoolean(KEY_INDEFINITE_ACTIVE, false)
    }

    /** Non-zero only while a timed (not indefinite) session is running. */
    fun activeUntilMillis(): Long = sessionPrefs.getLong(KEY_UNTIL_MILLIS, 0L)

    /** [targetMillis] is an absolute end time (epoch millis), not a duration -- callers (the
     *  duration-picker UI) already resolved a picked "hours : minutes from now" span into a
     *  concrete timestamp. */
    fun startTimedSessionUntil(targetMillis: Long) {
        setPolicyFilterOn()
        sessionPrefs.edit()
            .putLong(KEY_UNTIL_MILLIS, targetMillis)
            .putBoolean(KEY_INDEFINITE_ACTIVE, false)
            .commit()
        scheduleOffAlarm(targetMillis)
    }

    fun startIndefiniteSession() {
        setPolicyFilterOn()
        sessionPrefs.edit()
            .putLong(KEY_UNTIL_MILLIS, 0L)
            .putBoolean(KEY_INDEFINITE_ACTIVE, true)
            .commit()
        cancelOffAlarm()
    }

    fun extend(additionalMillis: Long) {
        val current = activeUntilMillis()
        val base = if (current > System.currentTimeMillis()) current else System.currentTimeMillis()
        val untilMillis = base + additionalMillis
        sessionPrefs.edit().putLong(KEY_UNTIL_MILLIS, untilMillis).commit()
        scheduleOffAlarm(untilMillis)
    }

    fun cancelSession() {
        runCatching { notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        sessionPrefs.edit()
            .putLong(KEY_UNTIL_MILLIS, 0L)
            .putBoolean(KEY_INDEFINITE_ACTIVE, false)
            .commit()
        cancelOffAlarm()
    }

    private fun setPolicyFilterOn() {
        runCatching { notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
    }

    private fun offPendingIntent(): PendingIntent {
        val intent = Intent(context, SilencerOffReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            OFF_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleOffAlarm(triggerAtMillis: Long) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, offPendingIntent())
    }

    private fun cancelOffAlarm() {
        alarmManager.cancel(offPendingIntent())
    }

    companion object {
        private const val OFF_ALARM_REQUEST_CODE = 4201
        private const val KEY_UNTIL_MILLIS = "until_millis"
        private const val KEY_INDEFINITE_ACTIVE = "indefinite_active"

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getSilencerController)

        fun getInstance(context: Context): SilencerController = INSTANCE.get(context)!!
    }
}
