package app.lawnchair.profile

import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.DisplayMetrics
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.util.restartLauncher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.provider.RestoreDbTask
import com.android.launcher3.util.DaggerSingletonObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Switches the entire visible workspace (desktop/dock layout + wallpaper) between the two fixed
 * Downshift profiles (Personal/Work), by swapping the launcher's own grid database file and
 * wallpaper, then restarting the process -- the same proven mechanism Lawnchair's own
 * backup/restore feature ([app.lawnchair.backup.LawnchairBackup]) already ships, rather than a
 * new one. A live in-place reload is deliberately not attempted: the injected [ModelDbController]
 * singleton's cached open SQLite connection would go stale if the db file were swapped underneath
 * it without a full process restart, so a fresh, non-DI [ModelDbController] is used for the
 * restore step and the process is killed and relaunched right after.
 *
 * Zen Mode Sync (per-profile DND rule switching, designed separately) is a deliberate later
 * addition -- this class's shape leaves room for it (a
 * [com.android.launcher3.util.SimpleBroadcastReceiver] owned here, the way
 * [com.android.launcher3.pm.UserCache] owns its own).
 */
@LauncherAppSingleton
class WorkspaceProfileManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val profilesDir: File
        get() = File(context.filesDir, "workspaceProfiles")

    private fun snapshotDir(id: WorkspaceProfileId): File = File(profilesDir, id.storageKey)

    private fun snapshotDbFile(id: WorkspaceProfileId): File = File(snapshotDir(id), SNAPSHOT_DB_NAME)

    private fun snapshotWallpaperFile(id: WorkspaceProfileId): File =
        File(snapshotDir(id), SNAPSHOT_WALLPAPER_NAME)

    private fun liveDbFile(): File = context.getDatabasePath(LauncherAppState.getIDP(context).dbFile)

    val activeProfile: WorkspaceProfileId
        get() = PreferenceManager2.getInstance(context).activeWorkspaceProfile.firstCached()

    /**
     * Switches the visible workspace to [target]. A no-op if [target] is already active.
     * Restarts the launcher process on success. Returns `false` (leaving the workspace
     * untouched) if the underlying db restore fails.
     */
    suspend fun switchTo(target: WorkspaceProfileId): Boolean = withContext(Dispatchers.IO) {
        val current = activeProfile
        if (current == target) return@withContext true

        snapshotCurrent(current)

        val liveDb = liveDbFile()
        liveDb.delete()
        File(liveDb.path + "-journal").delete()

        val incomingDb = snapshotDbFile(target)
        if (incomingDb.exists()) {
            incomingDb.copyTo(liveDb, overwrite = true)
        }

        val success = RestoreDbTask.performRestore(context, ModelDbController(context))
        if (!success) return@withContext false

        restoreWallpaper(target)

        PreferenceManager2.getInstance(context).activeWorkspaceProfile.set(target)

        val fadeOptions = ActivityOptions.makeCustomAnimation(
            context,
            android.R.anim.fade_in,
            android.R.anim.fade_out,
        ).toBundle()
        restartLauncher(context, fadeOptions)
        true
    }

    private fun snapshotCurrent(id: WorkspaceProfileId) {
        val dir = snapshotDir(id)
        dir.mkdirs()

        val liveDb = liveDbFile()
        if (liveDb.exists()) {
            liveDb.copyTo(snapshotDbFile(id), overwrite = true)
        }

        // Deliberately no wallpaper read-back here. Reading the *active* system wallpaper back
        // as a bitmap (WallpaperManager.getDrawable()) is gated behind broad photo/media access
        // on some OEM builds (confirmed on One UI) -- asking a launcher's own user to grant
        // "photos and videos" access is a red flag, so we don't. Wallpaper only gets captured
        // per-profile via setAndSnapshotWallpaper(), when the user picks one of our own bundled
        // wallpapers. A wallpaper set from outside the launcher (e.g. the system gallery) is
        // simply not tracked -- switching profiles leaves it as-is. That's the intended v1
        // behavior, not a bug.
    }

    /**
     * Applies [bitmap] as the system wallpaper and saves it as the active profile's own
     * snapshot, so it's restored the next time this profile becomes active. Use this for
     * wallpapers picked from our own bundled set -- never for reading back an arbitrary
     * already-applied wallpaper (see [snapshotCurrent]'s doc comment for why).
     */
    suspend fun setAndSnapshotWallpaper(bitmap: Bitmap): Unit = withContext(Dispatchers.IO) {
        val cropped = centerCropToScreen(bitmap)
        val dir = snapshotDir(activeProfile)
        dir.mkdirs()
        snapshotWallpaperFile(activeProfile).outputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        WallpaperManager.getInstance(context).setBitmap(cropped)
    }

    /**
     * Crops [source] to the device screen's aspect ratio, centered on both axes, so
     * WallpaperManager.setBitmap places it centered instead of anchoring on its default
     * (top-left-biased) offset when the picked image's aspect ratio doesn't match the screen.
     */
    private fun centerCropToScreen(source: Bitmap): Bitmap {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        if (screenWidth <= 0 || screenHeight <= 0) return source

        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
        val sourceAspect = source.width.toFloat() / source.height.toFloat()

        val (cropWidth, cropHeight) = if (sourceAspect > screenAspect) {
            (source.height * screenAspect).toInt() to source.height
        } else {
            source.width to (source.width / screenAspect).toInt()
        }
        val x = ((source.width - cropWidth) / 2).coerceAtLeast(0)
        val y = ((source.height - cropHeight) / 2).coerceAtLeast(0)
        val safeWidth = cropWidth.coerceAtMost(source.width - x)
        val safeHeight = cropHeight.coerceAtMost(source.height - y)
        if (safeWidth <= 0 || safeHeight <= 0) return source

        return Bitmap.createBitmap(source, x, y, safeWidth, safeHeight)
    }

    private fun restoreWallpaper(id: WorkspaceProfileId) {
        val incomingWallpaper = snapshotWallpaperFile(id)
        if (!incomingWallpaper.exists()) return
        val bitmap = BitmapFactory.decodeFile(incomingWallpaper.path)
        if (bitmap != null) {
            WallpaperManager.getInstance(context).setBitmap(bitmap)
        }
    }

    companion object {
        private const val SNAPSHOT_DB_NAME = "launcher.db"
        private const val SNAPSHOT_WALLPAPER_NAME = "wallpaper.png"

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getWorkspaceProfileManager)

        fun getInstance(context: Context): WorkspaceProfileManager = INSTANCE.get(context)!!
    }
}
