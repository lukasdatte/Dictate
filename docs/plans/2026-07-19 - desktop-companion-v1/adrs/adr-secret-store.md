# ADR-NNNN: Project-Wide `SecretStore` Port — Encrypted-at-Rest Secrets on Every Host, Resolving the ADR-0017 §F-3 Plaintext Defer

**Status:** Proposed (plan-scoped — pending promotion)
**Scope:** Project-Wide
**Date:** 2026-07-20
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Plain-language summary.** Today every secret Dictate holds — API keys for
> OpenAI/Groq/Anthropic/…, and the phone↔PC pairing secret — sits in
> **plaintext** in Android `SharedPreferences`. This ADR introduces one narrow
> interface, `SecretStore`, that every host talks to instead of touching storage
> directly. Behind it, each platform encrypts at rest with the strongest
> mechanism it has: **Android Keystore** (AES-GCM) on the phone, **Windows DPAPI**
> on the companion, and a rights-restricted **file fallback** on Linux/headless.
> A one-time migration moves all 11 existing plaintext secrets into the store.
> Jargon: **KEK** = key-encryption-key, the master key that wraps each secret
> blob; **DPAPI** = Windows' Data Protection API, which binds a blob to the OS
> user account; **GCM** = an authenticated cipher mode that detects tampering.

## Research

- **SecretStore spec** (`docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md`):
  §3 inventories all 11 secret slots across `:app` and `:companion`; §4.1 argues
  the port belongs in `:shared-ai` (next to `AiConfig`, its main consumer); §4.2
  gives the `SecretStore` / `SecretRef` signature (byte-oriented, namespaced);
  §4.3 fixes the error semantics (`Unavailable` / `DecryptionFailed` / `StorageIo`);
  §5 the Android Keystore AES-GCM implementation and its Robolectric seam
  (`KekProvider`); §6 the desktop DPAPI + file-fallback (JNA `Crypt32Util`, already
  a dependency at 5.19.1); §7 the migration of all 11 secrets.
- **ADR-0017 §4/§F-3** explicitly stored the pairing device secret in plain
  `SharedPreferences` and deferred encrypted storage as *"a project-wide concern
  spanning all secrets and its own follow-up"* — the exact defer this ADR resolves.
- **Existing platform patterns:** `PlatformModule.detect()` and
  `InputCommandPerformer.available` (`companion/.../platform/PlatformModule.kt`)
  are the backend-selection blueprint reused by `detectSecretStore`; `TextInserter.available`
  (ADR-0018) is the "warn when the capability is absent" precedent mirrored by
  `SecretStore.available`.
- **Concept / decisions:** `.../research/fragenkatalog.md` §F11 (introduce
  project-wide encrypted secret storage now, Android included), §F12 (envelope
  encryption — offering peers may decrypt; no zero-knowledge), §F13 (secrets never
  reach a UI layer); `.../research/konzept-skizze.md` "Verschlüsselungskonzept".

## Context

`:app` keeps ten API keys plus the ADR-0017 pairing device secret in plaintext
`SharedPreferences`. `:companion` has no encrypted tier at all. The desktop-companion
work adds *more* secret surface: shared credentials delivered from peer to peer
(F12) must be written somewhere at rest on the receiver. Continuing to sprinkle
`sp.getString(...)` calls across both codebases makes an encrypted-at-rest upgrade
un-auditable — there is no single choke point to harden.

ADR-0017 already named this: it stored the pairing secret in plaintext "for
consistency with the existing API keys" and recorded a **project-wide encrypted-secrets
migration** as the follow-up. That follow-up is now due, because the entity model
(Block C) and peer sync (Block E) both need a credible secret store to build on.

## Decision

Introduce a single **`SecretStore` port** in `:shared-ai` (package
`net.devemperor.dictate.ai.secrets`, alongside the other AI ports), and route
**all** secret access through it.

1. **Port shape (spec §4.2).** `get/put/delete` operate on **`ByteArray`** keyed by
   a namespaced **`SecretRef(namespace, id)`** (a credential blob or raw key may be
   non-UTF-8; the store round-trips bytes losslessly). Two capability flags:
   `available` (false → no secure store on this host; `get` returns null, UI warns,
   mirroring `TextInserter.available`) and `hardwareBacked` (true → KEK bound to
   hardware/OS-user: Keystore, DPAPI; false → the file fallback). The port has **no
   notion of crypto** — encryption at rest is the implementation's job.

