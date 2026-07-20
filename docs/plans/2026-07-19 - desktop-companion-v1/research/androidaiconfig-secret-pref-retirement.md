# Research — AndroidAiConfig secret-pref retirement (un-ignoring `NoLegacyKeyReadTest`)

**Date:** 2026-07-20T00:40:00+02:00
**Triggered by:** Finding C-TEST-2 / C3-1 — `NoLegacyKeyReadTest.secretPrefs_areReferencedOnlyInDefinitionAndMigration` stays `@Ignore`d after C3; `AndroidAiConfig.kt` still names the secret pref constants, so the spec §2.6 end-state invariant is enforced by no running test.
**Agent-ID:** repair-research (`androidaiconfig-secret-pref-retirement`)

## Summary (the essence)

The finding is **correct but incomplete**. Retiring `AndroidAiConfig` is the right call and is clean (it is already off the live read path, and only its non-secret `completionParameters` is still used in main). **But moving `AndroidAiConfig` alone will not turn the test green:** the `WindowsDeviceSecret` slot has **three further live main-source references** (`WindowsTarget.kt`, `WindowsPairingActivity.java`, `PipelinePrefMirror.kt`) that C2/C3 never re-pointed. Worse, those references are a **latent Critical runtime bug**, not just a test-scan blocker: the B2 migration (wired live in `DictateApplication`) deletes `Pref.WindowsDeviceSecret` on every start, while `WindowsTarget.from(sp)` still reads it — silently un-pairing existing users — and `WindowsPairingActivity` still *writes the secret back into plaintext prefs*, defeating the Block-B "no plaintext secret at rest" goal for the pairing secret.

## Sources

1. `app/src/test/java/net/devemperor/dictate/secrets/NoLegacyKeyReadTest.kt` — the guard: scans `src/main/java` for `Pref.<secretName>`, allow-list = `{DictatePrefs.kt, SecretsMigration.kt}`, `@Ignore`d assertion.
2. `app/src/main/java/net/devemperor/dictate/ai/adapter/AndroidAiConfig.kt` — the pref-based `AiConfig`; secret prefs appear **only** in `apiKey()` (lines 40–61).
3. `app/src/main/java/net/devemperor/dictate/config/ConfigEntityMigration.kt:220` — the sole functional main-source consumer of `AndroidAiConfig` (calls only `.completionParameters`).
4. `app/src/main/java/net/devemperor/dictate/secrets/SecretsMigration.kt` — deletes all 11 slots incl. `WindowsDeviceSecret` (line 175); wired via `PrefsMigration.migrateSecrets` ← `DictateApplication.java:34`.
5. `app/src/main/java/net/devemperor/dictate/preferences/WindowsTarget.kt:39`, `settings/WindowsPairingActivity.java:218,286`, `state/PipelinePrefMirror.kt:328` — the un-re-pointed `WindowsDeviceSecret` consumers.
6. `research/secretstore.md` §2.6 / §3.1 / §7.1–7.2 — the invariant, the 11-slot inventory, and the "C2 re-points reads (incl. `WindowsTarget`), C3 re-points writes" plan.
7. `reports/B/B2-impl.md` (Scope decision §) + `reports/B/B2-selffix.md:55` — B2 explicitly deferred **both** `AndroidAiConfig` and `WindowsTarget` re-pointing to C2/C3.
8. `desktop-companion-v1.md:490–497` (Chunk B2 acceptance: "kein Codepfad liest alte Pref-Keys — grep-Test").

## Findings

### F1 — How the test decides (mechanics)

`NoLegacyKeyReadTest` walks `src/main/java` only, flags any file **outside** `{DictatePrefs.kt, SecretsMigration.kt}` whose lines `contain("Pref.$name")` for the 11 secret-pref names. The companion test `theScanner_readsSourcesAndCanMatch` (already running, not ignored) pins that the scanner reaches sources and that `SecretsMigration.kt` matches — so the moment `@Ignore` comes off, the assertion is meaningful. **To go green, every main-source `Pref.<secretName>` reference outside the two allow-listed files must be gone.**

