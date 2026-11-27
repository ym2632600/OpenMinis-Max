# Agent coordination — OpenMinis-Max

Two coding agents work on this repo: **Instinct** (the owner's assistant, working since 2026-11-26) and **Muse** (added 2026-11-27). This file is the single source of truth for who builds what. Read it fully before your first commit, and re-check it before starting any new item.

ملخص بالعربي ليوسف: الملف ده بيقسم الشغل بين Instinct و Muse عشان محدش فيهم يمسح شغل التاني، وفيه قواعد التعديل والجدول بملكية كل نقطة.

## Rules of engagement (both agents)

1. One backlog item per commit. Reference the item id (e.g. "B9: add SSH offload handler") in the commit message.
2. NEVER force-push. Work directly on main with small commits.
3. Before editing any file, work from the latest main. The GitHub web editor replaces whole files, so a stale edit page WILL clobber other people's concurrent commits (this already happened once here and had to be repaired). Open the edit page fresh for every commit; never reuse an edit page left open from earlier.
4. Shared hot files — keep your hunk small and additive, never reformat or reorder the rest:
   `src/android/app/src/main/AndroidManifest.xml`, `MinisApp.kt`, `AppNavigation.kt`, `res/values/strings.xml`, `res/values-ar/strings.xml`, `ROADMAP.md`, `ChatViewModel.kt` (1.17 MB — do NOT full-file replace; make minimal targeted edits only), `OffloadPermissionManager.kt`.
5. Every user-facing string goes in BOTH `values/strings.xml` and `values-ar/strings.xml`. Arabic is first-class here (the owner is an Arabic speaker). Escape apostrophes as `\'`.
6. `ROADMAP.md`: update ONLY the row of the item you completed. Never touch another agent's rows.
7. Done = the "Android Debug APK" GitHub Actions run on your commit is green and produces the APK artifact. CI is the only build environment. A red run on your commit is yours to fix: open the failing job log, fix, recommit.
8. Prefer creating NEW files over editing existing ones, and follow existing patterns:
   - Sandbox/offload CLIs mirror `sandbox/offload/ContactsOffloadHandler.kt` (OffloadGate + runtime permission flow + CLI for the sandbox shell). SMS example: `SmsOffloadHandler.kt`.
   - Settings screens use `SettingsScaffold` + the public `SettingsSection(header = ...)` from `SettingsComponents.kt`. Example: `ui/settings/DataFlowScreen.kt`. Routes are consts in the `Routes` object in `AppNavigation.kt`.
   - Sensitive agent actions register in `OffloadPermissionManager` with an approval default (e.g. `sms_send` = ASK_ONCE).
9. App-widget layouts inflate with the launcher theme — never reference appcompat/theme attrs in them (AAPT fails).
10. GPL-3. No paid services. Privacy-first: no analytics, no tracking, no unexplained network calls.

## Current assignment

| Item | Owner | Status |
|---|---|---|
| A10 fast search (FTS) | Instinct | done |
| A4 battery-saver mode | Instinct | done |
| A7 human-readable errors | Instinct | done |
| A11 token/cost cap | Instinct | done |
| A13 backup & restore | Instinct | done |
| A15 skills-store warning | Instinct | done |
| B2 notification summarize/reply UX | Instinct | done |
| B13 central consent layer | Instinct | queued |
| B14 activity log with undo | Instinct | queued |
| B5 floating bubble (remainder of B5) | Muse | done |
| B8 camera capture + call initiation (remainder) | Muse | queued |
| B9 built-in SSH client | Muse | queued |
| B10 Termux integration | Muse | queued |
| B11 sub-agents with roles | Muse | queued |
| B15 Ollama one-field setup + connection test | Muse | done |
| A1 Android bridge parity | Instinct | done (device tool, Health Connect, Bluetooth, intent broadcasts) |
| A14 true offline mode | Instinct | done (offline-mode skill, local Ollama in sandbox) |
| B12 optional cloud sync | Instinct | done (passphrase-encrypted rclone sync) |
| C1 guard mode (anti-scam watcher) | Instinct | done |
| C13 focus mode | Instinct | done |
| C2 security research agent | Instinct | done |
| C10 mini finance agent | Instinct | done |
| C16 glass-effect theme | Instinct | done |
| C3–C9, C11, C12, C14, C15 | Instinct | done (all remaining C items) |

## Claim protocol

To take an UNCLAIMED item, commit an edit to THIS TABLE ONLY, setting the owner cell to your name, before writing any code. First commit wins; if two agents claim the same item, the earlier commit timestamp wins and the later claim reverts its own row. Work one claimed item at a time.

## For Muse — repo map

- Backlog spec with acceptance hints: `BACKLOG.md`. Live status: `ROADMAP.md` (audit markers show what already exists — several plan items are already built; do not rebuild them).
- Build: `GitHub Actions → Android Debug APK` (`.github/workflows/android-debug-apk.yml`): NDK r28c, Go/gomobile builds `rclone.aar` into `src/android/app/libs/`, proot build, then `assembleDebug`. The heap in `src/android/gradle.properties` was raised to fix an OOM — do not lower it.
- Package `com.openminis.app`, compileSdk 36, minSdk 26, JDK 17, Kotlin Compose UI.
- Docs for the build flow: `BUILDING.md`.
- To reach the owner (decisions, questions): open an issue titled `coordination: <topic>` — he reads issues on his phone.
