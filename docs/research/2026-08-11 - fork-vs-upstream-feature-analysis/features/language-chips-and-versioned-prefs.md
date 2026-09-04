---
date: 2026-08-11
author: Lukas + Claude (analysis session)
status: Research
context: Fork-vs-upstream analysis of the language-chip curation UI and the versioned-envelope preference layer beneath it — what we built, what Dictate Keyboard 5.3 has, and whether either half is still worth pursuing.
related-adrs: — (no ADR; the design record is the archived plan)
---

# Language Chips & Versioned Preference Storage — Fork vs. Upstream Analysis

One plan shipped two unrelated things. The **visible half** turned Dictate 3.2's 58-entry
language spinner into an always-present two-letter pill on the prompt bar with a curated
shortlist. The **invisible half** is a generic versioned-envelope storage layer — preference
values persisted as `{version, payload}` JSON behind a plugin registry and a migrator chain —
built because the curation feature needed to change a `SharedPreferences` key's *type* without
losing the user's data. The verdicts differ sharply and must be kept apart: upstream
**adopted an equivalent** of the chip UI down to the same default value, so as a differentiator
it is worth nothing; the envelope layer has no upstream counterpart, is itself a port from
another project, and its value was never contingent on this feature.

## 1. Feature Overview

Upstream 3.2 showed the input-language selector only in certain UI states and offered all 58
entries as one flat list. Two problems: the language you are about to dictate in is not visible
when you need to check it, and picking it means scrolling a list where 55 entries are noise.

Our answer makes the input language a compact, always-visible two-letter pill sitting as the
first item of the prompt bar. Tapping it opens a grouped `PopupMenu` — the user's curated
languages above a divider, the full remaining catalogue below — and the curated set is edited in
settings via a multi-select preference. `0d8eb736` is worth noting for the reason behind the
widget choice: an IME does not own a window token, so showing an `AlertDialog` from it throws
`BadTokenException` on several OEM skins (Samsung One UI in particular). `PopupMenu` anchors to
a view instead and is the only safe option. (`setGroupDividerEnabled` is API 28+ against our
minSdk 26, so on Android 8 the divider degrades to a disabled label item — Risk D in the plan.)

Underneath sits the versioned envelope, and the reason it exists is narrow and concrete: the
`input_languages` key had to change type from `Set<String>` to `String`. A plain type change on
a live key means a `ClassCastException` on the next read and a silent fallback to defaults —
i.e. the user's curated list vanishes. The envelope makes stored values carry their own schema
version so a payload shape can evolve through a registered migration chain instead of breaking.
The plan documents the residual hazard honestly: a downgrade to an older app version after
migration is still data loss (Risk A), and `RESET_TO_DEFAULT` self-healing destroys the
original bytes it could not parse.

The same plan bundled an unrelated resend-button fix (robust `InputConnection` capture with a
three-stage fallback; a `FAILED` status becomes a no-op instead of a silent auto-resume). It is
mentioned here only because it shares the plan and the verification checklist — it belongs to no
part of this comparison.

## 2. Our Implementation

**The envelope layer** — `preferences/versioned/`, 7 files, 502 LOC, no Android dependency
beyond `SharedPreferences`:

| File | LOC | Role |
|---|---|---|
| `VersionedMigrator.kt` | 101 | the migration chain and its `MigrationResult` |
| `VersionedPrefs.kt` | 90 | `load` / `save` façade |
| `VersionedSerializer.kt` | 79 | envelope ↔ JSON |
| `VersionedPluginRegistry.kt` | 76 | `ConcurrentHashMap` of registered plugins |
| `VersionedPlugin.kt` | 56 | the per-key contract (version, default, codec, sanitize) |
| `Versioned.kt` | 50 | the `{version, payload}` envelope |
| `JsonCodec.kt` | 50 | payload codecs (`StringListCodec`) |