### F2 — Complete offender inventory (grep of `src/main/java`)

| File | Secret prefs referenced | Live? | Status |
|---|---|---|---|
| `ai/adapter/AndroidAiConfig.kt` | 10 API keys (`apiKey()`, lines 40–61) | **Off** live read path (factory flipped to `ProfileResolver` in C3) | The finding's subject |
| `preferences/WindowsTarget.kt:39` | `WindowsDeviceSecret` (`sp.get(...)`) | **On** live path — `WindowsTarget.from` is called from ~13 sites (pipeline, IME, mirror, UI) | **Missed** by C2/C3 |
| `settings/WindowsPairingActivity.java:218,286` | `WindowsDeviceSecret` (write / clear) | **On** — pairing UI | **Missed** by C2/C3 |
| `state/PipelinePrefMirror.kt:328` | `Pref.WindowsDeviceSecret.key` (change-watch) | **On** — reactive `windowsPaired` recompute | **Missed** by C2/C3 |
| `secrets/SecretsMigration.kt` | all 11 | n/a | Allow-listed (OK) |
| `preferences/DictatePrefs.kt` | definitions | n/a | Allow-listed (OK) |

So the un-ignore work is **two distinct sub-scopes**, not one.

### F3 — AndroidAiConfig is already inert in main (retirement is low-risk)

The only functional main-source use of `AndroidAiConfig` is `ConfigEntityMigration.kt:220` → `AndroidAiConfig(sp).completionParameters(provider, model)`, which reads **non-secret** parameter prefs (`Temperature*`/`MaxTokens*`/`ReasoningEffort*`). Every other method (`apiKey`, `provider`, `modelName`, `baseUrl`, `elevenLabsKeyterms`) is now called **only from tests** — the parity/characterization tests (`AiConfigParityTest`, `ParameterResolutionParityTest`, `ProfileResolverCharacterizationTest`) and the orchestrator/pipeline tests that use `AndroidAiConfig(sp)` as a pref-driven `AiConfig` fixture. `ProfileResolverCharacterizationTest:78` even captures `snapshot(AndroidAiConfig(sp))` **"BEFORE B2 removes the key prefs"** — i.e. `AndroidAiConfig` is, functionally, the **frozen pref-based baseline** the new `ProfileResolver` is proven against. Its home is therefore test sources, not main.

### F4 — "Retire" beats "widen the allow-list" (the C3-1 decision)

Two options were named in C3-1. **Retire wins decisively:**
- Widening the allow-list to include `AndroidAiConfig.kt` would enshrine a *runtime `AiConfig` implementation that reads plaintext key prefs* as spec-blessed — the exact thing §2.6 exists to forbid. It also leaves a class in main that a future wiring change could accidentally reactivate as the live path.
- `AndroidAiConfig.apiKey` is already dead in main (F3). Keeping a dead plaintext-pref reader in production code and then loosening the guard so it "passes" is the anti-sustainable choice.
- The report's own preferred phrasing is "retire … (move baseline into test sources)". Confirmed: **relocate, don't widen.**

### F5 — The `WindowsDeviceSecret` gap is a latent Critical bug (escalate separately)

`SecretsMigration.run` (live via `DictateApplication.java:34`) deletes `Pref.WindowsDeviceSecret` for every slot (line 175) once, sets `SecretsMigratedV1`, and parks the value in the store under `SecretRef("pairing", "windows_device_secret")`. But **no reader/writer was re-pointed**:
- `WindowsTarget.from(sp)` reads the now-empty pref → `deviceSecret.isEmpty()` → returns `null` → a previously-paired user is treated as **unpaired**; PC-dictation send is disabled (regression in the very feature this plan ships).
- `WindowsPairingActivity` still **writes the secret into the plaintext pref** on (re-)pairing. Because the one-shot migration flag is already set, that plaintext value now **persists at rest** — directly defeating Block B's F11 goal for the pairing secret.
- `PipelinePrefMirror` watches `Pref.WindowsDeviceSecret.key` to recompute `windowsPaired`; once the secret is not a pref, that reactive signal is stale/meaningless.

