package app.lawnchair.profile

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.Condition
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.util.requireSystemService
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import javax.inject.Inject

/**
 * Links [WorkspaceProfileId] to a real Android Do Not Disturb rule per profile ("Downshift
 * Personal"/"Downshift Work"), opt-in via [PreferenceManager2.isZenModeSyncEnabled].
 * [setActiveProfileRule] -- called from [WorkspaceProfileManager.switchTo] -- turns the target
 * profile's rule on and the other off, and switching profiles is the *only* way either rule
 * changes state: this is a one-way sync (app -> Android), not two-way.
 *
 * A two-way "Auto Shift" (an OS-driven schedule flipping the visible profile back) was in the
 * original design but was dropped after checking real platform behavior, not assumption:
 * - Android has no built-in scheduling/location-trigger UI for a *third-party*-owned rule --
 *   tapping into "Downshift Work" from system DND settings only lets the user configure its
 *   `ZenPolicy` (which apps/people get through), the same as [ruleSettingsIntent] hands off to
 *   here. Any time-based trigger would have to be built and evaluated by this app itself.
 * - Even if it were built, this project's `minSdk` 26 rules out reliably detecting it: the
 *   `NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED` broadcast's
 *   `AUTOMATIC_RULE_STATUS_ACTIVATED`/`DEACTIVATED` values (which would mean "the rule just
 *   started/stopped actively silencing the phone") were only added at API 35 -- confirmed via
 *   this project's own `api-versions.xml`. On API 30-34 that broadcast only ever reports
 *   ENABLED/DISABLED/REMOVED (whether the rule is *allowed to exist*, not whether it's *on*), so
 *   there is no passive signal to react to on the vast majority of real devices.
 *
 * [AutomaticZenRule] requires either a real, manifest-registered `ConditionProviderService` as
 * `owner`, or a real activity as `configurationActivity` -- confirmed by reading
 * `ZenModeHelper.addAutomaticZenRule`'s actual validation in AOSP, which throws
 * `IllegalArgumentException` if neither resolves. This uses `configurationActivity` (this app's
 * own [PreferenceActivity], already exported) with `owner` left `null`, rather than standing up a
 * `ConditionProviderService` purely as a placeholder.
 *
 * Everything here requires API 30 (`Build.VERSION_CODES.R`) and silently no-ops below it -- the
 * base profile switch works fine without Zen Mode Sync on older phones.
 */
@LauncherAppSingleton
class ZenModeSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager: NotificationManager = context.requireSystemService()
    private val prefs2 get() = PreferenceManager2.getInstance(context)

    fun isPolicyAccessGranted(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    fun policyAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Creates the "Downshift Personal"/"Downshift Work" rules the first time sync is turned on,
     * reusing the saved rule IDs (from [PreferenceManager2.zenRuleIdPersonal]/[zenRuleIdWork]) on
     * every later call rather than creating duplicates. Returns `false` (no-op) below API 30 or
     * without policy-access permission.
     */
    suspend fun ensureRulesRegistered(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isPolicyAccessGranted()) return false
        WorkspaceProfileId.entries.forEach { ensureRuleRegistered(it) }
        return true
    }

    /**
     * Turns [target]'s rule on and the other profile's rule off. No-op if sync isn't enabled,
     * unsupported on this API level, or permission isn't granted -- callers don't need to check
     * [PreferenceManager2.isZenModeSyncEnabled] themselves.
     */
    suspend fun setActiveProfileRule(target: WorkspaceProfileId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!prefs2.isZenModeSyncEnabled.firstCached(prefs2)) return
        if (!isPolicyAccessGranted()) return
        WorkspaceProfileId.entries.forEach { profile ->
            val ruleId = ruleIdPreference(profile).firstCached(prefs2)
            if (ruleId.isEmpty()) return@forEach
            val state = if (profile == target) Condition.STATE_TRUE else Condition.STATE_FALSE
            runCatching {
                notificationManager.setAutomaticZenRuleState(
                    ruleId,
                    Condition(conditionUri(profile), "", state),
                )
            }
        }
    }

    /** Deep-links into Android's own configuration screen for [profile]'s rule, if it exists. */
    fun ruleSettingsIntent(profile: WorkspaceProfileId): Intent? {
        val ruleId = ruleIdPreference(profile).firstCached(prefs2)
        if (ruleId.isEmpty()) return null
        return Intent(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
            .putExtra(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID, ruleId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun ensureRuleRegistered(profile: WorkspaceProfileId) {
        val pref = ruleIdPreference(profile)
        val savedId = pref.firstCached(prefs2)
        if (savedId.isNotEmpty() && notificationManager.getAutomaticZenRule(savedId) != null) {
            return
        }
        val rule = AutomaticZenRule(
            ruleName(profile),
            null,
            ComponentName(context, PreferenceActivity::class.java),
            conditionUri(profile),
            null,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            false,
        )
        val newId = runCatching { notificationManager.addAutomaticZenRule(rule) }.getOrNull()
        if (newId != null) {
            pref.set(newId)
        }
    }

    private fun ruleIdPreference(profile: WorkspaceProfileId) = when (profile) {
        WorkspaceProfileId.PERSONAL -> prefs2.zenRuleIdPersonal
        WorkspaceProfileId.WORK -> prefs2.zenRuleIdWork
    }

    private fun ruleName(profile: WorkspaceProfileId) = when (profile) {
        WorkspaceProfileId.PERSONAL -> context.getString(R.string.zen_mode_sync_rule_name_personal)
        WorkspaceProfileId.WORK -> context.getString(R.string.zen_mode_sync_rule_name_work)
    }

    private fun conditionUri(profile: WorkspaceProfileId) =
        Condition.newId(context).appendPath(profile.storageKey).build()

    companion object {
        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getZenModeSyncManager)

        fun getInstance(context: Context): ZenModeSyncManager = INSTANCE.get(context)!!
    }
}