Plus `preferences/InputLanguagesPlugin.kt` and `preferences/InputLanguagesLegacyMigration.kt`,
which bootstraps the legacy `Set<String>` payload into v1 before `VersionedPrefs` ever reads.
Seven JVM test files cover the layer.

Two design choices are worth carrying forward because they are the transferable part.
**Plugins do not self-register**: `VersionedPluginRegistry.register(InputLanguagesPlugin)` is an
explicit call from `DictateApplication.onCreate()`, replacing an earlier `init { register(this) }`
that made correctness depend on Kotlin object class-load timing. And each plugin declares an
**error strategy** — `InputLanguagesPlugin` uses `RESET_TO_DEFAULT` because a corrupt language
list should not brick the app, with the plugin's own KDoc noting that a future key holding
something critical (API keys are named) would want `THROW` instead.

**The chip and its resolvers.** `preferences/LanguageLabelResolver.kt` (204) is the single source
of truth for ISO-code ↔ label translation and the allowlist, reading the parallel resource
arrays (58 codes, 58 labels, plus short forms for the record button); it is initialised once in
`Application.onCreate()` and is pure lookups thereafter. `preferences/LanguageResolver.kt` (149)
owns the read/write path for the curated list and the active language.
`core/LanguageEffectiveObserver.kt` (74) and `state/modules/LanguageModule.kt` (151) carry it
into the state store. The chip itself is `VIEW_TYPE_LANGUAGE_CHIP` at position 0 of
`rewording/PromptsKeyboardAdapter.java` with `item_prompts_keyboard_language_chip.xml`; the
picker is `showLanguagePicker(View anchor)` in `DictateInputMethodService.java:3522`.

**The sanitize invariant** is the piece that makes the rest simple: the persisted list is always
label-sorted, deduplicated and free of unknown codes, with empty input collapsing to the default
(itself sorted through the same path, so there is no default-path loophole). Every consumer —
the cycle logic, the popup, the job request — can therefore trust positional access without
re-sorting.

> [!IMPORTANT]
> The active language is stored as **`Pref.InputLanguagePos`, an index into the label-sorted
> curated list**, not as a language code. The plan flags this as Risk H: if display labels ever
> change (a new localisation, a different translation), the sort order shifts and the index
> points at a different language. It was accepted for that plan with the documented follow-up
> "replace Pos with a direct `CurrentLanguageCode: Pref<String>`". That follow-up is still open —
> and, as §3 shows, it is exactly what upstream implemented.

**Plan.** [`../../../plans/archive/language-chip-curation.md`](../../../plans/archive/language-chip-curation.md)
with its `.state.md`, `.chunks.json` and `.verification.md`; 27 quality-gate findings (8
critical) and eight named risks A–H. **Size:** ~7,500 added lines across `0c8828f7`, `5d988cac`,
`d8328cd3` (29 files, +4,994 / −147), `0a7071f5`, `798ceb24`, archived at `e8d8fd00`; of the
first commit's +1,268 lines, 763 are tests.

**Coupling.** The envelope layer is fully self-contained and portable. The chip UI is fork-shaped
but shallowly so — it predates the state store and still lives in a Java RecyclerView adapter
plus an IME-service method, with `LanguageModule` layered on afterwards.

## 3. Upstream 5.3 Status

**Verdict: adopted / equivalent** for the chip; **absent, and not felt as a gap** for the
envelope.

Upstream implements the identical two-level model — a full catalogue, a user-curated subset, and
a separately-tracked active language:

- **Catalogue:** `dictate/DictateLanguages.kt:48-153`, 104 entries with `DETECT` first, grown by
  45 in 5.3 (`0ddb0b12`, `#252`). Ours has 58.
- **Curated subset:** `prefs.dictate.inputLanguages` (`app/AppPrefs.kt:672-677`), a
  comma-separated string parsed by `parseSelection` / `serializeSelection`
  (`DictateLanguages.kt:187-198`), one-time seeded from the device language via
  `DictateLanguages.matchDevice`. **Its default is `"detect,en"`** — the same pair as our
  `InputLanguagesPlugin.defaultValue = listOf("detect", "en")`. Convergent, not copied: there
  is no shared history between the codebases.
