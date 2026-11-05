# Stopwatch Reminders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans task-by-task. User authorized direct native execution; do not ask for another design/plan confirmation.

**Goal:** Independent stopwatch periodic rules, persistent snapshots and consistent stable hour readouts.

**Architecture:** Pure reminder engine consumes existing cumulative runtime. FloatingService owns scheduling and resources; existing effects/permissions/preset/recovery paths are reused.

**Tech Stack:** Existing Kotlin/Compose, SharedPreferences/AtomicFile, JUnit, native ADB runner. No new dependency.

**Spec:** docs/superpowers/specs/2026-11-05-stopwatch-reminders-design.md

## Global Constraints

- Three defaults OFF; intervals60..10800 seconds, initial300/900/1800. All presets/old checkpoints missing fields OFF.
- Snapshot rules at start; global sound/vibration permission OFF and immediately restricts effects, never enabled by presets.
- Actual running time only, paused frozen; resume skips past deadlines; ties highest priority and all cycles advance.
- No new timer service, permission, monitoring, keepalive, release/version change; preserve user files.
- Existing countdown/C4 behavior unchanged; native inline execution, one final independent review.

## Review Focus

- Nested malformed rule snapshot must not silently fall back to legacy record/current globals.
- Enabling audio during an old cue cannot replay it; preview disposal must not change real session data.
- Preset draft and recovered source must not mutate global rules or unrelated preset/record fields.
- Late callbacks/multi-level ties/Long overflow must not create immediate rescheduling loops.
- Native notification/widget H display cannot be declared HH by testing only Kotlin format strings.

### Task 1: Pure rules, snapshots and hour model

**Files:** new StopwatchReminders.kt / StopwatchReminderSettingsStore.kt; TimerPreset/Store, StopwatchRecovery, Countdown/OvertimeReadout, TimerWidgetModel; new StopwatchRemindersTest, StopwatchReminderSnapshotTest, StopwatchTimeTest.

**Interfaces:** StopwatchLevel {PRIMARY,MIDDLE,HIGH}; StopwatchRule(enabled,intervalSeconds,effects); StopwatchReminderRules(primary,middle,high); StopwatchReminderPermissions(sound,vibration); StopwatchReminderEngine(rules,initialElapsed).update(elapsed,paused):StopwatchCue? and nextElapsed:Long?; strict StopwatchReminderCodec. Checkpoint and preset append stopwatchReminders rules with missing=>OFF, explicit bad=>read failure. formatElapsed hour logic; stopwatchClockFormat(elapsed).

- [x] Write RED tests for independent periods/ties/repeated ticks/late/paused/recovered/overflow, codec/legacy snapshots, HH boundaries0/3599/3600/16580/24h/99h/100h/1000h and widget formatting bucket; run Unit and inspect failure.
- [x] Implement pure types and codec/store, append preset and checkpoint fields without changing existing identity/position/activity. Add hour formatter; widget model exposes format only for stopwatch.
- [x] Run Unit GREEN and record evidence. One task-wide logical commit after integrated checks (repository convention).

### Task 2: Shared settings, launch and actual effect lifecycle

**Files:** new StopwatchReminderScreen.kt; SettingsActivity, TimerPresetsCard, AppTimerLauncher, PresetLauncher, FloatingService, OvertimeReminderFeedback, FocusOverlay, ReminderPresentation, new StopwatchTimeHeader.kt.

**Interfaces:** common rule editor(value,onChange), current settings page with global permissions and inert preview; common start accepts nullable stopwatchReminders and private serialized extra. Service captures source/preset/current rules once; one nullable engine per stopwatch and one due callback in existing clock domain. Shared feedback play/restrict by effects; cue conversion only renders, no accounting.

- [x] Extend snapshot tests as needed before implementation; wire save-current, independent draft/cancel and shared preset launcher. No rules for countdown.
- [x] Add settings entry, three rule selectors, wheels/effects, global permissions; preview and explicit audition lifecycle.
- [x] Wire start/recovery, deadline scheduling, tie dedupe, pause/end/cancel cleanup and immediate permission restriction; retain existing overtime behavior.
- [x] Render stopwatch digits with stable measured slot and optional reserved status line. Update home/recovery/notification/tiles/widgets from one format; widget refresh on format bucket only, notification uses existing sampled clock.
- [x] Run Full Debug/Release and related JVM checks; source/APK identities preserved.

### Task 3: Native verification, independent review and delivery

**Files:** new scripts/device_stopwatch_reminders.py; existing runner entries; docs/STOPWATCH_REMINDERS.md, task report/images, impacted AGENTS/docs/CHANGELOG/testing library.

- [x] Dedicated emulator backup/restore, real rules/preview permissions/preset cancel/save, independent/tie pulses, pause/screen-pause/recovery no backlog. Seed reliable carried checkpoints for near60/120/180s and long-hour tests, not production clock hooks.
- [x] Real overlay folded/expanded and rotations across59:59/1h and99h/100h, text bounds/native frames, widget+notification reading; relevant existing stability and lifecycle suites. Keep true environment and screenshots vs sound/vibration boundaries distinct.
- [x] Fresh whole-branch reviewer via executing-plans, fix meaningful findings RED/GREEN, no second review loop.
- [x] Append actual evidence/test cases and phone minimum list; document snapshot/legacy behavior and interval policy. Verify app input hashes/user files/signature/APK, then logical commit and safe public development branch push only.