2. **Hard error semantics (spec §4.3).** `get` distinguishes *"no such secret"*
   (`null`) from *"secret present, decryption failed"* (`DecryptionFailed`). The
   latter must **never** collapse into an empty value — an empty API key would start
   the pipeline and yield an opaque 401 instead of a clear "re-enter your key"
   message (serviceability). `Unavailable` and `StorageIo` cover an uninitialisable
   store and underlying IO failure.

3. **Android implementation (spec §5).** `AndroidKeystoreSecretStore` uses an
   AES-256 key in `"AndroidKeyStore"` (GCM, no padding, `setUserAuthenticationRequired(false)`
   so the IME reads keys without an unlock prompt; **no** StrongBox request — it
   throws on many devices). At-rest record = `IV(12) ‖ GCM(ciphertext+tag)`, fresh
   `SecureRandom` IV per `put`, in a dedicated `secretstore.xml` separate from the
   migrated main prefs. Crypto sits behind a `KekProvider` seam so Robolectric tests
   run against an in-memory key (the real `AndroidKeyStore` provider is not
   functional under Robolectric).

4. **Desktop implementation (spec §6).** `detectSecretStore(configDir)` picks by
   platform (the `PlatformModule` pattern): **Windows →** `DpapiSecretStore` via JNA
   `Crypt32Util.cryptProtectData/cryptUnprotectData` (user-scoped, no key-management
   code, `hardwareBacked=true`); **non-Windows →** `FileAesGcmSecretStore`, a
   machine-local AES-256-GCM master key at `configDir/secrets/master.key` with POSIX
   `0600` permissions (`hardwareBacked=false`, honestly weaker, for Linux dogfooding
   and the headless peer). **No new dependency** — JNA `jna-platform` 5.19.1 is
   already present, so the Kotlin ≤ 2.1.20 ceiling is untouched.

5. **Migration of all 11 secrets (spec §7).** B2 migrates the ten API keys **and**
   the pairing device secret. Because B2 runs before the entity model (C2), API keys
   land under a stable legacy namespace `SecretRef("legacy", "<pref-key-suffix>")`,
   which C2 later re-maps onto Credential-entity IDs; the device secret goes to
   `SecretRef("pairing", "windows_device_secret")`. Migrating the device secret too
   is what lets this ADR legitimately claim to **resolve the ADR-0017 §F-3 defer** —
   leaving it plaintext would contradict the project-wide claim (F11).

### Scope of this Convention

Project-Wide because "secrets are encrypted at rest, reached only through
`SecretStore`" is a repository-wide rule.

- **Applies to:** every secret on every host — all API keys, the pairing device
  secret, and peer-delivered shared credentials. No component may read or write a
  secret except through the port.
- **Exempt:** transient in-memory use of a decrypted value during a request is not a
  storage event. Non-secret preferences stay in ordinary `SharedPreferences` /
  `CompanionSettings`. On a host where `available == false` (e.g. a misconfigured
  Linux FS), the app degrades to "no stored secret + visible warning", not a crash.

## Alternatives Considered

1. **`EncryptedSharedPreferences` (Jetpack Security) on Android only.** The
   off-the-shelf option ADR-0017 named. Rejected as the *primary* design: it is
   Android-only (the companion still needs DPAPI/file), its Tink/keyset handling is
   heavier than a single AES-GCM key, and it would leave the desktop and headless
   hosts unaddressed. A single cross-platform port with per-host backends is the
   sustainable shape.
2. **Put the port in `:shared`.** Rejected: `:shared` is wire-pure (ADR-0015) and
   the port has no wire/serialization relation; it would dilute that module's
   purpose. `:shared-ai` is the natural consumer boundary (`AiConfig` resolves the
   very key the store holds) and needs no new dependency.
3. **A dedicated `:shared-secrets` module.** Rejected as over-modularisation: one
   interface + one exception + one value class does not carry its build cost (YAGNI).
   If the port ever became wire-relevant (it is not — credentials travel as entity
   DTOs), moving it is a one-file change.
4. **Zero-knowledge sharing (offering peers cannot read shared keys).** Rejected per
   F12: it would require per-recipient key wrapping and a key-distribution scheme far
   beyond v1; instead TLS-in-transit + `SecretStore`-at-rest with offering peers
   permitted to decrypt is the accepted trust model.