This is spec §7.2 ("Device-Secret: `WindowsTarget.kt` liest künftig darüber [den SecretStore]") that C2/C3 left undone. It is **out of scope for "retire AndroidAiConfig"** — it is a genuine feature re-point plus a reactive-mirror redesign — and should be raised as its own **Critical** finding. `@Ignore` must **not** be removed until this lands, or the test is red.

## Implementation Hints

### Part 1 — Retire `AndroidAiConfig` (this topic; clears the 10 API-key offenders)

1. **Extract the non-secret completion-parameter reader into main.** Create `app/src/main/java/net/devemperor/dictate/ai/adapter/PrefCompletionParameters.kt` (SRP: "read completion params from legacy prefs"). Move `AndroidAiConfig.PARAMETER_PREFS` + the body of `completionParameters(provider, model)` into it as a small object/function taking `(sp, provider, model)`. This logic touches only non-secret prefs, so it may live in main freely.
2. **Re-point `ConfigEntityMigration.kt:220`** to `PrefCompletionParameters.of(sp, provider, model)` and drop the `import …AndroidAiConfig` (line 8). This removes the last main-source *functional* dependency on `AndroidAiConfig`.
3. **Move `AndroidAiConfig.kt` from `src/main/java/…/ai/adapter/` to `src/test/java/…/ai/adapter/`** (same package `net.devemperor.dictate.ai.adapter`). Because the ~7 test files reference it by the identical FQN and the parity tests already sit in that test package, **no test import changes are needed**. Have the moved fixture's `completionParameters` **delegate** to `PrefCompletionParameters` (keeps `ParameterResolutionParityTest` a real test of the extracted main helper, and stays DRY).
   - Naming: consider renaming the fixture to `LegacyPrefAiConfig` (or `PrefBackedAiConfig`) so a future reader sees it is the frozen baseline, not production. This ripples to the ~7 test imports; it is optional churn — a class KDoc line ("test-only frozen pref-based baseline; production reads via `ProfileResolver`") is the minimum. Weigh maintainability vs. diff size; a rename is the more sustainable end-state.
4. **Update the two comment-only references in main** that name the class in prose (`AndroidAiFactory.kt:19`, `ProfileResolver.kt` KDoc, `SecretsMigration.kt:50`) if you rename — otherwise leave. These are comments, not code, and do not affect the test.
5. Rebuild `:app` debug + run `:app:testDebugUnitTest` — the parity/characterization/orchestrator tests must stay green (the fixture still exists, just in test scope).

### Part 2 — `WindowsDeviceSecret` re-point (REQUIRED co-requisite; recommend a separate Critical finding)

This must land before `@Ignore` is removed. Concrete shape (for the fix/design agent that owns it):
- **Read seam:** `WindowsTarget.from` needs the secret from `SecretStore.get(SecretRef("pairing", "windows_device_secret"))` instead of `sp.get(Pref.WindowsDeviceSecret)`. Because `from(sp)` has ~13 callers, prefer widening its signature to accept a `SecretStore` (or a `() -> String?` secret supplier) rather than reaching into a singleton — inject from `DictateApplication`/the pipeline the way `AndroidAiFactory` already builds `AndroidKeystoreSecretStore.create(context)`.
- **Write seam:** `WindowsPairingActivity` pair → `SecretStore.put(ref, secret.toByteArray())`; unpair → `SecretStore.delete(ref)`. Stop writing `Pref.WindowsDeviceSecret`.
- **Reactive `paired?` signal:** `PipelinePrefMirror` can no longer watch the secret pref. Decouple "paired?" (a **non-secret** predicate over `WindowsTargetUrl` + `WindowsDeviceId`, which are already watched and are not secrets) from "the secret value" (store, needed only at send time). This keeps the lit-button/send-destination coupling ADR-0019 requires without a secret in prefs. This is the design decision that makes the re-point non-trivial and argues for handling it as its own scoped item (possibly a short spec/ADR note).
- **Regression test:** a test that a paired fixture survives `SecretsMigration.run` (i.e. `WindowsTarget.from` still resolves after the pref is deleted, reading from the store) — red on today's code, green after the re-point.

