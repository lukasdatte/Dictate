# SecretStore — projektweiter Secret-Port + Migration der Android-Klartext-Keys (Block B)

---
date: 2026-07-19
author: Lukas + Claude Code (Spec-Recherche Block B)
type: Spec
status: Spec — programmer-ready
context: Verbindliche Umsetzungsvorgabe für Block B des Plans desktop-companion-v1 — ein projektweiter SecretStore-Port (F11), zwei Plattform-Implementierungen (Android Keystore, Windows DPAPI + Linux-Fallback), die einmalige Migration der heute im Klartext liegenden Android-API-Keys, sowie die Abgrenzung der lokalen At-Rest-Verschlüsselung zur Envelope-Credential-Auslieferung im Peer-Netz (F12).
related-docs: ~/.claude/plans/desktop-companion-v1.md (§5 Block B, §3 D1/D4, §6 adr-secret-store), docs/decisions/0017-client-server-roles-transport-pairing.md, docs/plans/2026-07-19 - desktop-companion-v1/research/konzept-skizze.md, docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md (self), docs/DATABASE-PATTERNS.md
# convertibility: B
---

Diese Spec beschreibt **was** in Block B gebaut wird — Port-Signaturen, beide
Plattform-Implementierungen, das Migrations-Verfahren und die Krypto-Abgrenzung
zwischen lokaler Speicherung und Peer-Verteilung. Sie ist **kein** ADR
(die Grundsatzentscheidung F11/F12 steht im Plan §3 und wird in
`adr-secret-store` festgehalten) und **kein** Ersatz für die Primitive-Research
im Chunk: Datei-Startpunkte sind benannt, aber der B1/B2-Agent verifiziert die
konkreten JNA-/Keystore-Aufrufe im Bestand. Kanonische Quelle für alle hier
gezeigten Signaturen bleibt der Code nach Implementierung; diese Datei ist bis
dahin die Vorgabe.

## Table of Contents

