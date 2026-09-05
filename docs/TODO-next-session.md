# TODO — next session

Overwritten (not appended) every time work pauses. Check this first, every session, before doing anything else.

## Current status (2026-09-05)

Forked Lawnchair (AOSP Launcher3-based, Apache 2.0) as the new foundation, replacing the old custom-Compose `downshift-launcher` app (paused, not deleted — see `pre-lawnchair-fork` tag on that repo). Full background in memory: `project_downshift_lawnchair.md`.

**Stage 1 — DONE.** Cloned, builds clean from the command line, installed on the phone, no crashes.
```bash
cd /Volumes/External2TB/ClaudeCode/lawnchair && \
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew assembleLawnWithQuickstepGithubDebug
```
APK lands at `build/outputs/apk/lawnWithQuickstepGithub/debug/`.

**Search bar removed — DONE.** Just a settings toggle (Settings → Search bar → Dock tab → "Search bar widget" → Disabled), no code change.

**Profile switcher (Personal/Work) — PLAN APPROVED, IMPLEMENTATION NOT STARTED.** This is the next thing to build. Full design in `/Users/patricktomberlin/.claude/plans/composed-wondering-teacup.md` — re-read it fresh, plan files aren't durable across context resets. Short version: new `app.lawnchair.profile` package (`WorkspaceProfileId` enum, `WorkspaceProfileManager` DI singleton), one db-file+wallpaper snapshot per profile under `filesDir/workspaceProfiles/`, switch triggered from a new Settings screen for v1, reusing Lawnchair's own existing backup/restore mechanism (file swap + `RestoreDbTask.performRestore()` + full process restart via `restartLauncher()`) rather than inventing a new one. Exact file list and verified API signatures are in the plan file and in `project_downshift_lawnchair.md` memory — no re-investigation needed, go straight to writing code.

**Not started yet:** Silencer (DND) port, right-hand dock (likely rebuilt Lawnchair-native, not ported line-for-line), visual restyling of pop-up menus, Zen Mode Sync (designed separately, in memory, deliberately not part of the profile-switcher plan).

## Environment reminders
- `origin` remote → your fork `downshift-lawnchair`; `upstream` → real Lawnchair (for pulling their updates).
- Android Studio Preview is only needed for its SDK Manager (platform 37.1/build-tools 37.0.0) — already installed once, done. Drive actual builds/installs via `gradlew`+`adb` from the terminal.
- Phone: `R3GL4021XSY`.