5. **Store the master key next to the blobs unprotected on Linux.** Rejected in
   favour of `0600` file permissions and an explicit `hardwareBacked=false` signal —
   the weaker tier is *disclosed*, not hidden.

## Consequences

**Positive:**
- One auditable choke point: hardening, key rotation, or a stronger Linux backend
  (libsecret) is a change behind one interface, not a sweep across two codebases.
- Every host gets the strongest at-rest mechanism it has, and the UI can tell the
  user how strong it is (`hardwareBacked`).
- The ADR-0017 §F-3 defer is genuinely closed — no plaintext secret survives.
- Byte-oriented + namespaced refs make the store equally usable for API keys, the
  pairing secret, and peer-delivered credentials (Block E writes straight into it).

**Negative:**
- Two new platform implementations plus a migration to maintain; three at-rest
  formats (Keystore, DPAPI, file) to reason about.
- The Linux/headless fallback is genuinely weaker (`hardwareBacked=false`): a user
  with filesystem access can decrypt. Accepted for the self-hosted/dogfooding context
  and made honest via the flag.
- A two-stage namespace (legacy → credential IDs) exists transiently because B2
  precedes C2 — one extra re-map step in C2.

**Failure Modes:**
- **Android Keystore keys are device-bound and do NOT survive Auto-Backup / device
  transfer.** After a restore the encrypted blob exists but the KEK is gone → `get`
  throws `DecryptionFailed`; the caller must treat it as "key must be re-entered".
  The blob file must be **excluded** from Auto-Backup (`dataExtractionRules`) so a
  dead blob is never restored without its key.
- **A `DecryptionFailed` swallowed as an empty string is a silent footgun** — the
  pipeline would start with `""` as the API key and fail with a 401 far from the
  cause. The port forbids this by contract; every reader must propagate the
  exception, not `?: ""` it away.
- **GCM IV reuse breaks confidentiality** — every `put` must draw a fresh
  `SecureRandom` IV; a copy-paste that fixes the IV is catastrophic and invisible to
  tests that only check round-trip.
- **KEK init race:** the Android KEK is created lazily on first `put`; concurrent IME
  threads must be guarded (`@Synchronized` init) or two keys race.
- **DPAPI blobs are user-account-bound** — copying the companion config to another
  Windows user (or machine) makes every secret undecryptable; expected, but a support
  trap when users migrate profiles.

## References

- **Related Plan:** [desktop-companion-v1](docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md)
  — §3 (F11/F12/F13), §5 Block B. Motivates and is implemented by this ADR.
- **Spec:** `docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md`
  (§4 port, §5 Android, §6 desktop, §7 migration).
- **Concept:** `.../research/fragenkatalog.md` §F11/§F12/§F13; `.../research/konzept-skizze.md`
  "Verschlüsselungskonzept".
- **Related ADRs:**
  - ADR-0017 — resolves its §F-3 plaintext-secret defer (the pairing device secret
    is migrated). A Decision-History entry is added to ADR-0017 at promotion,
    pointing here as the resolution.
  - ADR-0015 — the port and its impls respect the module topology and Kotlin ceiling
    (no new dependency; JNA already present).
  - ADR-0018 — the `available`-flag "warn when a capability is absent" pattern
    (`TextInserter.available`) reused for `SecretStore.available`.

## Decision History

### 2026-07-20 — Initial proposal (plan-scoped)

**Trigger:** Feature decision F11 (project-wide encrypted secret storage, Android
included) plus the peer-sync design (F12) needing a credible at-rest store on the
receiver; the SecretStore spec resolved the port placement, backends, and migration.

**Before:** Ten API keys and the ADR-0017 pairing device secret sat in plaintext
`SharedPreferences`; `:companion` had no encrypted tier. ADR-0017 had explicitly
deferred encrypted storage as a project-wide follow-up.

**After:** A single `SecretStore` port in `:shared-ai` with per-host encrypted
backends (Android Keystore AES-GCM, Windows DPAPI, POSIX-`0600` file fallback),
hard `null`-vs-`DecryptionFailed` semantics, and a migration of all 11 secrets that
closes the ADR-0017 §F-3 defer.

**Reasoning:** One narrow port gives a single auditable choke point and the strongest
per-host mechanism, reusing the `PlatformModule`/`available` patterns already in the
companion and needing no new dependency (JNA is present). Migrating the pairing secret
too is what makes the project-wide claim (F11) and the ADR-0017 resolution honest;
zero-knowledge sharing was rejected as beyond v1 (F12).
