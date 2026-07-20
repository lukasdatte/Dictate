# Inline-Anchor Worker Report — `app-windows` group

**Date:** 2026-07-20T17:25:00+02:00
**SLUG:** app-windows
**Plan:** `docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md`
**Range:** `c46cfe8..HEAD`
**Agent:** docs-inline (finalize)

## Files in scope

1. `app/src/main/java/net/devemperor/dictate/windows/DispatchOutcomeMapper.kt`
2. `app/src/main/java/net/devemperor/dictate/windows/WindowsAutoSend.kt`
3. `app/src/main/java/net/devemperor/dictate/preferences/WindowsTarget.kt`

## Outcome — no anchor edits required (verification pass)

All three files carry rich, plan-current inline anchors that were written or
rewritten **during this plan's implementation** (the diff shows the header
rewrites landed in-range). The discovery inventory
(`reports/docs-discovery.md`) is stale on two points that this pass confirms
are already resolved:

- It says *"`WindowsAutoSend` lacks a module header"* — it now **has** a full
  class header (added in-range).
- It says *"app/windows `@see` coverage 0/10"* for the touched files — both
  touched files carry a strong module header with ADR references.

No anchors added, updated, or removed. Nothing to remove as comment-noise (no
code-restating comments present; the gotchas all carry non-derivable "why").

## Per-file verification

### DispatchOutcomeMapper.kt
- **Module header** — present, references **ADR-0019**; accurate description of
  the centralised classification (Gate-2 finding G2-9). Resolves. ✓
- **Gotcha comments** — the `EndpointMissing` and the **newly-added**
  `EntityGone` (Block E / peer-catalog) exhaustiveness comments are accurate:
  both errors genuinely cannot arise on a dictation dispatch, and the comments
  explain *why* they are still mapped (to keep the `when` exhaustive). ✓
- The `EntityGone` gotcha is the only anchor change this file received in-range
  and it is correct. ✓

### WindowsAutoSend.kt
- **Module header** — present, references **ADR-0019**; the single in-range edit
  was the body switch `WindowsTarget.from(sp) != null` → `WindowsTarget.isPaired(sp)`,
  and the header prose is consistent with that. ✓
- **`shouldDivertToPc` / `pcOnly` KDoc** — references
  `state.features.pcOnly` / `FeatureToggles.pcOnly` (code, resolves) and the
  guard commit `27b91b3` (exists: `[wd-13] SEAM 1 …`). ✓

### WindowsTarget.kt
- **Module header** — rewritten in-range for the SecretStore migration
  (`from` → `isPaired`/`resolve`). References **ADR-0017/0019** (resolve),
  **spec secretstore.md §7.2** (exists — "Namespace-Zuschnitt"),
  `PairingSecrets.DEVICE_SECRET_REF` (code, resolves). ✓
- **Method KDocs** — `isPaired`/`resolve` headers accurately describe the
  non-secret predicate vs. SecretStore read, `SecretStoreException` → `null`. ✓
- **Rename hygiene** — no lingering `[from]` KDoc references in the file; no
  `WindowsTarget.from(` call sites remain anywhere in `app/` or `shared/`. ✓

## Notes (name-smells / unresolved references — flagged, not edited)

Both items below are **pre-existing** (present at `c46cfe8`, before this plan's
range) and outside this plan's anchor footprint, so per worker scope they are
noted for a human with the windows-dispatch plan context to decide, not silently
edited.

1. **`WindowsTarget.kt:16` — "(purity rule V10)" is a dangling reference.**
   The string `V10` resolves to no purity/forbidden-pattern rule anywhere in
   `docs/` or the codebase (only occurrence is this KDoc line itself). It was
   present before this plan and likely refers to a numbered purity/Verbot rule
   from the earlier windows-dispatch plan spec that is not reachable from the
   current tree. Either point it at the concrete rule home
   (`docs/architecture/windows-dispatch/README.md` or ADR-0015/0028 purity
   clauses) or drop the bare `V10` token.

2. **`DispatchOutcomeMapper.kt:33` — bare "§6.1" reference is ambiguous.**
   The `EndpointMissing` gotcha points at "the keyboard-action path …
   `PcInputFailure.COMPANION_UPDATE_REQUIRED` classification, §6.1" with no doc
   name. `COMPANION_UPDATE_REQUIRED` is a code enum (resolves), but the bare
   `§6.1` does not qualify which document it lives in. Pre-existing; consider
   qualifying it (e.g. `windows-dispatch/README.md §6.1` or the spec path) on a
   future touch.

## Files outside assigned scope (drift)

none.
