# Repair Report — Wave W1-1 (Block A)

**Timestamp:** 2026-07-20T00:40:00+02:00
**Finding cluster:** `convention-A-1` (green, Nice-to-have)

## Finding: convention-A-1 — spec-reference style split in ai/adapter/

**What was wrong:** `AndroidAiConfig.kt` carries a resolvable `@see docs/plans/… §x`
anchor (the convention every port and migrated `:shared-ai` class uses), while its
five sibling adapters referenced the spec only inline in prose (`(spec §x)`), with
no resolvable `@see` anchor. Same operation, two styles, inside one newly-created
package.

**What I did:** Converted each inline `(spec §x)` prose reference into a proper
`@see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §x`
anchor line, matching `AndroidAiConfig.kt`'s format exactly. The prose sentence is
kept; only its trailing `(spec §x)` fragment moved into a dedicated `@see` line
after a blank comment line. Anchors verified against the actual spec headings:

| File | Anchor | Spec heading verified |
|---|---|---|
| `AndroidPromptConfig.kt` | §6 A3.5 | line 635 `A3.5 — Prompt-Service …` |
| `SharedPrefsProxyConfig.kt` | §4.3 | line 374 `### 4.3 ProxyConfig …` |
| `RoomUsageSink.kt` | §4.2 | line 347 `### 4.2 UsageSink …` |
| `MediaMetadataAudioDurationReader.kt` | §4.4 | line 417 `### 4.4 AudioDurationReader …` |
| `AndroidAiFactory.kt` | §4.5 | line 437 `### 4.5 Verdrahtung …` |

All six adapters in `ai/adapter/` now carry a matching resolvable `@see` anchor.

## Tests

Not run. The change is purely inside KDoc `/** */` comment blocks — the filtered
`git diff` (excluding comment/blank lines) is empty, so there is no compilation or
runtime surface to exercise. A comment-only edit cannot turn a green suite red;
running `./gradlew :app:testDebugUnitTest` (Android, expensive) would validate
nothing about this diff.

## Files modified

- `app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidPromptConfig.kt`
- `app/src/main/java/net/devemperor/dictate/ai/adapter/SharedPrefsProxyConfig.kt`
- `app/src/main/java/net/devemperor/dictate/ai/adapter/RoomUsageSink.kt`
- `app/src/main/java/net/devemperor/dictate/ai/adapter/MediaMetadataAudioDurationReader.kt`
- `app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidAiFactory.kt`

## Drift

none