- **Active language:** a separate pref `activeInputLanguage` (`AppPrefs.kt:678-683`) holding a
  **language code**, snapped back into the subset when it falls out
  (`DictateLanguagesScreen.kt:87-91`, with a repair routine at `DictateController.kt:679-685`,
  commit `4ac770ac` "fixes phantom globe"). This is precisely our deferred Risk-H follow-up,
  shipped.
- **Curation UI:** checkbox multi-select over the full catalogue plus a radio dialog restricted
  to the enabled subset for picking the active one (`DictateLanguagesScreen.kt:106-172`).
- **On the keyboard:** one `LanguageChip` (`ui/DictateSmartbarUi.kt:412-465`) — globe icon for
  detect, uppercase short code otherwise. **Tap cycles** through the subset
  (`DictateController.cycleLanguage()`), **long-press opens a dropdown**. The same globe appears
  in the legacy layout (`ui/LegacyDictateLayout.kt:496-506`).

**What upstream does better:** 104 languages to our 58; tap-to-cycle is a materially faster hot
path for the two-or-three-language user than open-menu-and-pick; and the code-based active
language avoids our index-drift hazard entirely.

**What ours does better:** the popup reaches the **full catalogue from the keyboard** — curated
above a divider, everything else below — so a one-off language never requires a trip into
settings. Verified in the upstream source: the dropdown iterates `selection` (the curated subset
only) and long-press does not even fire unless `selection.size > 1`
(`DictateSmartbarUi.kt:431-465`). On 5.3, dictating one sentence in a language you have not
curated means leaving the keyboard. Ours is also label-sorted under a sanitize contract;
upstream's curation list has fixed catalogue order, no search and no user ordering.

**The envelope layer has no upstream counterpart.** JetPref stores plain typed values; there is
no per-key schema version, no migrator, no envelope. But upstream has not needed one — and it is
worth noting *why*, because it is the same trade in the opposite direction: upstream declared
its prompts SQL schema **frozen** for legacy compatibility (`PromptsDatabaseHelper.kt:23-30`)
rather than building a migration mechanism. Absence of an envelope is not a gap they feel; it is
a constraint they accepted.

## 4. Assessment — is this feature still sensible?

The two halves need separate verdicts, so here they are separately.

### 4a. The chip UI — matched, not a differentiator

A user on 5.3 would not miss ours. They would trade the full-catalogue popup for tap-to-cycle
plus 46 more languages plus a repair routine we still owe ourselves. For the fork's actual
usage — a heavy German dictation user who occasionally works in English — the relevant comparison
is between "one tap cycles DE→EN" and "tap, read a menu, pick", and the cycle wins on a hot
path used many times a day. The full-catalogue access is genuinely better, but it serves the
*cold* path (a language you use rarely enough not to have curated), which by construction is
rare.

The uncomfortable framing: this cluster cost ~7,500 lines including a quality-gate round with 27
findings, and upstream arrived at the same design independently with a simpler mechanism and a
better active-language model. That is not evidence the work was wrong — it is evidence the
design was the obvious one, which is a compliment to both and an argument against ever carrying
it forward as a distinguishing feature.

Cost of keeping it in the fork is near zero: the resolvers are stable, pure and well-tested. The
one live debt is Risk H (`InputLanguagePos` as an index into a sort order that can move), which
is worth closing on its own merits regardless of the upstream comparison, and upstream has
already demonstrated the shape of the fix.

### 4b. The versioned envelope — generic infrastructure with one consumer

