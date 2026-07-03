# ADR-0010: UI — Icon Color via Theme Attributes at the Usage Site

**Status:** Accepted
**Scope:** Project-Wide
**Date:** 2026-07-02
**Supersedes:** —
**Author:** Lukas + Claude Code

## Research

The history-UI overhaul analysis (spec: `docs/research/2026-07-02 - history-ui-overhaul.md`, UI-INVENTORY worker pass) established the defect class empirically:

1. Five of seven step-card action icons carry `android:tint="#000000"` **baked into the vector drawable** (`res/drawable/ic_baseline_play_arrow_24.xml`, `ic_baseline_edit_note_24.xml`, `ic_baseline_delete_24.xml`, `ic_baseline_redo_24.xml`, plus the list status badges `ic_baseline_sync/pending/error_outline/cancel_24`) — invisible in dark mode.
2. `ic_baseline_autorenew_24.xml` has **no** tint at all (white `fillColor` only) — invisible in *light* mode. Same class, opposite symptom.
3. The consuming `ImageButton`s set `android:src` but no view-level tint (`res/layout/item_pipeline_step.xml:59-133`), so the drawable literal wins; the Activities themselves are correctly DayNight-themed (`values/themes.xml:3` = `Theme.Material3.Light`, `values-night/themes.xml:3` = `Theme.Material3.Dark`) — the theme layer is NOT the problem.
4. The same drawables are consumed by 12 files outside `res/drawable/` (keyboard layout, prompts overview, overlay, notification coordinator) — so editing the drawables ripples across unrelated surfaces.
5. Where the codebase already does it right, it does it via theme attrs at the usage site: `activity_history_detail.xml:71,81` (`app:iconTint="?attr/colorOnPrimary"`), `item_reprocess_queue_entry.xml` (`?attr/colorControlNormal` / `?android:attr/textColorSecondary`), and programmatically `state/render/RecordButtonColorController.kt` (centralized theme-attr resolution for the record button).

## Context

Dictate is a DayNight Material 3 app: every screen must render correctly in light and dark mode. Vector assets imported from the Material icon set arrive with arbitrary baked color literals (`#000000`, `#29B6F6`, or none), and whether an icon is visible depends on which literal it happens to carry — the "black buttons" user report. Fixing individual icons by editing hex values reproduces the bug the next time an icon is added or a theme changes. A convention plus an enforcement mechanism is needed, not a batch of color patches.

## Decision

**Icon color always comes from a theme attribute resolved at the usage site (layout `style=`/`app:tint`, or programmatic theme-attr resolution). Baked drawable tints are never relied upon for on-screen color, and layouts never carry color literals.**

Concretely:

1. **Usage site owns the color.** Every `ImageButton`/`ImageView` that renders an icon declares its tint via a shared style or an explicit `?attr/...` tint (e.g. `?attr/colorOnSurfaceVariant`, `?attr/colorControlNormal`, `?attr/colorError`). View-level tint (`ImageView.setImageTintList` / `app:tint`) overrides a drawable's baked `android:tint` at draw time, so shared drawables keep working for all consumers regardless of their baked literal.
2. **Shared styles over per-view attributes.** Repeated icon roles get a named style (first instance: `Widget.Dictate.HistoryIconButton` — theme-attr tint, borderless ripple, ≥48dp touch target). New icon views join the style instead of re-declaring tints.
3. **Drawables are color-neutral by intent.** *New* vector assets are added without baked color literals (tint omitted; `fillColor` may stay `@android:color/white` as the tint base). *Existing* shared drawables keep their literals until all their consumers declare usage-site tints — stripping is done per-drawable after a consumer audit, never blind.
4. **Semantic text colors use theme attrs too.** Error text = `?attr/colorError`; warning text = the app-defined `?attr/dictateColorWarning` (light + night values); never `@android:color/holo_*` or hex literals.
5. **Enforcement is a source-scan invariant test**, not review discipline: a pure-JVM test scans the subsystem's layout files and fails on hex color attribute values, `@android:color/holo_*`, and tint-less icon views (first instance: `HistoryThemeInvariantTest`, precedent pattern `HistoryDetailJobRoutingInvariantTest` / `MotionSceneSchemaTest`).

### Scope of this Convention

- **Applies to:** all layout XML and view-binding code that renders tintable icons or semantic (error/warning/status) colors — history screens first (the implementing spec), every touched or newly built screen thereafter.
- **Exempt:** brand assets with intentionally fixed colors (launcher icon, logo imagery); notification small icons (the system applies its own tinting; alpha channel is what matters); existing untouched legacy screens (no mass-refactor — they adopt the convention when they are next edited); MotionScene/keyboard color logic already governed by `RecordButtonColorController` (same principle, programmatic form).

## Alternatives Considered