### `@Ignore` removal (gating)

Remove `@Ignore` from `secretPrefs_areReferencedOnlyInDefinitionAndMigration` **only after both** Part 1 and Part 2 land. If Part 2 is deferred, keep `@Ignore` but **rewrite its reason string** to name the precise remaining blocker (the three `WindowsDeviceSecret` consumers), not "C2/C3 re-point reads/writes" (which is now misleading — the API-key half is done). Update the class KDoc header (lines 12–24) the same way so the pending marker points at the real, narrowed gap.

## References

- Finding source: `reports/C/C3-impl.md` issue **C3-1** (delegated, Important) — route this research to that decision (per DEDUP note); do not spawn a duplicate work item.
- Spec: `research/secretstore.md` §2.6 (invariant), §3.1 (11-slot inventory incl. `WindowsDeviceSecret`), §7.1–7.2 (migration + `WindowsTarget` re-point intent), §10 (`NoLegacyKeyReadTest` row).
- Plan: `desktop-companion-v1.md` Chunk B2 (L490–497), Block C (C2/C3).
- Prior scope decisions: `reports/B/B2-impl.md` "Scope decision — reader re-pointing is C2/C3, not B2"; `reports/B/B2-selffix.md:55`.
- Code: `ai/adapter/AndroidAiConfig.kt`, `config/ConfigEntityMigration.kt:220`, `ai/adapter/ProfileResolver.kt`, `secrets/SecretsMigration.kt:175`, `preferences/WindowsTarget.kt:36–48`, `settings/WindowsPairingActivity.java:218,286`, `state/PipelinePrefMirror.kt:315–335`, `DictateApplication.java:34`.
- Conventions: `~/.claude/snippets/test-first-patterns.md` (regression red-before-green for the Part 2 test), engineering-principles (SRP for `PrefCompletionParameters`, meaningful name for the moved fixture).

---

## Findings (Update 2026-07-20T00:40:00+02:00 — Part 2 concrete design: the `WindowsDeviceSecret` re-point)

**Triggered by:** Finding **C-TEST-2** (re-classified Critical). The Part 1 retirement landed (the 10 API-key offenders are gone), but the three `WindowsDeviceSecret` consumers are still live main-source references AND a live-reachable regression (a migrated user reads as unpaired; `WindowsPairingActivity` re-persists the secret in plaintext). The earlier Part 2 above left the design open ("possibly a short spec/ADR note", "the design decision that makes the re-point non-trivial"). This update **closes those decisions** so the fix agent has no "it depends" left. It supersedes the sketch in "Part 2 — `WindowsDeviceSecret` re-point" above where they differ.

### F6 — The decided design: split the **non-secret paired-predicate** from the **secret-bearing target**

The re-point is non-trivial *only* if you keep `WindowsTarget` (which carries the secret) as the thing every "is a PC paired?" call site reads. It is trivial once you notice that **9 of the 13 `WindowsTarget.from` call sites need only a boolean and never touch the secret.** Forcing a `SecretStore` into all 13 (the "widen `from(sp)`" sketch) is the wrong trade-off — it couples UI/state/routing code to the crypto store for no reason (SRP/DIP violation). Instead:

