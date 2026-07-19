# Chunk C1 — Self-Fix (fresh eyes, diff-based)

**Agent:** C1 SELF-FIX · **Timestamp:** 2026-07-20T00:40:00+02:00
**Wave commit:** c44a0d657f28de749d194cb93f75397087d42761
**Spec:** `research/entitaetenmodell-android.md` §4.1–4.8, §5.1–5.4 · **Plan:** §5 C1, §3 D5.a

## What I did

Reviewed the committed C1 diff (six `:shared/config` main files + four test files) against
the plan/spec with the three lenses (plan correctness, code quality, test quality). Applied one
inline fix; verified the delegated parity-test issue is architecturally correct and that the
wire-enum *values* themselves are right (so the future `:app` parity test will pass). Re-ran the
suite green.

## Fix applied

| # | File:line | What | Why |
|---|---|---|---|
| 1 | `CanonicalJsonTest.kt:101,102` | Replaced two raw `U+0001` control bytes (a `''` char literal and the same byte in the doc comment) with the Kotlin `` escape / literal text | The implementer's self-check claimed these were already replaced with an escape, but the file still carried the raw control bytes (`grep -P '[\x00-\x1f]'` found 2). A raw control char in source is invisible in diffs/editors and can be silently mangled by tooling. `name` still equals `"a"+U+0001+"b"`, so both assertions keep their exact meaning; suite still green. |

## Verification beyond the fix

- **Wire-enum parity values (C1 owns the definitions).** Hand-checked the four `:shared` wire
  enums against the domain originals in `:shared-ai`:
  `ProviderType{OPENAI,GROQ,ANTHROPIC,ELEVENLABS,OPENROUTER,CUSTOM}` == `AIProvider`;
  `ModelFunction{TRANSCRIPTION,COMPLETION}` == `AIFunction`;
  `AmbiguityModeValue{ALWAYS_INSERT,AUTO,ALWAYS_REVIEW}` == `AmbiguityMode.persistKey`;
  `PromptSelectionMode{NONE,PREDEFINED,CUSTOM}` ↔ `PromptMode` 0/1/2. All match — the deferred
  parity test cannot fail on a C1-side value bug.
- **Protocol-type reuse.** `DecodeResult` / `ProtocolViolationException` / `ValidationDetail`
  are reused verbatim from `shared/protocol`; `CatalogCodec` mirrors `ProtocolCodec`'s Malformed
  vs Invalid split correctly. The `entities[i]` path normalisation (empty/`.` root → plain
  `entities[i]`, property path → `entities[i].label`) is covered by three tests and passes.
- **Envelope stripping** is top-object-only (entity hash strips envelope; catalog-file export
  keeps per-entity envelope so a receiver can recompute) — matches §5.4; round-trip byte-stable.

## Issues (carried forward — not fixable in C1 scope)

| ID | Severity | Description | Status | Marker |
|---|---|---|---|---|
| C1-1 | Important | Enum-parity tests + `AIProvider↔ProviderType` etc. mappers (§4.8 / §13 D6 / Plan §3 D5.a) are NOT in C1. `:shared` sits below `:shared-ai` and there is deliberately no `:shared-ai`→`:shared` edge, so the tests structurally MUST live in `:app` (the only module seeing both wire enums and the domain originals). §6/§12/D5.a place them there; route to C2 after Block A settles the enum homes. Values were verified correct (above), so this is a placement/gate concern, not a latent bug. | delegated | blocks-following |

## Files modified

- `shared/src/test/kotlin/net/devemperor/dictate/shared/config/CanonicalJsonTest.kt`

## Files outside assigned scope (drift)

none.

## Final test result

`./gradlew :shared:test --rerun-tasks` → BUILD SUCCESSFUL. Config suites all green, 0 failures /
0 skips: CanonicalJson 7, ContentHash 8, ConfigValidations 16, CatalogCodec 12 (43 total);
`SharedPurityTest` green.
