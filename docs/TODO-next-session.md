# Next session TODO

Last commit: `aeae870a2a` (pushed to origin/16-dev), 2026-09-06.

## What's done and committed

- Announcement banner removed from settings; Profiles tile moved above DownShift Clock Widget.
- Widget color picker restructured into a "Profile Colors" section (Personal/Work chip
  selector, per-profile ARGB color) with proper "Appearance"/"Tap actions" section headings.
- Per-profile home-screen icon LABEL color added to Profiles settings (separate, additive
  toggle on top of the existing global workspaceTextColor Auto/Light/Dark setting) --
  `LawnchairUtils.overrideWorkspaceIconLabelColorForProfile()`, wired into `BubbleTextView.java`.
- Silencer's static duration presets (15m/30m/1h) replaced with an M3 TimePicker reinterpreted
  as an hours:minutes DURATION from now (not a clock time), plus "until I turn it off".
- Fixed `config_default_hotseat_mode` so a fresh install shows the DownShift command center
  instead of the Google search bar.
- Personal/Work profile pill replaced with a native M3 `Switch` (person/work thumb icons, gray
  palette matching Search/Silencer buttons, custom `scaleToHeight()` layout modifier so the
  scaled-up switch doesn't crowd its neighbors -- plain `Modifier.scale()` doesn't affect layout
  size, which was the bug).
- Widget-not-showing-on-fresh-install bug: root-caused and partially fixed. Confirmed via
  logcat/dumpsys on a clean second emulator (`small_phone_test`):
  1. `bindAppWidgetIdIfAllowed()` doesn't auto-allow same-package providers (no `BIND_APPWIDGET`
     permission) -- fixed via the `ACTION_APPWIDGET_BIND` intent + `BlankActivity` fallback
     (same mechanism `SmartspaceWidgetReader` already uses). This intent flow shows a REAL
     system confirmation dialog on this Android build ("Create widget and allow access?"),
     contrary to the original doc-comment assumption that it's silent for same-package callers.
  2. `LocalContext.current` in the widget's ComposeView is a `ContextThemeWrapper`, not a bare
     `Activity` -- fixed with a recursive `findActivity()` unwrap.
  3. `DownshiftHeaderHostLayout`'s reattach handler only called `requestLayout()`, never
     re-establishing Compose content after `DisposeOnDetachedFromWindow` tore it down (which
     happens whenever our own bind flow launches `BlankActivity`) -- fixed, now calls
     `setContent()` again on every reattach.
  4. The final `AndroidView(...)` in `DownshiftHeaderWidgetUi.kt` had no width modifier at all
     -- fixed, added `.fillMaxWidth()`.

## Still open

- **Widget rendering**: even after all four fixes above, confirmed via `dumpsys appwidget` that
  the widget successfully binds (real `RemoteViews` pushed, correct final bounds `688x118`
  matching its parent slot) -- but nothing actually draws on screen. Zoomed into the exact
  screen region: no faint/white-on-white text either, just clean wallpaper. This is a NEW,
  narrower problem than the one we started the last session with (binding/lifecycle is now
  provably correct) -- something about how `HeadlessAppWidgetHostView`'s pushed RemoteViews
  actually get composited is the remaining piece. Next step: check whether
  `AppWidgetHostView.updateAppWidget()`/`onCreateView()` -- our `HeadlessAppWidgetHostView` in
  `HeadlessWidgetsManager.kt` -- is actually inflating the RemoteViews content, or whether a
  headless `AppWidgetHost` needs something else (e.g. `setAppWidget()` called explicitly, or
  the RemoteViews apply needs a real attached-to-window host) that a normal workspace-placed
  widget gets for free.
- Package rename (applicationId) to something like `com.downshiftlauncher.app`, display name
  "DownShift Launcher", icon label "DownShift" -- user explicitly wants the full applicationId
  change (new app, no data migration). Not started; deliberately deferred behind the widget bug.
- Renaming old/legacy projects to include "Legacy" -- not yet scoped (which directories, what
  convention). Deferred alongside the package rename.
- Eventually: extract the DownShift widget + settings screen into a standalone Play Store app
  (settings screen must reuse Lawnchair's real preference-UI components, not a re-skin).
- Smooth the profile-switch transition. Right now `WorkspaceProfileManager.switchTo()` does a
  full process restart (`restartLauncher()`) -- the same abrupt jump the underlying
  backup-restore mechanism it's built on already has, not a crossfade. Was called out as
  explicitly out-of-scope for v1 in the original profile-switching plan; a live, no-restart
  swap would need a new `ModelDbController` method that swaps its cached `mOpenHelper` in place
  (the way `attemptMigrateDb()` already does for grid changes), or short of that, some kind of
  transition animation/overlay to mask the restart visually.

## Notes for whoever resumes

- Always test on BOTH the physical phone (`R3GL4021XSY`) and an emulator, per standing
  practice -- and when testing "fresh install" behavior specifically, prefer booting a genuinely
  separate/never-used AVD (e.g. `small_phone_test`) over uninstall+reinstall on
  `minimal_launcher_test`, since that AVD accumulates state across sessions.
- Bash `&&` chains after a `grep` with zero matches silently abort (exit code 1) -- use `;` or
  explicit `echo "exit=$?"` when chaining diagnostic logcat/grep commands.