- **`WindowsTarget.isPaired(sp): Boolean`** — a **non-secret** predicate over `Pref.WindowsTargetUrl` + `Pref.WindowsDeviceId` (both non-secret, both written by pairing, `WindowsTargetUrl` cleared by unpair). This *replaces* the current "secret present" gate at the 9 predicate sites.
- **`WindowsTarget.resolve(sp, secretStore): WindowsTarget?`** — the full send target, reading the secret from `SecretStore.get(PairingSecrets.DEVICE_SECRET_REF)`. Returns `null` when not paired **or** the secret is absent/undecryptable. Used at the 4 send/test sites only.
- **Remove `WindowsTarget.from(sp)` entirely** — it is the offender (`sp.get(Pref.WindowsDeviceSecret)` at `WindowsTarget.kt:39`); no deprecated shim, or the grep test stays red.

**Why the non-secret predicate is the *correct* fix, not just cleaner:** unpair clears `WindowsTargetUrl` and the secret **together** (`WindowsPairingActivity:285–286`); pair writes url+deviceId+secret **together**. So in every steady state `url-present ⟺ secret-present`. The *only* state where they diverge is the migration window — the secret pref was deleted by `SecretsMigration` but url+deviceId remain — which is **exactly** the C-TEST-2 bug: those users ARE paired. `isPaired(sp) = url≠"" && deviceId≠""` reads them as paired (correct); the current secret-in-pref gate flips them to unpaired (the regression). The predicate is the fix.

### F7 — Call-site classification (drives the exact edit at each site)

| Call site | Needs | New call |
|---|---|---|
| `state/PipelinePrefMirror.kt:217` (initial mirror) | predicate | `WindowsTarget.isPaired(sp)` |
| `state/PipelinePrefMirror.kt:333` (change sync) | predicate | `WindowsTarget.isPaired(sp)` |
| `windows/WindowsAutoSend.kt:23` (`shouldAutoSend`) | predicate | `... && WindowsTarget.isPaired(sp)` |
| `settings/PreferencesFragment.java:523` | predicate | `WindowsTarget.isPaired(sp)` |
| `core/StartPcDictationActivity.kt:55` (`isPaired=`) | predicate | `WindowsTarget.isPaired(sp)` |
| `core/DictateInputMethodService.java:2207` (`windowsPaired`) | predicate | `WindowsTarget.isPaired(sp)` |
| `core/DictateInputMethodService.java:6975` (PC long-press) | predicate | `WindowsTarget.isPaired(sp)` |
| `core/DictateInputMethodService.java:7161` (send gate; actual send via coordinator) | predicate | `!WindowsTarget.isPaired(sp)` |
| `settings/WindowsPairingActivity.java:299` (`refreshPairedState`) | predicate + serverName | `isPaired(sp)`; read name via `Pref.WindowsServerName` |
| `core/DictatePipelineService.kt:851` (coordinator `targetProvider`) | **secret** | `WindowsTarget.resolve(sharedPrefs, secretStore)` |
| `core/DictatePipelineService.kt:872` (`PcInputCoordinator.send`) | **secret** | `WindowsTarget.resolve(sharedPrefs, secretStore)` |
| `core/DictatePipelineService.kt:896` (app-start sync) | **secret** | `WindowsTarget.resolve(sharedPrefs, secretStore)` |
| `settings/WindowsPairingActivity.java:249` (`onTestClicked` → health) | **secret** | `WindowsTarget.resolve(sp, AndroidKeystoreSecretStore.create(this))` |

`state/DictateUiState.kt:1042` is a KDoc comment — update the prose ("`WindowsTarget.from(sp) != null`" → "`WindowsTarget.isPaired(sp)`") but it is not code and not a test blocker.

### F8 — SSoT for the pairing `SecretRef` (new `PairingSecrets`, mirrors `ConfigSecrets`)

Neither the new reader (`WindowsTarget.resolve`) nor the new writer (`WindowsPairingActivity`) may name `Pref.WindowsDeviceSecret` (they are not on the allow-list). Give them a shared handle instead — a new `secrets/PairingSecrets.kt`, exact analogue of the existing `config/ConfigSecrets.kt`:

