# Overlay-Widget Transparency + Theme Unification

---
date: 2026-07-02
author: Lukas + Claude (multi-agent review session)
type: Spec
status: Accepted
context: Configurable background transparency for the floating overlay widget, plus the two theming defects that must ship with it (Pref.Theme ignored, no config-change reaction).
related-plan: n/a (seeded by 2026-07-02 - feature-wiring-code-review.md, F-118/F-119/F-120/F-121)
related-adrs: —
---

The user wants the floating widget to be transparent so the content behind it stays visible. This spec defines a configurable opacity preference end-to-end (pref → state → render), and bundles two adjacent theming defects that interact with the same code path: the overlay ignores `Pref.Theme` (F-119, confirmed), and an attached overlay never reacts to configuration changes (F-120). A dead-code cleanup in the same file rides along (F-121).

## 1. Vision and Motivation

### 1.1 Why this exists

The floating overlay card (record/pause/trash/close over other apps) draws a **fully opaque** `?attr/colorSurface` background (`overlay_background.xml:14`). Because the widget is deliberately sticky and floats over the host app, an opaque card hides whatever is behind it — the user explicitly asked for transparency.

> [!IMPORTANT]
> **Framing correction from the review:** Dictate has **no home-screen AppWidget**. The "widget" is a `TYPE_APPLICATION_OVERLAY` window hosting an ordinary View tree. None of the RemoteViews restrictions apply, and the window is *already* alpha-capable: `OverlayLayoutParamsFactory` sets `PixelFormat.TRANSLUCENT` (`:101`) precisely to honour the rounded-corner alpha mask. The only opaque element is the drawable's solid fill — transparency is a drawable mutation, not a window/manifest change.

### 1.2 What problem this solves

1. **F-118 (feature request):** no opacity preference exists — no pref key, no settings UI, no render-path mutation.
2. **F-119 (confirmed inconsistency):** keyboard background honours `Pref.Theme` (`DictateInputMethodService.java:3303-3308`); the overlay resolves its theme purely from system `uiMode` via `ContextThemeWrapper(ctx, R.style.Theme_Dictate)` (`OverlayBackend.kt:426`). Theme=dark on a light system ⇒ dark keyboard, light widget. Transparency composes onto `colorSurface`, so this must be fixed first or "transparent dark" users get a translucent *light* card.
3. **F-120:** the overlay inflates once and never re-inflates on configuration change — an auto night-schedule flip leaves stale colors on a multi-hour-attached widget.

### 1.3 Discarded Alternatives

- **`WindowManager.LayoutParams.alpha`** — fades the entire window including button icons/labels; text becomes unreadable before the background becomes useful. Rejected.
- **Fixed semi-transparent color resource** — no user control; the right opacity depends on wallpaper/host-app contrast. Rejected in favour of a SeekBar pref with a 20 % floor.
- **RemoteViews-style `setInt` workarounds / API-31 corner-radius attrs** — inapplicable; there is no AppWidget.

## 2. Acceptance Criteria

1. `Pref.WidgetOpacity` exists in `DictatePrefs.kt` (Int, percent, default 100) and is set via a `SeekBarPreference` (min 20, max 100, increment 5, value shown) in the theme category of `fragment_preferences.xml`.
2. With opacity < 100, the card **fill** is translucent while the 1 dp `colorOutlineVariant` stroke and all button icons/labels remain fully opaque.
3. With `Pref.Theme = dark` on a light-uiMode system, keyboard *and* overlay card both render dark (and inverse). One shared helper decides "effective night mode" for both surfaces.
4. A system dark/light flip while the widget is attached re-renders the card with fresh colors without user interaction.
5. `OverlayLayoutParamsFactory` contains no `SDK_INT >= O` branch (minSdk 26).
6. JVM test: `OverlayBackend` with a fake `OverlayWindow` asserts the background drawable's fill alpha for a given `ThemingState.widgetOpacity`.
7. Unit tests cover the effective-night-mode helper (theme=light/dark/system × uiMode day/night).

## 3. Architecture Specification

Design follows the established pref-mirror pattern (all `ThemingState` fields are Pref-mirrored per the ThemingModule KDoc), so the value reaches `OverlayBackend.render(state, mode)` for free.

### 3.1 Preference layer

```kotlin
// DictatePrefs.kt
/** Overlay-widget card opacity in percent (20..100). 100 = opaque. */
object WidgetOpacity : Pref<Int>("net.devemperor.dictate.widget_opacity", 100)
```