The honest number first: **exactly one plugin is registered.** A repo-wide grep for
`VersionedPlugin<` returns `InputLanguagesPlugin` and nothing else, and that plugin declares
`override val migrations: Map<Int, MigrationFn> = emptyMap()` at `currentVersion = 1`. So 502
LOC of infrastructure plus 7 test files currently serve one preference key that has never been
migrated.

Two readings, and both are true:

- **Over-built for this app.** Judged strictly as infrastructure for Dictate, a plain
  hand-written `Set<String>` → `String` bootstrap would have been perhaps 40 lines and would
  have solved the actual problem the plan faced.
- **It earned its keep anyway, and cheaply.** It made a live type change on a user-facing key
  survivable, which the plan documents as a real data-loss risk with a named mitigation. It is
  itself a port of a proven pattern from `excel_ekl` (documented in the `knowledge-reference`
  skill), so it was not designed from scratch here. And its ongoing cost is genuinely zero:
  nothing depends on it growing, nothing breaks if it never gains a second plugin, and the
  registration is a single explicit line that a reader can grep.

The real risk is neither maintenance nor correctness — it is **infrastructure nobody remembers
exists**. The next person who needs a schema-evolving preference (the plugin's own KDoc nominates
API keys, with `THROW` as the right strategy there) will only find this layer if something points
them at it. That argues for a pointer in the docs, not for removal.

**Upstream signal: none in either direction.** Language curation was implemented upstream
independently and needs nothing from us. Nobody has asked for versioned preferences upstream, and
an envelope layer would be a foreign body in a codebase whose house store is JetPref — proposing
it would be exactly the kind of large architectural contribution the upstream analysis identifies
as unlikely to land.

## 5. Options going forward

**(a) Keep both in fork as-is.** The default and the cheap answer. The chip works, the envelope
costs nothing to carry. Leaves Risk H open.

**(a′) Keep, plus close Risk H.** Replace `Pref.InputLanguagePos` with a
`CurrentLanguageCode: Pref<String>`, which is the follow-up the plan already documents and the
model upstream shipped. Small, self-contained, removes a real (if slow-moving) correctness
hazard, and does not depend on any decision about migrating to 5.x.

**(b) Port to Dictate Keyboard 5.x as a private patch.** Not worth it for the chip — 5.x's model
is equivalent and its catalogue is larger. The envelope has no host to attach to there.

**(c) Propose upstream via issue.** One small, well-shaped candidate: *"the keyboard language
picker only reaches enabled languages"* — a request that long-press either append the remaining
catalogue below a divider or offer a "more…" entry, so a one-off language does not require a
settings trip. That is issue-sized, matches the issue-anchored contribution pattern, and needs no
architectural argument. The envelope layer is not proposable and should not be proposed.

**(d) Retire.** For the chip, only as a consequence of migrating to 5.x, where it is replaced by
an equivalent. For the envelope, only if the fork itself retires — there is nothing to gain from
deleting a self-contained, tested, zero-cost layer.

**(e) Promote the envelope as a cross-project pattern.** Not an alternative to the others: make
sure the `knowledge-reference` skill's versioned-envelope entry points at this implementation as
a second worked example alongside `excel_ekl`, so the layer is findable by the next person who
needs it rather than rediscovered.

*Leaning:* the chip half is a non-differentiator that should not influence the fork-vs-5.x
decision either way, though Risk H is worth closing regardless; the envelope half is a
keep-and-forget whose only real need is a signpost.

## 6. Information Gaps

1. **How many languages does Lukas actually dictate in?** This decides the whole chip
   comparison: at two languages, upstream's tap-to-cycle is strictly better and our full-catalogue
   popup is dead weight; at six or more, ours wins on the cold path. *Owner: Lukas.*
   *Fallback:* read `input_languages` and the distribution of `language` across recent
   `sessions` rows.
2. **Has anyone ever hit Risk H in practice?** The index-drift hazard requires display labels to
   re-sort, which needs a localisation change we have not shipped. If the answer is "never, and
   no localisation is planned", (a′) drops from worthwhile to optional. *Owner: Claude.*
   *Fallback:* assume it stays latent; the fix is small enough that the answer barely changes the
   recommendation.