```kotlin
package net.devemperor.dictate.secrets
import net.devemperor.dictate.ai.secrets.SecretRef
/** The SecretStore address of the pairing device secret (spec secretstore.md §7.2). SSoT so the
 *  migration write, the WindowsTarget read, and the pairing write/clear can never drift. */
object PairingSecrets {
    const val NAMESPACE = "pairing"
    @JvmField val DEVICE_SECRET_REF = SecretRef(NAMESPACE, "windows_device_secret")
}
```

- `@JvmField` so Java (`WindowsPairingActivity`) reads it as `PairingSecrets.DEVICE_SECRET_REF`.
- **`SecretsMigration` uses it too:** replace the inline `SecretRef(PAIRING_NAMESPACE, Pref.WindowsDeviceSecret.key.removePrefix(PREF_KEY_PREFIX))` (line 100–101) with `PairingSecrets.DEVICE_SECRET_REF` as the slot's **destination** ref. `SecretsMigration` still names `Pref.WindowsDeviceSecret` as the migration **source** pref in `SLOTS` — that is allow-listed and correct. This makes the destination ref a real SSoT the reader shares, not a string duplicated in two files. Its own `PAIRING_NAMESPACE`/`PREF_KEY_PREFIX` consts can then go. `SecretsMigrationTest:95–96` still passes (id/namespace unchanged: `pairing`/`windows_device_secret`).

### F9 — Write seam (`WindowsPairingActivity`, Java) — with the store-failure decision

`persistPairing` (pair) and `onUnpairClicked` (unpair) stop touching `Pref.WindowsDeviceSecret` and go through the store. Build it once per handler: `SecretStore store = AndroidKeystoreSecretStore.create(this);` (the Activity has `Context`).

- **Pair (`persistPairing`, replacing line 218):**
  1. `store.put(PairingSecrets.DEVICE_SECRET_REF, response.getDeviceSecret().getBytes(StandardCharsets.UTF_8))` **first**;
  2. then `editor.apply()` writing the non-secret prefs (`WindowsTargetUrl`, `WindowsDeviceId`, `WindowsServerName`) — **drop the secret `put`**.
  Order = secret-before-url, mirroring the migration's "put before mark" invariant: `isPaired` (url present) must never become true without a stored secret.
  - **Store unavailable / `put` throws (`SecretStoreException`):** `put` can throw (`StorageIo`/`Unavailable`) where a pref write never did. **Decision:** wrap the `put`; on failure (or `!store.available`) surface a pairing error (reuse `R.string.dictate_pairing_error_generic`) and **do NOT write the url/deviceId/serverName prefs** — so a user is never shown "paired" with no usable secret (keeps `url-present ⟺ secret-present`). On a healthy Android Keystore this path is effectively unreachable; it mirrors the `SecretStore.available` UI-warning contract (spec §4.3). The crypto is a few ms on a ~40-byte secret — acceptable inline; the agent may fold it into the existing pairing worker `Thread` if preferred.
- **Unpair (`onUnpairClicked`, replacing line 286):** `store.delete(PairingSecrets.DEVICE_SECRET_REF)`; keep clearing `WindowsTargetUrl` + `WindowsServerName` and disarming `WindowsAutoSendEnabled`; keep `WindowsDeviceId` (stable re-pair identity, unchanged intent). Clearing the url already flips `isPaired` to false.
- **`refreshPairedState` (line 299):** `boolean paired = WindowsTarget.isPaired(sp);` and read the display name from `DictatePrefsKt.get(sp, Pref.WindowsServerName.INSTANCE)` (same as `PreferencesFragment:525` already does), since there is no target object anymore.
- **`onTestClicked` (line 249):** `WindowsTarget target = WindowsTarget.resolve(sp, AndroidKeystoreSecretStore.create(this));` — unchanged below (still `target::credentials`).

### F10 — Reactive mirror (`PipelinePrefMirror`)

