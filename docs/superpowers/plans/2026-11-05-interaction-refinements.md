# Interaction Refinements Implementation Plan

> **For agentic workers:** Use focused parallel implementation per dispatching-parallel-agents, root integrates and verifies. User approved recommended behavior and direct implementation; no further UI approval.

**Goal:** Automatic faithful preview/color effects, home mode memory, dedicated recovery selection, format-sized headers and reliable overlay lock.
**Architecture:** Existing session/service/window/preset stores remain authoritative. Separate home preference from shortcut defaults; lock is session-only. Native UI and renderer reused.
**Tech Stack:** Current Kotlin/Compose, SharedPreferences, JUnit, existing ADB runner. No dependency/permission/service addition.
**Spec:** docs/superpowers/specs/2026-11-05-interaction-refinements-design.md

## Constraints and ownership

- Approved: lock requests pass-through, transparent enough per system threshold only while locked, saved opacity unchanged; home remembers explicit mode, widget/tile defaults independent.
- Current base09bea95; public95dc179. Preserve user guide whitespace and151 asset/video files. Do not raw-push private video ancestry; publish same app tree on safe public parent95dc179. No release/version bump/merge.
- Agent home: MainActivity/QuickDurations/preferences UI/StopwatchRecoveryControls. Root adds lock home control after agent completes.
- Agent effects: StopwatchReminders/codec and settings screen/StopwatchTimeHeader untouched/FocusOverlay color rendering/shared feedback as needed; communicate cue API to root/service owner. No service edits.
- Agent lock: OverlayWindow/FloatingService/SessionState and new pure lock policy. Root handles FocusOverlay badge/lock affordance and home hookup after other agents complete. No MainActivity/FocusOverlay edits.
- Root: time-format slot model/header and tests; plans/docs/catalog/scripts/native integration, review and commit. No concurrent Gradle/ADB checks while agents edit.

## Tasks

- [x] Home: tests mode priority/legacy/default isolation; persist explicit home mode only; clarify default labels; dedicated candidate list/detail with source and approximate feedback, shared ID-only recovery.
- [x] Effects: strict optional color config in rules/snapshots; bounded real/preview same renderer; automatic latest-selection preview, explicit authorized sound/vibration feedback; preserve default old behavior and opacity/readability.
- [x] Lock: pure session/opacity policy tests, public non-touchable window flag, cancel gestures, one alpha layer, bound stale command checks; home/notification unlock, C4 restrictions unchanged; clear on new session/recovery.
- [x] Width: MM:SS five positions, HH:MM:SS eight then growing hour digits, mono stable within format; preserve geometry anchor, adapt native assertions to allow intended format growth.
- [x] Root integration: Full Debug/Release, script checks and related actual native suites, verify preview does not create sessions and cross-App lock touch/restore with C4 boundary controls.
- [x] Read-only independent final review, evidence/catalog/guide/report with screenshots and phone gaps; no merge/release. Git synchronization is recorded by actual Git history and delivery, rather than duplicated as a checklist verdict.

## Review focus

1. Rapid option changes cannot queue audio or re-randomize color on recomposition.
2. Old rules missing color fields remain compatible; malformed explicit new values fail closed.
3. Restoring page/session/preset cannot overwrite explicit last-home mode or widget defaults.
4. Lock/unlock stale intents and in-flight touches cannot mutate a newer session or submit C4 keys.
5. Windows alpha and rendered alpha applied once; lock transparency is temporary, ordinary/C4 settings unchanged.
