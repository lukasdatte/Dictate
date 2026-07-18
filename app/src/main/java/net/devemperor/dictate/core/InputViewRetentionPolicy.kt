@file:JvmName("InputViewRetentionPolicy")

package net.devemperor.dictate.core

import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * Decides whether the inflated IME view tree may be **reused** for a new
 * [Configuration] instead of being torn down and re-inflated
 * (render-latency-wave2 §B — Problem B, selective view retention).
 *
 * # Why
 *
 * A configuration change (rotation, theme flip, font-scale change, …)
 * makes `InputMethodService` call `onCreateInputView` again. The full
 * rebuild — detach + ~40 `findViewById` + controller reconstruction +
 * re-attach + first-render + GL-draw of the new tree — costs ~210 ms
 * (measurements 2026-07-17). For a **pure geometry** change (rotation /
 * window resize) none of that is necessary: the MotionLayout /
 * ConstraintLayout geometry is entirely dp-based in the MotionScene and
 * re-lays-out to the new width in the normal measure/layout pass, and the
 * project ships **no** orientation-qualified resources (no `-land` /
 * `-sw*` resource folders). Returning the same, parent-less view tree
 * skips all of it.
 *
 * # Conservative by construction
 *
 * Reuse is allowed **only** when *every* changed configuration bit is a
 * pure-geometry bit ([REUSE_ALLOWED_MASK]). Any resource-affecting delta
 * — night mode, locale, density, font-scale, layout-direction, overlay
 * asset paths — forces a rebuild so the fresh resources are picked up.
 * **Unknown / future config bits also force a rebuild** (fail-safe): a bit
 * we did not explicitly whitelist is treated as resource-affecting.
 *
 * @param inflatedAt the configuration the retained tree was last inflated
 *   (or last reused) under.
 * @param now the configuration `onCreateInputView` is now running under.
 * @return `true` iff the retained tree may be returned unchanged.
 */
fun canReuseInputView(inflatedAt: Configuration, now: Configuration): Boolean {
    val diff = inflatedAt.diff(now)
    // No delta at all → trivially reusable.
    if (diff == 0) return true
    // Reuse iff every set delta bit is in the geometry allowlist. A single
    // bit outside it (including unknown/future bits) → rebuild.
    return (diff and REUSE_ALLOWED_MASK.inv()) == 0
}

/**
 * The set of `ActivityInfo.CONFIG_*` bits that are **pure geometry** and
 * therefore safe to reuse the view tree across. Everything else (night
 * mode, locale, density, font-scale, layout-direction, asset paths, and
 * any bit not listed here) rebuilds.
 *
 * - [ActivityInfo.CONFIG_ORIENTATION] — portrait ↔ landscape.
 * - [ActivityInfo.CONFIG_SCREEN_SIZE] — current w/h in dp (changes on
 *   rotation and multi-window resize).
 * - [ActivityInfo.CONFIG_SCREEN_LAYOUT] — size/long/round screen-layout
 *   bits that co-change with orientation.
 * - [ActivityInfo.CONFIG_KEYBOARD_HIDDEN] — hardware-keyboard hidden state.
 * - [ActivityInfo.CONFIG_NAVIGATION] — navigation availability.
 * - [CONFIG_WINDOW_CONFIGURATION] — window bounds / windowing-mode change.
 *   Set by `Configuration.diff` on **every** rotation and multi-window
 *   resize (the window bounds change). It is an `@hide` `ActivityInfo`
 *   constant, so it is referenced by its literal value. Whitelisting it is
 *   mandatory, not optional: emulator measurement (2026-07-18) showed a
 *   plain portrait↔landscape rotation produces `diff=0x20000480`
 *   (ORIENTATION | SCREEN_SIZE | WINDOW_CONFIGURATION) — without this bit
 *   allowed, the fast-path would never fire on a real rotation and the
 *   whole retention optimisation would be dead code. It is a pure-geometry
 *   change in spirit (the view re-lays-out to the new bounds); any
 *   resource-affecting delta still carries its own non-whitelisted bit
 *   (e.g. night mode → CONFIG_UI_MODE) and forces a rebuild regardless.
 */
private val REUSE_ALLOWED_MASK: Int =
    ActivityInfo.CONFIG_ORIENTATION or
        ActivityInfo.CONFIG_SCREEN_SIZE or
        ActivityInfo.CONFIG_SCREEN_LAYOUT or
        ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
        ActivityInfo.CONFIG_NAVIGATION or
        CONFIG_WINDOW_CONFIGURATION

/**
 * `ActivityInfo.CONFIG_WINDOW_CONFIGURATION` (0x20000000) — `@hide`, so
 * inlined as its documented literal. Signals a window bounds / windowing-mode
 * change (rotation, multi-window resize).
 */
private const val CONFIG_WINDOW_CONFIGURATION: Int = 0x20000000