Drop `Pref.WindowsDeviceSecret.key` from the multi-key `when` arm (line 328). Keep `WindowsAutoSendEnabled.key`, `WindowsTargetUrl.key`, `WindowsDeviceId.key` — those already fire the recompute, and both recomputed fields (`windowsAutoSendActive = WindowsAutoSend.shouldAutoSend(sp)`, `windowsPaired = WindowsTarget.isPaired(sp)`) are now pure non-secret predicates. Pairing writes url+deviceId → recompute → paired; unpair clears url → recompute → unpaired. The reactive signal is fully preserved without a secret in prefs. The 4-key comment block (lines 316–324) should say "url + deviceId" instead of naming the secret.

### F11 — Testability of the send path (the one seam decision the fix needs)

The send sites in `DictatePipelineService` need a `SecretStore`. The service is framework-constructed (no ctor injection) and today builds nothing; `AndroidKeystoreSecretStore.create(this)` uses the **real** `KeystoreKekProvider`, whose `AndroidKeyStore` provider is **absent under Robolectric** (`KekProvider.kt:12`; that is why `SecretsMigrationTest` builds its store with `InMemoryKekProvider`). So `DictatePipelineServiceCompositionTest`'s paired-sync case (`onCreate actually fires the app-start sync when a PC is paired`, seeds the secret today) would get `available=false → resolve()=null → sync never fires → test red`.

**Decision — add a companion test-seam factory, consistent with the service's existing `…Provider` lambda style:**
```kotlin
companion object {
    /** Test seam: the real Keystore is absent under Robolectric, so composition tests swap in an
     *  in-memory store. Defaults to the production Keystore-backed store. Reset in @After. */
    @JvmStatic @VisibleForTesting
    internal var secretStoreFactory: (Context) -> SecretStore = { AndroidKeystoreSecretStore.create(it) }
}
```
In `onCreate`, build `val secretStore = secretStoreFactory(this)` once, alongside `sharedPrefs` (line 394), and close over it in the three `resolve(sharedPrefs, secretStore)` lambdas. The composition test sets `secretStoreFactory = { AndroidKeystoreSecretStore(FakeSharedPreferences(), InMemoryKekProvider(), false).apply { put(PairingSecrets.DEVICE_SECRET_REF, "s3cr3t".toByteArray()) } }` (or the extracted `FakeSecretStore`, below) and resets it in `@After`. This is the minimal seam and matches how the codebase already injects the store elsewhere (`AndroidAiFactory` → `ProfileResolver` ctor).

### F12 — Tests: the regression + the existing suites to update

