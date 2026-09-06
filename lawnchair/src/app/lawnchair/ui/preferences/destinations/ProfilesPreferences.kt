package app.lawnchair.ui.preferences.destinations

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.profile.WorkspaceProfileId
import app.lawnchair.profile.WorkspaceProfileManager
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
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
    val activeProfile by preferenceManager2().activeWorkspaceProfile.getAdapter()
    val manager = WorkspaceProfileManager.getInstance(context)

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
