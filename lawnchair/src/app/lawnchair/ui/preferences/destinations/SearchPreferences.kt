package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.search.DrawerSearchPreference
import app.lawnchair.ui.preferences.navigation.Search
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken

/**
 * A clickable row that navigates to [SearchPreferences] (the app-drawer search settings). Used
 * from within the App Drawer section's own "General" group -- there used to be a matching one in
 * the Dock section too, back when the dock had its own search bar to configure.
 */
@Composable
fun SearchBarPreference(
    modifier: Modifier = Modifier,
) {
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)
    val navController = LocalNavController.current
    ClickablePreference(
        label = stringResource(R.string.search_bar_settings),
        modifier = modifier,
        hapticToken = null,
    ) {
        mMSDLPlayerWrapper.playToken(MSDLToken.TAP_HIGH_EMPHASIS)
        navController.navigate(route = Search)
    }
}

@Composable
fun SearchPreferences(
    modifier: Modifier = Modifier,
) {
    PreferenceLayout(
        label = stringResource(id = R.string.search_bar_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        Spacer(Modifier.height(8.dp))
        DrawerSearchPreference()
    }
}
