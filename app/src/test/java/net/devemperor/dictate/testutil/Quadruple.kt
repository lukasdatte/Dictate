package net.devemperor.dictate.testutil

/**
 * Test-only N-tuple — Kotlin stdlib ships only Pair / Triple.
 *
 * Used by N-axis truth-table tests (KeyboardVisibilityPredicates today;
 * future LayoutCatalog slot resolvers per Spec 2 §14.2 — Block 4/5 expand
 * to the 25-case matrix). Promoted from a private declaration inside
 * `KeyboardVisibilityPredicatesTest` so the same destructuring shape can
 * be reused without per-file duplicates.
 *
 * Quality-Gate K-1 friendly: pure data, no Android / Mockito surface.
 */
internal data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
