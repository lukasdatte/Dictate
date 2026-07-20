# SecretStore — Project-Wide Secret Port + Migration of the Android Plaintext Keys (Block B)

---
date: 2026-07-19
author: Lukas + Claude Code (spec research Block B)
type: Spec
status: Spec — programmer-ready
context: Binding implementation specification for Block B of the desktop-companion-v1 plan — a project-wide SecretStore port (F11), two platform implementations (Android Keystore, Windows DPAPI + Linux fallback), the one-time migration of the Android API keys that today sit in plaintext, plus the delimitation between local at-rest encryption and the envelope credential delivery over the peer network (F12).
related-docs: ~/.claude/plans/desktop-companion-v1.md (§5 Block B, §3 D1/D4, §6 adr-secret-store), docs/decisions/0017-client-server-roles-transport-pairing.md, docs/plans/2026-07-19 - desktop-companion-v1/research/konzept-skizze.md, docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md (self), docs/DATABASE-PATTERNS.md
# convertibility: B
---

This spec describes **what** is built in Block B — port signatures, both
platform implementations, the migration procedure, and the crypto delimitation
between local storage and peer distribution. It is **not** an ADR
(the foundational decision F11/F12 lives in plan §3 and is captured in
`adr-secret-store`) and **not** a substitute for the primitive research in
the chunk: file starting points are named, but the B1/B2 agent verifies the
concrete JNA/Keystore calls in the existing code. The canonical source for all
signatures shown here remains the code after implementation; until then this
file is the specification.

## Table of Contents