**New regression tests (red on today's code, green after the re-point — test-first-patterns):**
1. `WindowsTargetTest`: `isPaired` is true when only `WindowsTargetUrl` + `WindowsDeviceId` are set (no secret pref) — models a migrated user; **red today** (`from` requires the secret pref → null → treated unpaired). And `resolve(sp, store)` returns the full target (secret from the store) while `Pref.WindowsDeviceSecret` is absent from prefs.
2. `SecretsMigration` survival: seed url+deviceId in prefs + the secret pref, run `SecretsMigration.run`, then assert (a) `WindowsTarget.isPaired(sp)` still true, (b) `WindowsTarget.resolve(sp, store)` non-null with the migrated secret — proving the paired user survives the migration. Red today.
3. `PairingSecrets.DEVICE_SECRET_REF == SecretsMigration.SLOTS.first { it.pref == Pref.WindowsDeviceSecret }.ref` — pins the write/read address agreement.
4. Grep guard: **remove `@Ignore`** from `NoLegacyKeyReadTest.secretPrefs_areReferencedOnlyInDefinitionAndMigration` (see F13).

**Fixture to extract:** promote the private `FakeSecretStore` inside `SecretsMigrationTest` (lines 247–264) to `testutil/FakeSecretStore.kt` (pure-JVM in-memory `SecretStore`), so `WindowsTargetTest` stays a pure JVM test (no Robolectric) and the composition test can reuse it. Low-churn, and it is already the exact shape needed.

**Existing tests that seed the secret as a pref and must switch** (all currently `.put(Pref.WindowsDeviceSecret, …)` / `.putString(Pref.WindowsDeviceSecret.key, …)`):
- `preferences/WindowsTargetTest.kt` — rewrite around `isPaired` + `resolve` (drop the "secret empty ⇒ null" cases; add the migration-survival case).
- `windows/WindowsAutoSendTest.kt:23`, `state/WindowsAutoSendBothProducersTest.kt:105` — seed `WindowsTargetUrl`+`WindowsDeviceId` for the paired state (predicate basis).
- `state/PipelinePrefMirrorTest.kt:193,235,236` — the paired fixture seeds url+deviceId; the "unpair recompute" case drives `WindowsTargetUrl.key` (or `WindowsDeviceId.key`) instead of `WindowsDeviceSecret.key`.
- `core/DictatePipelineServiceCompositionTest.kt:266,303` — inject the seeded store via `secretStoreFactory` (F11); keep url+deviceId in prefs for `isPaired`; drop the secret-pref seed.

### F13 — `@Ignore` removal + `NoLegacyKeyReadTest` KDoc (now unblocked)

Once F6–F12 land, **remove `@Ignore`** from `secretPrefs_areReferencedOnlyInDefinitionAndMigration` and rewrite the class KDoc (lines 12–31) + the `@Ignore` reason: the pending gap is closed, all 11 secret prefs are now referenced only in `{DictatePrefs.kt, SecretsMigration.kt}`. Also update the `Pref.WindowsDeviceSecret` KDoc in `DictatePrefs.kt:73` ("The long-lived device secret … Empty = not paired") and the `WindowsAutoSendEnabled` KDoc (`:65`, "Gated on `WindowsDeviceSecret` being non-empty") — the pref is now migration-only; "paired" is `WindowsTargetUrl`+`WindowsDeviceId`. Update the `WindowsTarget` class KDoc (the "empty secret is the single is-a-PC-coupled? gate" line) to name the non-secret predicate.

### F14 — Scope note

This is a self-contained re-point (one small new SSoT file, one read seam, one write seam, one mirror arm, one service test-seam, test updates) — **no spec/ADR change is required.** Spec §7.2 already prescribes exactly this ("`WindowsTarget.kt` liest künftig über `SecretRef("pairing", "windows_device_secret")`"); ADR-0017/0019's "pairing proof" contract is preserved (the secret still proves pairing at send time; the *pref-shaped* representation of "paired" simply moves from the secret to the already-present url+deviceId). No new decision to record — it closes a deferred one.

## Implementation Hints (Update — Part 2 execution order)

1. Add `secrets/PairingSecrets.kt` (F8). Re-point `SecretsMigration` destination ref to it; drop its now-unused `PAIRING_NAMESPACE`/`PREF_KEY_PREFIX` consts.
2. Rewrite `preferences/WindowsTarget.kt`: `isPaired(sp)` + `resolve(sp, SecretStore)`; delete `from(sp)`. Update its KDoc.
3. Re-point the 9 predicate sites to `isPaired` (F7 table). Pure boolean swaps.
4. Re-point the 4 secret sites to `resolve` (F7). Add the `secretStoreFactory` seam + one `secretStoreFactory(this)` build in `DictatePipelineService.onCreate` (F11).
5. `WindowsPairingActivity`: store `put` on pair (with the availability/failure guard), `delete` on unpair, `resolve` in `onTestClicked`, `isPaired`+`WindowsServerName` in `refreshPairedState` (F9).
6. `PipelinePrefMirror`: drop the secret key from the `when` arm; fix the comment (F10).
7. Extract `testutil/FakeSecretStore.kt`; add the regression tests; update the five existing suites (F12).
8. Remove `@Ignore`; update the KDocs in `NoLegacyKeyReadTest`, `DictatePrefs`, `WindowsTarget` (F13).
9. `./gradlew :app:testDebugUnitTest` — the grep guard now runs and must be green, proving §2.6 end-to-end.
