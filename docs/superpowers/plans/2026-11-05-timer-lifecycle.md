# Timer Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Native execution is already selected; do not ask again.

**Goal:** Keep formal sessions through task removal, add explicit reliable stopwatch continuation, snapshot stopwatch behavior, and repair orientation layout/position memory.

**Architecture:** Reuse FloatingService, Countdown and admission gates. Add a small behavior preference and record continuation metadata; each continued segment stores only new active time. The overlay host owns direction changes and persists only completed user drags.

**Tech Stack:** Kotlin, existing Compose/SharedPreferences/AtomicFile, JUnit4, existing Python/ADB regression runner; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-05-timer-lifecycle-design.md`, approved by user 2026-11-05.

## Global Constraints

- No new permission, background restart, service, monitoring, dependency or release/version change.
- Preserve user AGENTS whitespace edits, asset/, and private video files; use existing checkout on a development branch.
- Old records remain unchanged. Stopwatch continuation excludes downtime and never replaces an active session.
- Current behavior applies next session; presets own snapshots, absent field means CONTINUOUS.
- C4/countdown behavior, quota day boundaries and appearance application remain otherwise unchanged.

## Review Focus

- A child record saved just before death prevents replaying the parent; failed startup must not consume it.
- Changing preset target or deleting it between interruption and continuation must not launch another App or cross-write positions.
- A pause/stop PendingIntent from another session cannot operate a new screen-driven session.
- Rotation during a drag must not commit the previous orientation's gesture into the new slot.
- Malformed new record fields must be a read error rather than silently degrading to legacy elapsed time.

### Task 1: Stopwatch behavior and task lifecycle

**Files:** create `StopwatchBehavior.kt`, `StopwatchBehaviorStore.kt`; modify `ScreenOnTimerScreen.kt`, `FloatingService.kt`, `TimerTaskPolicy.kt`, `TimerPreset.kt`, `TimerPresetStore.kt`, `PresetLauncher.kt`, `AppTimerLauncher.kt`, `SettingsActivity.kt`, `TimerPresetsCard.kt`, relevant controls/tests.

**Interfaces:** `StopwatchBehavior { CONTINUOUS, SCREEN_ON }`, `StopwatchBehavior.decode(Any?)`; store `.behavior` flow/`.set(behavior)`. `TimerPreset.stopwatchBehavior` default CONTINUOUS. Shared launch accepts nullable behavior, resolves once, only STOPWATCH can be screenDriven. `TimerTaskPolicy.endsSession` false for task removals.

- [x] Add RED cases for task removal, default/decode, old/new preset roundtrip and screen-bound App preservation. Run `./scripts/check.ps1 -Mode Unit -Offline`; retain failure log.
- [x] Implement preference-only single-choice settings, reuse component in preset draft/save-current; next-session snapshot in common launch/service. Keep screen pause capabilities through SessionControls.
- [x] Change onTaskRemoved to checkpoint without ending. Keep START_NOT_STICKY, explicit ends, C4 and permission cleanup.
- [x] Run Unit GREEN. Do not commit partial user files; checkpoint logical implementation after integration tasks.

### Task 2: Reliable stopwatch records and explicit continuation

**Files:** create `StopwatchRecovery.kt`, `StopwatchRecoveryControls.kt`; modify `TimerRecord.kt`, `TimerRecordStore.kt`, `FloatingService.kt`, `HistoryStatistics.kt`, `HistoryScreen.kt`, `HistoryActivity.kt`, `MainActivity.kt`; add `StopwatchRecoveryTest.kt`.

**Interfaces:** `StopwatchCheckpoint(behavior, activity, carriedMillis, continuationOf, rootId, presetId, positions)` on TimerRecord. `activeTime` exposes new/legacy screen intervals. `StopwatchRecovery.candidates(records)` checks status, reliable checkpoint and children. `FloatingService.resumeStopwatch(context, recordId)` uses shared admission and validates again in service.

- [x] RED tests: manual/TASK_REMOVED/countdown/legacy-span excluded; old SCREEN_ON eligible; parent consumed only by linked child; chain cumulative vs segment sum; malformed checkpoints; quota only new physical interval. Run Unit and inspect failure.
- [x] Serialize optional checkpoint with strict decode, keep existing records/CSV columns and append origin/baseline. Reuse interval tracker for all new stopwatches and actual pause transitions; countdown records unchanged.
- [x] Start resumed Countdown with carried elapsed but record only new activity. Preserve behavior/App identity, never open App; reject active/stale requests. Child file persists relation without rewriting parent; IO failure does not masquerade as saved consumption.
- [x] Add compact home recovery and record-row continue with permission/error/retry states, same service entry, no automatic start/replay. Old missing checkpointer explanation.
- [x] Unit GREEN, including actual interval statistics and chained continuation; later ADB uses real files/CSV.

### Task 3: Direction slots and rotation rendering

**Files:** create `OverlayPositions.kt`, `OverlayPositionStore.kt`; modify `OverlayWindow.kt`, `OverlayPosition.kt`, `OverlayTouchLayout.kt`, preset codec/store, `FloatingService.kt`; add `OverlayPositionsTest.kt` and extend position tests.

**Interfaces:** `OverlayOrientation { PORTRAIT, LANDSCAPE }`, `OverlayPositions` with independent anchors and optional legacy; `.migrate(direction)`/`.remember(direction,anchor)`/codec. OverlayWindow `.positionsSnapshot()` and direction-aware persistence callback; viewport changes reset transient pin/placement and cancel gestures.

- [x] RED tests: compact center at clamped edge; independent portrait/landscape, legacy claimed only once, no slot overwrite, repeated rotation no drift, deleted preset fallback has no external write.
- [x] Restore selected direction during measurement using one safe viewport. Compact title centered; normal expanded time anchoring preserved. Native measured size/position remain one update, no after-layout correction.
- [x] Persist ordinary shared slots and preset-specific slots only on completed drag, plus explicit one-time migration; drop gesture on environment change. Recovery carries position context, deleted preset uses session-only copy.
- [x] Unit GREEN; add ADB rotations/shapes/sizes/anchor clicks and same-condition screenshots/coordinates/video.

### Task 4: Integrated verification and delivery

**Files:** new `scripts/device_timer_lifecycle.py`; integrate `device_regression.py`/`test-device.ps1`; update impacted screen_on suite and relevant docs/catalog.

- [x] Add regression assertions for true recents swipe survival, SCREEN_ON vs CONTINUOUS startup/current/preset defaults, active conflict and same-ID events.
- [x] Force-stop→reopen→continue explicitly; verify no stopped time, new record identity/link, no parent reuse, normal end excluded, bound quota and CSV delta only. Test permission denial/read error and changed/deleted preset origins.
- [x] Run Full Debug and Release `check.ps1 -Offline`, scripts unittest; capture source fingerprint/APK SHA. Run dedicated AVD lifecycle/rotation and affected existing suites with backup/restore. Correct stale test expectations to new approved behavior, do not relabel historical results.
- [x] Fresh whole-change review, fix important findings with RED/GREEN then relevant checks. Maintain ledger rulings; preserve unexecuted phone boundaries.
- [x] Update AGENTS semantics without staging user's whitespace diff, feature docs, CHANGELOG unreleased and task report with before/after visuals, test catalog append-only results and phone steps.
- [x] Commit logical change, publish only task tree on safe public parent excluding private video ancestry; normal non-force push development branch only. No merge, tag or release.