- [Glossary](#glossary)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Current Inventory of All Secret Stores](#3-current-inventory-of-all-secret-stores)
- [§4 Port Design (`SecretStore`)](#4-port-design-secretstore)
- [§5 Android Implementation (Keystore AES-GCM)](#5-android-implementation-keystore-aes-gcm)
- [§6 Desktop Implementation (DPAPI + Fallback)](#6-desktop-implementation-dpapi--fallback)
- [§7 Migration Design (B2)](#7-migration-design-b2)
- [§8 Envelope Encryption for Sharing (F12)](#8-envelope-encryption-for-sharing-f12)
- [§9 Directory Layout](#9-directory-layout)
- [§10 Testing Approach](#10-testing-approach)
- [§11 Footguns / Anti-Patterns](#11-footguns--anti-patterns)
- [§12 Information Gaps](#12-information-gaps)
- [§13 Change History](#13-change-history)
- [§14 References](#14-references)

## Glossary

### Types & Ports
- **`SecretStore`** — platform-neutral port: `get/put/delete(SecretRef): ByteArray?` plus `available`/`hardwareBacked` flags. Defined in §4; lives in `:shared-ai`.
- **`SecretRef`** — stable, namespaced identifier of a secret (not the value). Derived from a credential entity OR a legacy pref slot. Defined in §4.2.
- **`SecretStoreException`** — carrier of error semantics (store-missing vs. decryption-fails vs. IO). Defined in §4.3.

### Crypto Building Blocks
- **KEK (Key-Encryption-Key)** — the non-exportable master key in the platform keystore (Android Keystore) or the DPAPI user key (Windows). Never leaves the device. §5.1 / §6.1.
- **At-rest blob** — `IV ‖ AES-256-GCM ciphertext ‖ Tag` per secret, stored platform-side in a file/pref. §5.2 / §6.3.
- **Envelope delivery (F12)** — the offering peer decrypts locally, sends plaintext over TLS, the receiver immediately re-encrypts into its SecretStore. NO cross-peer sealed box. §8.

### Disambiguation
> **At-rest envelope ≠ peer envelope ≠ zero-knowledge sharing.** *At-rest envelope*
> is the local storage encryption (KEK in the keystore/DPAPI wraps the
> secret blob). *Peer envelope (F12)* is the distribution path: decrypt locally →
> TLS → re-encrypt locally at the receiver — both ends use their own
> at-rest envelope, plaintext exists only transiently in RAM and on the
> TLS channel. *Zero-knowledge sharing* (share password / X25519 sealed box) is
> the **discarded** variant, in which the offering peer CANNOT decrypt the
> key — per F12 explicitly NOT part of v1 (§8.4).

## 1. Vision and Motivation

### 1.1 Why this block exists

Today the phone holds **eleven secrets in plaintext** in
`SharedPreferences` under `net.devemperor.dictate`: ten provider API keys
(transcription + rewording) and the 256-bit device secret of the
Windows pairing relationship. ADR-0017 §4 (F-3) deliberately left it that way and
noted a **project-wide** encrypted secret store as a follow-up — Block B
delivers exactly this follow-up (F11). Additionally the peer catalog
(Block E, F12) requires it: an acquired API key must **never** land on disk in
plaintext at the receiver — without a SecretStore on both platforms
credential distribution cannot be implemented securely.

### 1.2 What this block solves

1. **One port, project-wide** — `SecretStore` as the single door to secrets;
   `AiConfig` (Block A3), the Android migration (B2), and peer credential acquisition
   (E2) read/write exclusively through it.
2. **No more plaintext key at rest** — Android: Keystore KEK + AES-256-GCM blobs;
   Desktop: DPAPI (Windows) or file-based AES-GCM fallback (Linux/headless).
3. **Lossless, idempotent migration** — the 11 plaintext prefs move once
   into the SecretStore and are deleted from SharedPreferences; a
   prefs backup export serves as rollback (plan D4.7 / C3 risk mitigation).
4. **Foundation for F12** — the local at-rest encryption is at the same time the
   "stored locally encrypted" half of the F12 requirement pair.

### 1.3 Discarded Alternatives

- **`EncryptedSharedPreferences` (androidx.security-crypto):** discarded — the
  Jetpack lib is **deprecated** (as of `security-crypto` 1.1.0-alpha / early 2024
  officially no longer recommended, no further development), it is Android-only
  (useless for the shared port), and ADR-0017 Alt-5 already discarded the
  point-wise beautification of a single secret as inconsistent. A
  custom, thin Keystore-AES-GCM adapter (~80 lines) is more maintainable than a dead
  dependency and shares the port semantics with the desktop.
- **Port in `:shared` instead of `:shared-ai`:** discarded — `:shared` is, per
  `SharedPurityTest`, the pure wire protocol (jvmTarget 1.8, no coroutines).
  The SecretStore has no wire relation, but is consumed by the AI core (`AiConfig`) and
  the platform hosts — it belongs to the `:shared-ai` port family alongside
  `AiConfig`/`UsageSink`/`ProxyConfig` (§4.1).
- **libsecret as the Linux primary backend:** discarded as default — libsecret requires
  a running secret-service daemon (gnome-keyring/KWallet over D-Bus),
  which on a **headless hub peer** (F8, `--headless` on a VM) is typically
  missing; the file-based AES-GCM fallback runs everywhere. libsecret remains a
  later optional hardening (§12 Gap 2).
- **Zero-knowledge key sharing (share password / sealed box):** discarded per F12 —
  see §8.4; remains a documented hardening option.

## 1a. Architecture Walkthrough

### 1a.0 ASCII Stack Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  CONSUMERS                                               (top)      │
│  AiConfig (A3) · B2 migration (:app) · peer credential (E1/E2)     │
│  Access ONLY via the port — never directly to Keystore/DPAPI/file  │
└─────────────────────────────────────────────────────────────────────┘
                          ↓ get/put/delete(SecretRef)
┌─────────────────────────────────────────────────────────────────────┐
│  PORT  :shared-ai                                                   │
│  interface SecretStore { get/put/delete; available; hardwareBacked }│
│  data class SecretRef(namespace, id)  ·  sealed SecretStoreException │
└─────────────────────────────────────────────────────────────────────┘
              ↓ implemented by :app          ↓ implemented by :companion
┌──────────────────────────────┐  ┌───────────────────────────────────┐
│  AndroidKeystoreSecretStore  │  │  PlatformSecretStore (Bindings)    │
│  KEK: AndroidKeyStore        │  │  Windows → DpapiSecretStore (JNA)  │
│  AES/GCM/NoPadding           │  │  else    → FileAesGcmSecretStore   │
│  Blob file per namespace     │  │  hardwareBacked = OS-dependent     │
└──────────────────────────────┘  └───────────────────────────────────┘
```

### 1a.1 Port layer — `:shared-ai`

- **Purpose:** exactly one contract surface for secrets, platform-neutral.
- **File:** `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/secrets/SecretStore.kt`
- **Type contract:** `fun get(ref: SecretRef): ByteArray?` · `fun put(ref, value: ByteArray)` · `fun delete(ref)` · `val available: Boolean` · `val hardwareBacked: Boolean`
- **Detail:** §4.

### 1a.2 Android impl — `:app`

- **Purpose:** KEK in the Android Keystore, AES-256-GCM blobs per secret in a dedicated file.
- **File:** `app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt`
- **Detail:** §5.

### 1a.3 Desktop impl — `:companion`

- **Purpose:** Windows DPAPI (JNA Crypt32), otherwise file-based AES-GCM fallback; wiring following the `PlatformModule.detect()` pattern.
- **File:** `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/`
- **Detail:** §6.

### 1a.4 Read-this-before-implementing checklist

- [ ] The port lives in `:shared-ai`, NOT in `:shared` — otherwise the `SharedPurityTest` spirit is broken (§4.1).
- [ ] The port knows **no** crypto — only `ByteArray?`. All AES/GCM/DPAPI details live exclusively in the impls (§4.1).
- [ ] The Android Keystore is NOT available under Robolectric → crypto behind a small `Cipher` seam for JVM tests (§5.4, §11).
- [ ] `getApiKey()` today strips non-ASCII (`RunnerFactory.kt:102`) — this behavior moves into the **read adapter** (`AndroidAiConfig`), NOT into the store; the store returns bytes byte-exactly (§7.4).
- [ ] Migration is idempotent and versioned (pref flag) — a second run = no-op (§7.3).
- [ ] `WindowsDeviceSecret` belongs to the migration scope (fully closes ADR-0017 §F-3) — §7.1 / §12 Gap 1.

## 2. Acceptance Criteria

Refines plan §2 criterion 6 for Block B. A mix of file/compile/test invariants:

1. **Port exists, shared:** `SecretStore.kt` lives in `:shared-ai`; `:app` and
   `:companion` each declare exactly one implementation; `./gradlew build` green
   across all modules; `SharedAiPurityTest` green (no Android/Ktor import in `:shared-ai`).
2. **Round-trip per impl:** `put(ref, bytes)` → `get(ref)` returns byte-identical
   bytes; `delete(ref)` → `get(ref)` == `null`. Tested for
   `AndroidKeystoreSecretStore` (Robolectric with Cipher seam, §5.4) and both
   desktop impls (`FileAesGcmSecretStore` runnable on Linux CI; `DpapiSecretStore`
   as pending until Windows acceptance, §10).
3. **Error semantics:** a decryption failure (foreign/rotated KEK, corrupt
   blob) throws `SecretStoreException.DecryptionFailed` and **never** an empty
   string that would be misinterpreted as "no key"; store-not-available →
   `available == false`, `get` == `null` (no throw) — analogous to `TextInserter`.
4. **Migration lossless:** fixture prefs with all 11 secret slots → after the run
   all are retrievable via the SecretStore, and **none** of the 11 plaintext pref keys
   exists anymore in the `SharedPreferences` XML (absence test).
5. **Migration idempotent:** a second run is a no-op (no store write,
   pref flag set); a fresh install without keys stays functional
   (regression test).
6. **No code path reads the old pref keys:** grep/convention test on the 11
   `Pref.*ApiKey*`/`WindowsDeviceSecret` constants finds only definition +
   migration code, no read usage in the runtime path.
7. **F12 boundary upheld:** the catalog index carries for credentials only
   metadata (provider, label, `contentHash` over the at-rest blob / key fingerprint),
   never the plaintext; the receiver path (E2) writes acquired keys exclusively
   via `SecretStore.put` (test in Block E, only the interface is fixed here).

## 3. Current Inventory of All Secret Stores

Complete survey (state of worktree `feature/desktop-companion-v1`, 2026-07-19).
"Plaintext at rest" = sits decrypted on a persistent medium.

### 3.1 Android (`:app`) — SharedPreferences `net.devemperor.dictate`

All values via `DictatePrefs.kt` (sealed `Pref<T>`); registry:
`app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt`.

| # | Secret | Pref constant (def line) | Plaintext? | Main read/write site |
|---|---|---|---|---|
| 1 | OpenAI transcription key | `TranscriptionApiKeyOpenAI` (`DictatePrefs.kt:119`) | yes | R: `RunnerFactory.kt:86`; W: `APISettingsActivity.java:706`, `OnboardingAdapter.java:187` |
| 2 | Groq transcription key | `TranscriptionApiKeyGroq` (`:120`) | yes | R: `RunnerFactory.kt:87`; W: `APISettingsActivity.java:707`, `OnboardingAdapter.java:184` |
| 3 | Custom transcription key | `TranscriptionApiKeyCustom` (`:121`) | yes | R: `RunnerFactory.kt:90`; W: `APISettingsActivity.java:709` |
| 4 | OpenRouter transcription key | `TranscriptionApiKeyOpenRouter` (`:122`) | yes | R: `RunnerFactory.kt:89` |
| 5 | ElevenLabs transcription key | `TranscriptionApiKeyElevenLabs` (`:125`) | yes | R: `RunnerFactory.kt:88`; W: `APISettingsActivity.java:708` |
| 6 | OpenAI rewording key | `RewordingApiKeyOpenAI` (`:136`) | yes | R: `RunnerFactory.kt:94`; W: `APISettingsActivity.java:716`, `OnboardingAdapter.java:188` |
| 7 | Groq rewording key | `RewordingApiKeyGroq` (`:137`) | yes | R: `RunnerFactory.kt:95`; W: `OnboardingAdapter.java:185` |
| 8 | Anthropic rewording key | `RewordingApiKeyAnthropic` (`:138`) | yes | R: `RunnerFactory.kt:96`; W: `APISettingsActivity.java:718` |
| 9 | OpenRouter rewording key | `RewordingApiKeyOpenRouter` (`:139`) | yes | R: `RunnerFactory.kt:97`; W: `APISettingsActivity.java:719` |
| 10 | Custom rewording key | `RewordingApiKeyCustom` (`:140`) | yes | R: `RunnerFactory.kt:98`; W: `APISettingsActivity.java:720` |
| 11 | Windows device secret (256 bit) | `WindowsDeviceSecret` (`:74`) | yes | R: `WindowsTarget.kt:39`; W: `WindowsPairingActivity.java:218` (set), `:286` (clear) |

**Central read seam:** `RunnerFactory.getApiKey(provider, function)`
(`RunnerFactory.kt:83-103`) — the sole runtime read path of the ten API keys.
After A3, `AiConfig` reads here instead of `sp`; after B2, `AndroidAiConfig` reads from the
SecretStore. **Gotcha:** line 102 strips non-ASCII (`replace(Regex("[^ -~]"), "")`)
— this behavior is part of the read adapter, not of the store (§7.4).

**Not a secret (co-inventoried for clarity, NOT migrated):**
`ElevenLabsKeytermsRaw/Parsed` (`:131/:132`, user vocabulary), `WindowsTargetUrl`,
`WindowsDeviceId`, `WindowsServerName`, `ProxyHost` — config, no secrets.

### 3.2 Companion (`:companion`) — current state

- **Device secret hash (no plaintext):** the server stores of a
  pairing secret only the **SHA-256 hash** in the `devices` table
  (`SqlDelightDeviceRepository.kt:16-24`, column `secret_hash`), produced in
  `PairingService`/`shared/auth/Secrets.kt:sha256`. A hash is not a
  recoverable secret → **not a SecretStore candidate**, stays unchanged.
- **No API keys today:** the companion is so far a pure text receiver
  (ADR-0017); it holds **no** provider keys. Only with the desktop pipeline
  (Block D) and credential acquisition (Block E) does it need keys — which then from
  the start run through the SecretStore (no plaintext intermediate state).
- **Settings store:** `SqlDelightSettingsRepository` / `CompanionSettings` hold
  the bind address, chords etc. — no secrets.

### 3.3 Consequence for the design

Only the Android side has a **migration burden** (11 plaintext slots). The
companion gets the SecretStore as a **greenfield** store — there is nothing there
to migrate, only a clean first writer (Block D/E).

## 4. Port Design (`SecretStore`)

### 4.1 Placement — recommendation: `:shared-ai`

**Recommendation: the port lives in `:shared-ai`** (package
`net.devemperor.dictate.ai.secrets`), next to the other ports `AiConfig`,
`UsageSink`, `ProxyConfig`, `AudioDurationReader` (plan §1a.2 / A3).

Rationale along the three ADR-0015 criteria:

- **`:shared` (wire purity) is out:** `SharedPurityTest` keeps `:shared`
  free of everything that is not wire protocol; the port has no
  serialization/wire relation whatsoever. Housing it there would dilute the "reason to exist"
  of the module (the same logic by which `:shared-ai` came into being as a
  fourth module in the first place, D1).
- **`:shared-ai` is the natural consumption boundary:** the main consumer is
  `AiConfig` (resolves the key the runner needs). Both platform hosts
  depend on `:shared-ai` anyway. A secret port next to the other AI ports is
  interface segregation without a new module.
- **A dedicated `:shared-secrets` would be over-modularization:** a module for one
  interface + one exception + one value class does not carry its build costs;
  YAGNI. Should the port later become wire-relevant (it is not — credentials
  travel as entity DTOs in `:shared`, §8.2), moving it would be a
  one-file move.

`:shared-ai` needs **no** new dependency for this — the port is pure Kotlin.
The implementations live in `:app` (Keystore) and `:companion` (DPAPI/file)
and bring their platform-specific deps with them (Android SDK or JNA, §6.2).

### 4.2 Port signature

```kotlin
package net.devemperor.dictate.ai.secrets

/**
 * The one door to secrets in the project (F11). Callers never touch the
 * Android Keystore, DPAPI or the fallback file directly — they name a
 * [SecretRef] and receive/hand over raw bytes. Encryption at rest is the
 * implementation's job; the port has no notion of crypto.
 *
 * Values are `ByteArray` (not `String`): a credential blob or a raw key may
 * be non-UTF-8, and the store must round-trip bytes losslessly. Callers that
 * hold a String key encode UTF-8 on write and decode on read (the read
 * adapter, e.g. AndroidAiConfig, also applies any legacy normalization such
 * as the non-ASCII strip — the store stays byte-exact).
 */
interface SecretStore {

    /** Decrypted bytes, or null if no secret is stored under [ref]. */
    fun get(ref: SecretRef): ByteArray?

    /** Encrypts and stores [value] under [ref], replacing any prior value. */
    fun put(ref: SecretRef, value: ByteArray)

    /** Removes the secret under [ref]. A no-op if none exists. */
    fun delete(ref: SecretRef)

    /**
     * false → no secure store on this platform/host (e.g. a Linux host where
     * even the file fallback could not initialise). Then [get] returns null and
     * the UI warns, mirroring TextInserter.available. It is NOT false merely
     * because a key is missing.
     */
    val available: Boolean

    /**
     * true → the KEK is bound to hardware/OS-user and never leaves the device
     * (Android Keystore, Windows DPAPI). false → the weaker file fallback
     * (§6.3). Surfaced in the UI so the user knows the at-rest strength.
     */
    val hardwareBacked: Boolean
}

/**
 * Stable, namespaced identity of a secret — never the value.
 *
 * [namespace] groups secrets by owning entity kind so a plausible key set
 * stays enumerable and deletable (e.g. all keys of a removed ProviderConfig).
 * [id] is unique within the namespace (a Credential-entity UUID, or a legacy
 * pref slot name during migration).
 */
data class SecretRef(val namespace: String, val id: String) {
    init {
        require(namespace.isNotBlank() && id.isNotBlank())
        // Used to derive a filesystem-safe storage handle — keep it total.
        require(namespace.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    }

    /** Stable storage handle, e.g. "credential/9f1c…" or "legacy/rewording_api_key_openai". */
    val handle: String get() = "$namespace/$id"
}
```

### 4.3 Error semantics

```kotlin
sealed class SecretStoreException(message: String, cause: Throwable? = null)
    : Exception(message, cause) {

    /** The store could not be initialised (no keystore/provider). Paired with available=false. */
    class Unavailable(message: String, cause: Throwable? = null) : SecretStoreException(message, cause)

    /**
     * A blob exists but could not be decrypted — a rotated/foreign KEK, a
     * corrupt blob, or a GCM tag mismatch. MUST surface as this exception, never
     * as null (null means "no such secret") and never as an empty value.
     */
    class DecryptionFailed(ref: SecretRef, cause: Throwable? = null)
        : SecretStoreException("decrypt failed for ${ref.handle}", cause)

    /** Underlying IO failed (file, registry). */
    class StorageIo(message: String, cause: Throwable? = null) : SecretStoreException(message, cause)
}
```

**Rules:**
- `get` distinguishes hard between *"no secret"* (`null`) and *"secret present,
  decryption fails"* (`DecryptionFailed`). The second must **never** slip through as an
  empty key — otherwise the pipeline starts with "" as the API key and
  produces a 401 instead of a clear error message (serviceability imperative).
- `available == false` (store not initializable): `get` returns `null`, `put`
  throws `Unavailable`. The UI warns analogously to the `TextInserter.available` pattern
  (`PlatformModule.kt`).
- **KEK loss after backup/restore** (Android: Keystore keys are device-bound
  and do **not** survive an app backup): `get` throws `DecryptionFailed`; the
  caller treats this as "key must be re-entered" (a deliberate
  consequence, captured in `adr-secret-store` — §5.3).

## 5. Android Implementation (Keystore AES-GCM)

**File:** `app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt`

### 5.1 KEK in the Android Keystore

- Provider `"AndroidKeyStore"`; a symmetric AES-256 key, via
  `KeyGenParameterSpec` with `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`,
  `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, alias e.g.
  `"net.devemperor.dictate.secretstore.kek.v1"`.
- **minSdk 26 (Android 8.0):** AES-GCM in the AndroidKeyStore is available from API 23
  — minSdk 26 is uncritical, no compatibility branch needed.
- **`setUserAuthenticationRequired(false)`** — the IME must read keys without
  a user prompt (dictation runs without an unlock interaction). Deliberately no
  biometric gating; the protection goal is "not in plaintext on disk",
  not "only after fingerprint".
- **Do NOT request StrongBox:** `setIsStrongBoxBacked(true)` exists only from API 28
  and only on devices with a secure element; an unconditional request throws on
  many devices with `StrongBoxUnavailableException`. Default = TEE-bound is enough;
  StrongBox remains an optional later hardening.

### 5.2 At-rest blob format

Per secret a record `IV(12 bytes) ‖ GCM ciphertext+tag`. The Keystore AES key
encrypts each secret blob directly (no separate DEK — the "envelope"
wording of the concept sketch means this KEK-wraps-blob structure;
a DEK per entry only pays off at key rotation, §12 Gap 3).

- **IV:** freshly per `put` from `SecureRandom` (12 bytes, GCM standard). **Never**
  reuse an IV — GCM nonce reuse breaks confidentiality. The IV is stored
  prepended to the ciphertext.
- **Storage:** a dedicated file/pref **separate** from the normal SharedPreferences,
  e.g. an `EncryptedSecrets` prefs file `net.devemperor.dictate.secretstore` with
  Base64 values per `SecretRef.handle`, or one file per namespace under
  `context.filesDir/secretstore/`. Recommendation: **a dedicated
  SharedPreferences file** `secretstore.xml`, values Base64 — minimal new build,
  clearly separated from the migrated main prefs file (eases the absence test
  §7.5).

### 5.3 Known Keystore pitfalls (document in `adr-secret-store`)

- **Backup/restore:** AndroidKeyStore keys are device-bound and are **not**
  taken along via auto-backup/device transfer. After a restore the blob exists,
  but the KEK is missing → `DecryptionFailed`. Consequence: keys must be re-entered on
  the new device. Deliberately accepted (plan §5 Block B risks); the
  app should **exclude** the secret blob file from auto-backup
  (`android:fullBackupContent` / `dataExtractionRules`), so that no "dead" blob
  without a key gets restored.
- **Key invalidation:** without `setUserAuthenticationRequired(true)` there is **no**
  biometrically triggered invalidation — the KEK stays valid as long as the
  keystore lives. (The case "user changes lockscreen" invalidates only
  auth-bound keys; irrelevant here.)
- **First initialization / race:** KEK creation is lazy on the first `put`;
  guard against parallel IME threads with a `@Synchronized` init.

### 5.4 Robolectric testability (pitfall + solution)

The `AndroidKeyStore` provider is **not real** under **Robolectric/JVM** — Robolectric
does not shadow the keystore with working crypto; a direct test fails
with `NoSuchProviderException`/`KeyStoreException`. Solution: put the crypto behind
a slim, injectable seam:

```kotlin
/** Supplies the AES key that wraps blobs. Prod → Android Keystore; test → in-memory key. */
fun interface KekProvider { fun encryptionKey(): SecretKey }
```

- **Prod:** `KeystoreKekProvider` (fetches/creates the key in the AndroidKeyStore).
- **Test:** `InMemoryKekProvider` with a fixed AES-256 key → the same
  `Cipher("AES/GCM/NoPadding")` logic runs under Robolectric, the round-trip test
  (§10) checks the store logic without a real keystore. The real keystore path is
  verified in the manual Android acceptance (Block C/F) or marked as an
  instrumented test (optional).

## 6. Desktop Implementation (DPAPI + Fallback)

### 6.1 Backend choice via the `PlatformModule` pattern

Analogous to `PlatformModule.detect()` (`companion/.../platform/PlatformModule.kt`) and
`InputCommandPerformer.available`:

```kotlin
fun detectSecretStore(configDir: Path): SecretStore =
    if (com.sun.jna.Platform.isWindows()) DpapiSecretStore(configDir)
    else FileAesGcmSecretStore(configDir)   // Linux/macOS/headless
```

`configDir` is the existing companion config directory (the SQLite DB already
lives there); the store creates a sub-store `secrets/`.

### 6.2 Dependency situation — JNA is already there

**No new dependency need.** `:companion` already depends on
`net.java.dev.jna:jna` **and** `net.java.dev.jna:jna-platform`, version **5.19.1**
(`libs.versions.toml:66`, `companion/build.gradle:58-59`). `jna-platform` provides
`com.sun.jna.platform.win32.Crypt32Util` with `cryptProtectData`/`cryptUnprotectData`
— the same acquisition pattern as the already-used `Advapi32Util`
(`platform/windows/WindowsRegistry.kt`). The Kotlin ceiling 2.1.20 (ADR-0015) is
untouched: no new library.

### 6.3 Windows — `DpapiSecretStore`

- **Crypto:** `Crypt32Util.cryptProtectData(plaintext)` on `put`,
  `cryptUnprotectData(blob)` on `get`. DPAPI binds the blob to the
  **Windows user account** (default scope `CRYPTPROTECT_USER`) — no own
  key-management code, no IV handling (DPAPI does that internally). `hardwareBacked`
  in the sense "bound to the OS user, does not leave the profile" → `true`.
- **Storage:** a DPAPI blob per `SecretRef.handle` as a file under
  `configDir/secrets/` (filename = URL-safe hash of the handle) or as a
  line in a small index file. Recommendation: **one file per secret** (simplest
  delete semantics for `delete`).
- **Optional entropy parameter:** DPAPI allows an additional
  app `optionalEntropy` — omit for v1 (unnecessary complexity; the
  user scope is enough for the protection goal). If used, the entropy itself
  must live somewhere — then again chicken-and-egg; therefore deliberately not.

### 6.4 Non-Windows — `FileAesGcmSecretStore` (fallback)

For Linux dogfooding **and** the headless hub peer (F8):

- **Master key:** a machine-local AES-256 key, created on first start,
  stored in `configDir/secrets/master.key` with file permissions **`0600`**
  (POSIX `PosixFilePermissions`, "owner read/write only"). The key is generated from
  `SecureRandom`.
- **Blobs:** `IV(12) ‖ AES-256-GCM(payload)` per secret, one file per `SecretRef.handle`
  under `configDir/secrets/`.
- **`hardwareBacked = false`** — the master key sits (permission-restricted) on disk,
  not in hardware. **Documented weaker** than DPAPI/Keystore: whoever has the
  file-system access of the user can decrypt. This is accepted for the
  self-hosted/dogfooding context and honestly visible via `available=true,
  hardwareBacked=false`. libsecret as a stronger option: §12 Gap 2.
- **`available`:** `true` as soon as `configDir/secrets/` is writable; `false`
  only if even the file store fails (read-only FS).

## 7. Migration Design (B2)

### 7.1 Scope — all 11 secrets (decided)

**All 11 slots from §3.1 are migrated**, i.e. the ten API keys **plus
`WindowsDeviceSecret`** (team-lead decision 2026-07-19, see §13). The
ADR draft `adr-secret-store` explicitly states that it **resolves the
ADR-0017 §F-3 defer** — and this defer is exactly the plaintext device secret
(ADR-0017 §4). The plan text B2 names only `Pref.*ApiKey*` (10), but a
left-behind plaintext device secret would counteract the project-wide SecretStore claim
(F11) and the ADR-0017 resolution; the 11-way migration is
long-term the cleanest solution (D4). B2 thus bindingly comprises **all 11
slots**; `adr-secret-store` may legitimately claim the ADR-0017 resolution.

### 7.2 Namespace layout

During the migration, legacy slots are stored under a stable `SecretRef`
that the read adapter (A3/`AndroidAiConfig`) knows:

- API keys: `SecretRef("credential", "<providerConfigId>")` **after** the
  entity rework (C2). Since B2 runs **before** C2 (sequence §7 in the plan:
  B2→{B1,A3}, C2→{C1,B2,A3}), B2 first migrates into a
  **legacy namespace** `SecretRef("legacy", "<pref-key-suffix>")` (e.g.
  `"transcription_api_key_openai"`), and C2 re-maps the legacy refs onto the
  credential entity IDs (the C2 prefs→entities migration knows the mapping
  provider→key anyway). This keeps B2 independent of the entity model.
- Device secret: `SecretRef("pairing", "windows_device_secret")`; `WindowsTarget.kt`
  reads from it going forward.

### 7.3 Flow (idempotent, versioned)

Pattern: `PrefsMigration` (`preferences/PrefsMigration.kt`) + pref flag like
`LegacyAudioPurgedV4` (`DictatePrefs.kt:182`).

```
new pref flag: object SecretsMigratedV1 : Pref<Boolean>(…secrets_migrated_v1, false)

migrateSecrets(sp, secretStore):
  if sp.get(SecretsMigratedV1): return          # idempotent: no-op on the 2nd run
  backupPlaintextPrefs(sp)                       # §7.6 rollback export FIRST
  for slot in ELEVEN_SLOTS:
     val plaintext = sp.getString(slot.key, "")
     if plaintext.isNotEmpty():
        secretStore.put(slot.ref, plaintext.toByteArray(UTF_8))
     sp.edit().remove(slot.key).apply()          # delete plaintext (also for "" → clean)
  sp.put(SecretsMigratedV1, true)
```

- **Order:** backup export **before** the first store write; store `put`
  **before** the pref `remove` per slot (never delete what is not safely written).
- **Failure case:** if a `put` fails (e.g. `available=false`), the migration
  aborts, does **not** set the flag, and leaves the plaintext prefs in place
  (the next start retries). No half-migrated state with a set
  flag.
- **Call site:** early in app start, before the first runner creation — where
  `PrefsMigration.migrateProviderPrefs` runs today.

### 7.4 Non-ASCII strip stays in the read adapter

`RunnerFactory.kt:102` today strips non-ASCII from the key on **read**. The
migration writes the bytes **unchanged** into the store (byte-exact
round-trip, §2.2). The strip moves into the read adapter `AndroidAiConfig` (A3),
so the runner behavior stays byte-identical (characterization test §10).
Rationale: the store is value-neutral; normalization is caller policy.

### 7.5 Absence proof

After the migration, **none** of the 11 plaintext keys may remain in the
`SharedPreferences` XML. Test (§10): fixture prefs with all slots →
`migrateSecrets` → assert `!sp.contains(key)` for each of the 11 keys **and**
`secretStore.get(ref) == originalBytes`. Because the store writes into a **separate** file
(§5.2), "plaintext pref file no longer contains the string" is directly
checkable.

### 7.6 Rollback — prefs backup export

Before the first deletion, the migration exports the affected plaintext prefs into
a **debug/backup file** (plan D4.7, C3 risk mitigation). Recommendation:
`context.filesDir/backup/prefs-secrets-pre-migration.json` with `0600` permissions,
clearly commented as "contains plaintext keys, delete manually after successful verification".
This is the only deliberate, short-lived plaintext-at-rest point —
it replaces the missing coexistence flag of the hard migration (F22).

## 8. Envelope Encryption for Sharing (F12)

> This section fixes the **boundary** that Block E upholds; the
> catalog protocol details (routes, index) belong to `secretstore`'s
> sister spec or `adr-peer-catalog`. Here: how a credential gets securely from
> peer to peer without ever sitting in plaintext on disk.

### 8.1 What F12 decided

Offering peers **may** decrypt the credentials they manage.
The "envelope" is thus **not** a cross-peer public-key envelope, but:

```
Offering peer                         Acquiring peer
─────────────                         ─────────────
SecretStore.get(ref)     ─ plaintext ►  (only in RAM)
  (decrypted locally)        over           │
        │                    TLS            ▼
   plaintext in RAM     (Tailscale-serve)  SecretStore.put(ref')
                                           (re-encrypted locally at once)
```

The mandatory pair exactly as in the concept sketch §4: **"transmitted encrypted"**
(TLS/Tailscale) **+** "stored locally encrypted" (SecretStore on both sides,
F11). On **no** disk does a plaintext key ever sit.

### 8.2 What is in the catalog index (and what is not)

- **Index (metadata, visible to all with auth):** provider kind, label,
  `contentHash`. The `contentHash` of a credential is formed over the **at-rest blob**
  or a **key fingerprint** (e.g. SHA-256 of the first/last characters or
  an HMAC) — **never** over the plaintext key itself, otherwise the hash
  would be a brute-force target. Recommendation: fingerprint = SHA-256 of the plaintext key,
  but only internally for drift detection; in the index only if deliberately accepted as
  non-invertible. **→ §12 Gap 4: finalize contentHash basis for credentials
  (Block E design).**
- **Never in the index:** the plaintext key and the at-rest blob itself.

### 8.3 Delivery only via a dedicated, authorized call

The secret **value** is not delivered in the catalog index, but via a
**separate, individually authorized** endpoint (Block E1) — behind the existing
pairing auth (ADR-0017), one audit-log line per delivery (plan risk R8).
The receiver writes the received plaintext **exclusively** via
`SecretStore.put` (never into a pref, never into a log). The `SecretStore` signature
(§4.2, `ByteArray`) is exactly the seam that enforces this.

### 8.4 Delimitation to the discarded zero-knowledge variant

Zero-knowledge (a share-password-derived key or an X25519 sealed box, in which the
offering peer **cannot** decrypt the key) is per F12 **NOT** part of
v1. Trade-off deliberate: whoever offers a key trusts the peer operator in the
self-hosted context anyway. The sealed box remains a documented later
hardening option — it would change **only** §8.1/§8.3 (the transport payload becomes a
package sealed for the receiver public key), **not** the local
SecretStore (§4–§6). This decoupling is the reason to cut the port
byte-oriented (not String) already now.

## 9. Directory Layout

```
shared-ai/src/main/kotlin/net/devemperor/dictate/ai/secrets/
├── SecretStore.kt                    [NEW]  Port + SecretRef + SecretStoreException (§4)
│
app/src/main/java/net/devemperor/dictate/secrets/
├── AndroidKeystoreSecretStore.kt     [NEW]  Keystore KEK + AES-GCM blobs (§5)
├── KekProvider.kt                    [NEW]  Cipher seam for Robolectric (§5.4)
└── SecretsMigration.kt               [NEW]  11-slot migration, idempotent (§7)
app/src/main/java/net/devemperor/dictate/preferences/
├── DictatePrefs.kt                   [EDIT] + SecretsMigratedV1 flag (§7.3)
└── PrefsMigration.kt                 [EDIT] call hook-in (§7.3)
app/src/main/java/net/devemperor/dictate/ai/factory/
└── RunnerFactory.kt                  [EDIT] getApiKey → via AiConfig/SecretStore (A3+B2)
app/src/main/                         [EDIT] backup rules: exclude secret blob file from auto-backup (§5.3)
│
companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/
├── DpapiSecretStore.kt               [NEW]  Crypt32Util protect/unprotect (§6.3)
├── FileAesGcmSecretStore.kt          [NEW]  0600 master key + AES-GCM (§6.4)
└── SecretStoreModule.kt              [NEW]  detectSecretStore() following the PlatformModule pattern (§6.1)
```

**File counts:** 8 new files (1 `:shared-ai`, 3 `:app`, 3 `:companion` +
1 module wiring), ~4 edits in `:app`. No new dependency.

## 10. Testing Approach

Conventions: `test-first-patterns.md` (TDD greenfield, characterization before
extraction, regression red-before-green).

| Tier | Test (file) | Checks |
|---|---|---|
| Unit (port) | `SecretRefTest.kt` (:shared-ai) | Handle derivation, blank/character validation |
| Unit (Android) | `AndroidKeystoreSecretStoreTest.kt` (Robolectric + `InMemoryKekProvider`) | Round-trip; delete→null; `DecryptionFailed` on foreign key/corrupt blob; IV uniqueness across two `put` |
| Unit (Desktop) | `FileAesGcmSecretStoreTest.kt` (:companion, Linux CI) | Round-trip; `0600` permissions; `available` false on read-only dir; `DecryptionFailed` on swapped master key |
| Unit (Desktop, pending) | `DpapiSecretStoreTest.kt` | `pending: block-B-windows-abnahme` — DPAPI real only on Windows; assertion prepared, verified in F1 acceptance |
| Characterization | `RunnerKeyResolutionCharacterizationTest.kt` | BEFORE B2/A3: same pref constellation ⇒ same key handed to the runner (incl. non-ASCII strip); after B2 identical via SecretStore |
| Migration | `SecretsMigrationTest.kt` (Robolectric) | 11-slot fixture → all retrievable; **no** plaintext key left in prefs XML (§7.5); idempotency (2nd run no-op); empty install stays green; abort on `put` failure leaves flag unset |
| Convention | `NoLegacyKeyReadTest.kt` | grep-like: the 11 pref constants are referenced only in def + migration, not in the runtime read path (§2.6) |

## 11. Footguns / Anti-Patterns

| Anti-pattern | Why bad | Correction |
|---|---|---|
| Passing `DecryptionFailed` through as an empty String/`null` | Pipeline starts with "" as the key → 401 instead of a clear error message; KEK loss is misread as "no key" | `get` throws `DecryptionFailed`; only a true absence ⇒ `null` (§4.3) |
| Reusing GCM IV / fixed IV | Nonce reuse fully breaks AES-GCM confidentiality | IV per `put` fresh from `SecureRandom`, prepended to the blob (§5.2/§6.4) |
| Instantiating the Android Keystore directly in the test | Robolectric has no real `AndroidKeyStore` provider → test crashes | Crypto behind a `KekProvider` seam, in-memory key in the test (§5.4) |
| Putting the non-ASCII strip into the store | The store would no longer be byte-exact; peer-acquired binary keys would be mangled | Strip stays in the read adapter (`AndroidAiConfig`), store round-trips bytes (§7.4) |
| Deleting a pref before the store `put` is confirmed | A crash between remove and put ⇒ key irrecoverably gone | Order: backup → `put` → `remove`; flag only after a complete run (§7.3) |
| Setting the migration flag before successful migration | A half-migrated state is never retried | Flag only on complete success; abort leaves plaintext + unset flag (§7.3) |
| Plaintext key in the catalog index / in the `contentHash` over plaintext | The index is broadly readable ⇒ key leak or brute-force target | Only metadata + hash over blob/fingerprint; value only via an authorized single call (§8.2/§8.3) |
| Secret blob file in the Android auto-backup | A "dead" blob without a device-bound KEK gets restored, appears corrupt | Exclude the blob file from `dataExtractionRules`/`fullBackupContent` (§5.3) |

## 12. Information Gaps

1. ~~**Migration scope 10 vs. 11 slots.**~~ — **closed 2026-07-19
   (team-lead):** **All 11 slots** are migrated (incl. `WindowsDeviceSecret`),
   so that the project-wide SecretStore (F11) cleanly resolves the ADR-0017 §F-3 defer;
   a left-behind plaintext device secret would counteract exactly that (D4).
   B2 = 11 slots (§7.1).
2. **Linux backend hardening (libsecret).** The file fallback is deliberately weaker
   (`hardwareBacked=false`). *Owner:* later hardening. *Fallback:* file AES-GCM with
   `0600` key; honestly visible via flags. libsecret only if a
   secret-service daemon is reliably present (not on the headless hub).
3. **DEK per entry / key rotation.** v1 encrypts blobs directly with the KEK
   (no separate DEK). *Owner:* follow-up at rotation need. *Fallback:* direct
   KEK encryption; rotation = rewrite all blobs (rare enough).
4. **`contentHash` basis for credentials.** Whether the fingerprint is formed over the at-rest blob
   (peer-specific, drifts despite the same key) or over a stable
   key fingerprint decides the Block E sync behavior. *Owner:*
   E1 design / `adr-peer-catalog`. *Fallback:* here only the boundary "never over
   plaintext" set (§8.2).
5. **Provider upload limit verification** is NOT part of Block B (belongs to D1)
   — noted here only for delimitation.

## 13. Change History

### 2026-07-19 — Initial version

- **Trigger:** spec research assignment Block B (team-lead, 2026-07-19) on the basis of the
  implementation-ready plan desktop-companion-v1 (§5 Block B, §3 D section).
- **Reasoning:** current inventory surveyed against the existing code (`DictatePrefs.kt`,
  `RunnerFactory.kt`, `companion/data`), port placement justified against ADR-0015/`SharedPurityTest`,
  JNA availability (5.19.1 incl. `Crypt32Util`) verified, migration
  cut against the `PrefsMigration`/`LegacyAudioPurgedV4` pattern, F12 boundary
  to peer distribution staked out.
- **What changed:** first version — §3 inventory (11 slots with path:line), §4 port,
  §5 Android Keystore, §6 DPAPI+fallback, §7 migration, §8 envelope, §10 tests,
  §11 footguns, §12 five gaps (of which Gap 1 as an open team-lead decision).

### 2026-07-19 — Gap 1 closed: migrate all 11 secrets

- **Trigger:** team-lead decision on §12 Gap 1.
- **Reasoning:** the user explicitly chose the project-wide SecretStore in order to
  cleanly resolve the ADR-0017 defer; a left-behind plaintext device secret would
  counteract that (D4: long-term cleanest solution). Thus the full
  11-way scope applies, and `adr-secret-store` may claim the ADR-0017 §F-3 resolution.
- **What changed:** §7.1 switched to a binding 11 slots; §12 Gap 1 closed.

### 2026-07-20 — Freshness pass (post-implementation, before archiving)

- **Trigger:** integration check after completing Block A–E (finding `integ-1`,
  green) — reconciliation of the five block specs against the built state before the
  F-stage archiving/EN translation.
- **Reasoning:** this spec is as-built correct. The `SecretStore` port lies
  as specified in §9 in `shared-ai/.../ai/secrets/SecretStore.kt`; the
  Android backends (`AndroidKeystoreSecretStore`/`KekProvider`/`SecretsMigration`)
  in `app/.../secrets/`, the desktop backends (`DpapiSecretStore`/
  `FileAesGcmSecretStore`/`SecretStoreModule`) in `companion/.../secrets/`.
  The only addition compared to the §9 file list: `app/.../secrets/PairingSecrets.kt`
  additionally came into being — pairing-secret access likewise runs through the
  SecretStore (ADR-0029), fitting the §7.1 scope "all 11 secrets".
- **What changed:** no body rework — §9 directory layout noted as extended by the de-facto
  present `PairingSecrets.kt`; otherwise no residual drift.

## 14. References

- **Plan:** `~/.claude/plans/desktop-companion-v1.md` — §5 Block B (B1/B2),
  §3 D1 (`:shared-ai`)/D4, §6 `adr-secret-store`/`adr-shared-ai-module`, §7 sequence,
  §9 R2/R8.
- **ADRs (binding):** `docs/decisions/0017-client-server-roles-transport-pairing.md`
  (§4 F-3 plaintext-secret defer, which B resolves; pairing auth for §8.3),
  ADR-0015 (module topology/Kotlin ceiling), ADR-0018 (port+`available` pattern,
  `TextInserter`).
- **Concept:** `docs/plans/2026-07-19 - desktop-companion-v1/research/konzept-skizze.md`
  §4 "encryption concept" (F11/F12), §2 module split.
- **Existing code (file:line):**
  `app/.../preferences/DictatePrefs.kt:74,119-146` (secret prefs),
  `app/.../ai/factory/RunnerFactory.kt:83-103` (central read seam + non-ASCII strip),
  `app/.../preferences/PrefsMigration.kt` (migration pattern),
  `app/.../settings/WindowsPairingActivity.java:218,286` (device secret set/clear),
  `app/.../preferences/WindowsTarget.kt:39` (device secret reader),
  `companion/.../platform/PlatformModule.kt` (`detect()`+`available` pattern),
  `companion/.../platform/windows/WindowsRegistry.kt` (JNA `Advapi32Util` pattern),
  `companion/.../data/SqlDelightDeviceRepository.kt:16-24` (only secret hash),
  `shared/.../auth/Secrets.kt` (SHA-256/constant-time primitive),
  `shared/.../SharedPurityTest.kt` (purity invariant).
- **Dependencies:** `gradle/libs.versions.toml:66` (`jna=5.19.1`), `142-143`
  (`jna`/`jna-platform`), `companion/build.gradle:58-59`.
- **Conventions:** `docs/DATABASE-PATTERNS.md`, `~/.claude/snippets/test-first-patterns.md`.