1. **Patch each broken icon's hex literal (per-view night-aware color pairs).** Fixes today's seven icons; the class of bug remains open — the next imported icon regresses silently, and review has no mechanical backstop. Rejected as the archetypal per-view patch this convention exists to prevent.

2. **Strip baked tints from the shared vector drawables and rely on drawable neutrality.** Cleanest end-state, but the same assets are consumed by 12 files across keyboard/prompts/overlay/notifications, some of which currently *depend* on the baked literal for their rendering. A blind strip changes unrelated surfaces. Rejected as the immediate mechanism; kept as the per-drawable end-state after consumer audits (Decision point 3).

3. **A custom `ThemedImageButton` widget that force-applies theme tint.** Centralizes the rule in code, but adds a custom view class where a style suffices, breaks tools-preview affordances, and does nothing for plain `ImageView`s and text colors. Rejected — a style plus invariant test achieves the same guarantee with standard components.

## Consequences

**Positive:**
- Dark/light correctness becomes structural: icons inherit M3 theme colors, so future theme changes (or new themes) propagate without touching layouts.
- The bug class is regression-locked by a JVM test — no device matrix needed to catch a black-on-black icon.
- New icon views are cheaper to add correctly (join the style) than incorrectly (declare a literal → test fails).

**Negative:**
- One more indirection: the rendered color of an icon is no longer visible in the drawable file — a reader must check the usage-site style/tint. Tools-preview shows the tinted result, mitigating this.
- The invariant test is text-based (XML scan), adding a small maintenance surface when layout conventions evolve (e.g. new legitimate literal-color use cases need explicit allowlisting in the test).

**Failure Modes:**
- **View-level tint silently tints the *whole* drawable.** Multi-color vector assets (rare here) lose their internal color structure when `app:tint` is applied — for those, the tint must be omitted and the asset itself must ship night-aware variants. Naive application of the style to a multi-color asset yields a monochrome blob without any warning.
- **`setImageResource` after inflation keeps the view tint, but `setImageDrawable` with a pre-tinted drawable instance can carry a stale tint** from a previous consumer (drawables are shared unless mutated). Code paths that programmatically swap icons must rely on the view's `imageTintList` (as `HistoryAdapter` does via `setImageResource`) or `mutate()` first.
- **The invariant test only covers scanned files.** A new history-adjacent layout added without registering it in the test's file list is unprotected — the test must enumerate by glob/directory, not by hardcoded file names, wherever feasible.

## References

- **Implementing spec:** [`2026-07-02 - history-ui-overhaul.md`](../research/2026-07-02%20-%20history-ui-overhaul.md) §3.1 (bidirectional link) — first subsystem rollout + `HistoryThemeInvariantTest`.
- Programmatic precedent: `app/src/main/java/net/devemperor/dictate/state/render/RecordButtonColorController.kt`.
- Invariant-test precedents: `app/src/test/java/net/devemperor/dictate/history/HistoryDetailJobRoutingInvariantTest.kt`, `app/src/test/java/net/devemperor/dictate/state/layout/MotionSceneSchemaTest.kt`.
- Related ADRs: ADR-0004 (LayoutCatalog + MotionLayout — owns keyboard rendering; this ADR governs static layout icon color everywhere else).

## Decision History

### 2026-07-02 — Initial proposal

**Trigger:** History-UI overhaul (user report: several buttons render black / wrong color); root-cause analysis found baked drawable tint literals + missing usage-site tints, not a theme defect.

**Before:** No convention — icon color depended on whatever literal the imported vector happened to carry; five history icons invisible in dark mode, one invisible in light mode; no enforcement.

**After:** Usage-site theme-attr tint convention (style-first), color-neutral new assets, semantic colors via theme attrs, source-scan invariant test as enforcement; history screens are the first enforced subsystem.

**Reasoning:** View-level tint overrides baked literals at draw time — the only fix that is simultaneously systemic (kills the class), safe (zero cross-consumer risk), and mechanically enforceable (JVM source scan).

### 2026-07-03 — Accepted (first enforcement landed)

**Trigger:** History UI overhaul chunks A–E implemented and adversarially reviewed (`[history-ui]` commits `defb3b6..135fae0`); `HistoryThemeInvariantTest` red-proven against the unfixed layouts, then green.

**Before:** Convention proposed; history layouts still carried holo-color literals and tint-less icon views.

**After:** Convention in force for the history subsystem: `Widget.Dictate.HistoryIconButton` style, `dictateColorWarning` theme attr (light+night), invariant test enumerating history layouts by directory predicate. The consumer-audit rule (Decision point 3) fired live during implementation: a tint-strip of `ic_baseline_pause_24` was reverted (commit `b83be9e`) because keyboard consumers use it as an untinted `android:foreground` — the exact failure mode the audit rule guards; view-level tint in history overrides the baked literal instead.

**Reasoning:** Implementation validated both halves of the decision — usage-site tint fixes the class without touching shared assets, and the blind-strip alternative was empirically shown to break non-history consumers.