```xml
<!-- fragment_preferences.xml, theme PreferenceCategory (precedent SeekBarPreference at :90) -->
<SeekBarPreference
    android:key="net.devemperor.dictate.widget_opacity"
    android:title="@string/dictate_widget_opacity"
    android:min="20" android:max="100"
    app:seekBarIncrement="5" app:showSeekBarValue="true"
    android:defaultValue="100" />
```

The 20 % floor keeps the card discoverable over matching content.

### 3.2 State layer

- `ThemingState` gains `widgetOpacity: Int = 100`.
- New reducer arm `Action.ThemingAction.SetWidgetOpacity` (note: the review found existing `ThemingAction` setters are dead — F-037; wire this one for real via `PipelinePrefMirror`, and take the opportunity to delete the dead siblings or wire them, per F-037's own entry).

### 3.3 Render layer — opacity application

In `OverlayBackend`, after `inflateAndAttach()` and idempotently per render tick (alongside the rendererBundle forwards, `OverlayBackend.kt:335-345`):

```kotlin
val surface = MaterialColors.getColor(overlayView, com.google.android.material.R.attr.colorSurface)
(overlayView.background.mutate() as GradientDrawable)
    .setColor(ColorUtils.setAlphaComponent(surface, state.theming.widgetOpacity * 255 / 100))
```

Mutate **only the fill**; the stroke stays opaque so the card boundary remains legible. Buttons keep their own opaque Material backgrounds. The mutation must re-run after every `inflateAndAttach` (teardown/attach recreates the drawable) — hence "idempotent per render tick", not one-shot.

### 3.4 Theme unification (F-119) — prerequisite

Extract the inline decision at `DictateInputMethodService.java:3305` into a shared helper (Kotlin, e.g. `state/render/EffectiveNightMode.kt`):

```kotlin
/** Single rule for "does Pref.Theme force night?" — used by keyboard background AND overlay inflate. */
fun effectiveNight(theme: String, config: Configuration): Boolean =
    theme == "dark" || (theme == "system" &&
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
```

In `OverlayBackend.inflateAndAttach`: when `effectiveNight(state.theming.theme, ctx.resources.configuration)` differs from the context's own night flag, wrap with `createConfigurationContext` (uiMode override) *before* the `ContextThemeWrapper`. Re-inflate when `ThemingState.theme` changes while attached.

### 3.5 Configuration-change reaction (F-120)

Override `onConfigurationChanged` in `DictatePipelineService` (Services receive it without manifest flags). When the `UI_MODE_NIGHT_MASK` bits differ from the last-seen value and the overlay is attached: call a new `OverlayBackend.reinflate()` (= `teardownOverlay()`, next render tick re-attaches). Position survives via the `OverlayPosition` prefs (`DictatePrefs.kt:123-126`). Recompute layout params in the same hook — the fixed window width is computed from `displayMetrics.density` at `create()` time (`OverlayLayoutParamsFactory.kt:93`) and goes stale on density/font-scale changes.

### 3.6 Dead-code cleanup (F-121)

Delete the unreachable `TYPE_PHONE` branch in `OverlayLayoutParamsFactory.kt:71-76` (+ `@Suppress("DEPRECATION")` + KDoc fallback paragraph); minSdk 26 ⇒ `TYPE_APPLICATION_OVERLAY` unconditionally.

### 3.7 Edge notes

- **Elevation shadow:** View shadows scale with outline alpha — the 8 dp shadow fades proportionally at low opacity. Acceptable; optionally clamp elevation to 0 below ~50 %.
- **Night mode composes for free** once F-119 is fixed: alpha applies to whichever `colorSurface` the (now pref-correct) theme resolves.

## 4. Migration Plan

1. **F-121 cleanup** — delete dead branch. Compiles standalone.
2. **Effective-night-mode helper** + keyboard call-site switch (`DictateInputMethodService.java:3305`) + unit tests. Behaviour-neutral refactor.
3. **F-119** — overlay inflate honours the helper (`createConfigurationContext` wrap), re-inflate on theme change. Device-check: Theme=dark on light system.
4. **F-120** — `onConfigurationChanged` → `reinflate()` + layout-params recompute.
5. **F-118** — pref + settings UI + `ThemingState.widgetOpacity` + reducer/mirror wiring + `OverlayBackend` fill mutation + JVM render test.

Each step compiles and tests independently; steps 3–5 each have a visible device check.

## 5. Testing Approach

- **Unit:** `EffectiveNightMode` truth table (6 cases). Reducer/mirror test for `SetWidgetOpacity`.
- **JVM render test:** existing fake-`OverlayWindow` seam in `OverlayBackend` tests — assert drawable fill alpha for opacity 20/55/100; assert stroke untouched.
- **Device (manual):** opacity slider live-check over a busy background; theme-mismatch matrix (Pref light/dark/system × system day/night); auto night-flip with attached widget.

## 6. Information Gaps

1. **String resources / wording** for the settings entry — owner: implementer (trivial); fallback: `dictate_widget_opacity` placeholder above.
2. **Whether opacity should also apply to the keyboard-hidden strip mode** (`KEYBOARD_HIDDEN_STRIP`) — owner: user decision at implementation time; fallback: apply to the card background uniformly (same drawable).

## 7. Change History

### 2026-07-03 — Opacity made backdrop-independent (opaque pre-blend, §3.3 superseded)

- **Trigger:** User report — the effective opacity differs between the
  widget's display modes with the same `Pref.WidgetOpacity` value ("Die
  Opacity im Widget-Modus bzw. im anderen Modus ist nicht identisch.
  Sie soll bitte immer gleich sein.").
- **Root cause:** Not a divergent code path — `OverlayBackend.
  applyBackgroundOpacity` writes the identical translucent ARGB in
  every mode. But a translucent fill composites against whatever is
  BEHIND the overlay window, and that backdrop differs between the two
  ViewModes sharing the card: WIDGET floats over the *opaque* keyboard
  background, HOVER over *arbitrary host-app content*. Same alpha byte,
  different on-screen pixels — an inherent property of alpha
  compositing, foreshadowed by §1.3's rejection of
  `WindowManager.LayoutParams.alpha`.
- **What changed:** §3.3's alpha-channel mutation is replaced by an
  **opaque pre-blend**: the fill painted into the card is
  `blend(keyboardBackground, colorSurface, opacity%)` — computed by the
  new pure policy `OverlayCardFill` (single source of truth; the
  keyboard anchor is `dictate_keyboard_background_{light,dark}`
  selected by the shared `effectiveNight` rule). The result has no
  alpha channel, so the rendered card is byte-identical in WIDGET and
  HOVER. Visually it reproduces, in every mode, exactly what the
  translucent card used to look like over the keyboard.
- **Trade-off (deliberate):** true see-through of host-app content in
  HOVER is sacrificed — cross-mode consistency was the user's explicit
  priority, and a real alpha channel cannot deliver it (the backdrop
  genuinely differs). §1.1's "content behind stays visible" is thereby
  narrowed to "the card dims toward the keyboard backdrop".
- **Test:** new `OverlayBackendTest.card fill is backdrop-independent —
  opaque pre-blend at every widgetOpacity in both modes` (red-proven on
  the alpha-channel code: fill alpha was 51 at 20 %, expected 255),
  plus `OverlayCardFillTest` policy tests (plain surface at 100 %,
  midpoint blend, forced-opaque output, clamping) and a night-anchor
  test. Existing fill-alpha assertions updated to the pre-blend
  contract.

### 2026-07-03 — Buttons kept opaque over the translucent card (§3.3 follow-up)

- **Trigger:** User report — in transparent-widget mode the *buttons*
  also lost opacity ("im transparenten Widget-Modus sollen auch die
  Buttons selbst opak sein"), contradicting §3.3's "buttons keep their
  own opaque Material backgrounds".
- **Root cause:** §3.3 was correct that `applyBackgroundOpacity` mutates
  *only* the card fill — the buttons were never touched by the opacity
  code. But the three icon buttons (Trash/Pause/Close) inherited
  `Widget.Material3.Button.IconButton` (`styles_overlay.xml`), a
  *standard* icon button whose container `backgroundTint` is
  `@android:color/transparent` (alpha 0). Over an opaque card that reads
  fine; once the card fill went translucent, those transparent-container
  buttons let the host content behind the card bleed straight through the
  whole button box. The icons stayed opaque; the button *background* was
  never opaque to begin with. So §3.3's assumption ("buttons keep their
  own opaque Material backgrounds") only held for the filled record
  button, not the icon buttons — a *visual-blending* cause, not an
  alpha-strip in the opacity code.
- **What changed:** `OverlayButton.Icon` now inherits
  `Widget.Material3.Button.IconButton.Filled.Tonal` — an opaque
  `colorSecondaryContainer` container. Every overlay button now carries
  an alpha-255 container tint (record button = filled `colorPrimary`,
  icon buttons = filled-tonal `colorSecondaryContainer`), so the
  translucent card shows through only in the gaps *between* the buttons.
  Single-style fix, no per-button code. `OverlayBackend.applyBackgroundOpacity`
  KDoc updated to state the buttons stay opaque by style, not by the
  opacity code.
- **Test:** new `OverlayBackendTest.overlay button backgrounds stay fully
  opaque at low widgetOpacity` — renders at `widgetOpacity = 20`, asserts
  the card fill is translucent (alpha 51) AND every button's
  `backgroundTintList.defaultColor` alpha is 255. Red-proven against the
  old transparent-container style (first icon button had alpha 0), green
  after the filled-tonal switch.

### 2026-07-02 — Implemented (status → Accepted)

- **Trigger:** Implementation of the full spec in migration order §4 (branch `worktree-agent-ad9f1f03e41e0e4d2`, commits `[widget-transparency]`).
- **What changed:** All five steps landed — F-121 dead-branch delete; shared `state/render/EffectiveNightMode.kt` helper + keyboard call-site switch (`DictateInputMethodService`); F-119 `createConfigurationContext` night-override before the overlay's `ContextThemeWrapper` + re-inflate on effective-night-mode change; F-120 `DictatePipelineService.onConfigurationChanged` → `OverlayBackend.reinflate()` on night-bits/density delta; F-118 `Pref.WidgetOpacity` + SeekBar settings entry + `ThemingState.widgetOpacity` + mirror/reducer wiring + fill-only drawable mutation. Full JVM test coverage per §5 (fill alpha 20/55/100, opaque stroke via constant-state reflection, per-tick idempotency, re-apply after reinflate, night-mode truth table, mirror/reducer/effect tests).
- **Deviations:**
  - **§3.5 `reinflate()` semantics:** re-renders *immediately* from the backend's cached state/mode snapshot instead of waiting for "the next render tick" — a uiMode flip does not emit state, so waiting would leave the window gone indefinitely. Density changes route through the same `reinflate()` (which re-runs `OverlayLayoutParamsFactory.create()`), covering the layout-params recompute without a second code path.
  - **§3.2 / F-037 rider:** the four dead `ThemingAction` setters were **deleted** (trivially possible; only reducer tests referenced them). The new `SetWidgetOpacity` arm additionally emits a `PersistWidgetOpacity` effect (per F-037's own guidance) so a future dispatcher cannot hit the unpersisted-write revert trap; the SP mirror remains the sole production update path.
  - **§6 gap 1 (strings):** repo-conventional names `dictate_settings_widget_opacity_title`/`_summary` instead of the `dictate_widget_opacity` placeholder; translations added for de/es/pt alongside the English default.
  - **§3.7 elevation clamp below ~50 %:** not implemented (spec marked it optional); the proportional shadow fade is accepted as-is.
  - **§6 gap 2:** opacity applies uniformly to the card background (same drawable in all widget modes), per the documented fallback.

### 2026-07-02 — Initial spec

- **Trigger:** User feature request (widget transparency) during the whole-app review; seed agent `seed:widget-transparency` delivered the design basis.
- **What changed:** Document created from findings F-118 (design), F-119 (confirmed, high-confidence verification), F-120, F-121.

## 8. References

- Parent catalog: [`2026-07-02 - feature-wiring-code-review.md`](<2026-07-02 - feature-wiring-code-review.md>) — F-118, F-119, F-120, F-121 (full evidence).
- Code: `res/drawable/overlay_background.xml:14`, `res/layout/overlay_5button_layout.xml:49`, `state/render/overlay/OverlayLayoutParamsFactory.kt:71,93,101`, `state/render/overlay/OverlayBackend.kt:318,335,426`, `core/DictatePipelineService.kt:792`, `core/DictateInputMethodService.java:3303-3308`, `preferences/DictatePrefs.kt`, `res/xml/fragment_preferences.xml:90,134`.
- Related finding: F-037 (dead `ThemingAction` setters) — touchpoint in §3.2.