3. **Will any future preference actually need the envelope?** The plugin KDoc nominates API keys
   with a `THROW` strategy. If nothing is on the horizon, the layer stays a one-consumer
   investment indefinitely. *Owner: Lukas (roadmap call).* *Fallback:* treat as "no" and rely on
   option (e) so it is findable if that changes.
4. **Does upstream's 104-entry catalogue cover everything our 58 does?** Almost certainly yes
   given the +45 expansion in `#252`, but it is unverified, and a migration that silently drops a
   language the user relies on would be a bad surprise. *Owner: Claude.* *Fallback:* diff
   `dictate_input_languages_values` against `DictateLanguages.all` before any migration.
5. **Is the `PopupMenu` divider degradation on API 26–27 still relevant?** Risk D accepted a
   missing visual divider below API 28. If no target device runs Android 8 any more, the fallback
   label item is dead code. *Owner: Lukas.* *Fallback:* leave it; it costs nothing.

## 7. References

**Source reports**
- [`../sources/fork-feature-inventory.md`](../sources/fork-feature-inventory.md) — cluster 6
  ("Language chip curation & versioned-envelope preference storage")
- [`../sources/upstream-rewrite-analysis.md`](../sources/upstream-rewrite-analysis.md) — area 9
  ("Language chip curation — adopted / equivalent"), area 8 (the frozen prompts schema)

**Our plans**
- [`../../../plans/archive/language-chip-curation.md`](../../../plans/archive/language-chip-curation.md)
  — 27 quality-gate findings, risks A–H (A: downgrade data loss; B: the
  `MultiSelectListPreference.persistStringSet()` trap solved via `setPersistent(false)`;
  D: `setGroupDividerEnabled` is API 28+; H: `InputLanguagePos` index drift)
- [`../../../plans/archive/language-chip-curation.verification.md`](../../../plans/archive/language-chip-curation.verification.md)
  — the manual end-to-end checklist for all three bundled features

No ADR covers this cluster; the plan is its design record.

**Our commits**
- `0c8828f7` (13 files, +1,268, of which 763 tests) — the envelope layer
- `5d988cac`, `d8328cd3` (29 files, +4,994 / −147), `0a7071f5`, `798ceb24` — chip + curation UI
- `0d8eb736` — `AlertDialog` → `PopupMenu` (IMEs have no window token)
- `e8d8fd00` — plan archived

**Our code**
- `app/src/main/java/net/devemperor/dictate/preferences/versioned/` (7 files, 502 LOC)
- `app/src/main/java/net/devemperor/dictate/preferences/InputLanguagesPlugin.kt`,
  `InputLanguagesLegacyMigration.kt`, `LanguageLabelResolver.kt`, `LanguageResolver.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:3522`
  (`showLanguagePicker`)

**Upstream evidence** (paths relative to the `upstream/main` worktree, `3e5ebe46` / `v5.3.0`)
- `dictate/DictateLanguages.kt:48-153` (104-entry catalogue), `:187-198`
  (`parseSelection` / `serializeSelection`)
- `app/AppPrefs.kt:672-677` (`inputLanguages`, default `"detect,en"`), `:678-683`
  (`activeInputLanguage` as a code)
- `dictate/ui/DictateSmartbarUi.kt:412-465` — the chip; tap cycles, long-press lists **only** the
  curated subset
- `app/settings/dictate/DictateLanguagesScreen.kt:87-91, 106-172` — curation UI and snap-back
- `DictateController.kt:665-672` (`cycleLanguage`), `:679-685` (active-language repair)
- `data/prompts/PromptsDatabaseHelper.kt:23-30` — the frozen-schema counterexample
- Commits: `0ddb0b12` (`#252`, +45 languages), `4ac770ac` ("fixes phantom globe")