- [Glossary](#glossary)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Ist-Inventar aller Secret-Ablagen](#3-ist-inventar-aller-secret-ablagen)
- [§4 Port-Design (`SecretStore`)](#4-port-design-secretstore)
- [§5 Android-Implementierung (Keystore AES-GCM)](#5-android-implementierung-keystore-aes-gcm)
- [§6 Desktop-Implementierung (DPAPI + Fallback)](#6-desktop-implementierung-dpapi--fallback)
- [§7 Migrations-Design (B2)](#7-migrations-design-b2)
- [§8 Envelope-Encryption fürs Teilen (F12)](#8-envelope-encryption-fürs-teilen-f12)
- [§9 Directory Layout](#9-directory-layout)
- [§10 Testing Approach](#10-testing-approach)
- [§11 Footguns / Anti-Patterns](#11-footguns--anti-patterns)
- [§12 Information Gaps](#12-information-gaps)
- [§13 Change History](#13-change-history)
- [§14 References](#14-references)

## Glossary

### Typen & Ports
- **`SecretStore`** — plattformneutraler Port: `get/put/delete(SecretRef): ByteArray?` plus `available`/`hardwareBacked`-Flags. Definiert in §4; lebt in `:shared-ai`.
- **`SecretRef`** — stabiler, namespaced Bezeichner eines Geheimnisses (nicht der Wert). Ableitung aus Credential-Entität ODER Legacy-Pref-Slot. Definiert in §4.2.
- **`SecretStoreException`** — Fehler-Semantik-Träger (Store-fehlt vs. Entschlüsselung-scheitert vs. IO). Definiert in §4.3.

### Krypto-Bausteine
- **KEK (Key-Encryption-Key)** — der nicht-exportierbare Master-Schlüssel im Plattform-Keystore (Android Keystore) bzw. der DPAPI-Nutzerschlüssel (Windows). Verlässt das Gerät nie. §5.1 / §6.1.
- **At-Rest-Blob** — `IV ‖ AES-256-GCM-Ciphertext ‖ Tag` je Secret, plattformseitig in Datei/Pref abgelegt. §5.2 / §6.3.
- **Envelope-Auslieferung (F12)** — anbietender Peer entschlüsselt lokal, sendet Klartext über TLS, Empfänger re-verschlüsselt sofort in seinen SecretStore. KEIN cross-peer Sealed-Box. §8.

### Disambiguation
> **At-Rest-Envelope ≠ Peer-Envelope ≠ Zero-Knowledge-Sharing.** *At-Rest-Envelope*
> ist die lokale Speicher-Verschlüsselung (KEK im Keystore/DPAPI umschließt den
> Secret-Blob). *Peer-Envelope (F12)* ist der Verteilungsweg: lokal entschlüsseln →
> TLS → beim Empfänger lokal re-verschlüsseln — beide Enden nutzen ihren eigenen
> At-Rest-Envelope, der Klartext existiert nur transient im RAM und auf dem
> TLS-Kanal. *Zero-Knowledge-Sharing* (Share-Passwort / X25519-Sealed-Box) ist
> die **verworfene** Variante, in der der anbietende Peer den Key NICHT
> entschlüsseln kann — per F12 explizit NICHT Teil von v1 (§8.4).

## 1. Vision and Motivation

### 1.1 Warum dieser Block existiert

Heute liegen auf dem Phone **elf Geheimnisse im Klartext** in
`SharedPreferences` unter `net.devemperor.dictate`: zehn Provider-API-Keys
(Transkription + Rewording) und das 256-bit-Device-Secret der
Windows-Pairing-Beziehung. ADR-0017 §4 (F-3) hat das bewusst so belassen und
eine **projektweite** verschlüsselte Secret-Ablage als Follow-up notiert — genau
diesen Follow-up löst Block B ein (F11). Zusätzlich verlangt der Peer-Katalog
(Block E, F12): ein bezogener API-Key darf beim Empfänger **nie** auf Platte im
Klartext landen — ohne einen SecretStore auf beiden Plattformen ist die
Credential-Verteilung nicht sicher umsetzbar.

### 1.2 Was dieser Block löst

1. **Ein Port, projektweit** — `SecretStore` als einzige Tür zu Geheimnissen;
   `AiConfig` (Block A3), die Android-Migration (B2) und der Peer-Credential-Bezug
   (E2) lesen/schreiben ausschließlich durch ihn.
2. **Kein Klartext-Key mehr at rest** — Android: Keystore-KEK + AES-256-GCM-Blobs;
   Desktop: DPAPI (Windows) bzw. dateibasierter AES-GCM-Fallback (Linux/headless).
3. **Verlustfreie, idempotente Migration** — die 11 Klartext-Prefs wandern einmalig
   in den SecretStore und werden aus den SharedPreferences gelöscht; ein
   Prefs-Backup-Export dient als Rollback (Plan D4.7 / C3-Risiko-Mitigation).
4. **Fundament für F12** — die lokale At-Rest-Verschlüsselung ist zugleich die
   „lokal verschlüsselt gespeichert"-Hälfte des F12-Anforderungspaares.

### 1.3 Discarded Alternatives

- **`EncryptedSharedPreferences` (androidx.security-crypto):** verworfen — die
  Jetpack-Lib ist **deprecated** (seit `security-crypto` 1.1.0-alpha / Anfang 2024
  offiziell nicht mehr empfohlen, keine Weiterentwicklung), sie ist Android-only
  (nutzlos für den geteilten Port), und ADR-0017 Alt-5 hat das punktuelle
  Aufhübschen eines einzelnen Secrets schon einmal als inkonsistent verworfen. Ein
  eigener, dünner Keystore-AES-GCM-Adapter (~80 Zeilen) ist wartbarer als eine tote
  Dependency und teilt die Port-Semantik mit dem Desktop.
- **Port in `:shared` statt `:shared-ai`:** verworfen — `:shared` ist per
  `SharedPurityTest` das reine Wire-Protokoll (jvmTarget 1.8, keine Coroutines).
  Der SecretStore hat keinen Wire-Bezug, wird aber vom AI-Kern (`AiConfig`) und
  den Plattform-Hosts konsumiert — er gehört zur `:shared-ai`-Port-Familie neben
  `AiConfig`/`UsageSink`/`ProxyConfig` (§4.1).
- **libsecret als Linux-Primär-Backend:** verworfen als Default — libsecret setzt
  einen laufenden Secret-Service-Daemon (gnome-keyring/KWallet über D-Bus) voraus,
  der auf einem **headless Hub-Peer** (F8, `--headless` auf einer VM) typischerweise
  fehlt; der dateibasierte AES-GCM-Fallback läuft überall. libsecret bleibt eine
  spätere optionale Härtung (§12 Gap 2).
- **Zero-Knowledge-Key-Sharing (Share-Passwort / Sealed-Box):** verworfen per F12 —
  siehe §8.4; bleibt dokumentierte Härtungsoption.

## 1a. Architecture Walkthrough

### 1a.0 ASCII Stack Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  KONSUMENTEN                                              (top)      │
│  AiConfig (A3) · B2-Migration (:app) · Peer-Credential (E1/E2)      │
│  Zugriff NUR über den Port — nie direkt auf Keystore/DPAPI/Datei    │
└─────────────────────────────────────────────────────────────────────┘
                          ↓ get/put/delete(SecretRef)
┌─────────────────────────────────────────────────────────────────────┐
│  PORT  :shared-ai                                                   │
│  interface SecretStore { get/put/delete; available; hardwareBacked }│
│  data class SecretRef(namespace, id)  ·  sealed SecretStoreException │
└─────────────────────────────────────────────────────────────────────┘
              ↓ implementiert :app            ↓ implementiert :companion
┌──────────────────────────────┐  ┌───────────────────────────────────┐
│  AndroidKeystoreSecretStore  │  │  PlatformSecretStore (Bindings)    │
│  KEK: AndroidKeyStore        │  │  Windows → DpapiSecretStore (JNA)  │
│  AES/GCM/NoPadding           │  │  sonst  → FileAesGcmSecretStore    │
│  Blob-Datei je Namespace     │  │  hardwareBacked = OS-abhängig      │
└──────────────────────────────┘  └───────────────────────────────────┘
```

### 1a.1 Port-Schicht — `:shared-ai`

- **Purpose:** genau eine Vertragsfläche für Geheimnisse, plattformneutral.
- **File:** `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/secrets/SecretStore.kt`
- **Type contract:** `fun get(ref: SecretRef): ByteArray?` · `fun put(ref, value: ByteArray)` · `fun delete(ref)` · `val available: Boolean` · `val hardwareBacked: Boolean`
- **Detail:** §4.

### 1a.2 Android-Impl — `:app`

- **Purpose:** KEK im Android Keystore, AES-256-GCM-Blobs pro Secret in eigener Datei.
- **File:** `app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt`
- **Detail:** §5.

### 1a.3 Desktop-Impl — `:companion`

- **Purpose:** Windows DPAPI (JNA Crypt32), sonst dateibasierter AES-GCM-Fallback; Wiring nach dem `PlatformModule.detect()`-Muster.
- **File:** `companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/`
- **Detail:** §6.

### 1a.4 Read-this-before-implementing checklist

- [ ] Port lebt in `:shared-ai`, NICHT in `:shared` — sonst reißt der `SharedPurityTest`-Geist (§4.1).
- [ ] Der Port kennt **kein** Krypto — nur `ByteArray?`. Alle AES/GCM/DPAPI-Details liegen ausschließlich in den Impls (§4.1).
- [ ] Android Keystore ist unter Robolectric NICHT verfügbar → Crypto hinter einer kleinen `Cipher`-Seam für JVM-Tests (§5.4, §11).
- [ ] `getApiKey()` strippt heute Non-ASCII (`RunnerFactory.kt:102`) — dieses Verhalten wandert in den **Lese-Adapter** (`AndroidAiConfig`), NICHT in den Store; der Store gibt Bytes byte-genau zurück (§7.4).
- [ ] Migration ist idempotent und versioniert (Pref-Flag) — doppelter Lauf = No-Op (§7.3).
- [ ] `WindowsDeviceSecret` gehört zum Migrationsumfang (schließt ADR-0017 §F-3 vollständig) — §7.1 / §12 Gap 1.

## 2. Acceptance Criteria

Verfeinert Plan §2 Kriterium 6 für Block B. Mischung aus Datei-/Compile-/Test-Invarianten:

1. **Port existiert, geteilt:** `SecretStore.kt` liegt in `:shared-ai`; `:app` und
   `:companion` deklarieren je genau eine Implementierung; `./gradlew build` grün
   über alle Module; `SharedAiPurityTest` grün (kein Android/Ktor-Import in `:shared-ai`).
2. **Round-Trip pro Impl:** `put(ref, bytes)` → `get(ref)` liefert byte-identische
   Bytes zurück; `delete(ref)` → `get(ref)` == `null`. Getestet für
   `AndroidKeystoreSecretStore` (Robolectric mit Cipher-Seam, §5.4) und beide
   Desktop-Impls (`FileAesGcmSecretStore` auf Linux CI lauffähig; `DpapiSecretStore`
   als pending bis Windows-Abnahme, §10).
3. **Fehler-Semantik:** Entschlüsselungsfehler (fremder/rotierter KEK, korrupter
   Blob) wirft `SecretStoreException.DecryptionFailed` und **nie** einen leeren
   String, der als „kein Key" fehlinterpretiert würde; Store-nicht-verfügbar →
   `available == false`, `get` == `null` (kein Throw) — analog `TextInserter`.
4. **Migration verlustfrei:** Fixture-Prefs mit allen 11 Secret-Slots → nach Lauf
   sind alle über den SecretStore abrufbar, und **keiner** der 11 Klartext-Pref-Keys
   existiert mehr in der `SharedPreferences`-XML (Abwesenheits-Test).
5. **Migration idempotent:** zweiter Lauf ist ein No-Op (kein Store-Schreiben,
   Pref-Flag gesetzt); frische Installation ohne Keys bleibt funktionsfähig
   (Regression-Test).
6. **Kein Codepfad liest die alten Pref-Keys:** grep-/Konventions-Test auf die 11
   `Pref.*ApiKey*`/`WindowsDeviceSecret`-Konstanten findet nur noch Definition +
   Migrations-Code, keine Lese-Nutzung im Runtime-Pfad.
7. **F12-Grenze eingehalten:** der Katalog-Index trägt für Credentials nur
   Metadaten (Provider, Label, `contentHash` über den At-Rest-Blob / Key-Fingerprint),
   nie den Klartext; der Empfänger-Pfad (E2) schreibt bezogene Keys ausschließlich
   über `SecretStore.put` (Test in Block E, hier nur die Schnittstelle festgelegt).

## 3. Ist-Inventar aller Secret-Ablagen

Vollständige Erhebung (Stand Worktree `feature/desktop-companion-v1`, 2026-07-19).
„Klartext at rest" = liegt entschlüsselt auf einem persistenten Medium.

### 3.1 Android (`:app`) — SharedPreferences `net.devemperor.dictate`

Alle Werte über `DictatePrefs.kt` (sealed `Pref<T>`); Registry:
`app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt`.

| # | Secret | Pref-Konstante (Def-Zeile) | Klartext? | Haupt-Lese/-Schreibstelle |
|---|---|---|---|---|
| 1 | OpenAI-Transkription-Key | `TranscriptionApiKeyOpenAI` (`DictatePrefs.kt:119`) | ja | R: `RunnerFactory.kt:86`; W: `APISettingsActivity.java:706`, `OnboardingAdapter.java:187` |
| 2 | Groq-Transkription-Key | `TranscriptionApiKeyGroq` (`:120`) | ja | R: `RunnerFactory.kt:87`; W: `APISettingsActivity.java:707`, `OnboardingAdapter.java:184` |
| 3 | Custom-Transkription-Key | `TranscriptionApiKeyCustom` (`:121`) | ja | R: `RunnerFactory.kt:90`; W: `APISettingsActivity.java:709` |
| 4 | OpenRouter-Transkription-Key | `TranscriptionApiKeyOpenRouter` (`:122`) | ja | R: `RunnerFactory.kt:89` |
| 5 | ElevenLabs-Transkription-Key | `TranscriptionApiKeyElevenLabs` (`:125`) | ja | R: `RunnerFactory.kt:88`; W: `APISettingsActivity.java:708` |
| 6 | OpenAI-Rewording-Key | `RewordingApiKeyOpenAI` (`:136`) | ja | R: `RunnerFactory.kt:94`; W: `APISettingsActivity.java:716`, `OnboardingAdapter.java:188` |
| 7 | Groq-Rewording-Key | `RewordingApiKeyGroq` (`:137`) | ja | R: `RunnerFactory.kt:95`; W: `OnboardingAdapter.java:185` |
| 8 | Anthropic-Rewording-Key | `RewordingApiKeyAnthropic` (`:138`) | ja | R: `RunnerFactory.kt:96`; W: `APISettingsActivity.java:718` |
| 9 | OpenRouter-Rewording-Key | `RewordingApiKeyOpenRouter` (`:139`) | ja | R: `RunnerFactory.kt:97`; W: `APISettingsActivity.java:719` |
| 10 | Custom-Rewording-Key | `RewordingApiKeyCustom` (`:140`) | ja | R: `RunnerFactory.kt:98`; W: `APISettingsActivity.java:720` |
| 11 | Windows-Device-Secret (256 bit) | `WindowsDeviceSecret` (`:74`) | ja | R: `WindowsTarget.kt:39`; W: `WindowsPairingActivity.java:218` (set), `:286` (clear) |

**Zentrale Lese-Naht:** `RunnerFactory.getApiKey(provider, function)`
(`RunnerFactory.kt:83-103`) — der einzige Runtime-Lesepfad der zehn API-Keys.
Nach A3 liest hier `AiConfig` statt `sp`, nach B2 liest `AndroidAiConfig` aus dem
SecretStore. **Gotcha:** Zeile 102 strippt Non-ASCII (`replace(Regex("[^ -~]"), "")`)
— dieses Verhalten ist Teil des Lese-Adapters, nicht des Stores (§7.4).

**Kein Secret (zur Klarstellung mit-inventarisiert, NICHT migriert):**
`ElevenLabsKeytermsRaw/Parsed` (`:131/:132`, User-Vokabular), `WindowsTargetUrl`,
`WindowsDeviceId`, `WindowsServerName`, `ProxyHost` — Konfig, keine Geheimnisse.

### 3.2 Companion (`:companion`) — heutiger Stand

- **Device-Secret-Hash (kein Klartext):** Der Server speichert von einem
  Pairing-Secret nur den **SHA-256-Hash** in der `devices`-Tabelle
  (`SqlDelightDeviceRepository.kt:16-24`, Spalte `secret_hash`), erzeugt in
  `PairingService`/`shared/auth/Secrets.kt:sha256`. Ein Hash ist kein
  wiederherstellbares Geheimnis → **kein SecretStore-Kandidat**, bleibt unverändert.
- **Keine API-Keys heute:** Der Companion ist bisher reiner Text-Empfänger
  (ADR-0017); er hält **keine** Provider-Keys. Erst mit der Desktop-Pipeline
  (Block D) und dem Credential-Bezug (Block E) braucht er Keys — die dann von
  Anfang an über den SecretStore laufen (kein Klartext-Zwischenzustand).
- **Settings-Ablage:** `SqlDelightSettingsRepository` / `CompanionSettings` halten
  Bind-Adresse, Chords etc. — keine Geheimnisse.

### 3.3 Konsequenz fürs Design

Nur die Android-Seite hat eine **Migrationslast** (11 Klartext-Slots). Der
Companion bekommt den SecretStore als **Greenfield**-Ablage — es gibt dort nichts
zu migrieren, nur einen sauberen ersten Schreiber (Block D/E).

## 4. Port-Design (`SecretStore`)

### 4.1 Platzierung — Empfehlung: `:shared-ai`

**Empfehlung: der Port lebt in `:shared-ai`** (Paket
`net.devemperor.dictate.ai.secrets`), neben den übrigen Ports `AiConfig`,
`UsageSink`, `ProxyConfig`, `AudioDurationReader` (Plan §1a.2 / A3).

Begründung entlang der drei ADR-0015-Kriterien:

- **`:shared` (Wire-Reinheit) scheidet aus:** `SharedPurityTest` hält `:shared`
  frei von allem, was nicht Wire-Protokoll ist; der Port hat keinerlei
  Serialisierungs-/Wire-Bezug. Ihn dort einzuquartieren würde die „Reason to exist"
  des Moduls verwässern (dieselbe Logik, mit der `:shared-ai` überhaupt als
  viertes Modul entsteht, D1).
- **`:shared-ai` ist der natürliche Konsumboundary:** Der Haupt-Konsument ist
  `AiConfig` (löst den Key auf, den der Runner braucht). Beide Plattform-Hosts
  hängen ohnehin an `:shared-ai`. Ein Secret-Port neben den anderen AI-Ports ist
  Interface-Segregation ohne neues Modul.
- **Ein eigenes `:shared-secrets` wäre Über-Modularisierung:** Ein Modul für ein
  Interface + eine Exception + eine Value-Class trägt seine Build-Kosten nicht;
  YAGNI. Falls der Port später wire-relevant würde (er ist es nicht — Credentials
  reisen als Entitäts-DTOs in `:shared`, §8.2), wäre ein Verschieben ein
  Ein-Datei-Move.

`:shared-ai` braucht dafür **keine** neue Dependency — der Port ist reines Kotlin.
Die Implementierungen liegen in `:app` (Keystore) bzw. `:companion` (DPAPI/Datei)
und bringen ihre plattform-spezifischen Deps mit (Android SDK bzw. JNA, §6.2).

### 4.2 Port-Signatur

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

### 4.3 Fehler-Semantik

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

**Regeln:**
- `get` unterscheidet hart zwischen *„kein Secret"* (`null`) und *„Secret da,
  Entschlüsselung scheitert"* (`DecryptionFailed`). Die zweite darf **nie** als
  leerer Key durchrutschen — sonst startet die Pipeline mit „" als API-Key und
  produziert einen 401 statt einer klaren Fehlermeldung (Serviceability-Gebot).
- `available == false` (Store nicht initialisierbar): `get` gibt `null`, `put`
  wirft `Unavailable`. UI warnt analog zum `TextInserter.available`-Muster
  (`PlatformModule.kt`).
- **KEK-Verlust nach Backup/Restore** (Android: Keystore-Keys sind gerätegebunden
  und überleben ein App-Backup **nicht**): `get` wirft `DecryptionFailed`; der
  Aufrufer behandelt das als „Key muss neu eingegeben werden" (bewusste
  Konsequenz, in `adr-secret-store` festgehalten — §5.3).

## 5. Android-Implementierung (Keystore AES-GCM)

**File:** `app/src/main/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStore.kt`

### 5.1 KEK im Android Keystore

- Provider `"AndroidKeyStore"`; ein symmetrischer AES-256-Schlüssel, per
  `KeyGenParameterSpec` mit `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`,
  `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, Alias z. B.
  `"net.devemperor.dictate.secretstore.kek.v1"`.
- **minSdk 26 (Android 8.0):** AES-GCM im AndroidKeyStore ist ab API 23 verfügbar
  — minSdk 26 ist unkritisch, keine Kompatibilitäts-Weiche nötig.
- **`setUserAuthenticationRequired(false)`** — der IME muss Keys ohne
  User-Prompt lesen (Diktat läuft ohne Entsperr-Interaktion). Bewusst kein
  biometrisches Gating; das Schutzziel ist „nicht im Klartext auf Platte",
  nicht „nur nach Fingerprint".
- **StrongBox NICHT anfordern:** `setIsStrongBoxBacked(true)` gibt es erst ab API 28
  und nur auf Geräten mit Secure Element; ein bedingungsloses Anfordern wirft auf
  vielen Geräten `StrongBoxUnavailableException`. Default = TEE-gebunden reicht;
  StrongBox bleibt optionale spätere Härtung.

### 5.2 At-Rest-Blob-Format

Pro Secret ein Record `IV(12 Byte) ‖ GCM-Ciphertext+Tag`. Der Keystore-AES-Key
verschlüsselt jeden Secret-Blob direkt (kein separater DEK — die „Envelope"-
Formulierung der Konzept-Skizze meint diese KEK-umschließt-Blob-Struktur;
ein DEK-pro-Eintrag lohnt erst bei Key-Rotation, §12 Gap 3).

- **IV:** je `put` neu aus `SecureRandom` (12 Byte, GCM-Standard). **Niemals**
  IV wiederverwenden — GCM-Nonce-Reuse bricht die Vertraulichkeit. IV wird dem
  Ciphertext vorangestellt gespeichert.
- **Ablage:** eigene Datei/Pref **getrennt** von den normalen SharedPreferences,
  z. B. `EncryptedSecrets`-Prefs-Datei `net.devemperor.dictate.secretstore` mit
  Base64-Werten je `SecretRef.handle`, oder eine Datei pro Namespace unter
  `context.filesDir/secretstore/`. Empfehlung: **eine dedizierte
  SharedPreferences-Datei** `secretstore.xml`, Werte Base64 — minimaler Neubau,
  klar vom migrierten Haupt-Prefs-File getrennt (erleichtert den Abwesenheits-Test
  §7.5).

### 5.3 Bekannte Keystore-Fallstricke (dokumentieren in `adr-secret-store`)

- **Backup/Restore:** AndroidKeyStore-Keys sind gerätegebunden und werden **nicht**
  über Auto-Backup/Geräte-Transfer mitgenommen. Nach Restore existiert der Blob,
  aber der KEK fehlt → `DecryptionFailed`. Konsequenz: Keys müssen auf dem neuen
  Gerät neu eingegeben werden. Bewusst akzeptiert (Plan §5 Block B Risiken); die
  App sollte die Secret-Blob-Datei aus dem Auto-Backup **ausschließen**
  (`android:fullBackupContent` / `dataExtractionRules`), damit kein „toter" Blob
  ohne Key restauriert wird.
- **Key-Invalidierung:** ohne `setUserAuthenticationRequired(true)` gibt es **keine**
  biometrisch-getriggerte Invalidierung — der KEK bleibt gültig, solange der
  Keystore lebt. (Der Fall „User ändert Lockscreen" invalidiert nur
  auth-gebundene Keys; hier irrelevant.)
- **Erst-Initialisierung / Race:** KEK-Erzeugung ist lazy beim ersten `put`;
  gegen parallele IME-Threads mit einem `@Synchronized`-Init absichern.

### 5.4 Robolectric-Testbarkeit (Fallstrick + Lösung)

Der `AndroidKeyStore`-Provider ist unter **Robolectric/JVM nicht real** — Robolectric
shadowt den Keystore nicht mit funktionierender Krypto; ein direkter Test schlägt
mit `NoSuchProviderException`/`KeyStoreException` fehl. Lösung: die Krypto hinter
eine schmale, injizierbare Seam legen:

```kotlin
/** Supplies the AES key that wraps blobs. Prod → Android Keystore; test → in-memory key. */
fun interface KekProvider { fun encryptionKey(): SecretKey }
```

- **Prod:** `KeystoreKekProvider` (holt/erzeugt den Key im AndroidKeyStore).
- **Test:** `InMemoryKekProvider` mit einem festen AES-256-Key → dieselbe
  `Cipher("AES/GCM/NoPadding")`-Logik läuft unter Robolectric, der Round-Trip-Test
  (§10) prüft die Store-Logik ohne echten Keystore. Der echte Keystore-Pfad wird
  in der manuellen Android-Abnahme (Block C/F) verifiziert bzw. als
  Instrumented-Test (optional) markiert.

## 6. Desktop-Implementierung (DPAPI + Fallback)

### 6.1 Backend-Wahl über `PlatformModule`-Muster

Analog `PlatformModule.detect()` (`companion/.../platform/PlatformModule.kt`) und
`InputCommandPerformer.available`:

```kotlin
fun detectSecretStore(configDir: Path): SecretStore =
    if (com.sun.jna.Platform.isWindows()) DpapiSecretStore(configDir)
    else FileAesGcmSecretStore(configDir)   // Linux/macOS/headless
```

`configDir` ist das bestehende Companion-Konfigverzeichnis (dort liegt schon die
SQLite-DB); der Store legt eine Unterablage `secrets/` an.

### 6.2 Dependency-Lage — JNA ist bereits da

**Kein neuer Dependency-Bedarf.** `:companion` hängt bereits an
`net.java.dev.jna:jna` **und** `net.java.dev.jna:jna-platform`, Version **5.19.1**
(`libs.versions.toml:66`, `companion/build.gradle:58-59`). `jna-platform` liefert
`com.sun.jna.platform.win32.Crypt32Util` mit `cryptProtectData`/`cryptUnprotectData`
— dasselbe Bezugsmuster wie das schon genutzte `Advapi32Util`
(`platform/windows/WindowsRegistry.kt`). Kotlin-Ceiling 2.1.20 (ADR-0015) ist
nicht berührt: keine neue Library.

### 6.3 Windows — `DpapiSecretStore`

- **Krypto:** `Crypt32Util.cryptProtectData(plaintext)` beim `put`,
  `cryptUnprotectData(blob)` beim `get`. DPAPI bindet den Blob an den
  **Windows-Nutzeraccount** (Default-Scope `CRYPTPROTECT_USER`) — kein eigener
  Key-Management-Code, kein IV-Handling (DPAPI macht das intern). `hardwareBacked`
  im Sinne „an OS-Nutzer gebunden, verlässt das Profil nicht" → `true`.
- **Ablage:** DPAPI-Blob je `SecretRef.handle` als Datei unter
  `configDir/secrets/` (Dateiname = URL-sicherer Hash des Handles) oder als
  Zeile in einer kleinen Index-Datei. Empfehlung: **Datei pro Secret** (einfachste
  Lösch-Semantik für `delete`).
- **Optionaler Entropy-Parameter:** DPAPI erlaubt eine zusätzliche
  App-`optionalEntropy` — für v1 weglassen (unnötige Komplexität; der
  User-Scope reicht dem Schutzziel). Wenn genutzt, muss die Entropy selbst
  irgendwo liegen — dann wieder Henne-Ei; daher bewusst nicht.

### 6.4 Nicht-Windows — `FileAesGcmSecretStore` (Fallback)

Für Linux-Dogfooding **und** den headless Hub-Peer (F8):

- **Master-Key:** ein maschinenlokaler AES-256-Key, erzeugt beim ersten Start,
  abgelegt in `configDir/secrets/master.key` mit Dateirechten **`0600`**
  (POSIX `PosixFilePermissions`, „owner read/write only"). Der Key wird aus
  `SecureRandom` erzeugt.
- **Blobs:** `IV(12) ‖ AES-256-GCM(payload)` je Secret, Datei pro `SecretRef.handle`
  unter `configDir/secrets/`.
- **`hardwareBacked = false`** — der Master-Key liegt (rechtebeschränkt) auf Platte,
  nicht in Hardware. **Dokumentiert schwächer** als DPAPI/Keystore: wer den
  Dateisystem-Zugriff des Nutzers hat, kann entschlüsseln. Das ist für den
  Self-Hosted-/Dogfooding-Kontext akzeptiert und über `available=true,
  hardwareBacked=false` ehrlich sichtbar. libsecret als stärkere Option: §12 Gap 2.
- **`available`:** `true`, sobald `configDir/secrets/` beschreibbar ist; `false`
  nur, wenn selbst die Datei-Ablage scheitert (read-only FS).

## 7. Migrations-Design (B2)

### 7.1 Umfang — alle 11 Secrets (entschieden)

**Alle 11 Slots aus §3.1 werden migriert**, also die zehn API-Keys **plus
`WindowsDeviceSecret`** (Team-Lead-Entscheidung 2026-07-19, siehe §13). Der
ADR-Draft `adr-secret-store` erklärt ausdrücklich, den **ADR-0017 §F-3-Defer
aufzulösen** — und dieser Defer ist genau das plaintext-Device-Secret
(ADR-0017 §4). Der Plan-Text B2 nennt zwar nur `Pref.*ApiKey*` (10), aber ein
zurückgelassenes Klartext-Device-Secret würde den projektweiten SecretStore-Anspruch
(F11) und die ADR-0017-Auflösung konterkarieren; die 11er-Migration ist
langfristig die sauberste Lösung (D4). B2 umfasst damit verbindlich **alle 11
Slots**; `adr-secret-store` darf die ADR-0017-Auflösung berechtigt behaupten.

### 7.2 Namespace-Zuschnitt

Während der Migration werden Legacy-Slots unter einem stabilen `SecretRef`
abgelegt, den der Lese-Adapter (A3/`AndroidAiConfig`) kennt:

- API-Keys: `SecretRef("credential", "<providerConfigId>")` **nach** dem
  Entitäten-Umbau (C2). Da B2 **vor** C2 läuft (Sequenz §7 im Plan:
  B2→{B1,A3}, C2→{C1,B2,A3}), migriert B2 zunächst in einen
  **Legacy-Namespace** `SecretRef("legacy", "<pref-key-suffix>")` (z. B.
  `"transcription_api_key_openai"`), und C2 re-mapped die Legacy-Refs auf die
  Credential-Entitäts-IDs (die C2-Prefs→Entitäten-Migration kennt die Zuordnung
  Provider→Key ohnehin). Das hält B2 unabhängig vom Entitätenmodell.
- Device-Secret: `SecretRef("pairing", "windows_device_secret")`; `WindowsTarget.kt`
  liest künftig darüber.

### 7.3 Ablauf (idempotent, versioniert)

Muster: `PrefsMigration` (`preferences/PrefsMigration.kt`) + Pref-Flag wie
`LegacyAudioPurgedV4` (`DictatePrefs.kt:182`).

```
neuer Pref-Flag: object SecretsMigratedV1 : Pref<Boolean>(…secrets_migrated_v1, false)

migrateSecrets(sp, secretStore):
  if sp.get(SecretsMigratedV1): return          # idempotent: No-Op beim 2. Lauf
  backupPlaintextPrefs(sp)                       # §7.6 Rollback-Export ZUERST
  for slot in ELEVEN_SLOTS:
     val plaintext = sp.getString(slot.key, "")
     if plaintext.isNotEmpty():
        secretStore.put(slot.ref, plaintext.toByteArray(UTF_8))
     sp.edit().remove(slot.key).apply()          # Klartext löschen (auch bei "" → sauber)
  sp.put(SecretsMigratedV1, true)
```

- **Reihenfolge:** Backup-Export **vor** dem ersten Store-Schreiben; Store-`put`
  **vor** dem Pref-`remove` je Slot (nie löschen, was nicht sicher geschrieben ist).
- **Fehlerfall:** scheitert ein `put` (z. B. `available=false`), bricht die
  Migration ab, setzt das Flag **nicht** und lässt die Klartext-Prefs stehen
  (der nächste Start versucht erneut). Kein halb-migrierter Zustand mit gesetztem
  Flag.
- **Aufrufort:** früh im App-Start, vor der ersten Runner-Erzeugung — dort, wo
  heute `PrefsMigration.migrateProviderPrefs` läuft.

### 7.4 Non-ASCII-Strip bleibt im Lese-Adapter

`RunnerFactory.kt:102` strippt heute beim **Lesen** Non-ASCII aus dem Key. Die
Migration schreibt die Bytes **unverändert** in den Store (byte-exakter
Round-Trip, §2.2). Der Strip wandert in den Lese-Adapter `AndroidAiConfig` (A3),
sodass das Runner-Verhalten byte-gleich bleibt (Charakterisierungs-Test §10).
Begründung: der Store ist wertneutral; Normalisierung ist Aufrufer-Politik.

### 7.5 Abwesenheits-Nachweis

Nach der Migration darf **keiner** der 11 Klartext-Keys mehr in der
`SharedPreferences`-XML stehen. Test (§10): Fixture-Prefs mit allen Slots →
`migrateSecrets` → assert `!sp.contains(key)` für jeden der 11 Keys **und**
`secretStore.get(ref) == originalBytes`. Weil der Store in eine **getrennte** Datei
schreibt (§5.2), ist „Klartext-Pref-Datei enthält den String nicht mehr" direkt
prüfbar.

### 7.6 Rollback — Prefs-Backup-Export

Vor dem ersten Löschen exportiert die Migration die betroffenen Klartext-Prefs in
eine **Debug-/Backup-Datei** (Plan D4.7, C3-Risiko-Mitigation). Empfehlung:
`context.filesDir/backup/prefs-secrets-pre-migration.json` mit `0600`-Rechten,
klar als „enthält Klartext-Keys, manuell löschen nach erfolgreicher Verifikation"
kommentiert. Das ist der einzige bewusste, kurzzeitige Klartext-at-rest-Punkt —
er ersetzt das fehlende Koexistenz-Flag der harten Migration (F22).

## 8. Envelope-Encryption fürs Teilen (F12)

> Diese Sektion legt die **Grenze** fest, die Block E einhält; die
> Katalog-Protokoll-Details (Routes, Index) gehören zu `secretstore`s
> Schwester-Spec bzw. `adr-peer-catalog`. Hier: wie ein Credential sicher von
> Peer zu Peer kommt, ohne je im Klartext auf Platte zu liegen.

### 8.1 Was F12 entschieden hat

Anbietende Peers **dürfen** die von ihnen verwalteten Credentials entschlüsseln.
Der „Envelope" ist damit **nicht** ein cross-peer Public-Key-Envelope, sondern:

```
Anbieter-Peer                         Bezieher-Peer
─────────────                         ─────────────
SecretStore.get(ref)     ─ Klartext ─►  (nur im RAM)
  (lokal entschlüsselt)      über           │
        │                    TLS            ▼
   Klartext im RAM      (Tailscale-serve)  SecretStore.put(ref')
                                           (sofort lokal re-verschlüsselt)
```

Pflicht-Paar exakt wie in der Konzept-Skizze §4: **„verschlüsselt übertragen"**
(TLS/Tailscale) **+** „lokal verschlüsselt gespeichert" (SecretStore beidseitig,
F11). Auf **keiner** Platte liegt je ein Klartext-Key.

### 8.2 Was im Katalog-Index steht (und was nicht)

- **Index (Metadaten, für alle sichtbar mit Auth):** Provider-Kind, Label,
  `contentHash`. Der `contentHash` eines Credentials wird über den **At-Rest-Blob**
  bzw. einen **Key-Fingerprint** (z. B. SHA-256 der ersten/letzten Zeichen oder
  ein HMAC) gebildet — **nie** über den Klartext-Key selbst, sonst wäre der Hash
  ein Brute-Force-Ziel. Empfehlung: Fingerprint = SHA-256 des Klartext-Keys,
  aber nur intern für Drift-Erkennung; im Index nur, wenn bewusst als
  nicht-umkehrbar akzeptiert. **→ §12 Gap 4: contentHash-Basis für Credentials
  final festlegen (Block E-Design).**
- **Nie im Index:** der Klartext-Key und der At-Rest-Blob selbst.

### 8.3 Auslieferung nur über einen eigenen, autorisierten Call

Der Secret-**Wert** wird nicht im Katalog-Index mitgeliefert, sondern über einen
**separaten, einzeln autorisierten** Endpoint (Block E1) — hinter der bestehenden
Pairing-Auth (ADR-0017), pro Auslieferung eine Audit-Log-Zeile (Plan-Risiko R8).
Der Empfänger schreibt den erhaltenen Klartext **ausschließlich** über
`SecretStore.put` (nie in eine Pref, nie in ein Log). Die `SecretStore`-Signatur
(§4.2, `ByteArray`) ist genau die Naht, die das erzwingt.

### 8.4 Abgrenzung zur verworfenen Zero-Knowledge-Variante

Zero-Knowledge (Share-Passwort-abgeleiteter Key oder X25519-Sealed-Box, bei der der
Anbieter-Peer den Key **nicht** entschlüsseln kann) ist per F12 **NICHT** Teil von
v1. Trade-off bewusst: wer einen Key anbietet, vertraut dem Peer-Betreiber im
Self-Hosted-Kontext ohnehin. Die Sealed-Box bleibt dokumentierte spätere
Härtungsoption — sie würde **nur** §8.1/§8.3 ändern (Transport-Payload wird ein
für den Empfänger-Public-Key versiegeltes Paket), **nicht** den lokalen
SecretStore (§4–§6). Diese Entkopplung ist der Grund, den Port jetzt schon
byte-orientiert (nicht String) zu schneiden.

## 9. Directory Layout

```
shared-ai/src/main/kotlin/net/devemperor/dictate/ai/secrets/
├── SecretStore.kt                    [NEW]  Port + SecretRef + SecretStoreException (§4)
│
app/src/main/java/net/devemperor/dictate/secrets/
├── AndroidKeystoreSecretStore.kt     [NEW]  Keystore-KEK + AES-GCM-Blobs (§5)
├── KekProvider.kt                    [NEW]  Cipher-Seam für Robolectric (§5.4)
└── SecretsMigration.kt               [NEW]  11-Slot-Migration, idempotent (§7)
app/src/main/java/net/devemperor/dictate/preferences/
├── DictatePrefs.kt                   [EDIT] + SecretsMigratedV1-Flag (§7.3)
└── PrefsMigration.kt                 [EDIT] Aufruf-Einhängung (§7.3)
app/src/main/java/net/devemperor/dictate/ai/factory/
└── RunnerFactory.kt                  [EDIT] getApiKey → über AiConfig/SecretStore (A3+B2)
app/src/main/                         [EDIT] Backup-Regeln: Secret-Blob-Datei aus Auto-Backup ausschließen (§5.3)
│
companion/src/main/kotlin/net/devemperor/dictate/companion/secrets/
├── DpapiSecretStore.kt               [NEW]  Crypt32Util protect/unprotect (§6.3)
├── FileAesGcmSecretStore.kt          [NEW]  0600-Master-Key + AES-GCM (§6.4)
└── SecretStoreModule.kt              [NEW]  detectSecretStore() nach PlatformModule-Muster (§6.1)
```

**File counts:** 8 neue Dateien (1 `:shared-ai`, 3 `:app`, 3 `:companion` +
1 Modul-Wiring), ~4 Edits im `:app`. Keine neue Dependency.

## 10. Testing Approach

Konventionen: `test-first-patterns.md` (TDD Neubau, Charakterisierung vor
Extraktion, Regression rot-vor-grün).

| Tier | Test (Datei) | Prüft |
|---|---|---|
| Unit (Port) | `SecretRefTest.kt` (:shared-ai) | Handle-Ableitung, Blank-/Zeichen-Validierung |
| Unit (Android) | `AndroidKeystoreSecretStoreTest.kt` (Robolectric + `InMemoryKekProvider`) | Round-Trip; delete→null; `DecryptionFailed` bei fremdem Key/korruptem Blob; IV-Eindeutigkeit über zwei `put` |
| Unit (Desktop) | `FileAesGcmSecretStoreTest.kt` (:companion, Linux CI) | Round-Trip; `0600`-Rechte; `available` false auf read-only Dir; `DecryptionFailed` bei getauschtem Master-Key |
| Unit (Desktop, pending) | `DpapiSecretStoreTest.kt` | `pending: block-B-windows-abnahme` — DPAPI real nur auf Windows; Assertion vorbereitet, in F1-Abnahme verifiziert |
| Charakterisierung | `RunnerKeyResolutionCharacterizationTest.kt` | VOR B2/A3: gleiche Pref-Konstellation ⇒ gleicher an den Runner gereichter Key (inkl. Non-ASCII-Strip); nach B2 identisch über SecretStore |
| Migration | `SecretsMigrationTest.kt` (Robolectric) | 11-Slot-Fixture → alle abrufbar; **kein** Klartext-Key mehr in Prefs-XML (§7.5); Idempotenz (2. Lauf No-Op); leere Installation bleibt grün; Abbruch bei `put`-Fehler lässt Flag ungesetzt |
| Konvention | `NoLegacyKeyReadTest.kt` | grep-artig: die 11 Pref-Konstanten werden nur in Def + Migration referenziert, nicht im Runtime-Lesepfad (§2.6) |

## 11. Footguns / Anti-Patterns

| Anti-pattern | Warum schlecht | Korrektur |
|---|---|---|
| `DecryptionFailed` als leeren String/`null` durchreichen | Pipeline startet mit „" als Key → 401 statt klarer Fehlermeldung; KEK-Verlust wird als „kein Key" fehlgedeutet | `get` wirft `DecryptionFailed`; nur echtes Fehlen ⇒ `null` (§4.3) |
| GCM-IV wiederverwenden / fixe IV | Nonce-Reuse bricht AES-GCM-Vertraulichkeit vollständig | IV je `put` frisch aus `SecureRandom`, dem Blob vorangestellt (§5.2/§6.4) |
| Android Keystore direkt im Test instanziieren | Robolectric hat keinen echten `AndroidKeyStore`-Provider → Test crasht | Crypto hinter `KekProvider`-Seam, In-Memory-Key im Test (§5.4) |
| Non-ASCII-Strip in den Store legen | Store wäre nicht mehr byte-exakt; Peer-bezogene Binär-Keys würden verstümmelt | Strip bleibt im Lese-Adapter (`AndroidAiConfig`), Store round-trippt Bytes (§7.4) |
| Pref löschen bevor Store-`put` bestätigt ist | Crash zwischen remove und put ⇒ Key unwiederbringlich weg | Reihenfolge: Backup → `put` → `remove`; Flag erst nach vollständigem Lauf (§7.3) |
| Migrations-Flag vor erfolgreicher Migration setzen | Halb-migrierter Zustand wird nie wiederholt | Flag nur bei vollständigem Erfolg; Abbruch lässt Klartext + ungesetztes Flag (§7.3) |
| Klartext-Key im Katalog-Index / im `contentHash` über Klartext | Index ist breit lesbar ⇒ Key-Leak bzw. Brute-Force-Ziel | Nur Metadaten + Hash über Blob/Fingerprint; Wert nur über autorisierten Einzel-Call (§8.2/§8.3) |
| Secret-Blob-Datei im Android-Auto-Backup | „Toter" Blob ohne gerätegebundenen KEK wird restauriert, wirkt korrupt | Blob-Datei aus `dataExtractionRules`/`fullBackupContent` ausschließen (§5.3) |

## 12. Information Gaps

1. ~~**Migrationsumfang 10 vs. 11 Slots.**~~ — **geschlossen 2026-07-19
   (Team-Lead):** Es werden **alle 11 Slots** migriert (inkl. `WindowsDeviceSecret`),
   damit der projektweite SecretStore (F11) den ADR-0017 §F-3-Defer sauber auflöst;
   ein zurückgelassenes Klartext-Device-Secret würde genau das konterkarieren (D4).
   B2 = 11 Slots (§7.1).
2. **Linux-Backend-Härtung (libsecret).** Der Datei-Fallback ist bewusst schwächer
   (`hardwareBacked=false`). *Owner:* spätere Härtung. *Fallback:* File-AES-GCM mit
   `0600`-Key; über Flags ehrlich sichtbar. libsecret nur, wenn ein
   Secret-Service-Daemon verlässlich vorhanden (nicht auf headless Hub).
3. **DEK-pro-Eintrag / Key-Rotation.** v1 verschlüsselt Blobs direkt mit dem KEK
   (kein separater DEK). *Owner:* Follow-up bei Rotationsbedarf. *Fallback:* direkte
   KEK-Verschlüsselung; Rotation = alle Blobs neu schreiben (selten genug).
4. **`contentHash`-Basis für Credentials.** Ob der Fingerprint über den At-Rest-Blob
   (peer-spezifisch, driftet trotz gleichem Key) oder über einen stabilen
   Key-Fingerprint gebildet wird, entscheidet das Block-E-Sync-Verhalten. *Owner:*
   E1-Design / `adr-peer-catalog`. *Fallback:* hier nur die Grenze „nie über
   Klartext" gesetzt (§8.2).
5. **Provider-Upload-Limit-Verifikation** ist NICHT Teil von Block B (gehört zu D1)
   — hier nur zur Abgrenzung notiert.

## 13. Change History

### 2026-07-19 — Initialfassung

- **Trigger:** Spec-Recherche-Auftrag Block B (Team-Lead, 2026-07-19) auf Basis des
  implementierungsbereiten Plans desktop-companion-v1 (§5 Block B, §3 D-Sektion).
- **Reasoning:** Ist-Inventar am Bestand erhoben (`DictatePrefs.kt`,
  `RunnerFactory.kt`, `companion/data`), Port-Placement gegen ADR-0015/`SharedPurityTest`
  begründet, JNA-Verfügbarkeit (5.19.1 inkl. `Crypt32Util`) verifiziert, Migration
  gegen das `PrefsMigration`/`LegacyAudioPurgedV4`-Muster geschnitten, F12-Grenze
  zur Peer-Verteilung abgesteckt.
- **What changed:** Erstversion — §3 Inventar (11 Slots mit Pfad:Zeile), §4 Port,
  §5 Android-Keystore, §6 DPAPI+Fallback, §7 Migration, §8 Envelope, §10 Tests,
  §11 Footguns, §12 fünf Gaps (davon Gap 1 als offene Team-Lead-Entscheidung).

### 2026-07-19 — Gap 1 geschlossen: alle 11 Secrets migrieren

- **Trigger:** Team-Lead-Entscheidung zu §12 Gap 1.
- **Reasoning:** Der User hat den projektweiten SecretStore explizit gewählt, um den
  ADR-0017-Defer sauber aufzulösen; ein zurückgelassenes Klartext-Device-Secret würde
  das konterkarieren (D4: langfristig sauberste Lösung). Damit gilt der volle
  11er-Umfang, und `adr-secret-store` darf die ADR-0017-§F-3-Auflösung behaupten.
- **What changed:** §7.1 auf verbindliche 11 Slots umgestellt; §12 Gap 1 geschlossen.

### 2026-07-20 — Freshness-Pass (Post-Implementation, vor Archivierung)

- **Trigger:** Integrations-Check nach Abschluss Block A–E (Finding `integ-1`,
  green) — Abgleich der fünf Block-Specs gegen den gebauten Stand vor der
  F-Stage-Archivierung/EN-Übersetzung.
- **Reasoning:** Diese Spec ist as-built korrekt. Der `SecretStore`-Port liegt
  wie §9 spezifiziert in `shared-ai/.../ai/secrets/SecretStore.kt`; die
  Android-Backends (`AndroidKeystoreSecretStore`/`KekProvider`/`SecretsMigration`)
  in `app/.../secrets/`, die Desktop-Backends (`DpapiSecretStore`/
  `FileAesGcmSecretStore`/`SecretStoreModule`) in `companion/.../secrets/`.
  Einzige Ergänzung gegenüber der §9-Dateiliste: `app/.../secrets/PairingSecrets.kt`
  ist zusätzlich entstanden — der Pairing-Secret-Zugriff läuft ebenfalls über den
  SecretStore (ADR-0029), passend zum §7.1-Umfang „alle 11 Secrets".
- **What changed:** Kein Body-Umbau — §9-Directory-Layout um die de-facto
  vorhandene `PairingSecrets.kt` ergänzt vermerkt; sonst keine residuale Drift.

## 14. References

- **Plan:** `~/.claude/plans/desktop-companion-v1.md` — §5 Block B (B1/B2),
  §3 D1 (`:shared-ai`)/D4, §6 `adr-secret-store`/`adr-shared-ai-module`, §7 Sequenz,
  §9 R2/R8.
- **ADRs (bindend):** `docs/decisions/0017-client-server-roles-transport-pairing.md`
  (§4 F-3 Klartext-Secret-Defer, den B löst; Pairing-Auth für §8.3),
  ADR-0015 (Modul-Topologie/Kotlin-Ceiling), ADR-0018 (Port+`available`-Muster,
  `TextInserter`).
- **Konzept:** `docs/plans/2026-07-19 - desktop-companion-v1/research/konzept-skizze.md`
  §4 „Verschlüsselungskonzept" (F11/F12), §2 Modul-Aufteilung.
- **Bestands-Code (file:line):**
  `app/.../preferences/DictatePrefs.kt:74,119-146` (Secret-Prefs),
  `app/.../ai/factory/RunnerFactory.kt:83-103` (zentrale Lese-Naht + Non-ASCII-Strip),
  `app/.../preferences/PrefsMigration.kt` (Migrations-Muster),
  `app/.../settings/WindowsPairingActivity.java:218,286` (Device-Secret set/clear),
  `app/.../preferences/WindowsTarget.kt:39` (Device-Secret-Leser),
  `companion/.../platform/PlatformModule.kt` (`detect()`+`available`-Muster),
  `companion/.../platform/windows/WindowsRegistry.kt` (JNA `Advapi32Util`-Muster),
  `companion/.../data/SqlDelightDeviceRepository.kt:16-24` (nur Secret-Hash),
  `shared/.../auth/Secrets.kt` (SHA-256/constant-time-Primitive),
  `shared/.../SharedPurityTest.kt` (Reinheits-Invariante).
- **Dependencies:** `gradle/libs.versions.toml:66` (`jna=5.19.1`), `142-143`
  (`jna`/`jna-platform`), `companion/build.gradle:58-59`.
- **Konventionen:** `docs/DATABASE-PATTERNS.md`, `~/.claude/snippets/test-first-patterns.md`.
