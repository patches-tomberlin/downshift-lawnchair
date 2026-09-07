package app.lawnchair.profile

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.DisplayMetrics
import android.util.Log
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
import kotlinx.coroutines.delay
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
 * Zen Mode Sync (per-profile DND rule switching, opt-in) is handled by [ZenModeSyncManager],
 * notified from [switchTo] below. The hard process restart's cold-start gap is masked by a black
 * scrim: [app.lawnchair.hotseat.DownshiftControlsUi] fades it in on the outgoing screen right
 * before calling [switchTo], and [app.lawnchair.LawnchairLauncher] shows it immediately on every
 * cold start (see that class's doc comment for why it's unconditional, not flagged some other
 * way) and fades it back out once the workspace has finished binding.
 *
 * Running the wallpaper restore in parallel with the post-restart workspace bind (instead of
 * synchronously here, before the kill) was tried and reverted: applying the wallpaper via
 * [android.app.WallpaperManager.setBitmap] while the new process's Activity was still starting up
 * triggered a second, mid-transition `recreate()` (its wallpaper-color-changed callback feeds
 * into this app's own dynamic-theme recalculation), which raced the in-flight bind/restore
 * coroutines and left the profile switcher unresponsive. Restoring the wallpaper here, before the
 * restart, means it's fully applied and settled by the time the new process's Activity is even
 * created, so no such race can happen -- worth the roughly 500ms of extra black-scrim hold on
 * whichever profile has a saved wallpaper snapshot to decode.
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
     * untouched) if the underlying db restore fails -- the caller is expected to fade its own
     * black scrim back out in that case, since it fades it in before calling this.
     */
    suspend fun switchTo(target: WorkspaceProfileId): Boolean = withContext(Dispatchers.IO) {
        val current = activeProfile
        if (current == target) return@withContext true

        fun mark(step: String) = Log.d(
            PROFILE_SWITCH_LOG_TAG,
            "switchTo($current -> $target): $step at ${android.os.SystemClock.uptimeMillis()}",
        )
        mark("start")

        snapshotCurrent(current)
        mark("snapshotCurrent done")

        val liveDb = liveDbFile()
        liveDb.delete()
        File(liveDb.path + "-journal").delete()

        val incomingDb = snapshotDbFile(target)
        if (incomingDb.exists()) {
            incomingDb.copyTo(liveDb, overwrite = true)
        }
        mark("db swap done")

        val success = RestoreDbTask.performRestore(context, ModelDbController(context))
        mark("performRestore done")
        if (!success) return@withContext false

        restoreWallpaperForColdStart(target)
        mark("restoreWallpaper done")

        PreferenceManager2.getInstance(context).activeWorkspaceProfile.set(target)
        ZenModeSyncManager.getInstance(context).setActiveProfileRule(target)
        mark("prefs + zen sync done, restarting")

        restartLauncher(context)
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

    /**
     * Decodes and applies [id]'s saved wallpaper snapshot as the system wallpaper -- a no-op if
     * it never had one saved (see [snapshotCurrent]'s doc comment), beyond a fixed
     * [NO_WALLPAPER_SNAPSHOT_DELAY_MS] hold. Runs before the restart, not after -- see
     * [switchTo]'s doc comment for why a parallel, post-restart version of this was tried and
     * reverted.
     *
     * Decoding the saved snapshot at its full native resolution (these are cropped screenshots of
     * a bundled wallpaper, which can be considerably larger than the screen) measurably slowed
     * this down -- confirmed via logged timestamps: ~1s for a 433KB/3175x6000 snapshot on a
     * mid-range phone. Downsampling to roughly the screen's own resolution first (the standard
     * inSampleSize technique) cut that to ~500ms, since WallpaperManager.setBitmap crops/scales to
     * the screen internally anyway and doesn't need the full native resolution handed to it.
     *
     * The remaining ~500ms is real, unavoidable work for whichever profile actually has a saved
     * wallpaper -- but only that direction has it: switching to a profile with no saved snapshot
     * (the no-op path above) was instant, so the two directions felt inconsistent even though
     * both are equally hidden behind the black scrim (confirmed by the user testing it directly,
     * back to back, several times). [NO_WALLPAPER_SNAPSHOT_DELAY_MS] closes that gap the cheap
     * way: holding the fast path back by roughly the same amount, rather than trying to shave any
     * more off the slow path -- there's real appetite for a "the switcher just always takes about
     * this long" feel over a faster-but-inconsistent one.
     */
    private suspend fun restoreWallpaperForColdStart(id: WorkspaceProfileId): Unit = withContext(Dispatchers.IO) {
        val incomingWallpaper = snapshotWallpaperFile(id)
        if (!incomingWallpaper.exists()) {
            delay(NO_WALLPAPER_SNAPSHOT_DELAY_MS)
            return@withContext
        }
        val metrics = context.resources.displayMetrics
        val bitmap = decodeSampledBitmap(incomingWallpaper.path, metrics.widthPixels, metrics.heightPixels)
        if (bitmap != null) {
            WallpaperManager.getInstance(context).setBitmap(bitmap)
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        val halfHeight = bounds.outHeight / 2
        val halfWidth = bounds.outWidth / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }

        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { this.inSampleSize = inSampleSize })
    }

    companion object {
        private const val SNAPSHOT_DB_NAME = "launcher.db"
        private const val SNAPSHOT_WALLPAPER_NAME = "wallpaper.png"
        private const val PROFILE_SWITCH_LOG_TAG = "ProfileSwitchTransition"

        // Matches the roughly-500ms a real wallpaper-snapshot decode+apply takes on a mid-range
        // phone (see restoreWallpaperForColdStart's doc comment) -- so switching to a profile with
        // no saved wallpaper takes about as long as switching to one that has one.
        private const val NO_WALLPAPER_SNAPSHOT_DELAY_MS = 500L

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getWorkspaceProfileManager)

        fun getInstance(context: Context): WorkspaceProfileManager = INSTANCE.get(context)!!
    }
}
