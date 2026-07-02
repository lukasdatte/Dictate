package net.devemperor.dictate.state.render

import android.content.res.Configuration

/**
 * Single rule for "does `Pref.Theme` force night mode?" — shared by the
 * keyboard background (`DictateInputMethodService.setupTheming`) and
 * the floating-overlay inflate (`OverlayBackend.inflateAndAttach`).
 *
 * Before this helper existed the decision was inlined at the keyboard
 * call site only, and the overlay resolved its theme purely from the
 * system `uiMode` — so `Theme=dark` on a light-uiMode system produced a
 * dark keyboard next to a light widget card (F-119). One shared rule
 * keeps the two surfaces in lock-step.
 *
 * Semantics (mirrors the historic keyboard branch):
 *
 * | `theme`    | Result                                  |
 * |------------|-----------------------------------------|
 * | `"dark"`   | always night                            |
 * | `"light"`  | never night                             |
 * | `"system"` | follow `config.uiMode` night bits       |
 * | other      | never night (defensive — same as light) |
 *
 * @param theme the `Pref.Theme` value (`"light"` / `"dark"` / `"system"`).
 * @param config the current [Configuration] whose
 *   [Configuration.uiMode] night bits decide the `"system"` case.
 *
 * @see net.devemperor.dictate.preferences.Pref.Theme
 * @see docs/research/2026-07-02 - overlay-widget-transparency.md §3.4
 */
fun effectiveNight(theme: String, config: Configuration): Boolean =
    theme == "dark" || (
        theme == "system" &&
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        )
