package app.lawnchair.ui.preferences.destinations

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.profile.WorkspaceProfileId
import app.lawnchair.profile.WorkspaceProfileManager
import app.lawnchair.profile.ZenModeSyncManager
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.AdvancedColorPicker
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.navigation.WallpaperPicker
import com.android.launcher3.R
import kotlinx.coroutines.launch

@Composable
fun ProfilesPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val prefs2 = preferenceManager2()
    val activeProfile by prefs2.activeWorkspaceProfile.getAdapter()
    val manager = WorkspaceProfileManager.getInstance(context)
    val zenManager = ZenModeSyncManager.getInstance(context)
    val zenModeSyncAdapter = prefs2.isZenModeSyncEnabled.getAdapter()
    var zenSetupPromptProfile by remember { mutableStateOf<WorkspaceProfileId?>(null) }

    val labelColorEnabledPersonalAdapter = prefs2.downshiftProfileIconLabelColorEnabledPersonal.getAdapter()
    val labelColorArgbPersonalAdapter = prefs2.downshiftProfileIconLabelColorArgbPersonal.getAdapter()
    val labelColorEnabledWorkAdapter = prefs2.downshiftProfileIconLabelColorEnabledWork.getAdapter()
    val labelColorArgbWorkAdapter = prefs2.downshiftProfileIconLabelColorArgbWork.getAdapter()
    var selectedColorProfile by remember { mutableStateOf(WorkspaceProfileId.PERSONAL) }

    PreferenceLayout(
        label = stringResource(id = R.string.profiles_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup {
            ProfileRow(
                label = stringResource(id = R.string.profile_personal),
                selected = activeProfile == WorkspaceProfileId.PERSONAL,
                onClick = {
                    scope.launch {
                        val success = manager.switchTo(WorkspaceProfileId.PERSONAL)
                        if (!success) {
                            Toast.makeText(context, R.string.profile_switch_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            ProfileRow(
                label = stringResource(id = R.string.profile_work),
                selected = activeProfile == WorkspaceProfileId.WORK,
                onClick = {
                    scope.launch {
                        val success = manager.switchTo(WorkspaceProfileId.WORK)
                        if (!success) {
                            Toast.makeText(context, R.string.profile_switch_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }

        PreferenceGroup(
            heading = stringResource(id = R.string.zen_mode_sync_heading),
        ) {
            SwitchPreference(
                checked = zenModeSyncAdapter.state.value,
                onCheckedChange = { enable ->
                    when {
                        !enable -> zenModeSyncAdapter.onChange(false)
                        !zenManager.isPolicyAccessGranted() -> context.startActivity(zenManager.policyAccessSettingsIntent())
                        else -> {
                            zenModeSyncAdapter.onChange(true)
                            scope.launch {
                                if (zenManager.ensureRulesRegistered()) {
                                    zenSetupPromptProfile = activeProfile
                                }
                            }
                        }
                    }
                },
                label = stringResource(id = R.string.zen_mode_sync_label),
                description = stringResource(id = R.string.zen_mode_sync_description),
            )
        }

        val promptProfile = zenSetupPromptProfile
        if (promptProfile != null) {
            val ruleNameRes = if (promptProfile == WorkspaceProfileId.WORK) {
                R.string.zen_mode_sync_rule_name_work
            } else {
                R.string.zen_mode_sync_rule_name_personal
            }
            val ruleName = stringResource(id = ruleNameRes)
            AlertDialog(
                onDismissRequest = { zenSetupPromptProfile = null },
                title = { Text(text = stringResource(id = R.string.zen_mode_sync_setup_dialog_title, ruleName)) },
                text = { Text(text = stringResource(id = R.string.zen_mode_sync_setup_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        zenSetupPromptProfile = null
                        zenManager.ruleSettingsIntent(promptProfile)?.let { context.startActivity(it) }
                    }) {
                        Text(text = stringResource(id = R.string.zen_mode_sync_setup_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { zenSetupPromptProfile = null }) {
                        Text(text = stringResource(id = R.string.zen_mode_sync_skip_for_now))
                    }
                },
            )
        }

        PreferenceGroup(
            heading = stringResource(id = R.string.wallpaper_label),
            description = stringResource(id = R.string.wallpaper_description),
        ) {
            PreferenceTemplate(
                title = { Text(text = stringResource(id = R.string.wallpaper_label)) },
                startWidget = {
                    Icon(painter = painterResource(id = R.drawable.ic_wallpaper), contentDescription = null)
                },
                onClick = { navController.navigate(WallpaperPicker) },
            )
        }

        PreferenceGroup(
            heading = stringResource(id = R.string.profile_icon_label_color_heading),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedColorProfile == WorkspaceProfileId.PERSONAL,
                    onClick = { selectedColorProfile = WorkspaceProfileId.PERSONAL },
                    label = { Text(stringResource(id = R.string.profile_personal)) },
                )
                FilterChip(
                    selected = selectedColorProfile == WorkspaceProfileId.WORK,
                    onClick = { selectedColorProfile = WorkspaceProfileId.WORK },
                    label = { Text(stringResource(id = R.string.profile_work)) },
                )
            }

            key(selectedColorProfile) {
                val enabledAdapter = if (selectedColorProfile == WorkspaceProfileId.WORK) {
                    labelColorEnabledWorkAdapter
                } else {
                    labelColorEnabledPersonalAdapter
                }
                val argbAdapter = if (selectedColorProfile == WorkspaceProfileId.WORK) {
                    labelColorArgbWorkAdapter
                } else {
                    labelColorArgbPersonalAdapter
                }

                SwitchPreference(
                    adapter = enabledAdapter,
                    label = stringResource(id = R.string.profile_icon_label_color_custom_toggle),
                )
                ExpandAndShrink(visible = enabledAdapter.state.value) {
                    AdvancedColorPicker(
                        initialColorArgb = argbAdapter.state.value,
                        onColorChangeFinished = { argbAdapter.onChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceTemplate(
        title = { Text(text = label) },
        modifier = modifier,
        enabled = !selected,
        startWidget = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
            }
        },
        description = if (selected) {
            { Text(text = stringResource(id = R.string.profile_active), color = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        onClick = onClick,
    )
}
