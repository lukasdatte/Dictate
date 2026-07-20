---
date: 2026-07-19
author: Lukas + Claude (planning session, groundwork agent)
status: Spec — programmer-ready
context: Implementer-ready Spezifikation für Block C des Plans desktop-companion-v1 — Konfigurations-Entitätenmodell (ProviderConfig/ModelRef/ApiCredential/Prompt/Profil) in :shared, kanonischer v3-Codec + contentHash, Room-Migration v11→v12, Prefs→Entitäten-Migration, ProfileResolver und Android-Settings-UI-Umbau.
related-plan: ../desktop-companion-v1.md (liegt bis Archivierung in ~/.claude/plans/desktop-companion-v1.md)
related-adrs: 0012, 0013, 0016, 0024; plan-scoped adr-config-entity-model, adr-secret-store, adr-shared-ai-module
---

# Block C — Konfigurations-Entitätenmodell + Android-Umbau

Diese Spec ist die verbindliche Umsetzungsvorlage für **Block C** des
Desktop-Companion-Plans: sie überführt die heute als lose SharedPreferences-Strings
gespeicherte AI-Konfiguration (Provider, Modelle, Keys, Prompts, Parameter) in ein
teilbares, versioniertes **Entitätenmodell** in `:shared`, definiert die
**kanonische Serialisierung** (die zugleich `contentHash`-Basis und v3-Dateiformat
ist), die **Room-Migration v11→v12**, die **Prefs→Entitäten-Migration** mit
Backup-Rollback, den **ProfileResolver** (der das AiConfig-Port aus Block A
bedient) und den **Settings-UI-Umbau** auf das Entitätenmodell.

Sie ist ausschließlich Block-C-fokussiert. Block A (`:shared-ai`-Extraktion, Ports),
Block B (SecretStore), Block D (Desktop-Host), Block E (Peer-Katalog) werden nur an
ihren Nahtstellen referenziert, nie mitspezifiziert. Wo eine Nahtstelle noch offen
ist, steht sie in §14 Information Gaps.

## Table of Contents

- [Glossar](#glossar)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Ist-Inventar Konfiguration](#3-ist-inventar-konfiguration)
- [§4 Architecture Specification — Entitäten (`:shared`)](#4-architecture-specification--entitäten-shared)
- [§5 Kanonische Serialisierung + contentHash + v3-Format](#5-kanonische-serialisierung--contenthash--v3-format)
- [§6 Directory Layout](#6-directory-layout)
- [§7 Room-Persistenz v11→v12 (`:app`)](#7-room-persistenz-v11v12-app)
- [§8 Prefs→Entitäten-Migration](#8-prefsentitäten-migration)
- [§9 ProfileResolver (AiConfig-Port)](#9-profileresolver-aiconfig-port)
- [§10 Settings-UI-Umbau (C3)](#10-settings-ui-umbau-c3)
- [§11 Migration Plan (Chunk-Schnitt)](#11-migration-plan-chunk-schnitt)
- [§12 Testing Approach](#12-testing-approach)
- [§13 Decision Log](#13-decision-log)
- [§14 Information Gaps](#14-information-gaps)
- [§15 References](#15-references)

## Glossar

**Entitäten & Modell**

- **ConfigEntity** — Oberbegriff der fünf teilbaren Konfigurations-Entitäten
  (`ProviderConfig`, `ApiCredential`, `ModelRef`, `Prompt`, `Profile`). Jede trägt
  eine gemeinsame **Envelope** (§4.1) und einen **Payload** (§4.2).
- **Envelope** — der nicht-inhaltliche Metadaten-Rahmen jeder Entität:
  `id`, `contentHash`, `updatedAt`, `visibility`, `sourceRef` (Provenienz). Wird
  **nicht** in den `contentHash` einbezogen (§5.2).
- **Payload** — die inhaltlichen Felder einer Entität (Name, ModelId, Prompt-Text,
  Parameter …). Genau diese Felder gehen in die kanonische Serialisierung und damit
  in den `contentHash` ein.
- **ProviderConfig** — Anbieter-Definition (`providerType`, `kind`, `baseUrl`,
  `credentialRef`). §4.3.
- **ApiCredential** — Referenz auf ein Secret + Metadaten (`providerType`, `label`,
  `keyFingerprint`). Der Schlüssel-**Klartext** liegt nie in der Entität/DB, nur im
  **SecretStore** (Block B). §4.4.
- **ModelRef** — Modell-Definition (`providerRef`, `modelId`, `function`,
  `parameterDefaults`). §4.5.
- **Prompt** — teilbarer Nachbearbeitungs-Prompt (`name`, `text`,
  `requiresSelection`, `autoApply`) — **ohne** Pill-`type` (§13 D3). §4.6.
- **Profile** — die umschaltbare Einheit (F24): Transcription-/Completion-ModelRef,
  geordnete Prompt-Referenzen, System-/Style-Prompt-Auswahl, `AmbiguityMode`,
  Parameter-Overrides. §4.7.
- **ProviderType** — `:shared`-Spiegel-Enum von `AIProvider` (der in `:shared-ai`
  lebt und daher aus `:shared` nicht referenzierbar ist, §13 D2). Werte-parität via
  Test erzwungen.

**Serialisierung & Sync**

- **Kanonische Serialisierung** — deterministische Byte-Form eines Payloads
  (sortierte Objekt-Keys, definiertes Zahlen-/Null-/String-Handling); Grundlage von
  `contentHash` **und** v3-Dateiformat. §5.
- **contentHash** — `sha256(kanonische Payload-Bytes)`, lowercase Hex. Drift-Detektor
  und Sync-Watermark (F27). §5.2.
- **v3-Format** — `{ "version": 3, "entities": [ … ] }`; die eine Codec-Implementierung
  für Datei-Export **und** Peer-Wire (F23). §5.4.
- **sourceRef** — Provenienz einer bezogenen Kopie: `{ peerId, originalId,
  originalContentHash }` (F14 „Fork + Update-Hinweis"). `null` bei lokal erzeugten
  Entitäten.
- **subscriptionMode** — `LOCAL | SUBSCRIBE | ONE_SHOT`; Bezugs-Zustand einer Kopie.
  In Block C nur schema-seitig angelegt (alle Migrations-Entitäten = `LOCAL`); die
  Sync-Semantik ist Block E.

**Android-Persistenz**

- **Prefs→Entitäten-Migration** — die einmalige, idempotente Überführung der heutigen
  Provider-/Model-/Key-/Parameter-Prefs in ein **Default-Profil** + zugehörige
  Entitäten (§8).
- **Prefs-Backup** — vollständiger JSON-Dump aller SharedPreferences **vor** der
  Migration (Rollback-Pfad, F22/D4.7). §8.4.
- **ProfileResolver** — die Android-Implementierung des `AiConfig`-Ports (Block A):
  liefert die effektive Runner-Konfiguration aus aktivem Profil + Credentials. §9.

> **contentHash ≠ keyFingerprint ≠ sourceRef.originalContentHash.** Der
> `contentHash` identifiziert den **aktuellen** Inhalt einer lokalen Entität; der
> `keyFingerprint` ist ein Schlüssel-Abdruck **innerhalb** des `ApiCredential`-Payloads
> (damit ein Key-Wechsel den Hash ändert, ohne den Key zu zeigen); die
> `sourceRef.originalContentHash` ist der eingefrorene `contentHash` des Herkunfts-Peers
> **zum Zeitpunkt der Übernahme** (Basis für den „Update verfügbar"-Vergleich, Block E).

> **ProviderType ≠ ProviderKind.** `ProviderType` ist der Anbieter-Vendor
> (`OPENAI`, `ANTHROPIC`, `CUSTOM`, …, Spiegel von `AIProvider`). `ProviderKind` ist
> `LOCAL | GATEWAY` — ob der Provider direkt eine Vendor-API anspricht (`LOCAL`) oder
> künftig einen Peer als Gateway nutzt (`GATEWAY`, F31 reserviert, in v1 nicht wählbar).

## 1. Vision and Motivation

### 1.1 Warum dieses Modell existiert

Die gesamte AI-Konfiguration von Dictate lebt heute als ~40 flache
SharedPreferences-Strings (§3.1): zwei Provider-Auswahlen (Transkription +
Rewording), pro Provider ein API-Key im **Klartext**, ein Modell-String, ein
Custom-Host, plus Parameter-Prefs (Temperatur, Max-Tokens, Reasoning-Effort). Diese
Form ist an genau eine Android-Installation gebunden: nicht teilbar, nicht
kombinierbar, nicht versionierbar, und die Keys liegen unverschlüsselt in der
Prefs-XML. Der Desktop-Companion (Block D) und der Peer-Katalog (Block E) brauchen
dieselbe Konfiguration in teilbarer, plattformneutraler Form — die gemeinsame Wurzel
ist ein **entitätenbasiertes Modell** in `:shared`.

### 1.2 Welches Problem das löst

- **Teilbarkeit.** Provider, Modelle, Prompts und (verschlüsselte) Keys werden zu
  Entitäten mit stabiler UUID-Identität und `contentHash` — die Grundlage für
  Datei-Export (v3) und Peer-Sync (Block E).
- **Kombinierbarkeit.** Ein **Profil** bündelt Transcription-Modell,
  Completion-Modell, geordnete Prompts, Parameter und `AmbiguityMode` zu einer
  umschaltbaren Einheit (F17/F24) — statt einer einzigen global-verstreuten
  Konstellation.
- **Sicherheit.** Keys verlassen die Entität/DB als Wert vollständig; der Klartext
  lebt nur im **SecretStore** (Block B). Die Entität hält bloß eine Referenz +
  Fingerprint.
- **Drift-Erkennung geschenkt.** Der `contentHash` über die kanonische Form erkennt
  „lokal editiert?" und „Peer geändert?" ohne Zusatzmechanik (F27).

### 1.3 Discarded Alternatives

- **Prefs behalten, nur Keys verschlüsseln.** Verworfen: löst weder Teilbarkeit noch
  Kombinierbarkeit; der Desktop-Host bräuchte trotzdem ein zweites Konfigurationsmodell
  → doppelte Wahrheit.
- **Entitäten pro Plattform getrennt definieren (Room + SQLDelight je eigenes
  DTO).** Verworfen per Plan-D3: das Serialisierungs-/Hash-Format muss **eine**
  Wahrheit in `:shared` sein, sonst driften Android- und Desktop-Hash auseinander und
  der Peer-Sync bricht. Native Persistenz bleibt plattformeigen (Room/SQLDelight),
  aber die DTOs + Codec sind geteilt.
- **`contentHash` über die kotlinx-Standard-JSON-Ausgabe.** Verworfen: kotlinx-JSON
  garantiert keine sortierte Key-Reihenfolge über Refactorings hinweg und keine
  stabile Float-Darstellung — beides bricht Byte-Identität. Deshalb eine explizite
  Kanonik (§5, JCS-Teilmenge).
- **Feature-Flag „Entitätenmodell aktiv" für sanfte Koexistenz.** Verworfen per F22/
  D4.7 (harte Migration). Rollback-Pfad ist stattdessen der **Prefs-Backup-Export**
  vor der Migration (§8.4).

### 1.4 Was dieses Modell konkret einbringt

1. Eine **einzige** kanonische Serialisierung, die zugleich Hash-Basis, Datei-Export
   und Peer-Wire ist (F23/F27) — kein zweiter Codec.
2. **UUID-Identität + Provenienz** (`sourceRef`), sodass Block E Fork/Update ohne
   Merge-Problem umsetzen kann (F14/F29).
3. **Verlustfreie Android-Migration** mit Backup-Rollback (F22).
4. Ein **Port-sauberer** Auflösungspfad (`ProfileResolver` → `AiConfig`), der die
   Pipeline von der Pref-Kenntnis entkoppelt — dieselbe Naht, die Block D/E nutzen.
5. `GATEWAY`-Pfad **reserviert, nicht gebaut** (F31): Double-Enum erlaubt die spätere
   Migration sauber.

## 1a. Architecture Walkthrough

### 1a.0 Schicht-Diagramm

```
┌─────────────────────────────────────────────────────────────────────┐
│  :shared/config/ (NEU)                                     (unten)  │
│  ConfigEntity-DTOs (@Serializable) + Konform Validation<T>          │
│  + CanonicalJson + contentHash + CatalogCodec (v3)                  │
│  Reine JVM, jvmTarget 1.8, kein Android/okhttp-Leak (SharedPurity)  │
└─────────────────────────────────────────────────────────────────────┘
        ▲ referenziert DTOs                    ▲ referenziert DTOs
        │                                      │
┌───────┴──────────────────────────┐  ┌────────┴──────────────────────────┐
│  :app/config/ (NEU, Block C)     │  │  :companion (Block D/E)           │
│  Room-Tabellen v12 + Mapper      │  │  SQLDelight-Spiegel (out of scope) │
│  ConfigEntityMigration           │  └────────────────────────────────────┘
│  ProfileResolver → AiConfig      │
│  (implementiert Port aus Block A)│
└───────┬──────────────────────────┘
        │ liefert AiConfig
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  :shared-ai (Block A)                                               │
│  RunnerFactory/AIOrchestrator lesen AiConfig statt SharedPreferences │
│  AIProvider, ParameterRegistry, PromptTypeClassifier                 │
└─────────────────────────────────────────────────────────────────────┘
```

> [!IMPORTANT]
> **Layering-Constraint (load-bearing).** `:shared` liegt **unter** `:shared-ai`.
> `AIProvider`, `ParameterRegistry`, `PromptTypeClassifier`, `AmbiguityMode` (heute in
> `:app`, ziehen per Block A in `:shared-ai`) sind aus `:shared` **nicht**
> referenzierbar. Die Entitäten in `:shared` definieren deshalb eigene Spiegel-Enums
> (`ProviderType`, `ModelFunction`, `AmbiguityModeValue`) mit Paritäts-Tests gegen die
> `:shared-ai`-Originale (§4.8, §13 D2). Wer diese Regel bricht, erzeugt eine
> Zyklus-Abhängigkeit, die `SharedPurityTest`/Gradle sofort verweigert.

### 1a.1 Auflösungs-Fluss (eine Transkription)

```
DictateInputMethodService (Aufnahme fertig)
    → AIOrchestrator.transcribe(...)            [:shared-ai, Block A]
        → factory: RunnerFactory(aiConfig)       aiConfig = ProfileResolver [:app, C2]
            → aiConfig.provider(TRANSCRIPTION)
                : ProfileResolver liest ActiveProfileId (Pref)
                → profiles[activeId].transcriptionModelRef
                → model_refs[ref].providerRef
                → provider_configs[pref].providerType     : ProviderType
            → aiConfig.apiKey(...)
                → provider_configs[pref].credentialRef
                → SecretStore.get(SecretRef(credentialId)) : ByteArray?  [Block B]
            → aiConfig.baseUrl(...) = providerConfig.baseUrl ?: providerType.default
            → aiConfig.parameters(...) = modelRef.parameterDefaults
                                          ⊕ profile.parameterOverrides
```

### 1a.2 Read-this-before-implementing Checklist

- [ ] JEDE neue `@Serializable`-Config-DTO bekommt eine co-lokierte `Validation<T>`
  in `:shared/config/ConfigValidations.kt` und läuft nur durch den `CatalogCodec`
  (ADR-0016-Muster, §5.4).
- [ ] JEDE Finite-Set-Spalte (Room) = Kotlin-Enum + SQL-`CHECK` (Double-Enum,
  docs/DATABASE-PATTERNS.md). Betrifft `provider_type`, `kind`, `function`,
  `ambiguity_mode`, `system_prompt_mode`, `style_prompt_mode`, `visibility`,
  `subscription_mode` (§7.2).
- [ ] `contentHash` wird bei **jedem** Schreibpfad (create/edit/import/migration) neu
  berechnet, nie aus einer externen Quelle übernommen (§5.3). Denormalisierter Cache
  im Sinne von docs/DATABASE-PATTERNS.md „Denormalized Cache Columns".
- [ ] Kein `Json.encodeToString`/`decodeFromString` für Config-Payloads außerhalb des
  `CatalogCodec`/`CanonicalJson` (ADR-0016).
- [ ] Klartext-Keys nie in eine Config-Tabelle schreiben — nur SecretRef +
  Fingerprint (§4.4, F12).
- [ ] `GATEWAY` als `ProviderKind`-Wert existiert, ist aber in v1 **nicht wählbar**;
  `providerConfig`-Validation lehnt Erzeugung mit `kind = GATEWAY` ab (aktiver Test,
  §12).
- [ ] Prompt-Pill-`type` (PROMPT/TEXT, ADR-0024) bleibt eine Android-Room-only-Spalte;
  die geteilte `Prompt`-Entität kennt sie nicht (§13 D3).
- [ ] Prefs→Entitäten-Migration ist idempotent (Flag-gated) und schreibt **vorher**
  das Prefs-Backup (§8.4).

## 2. Acceptance Criteria

1. **Entitäten + Codec (`:shared`).** Die fünf `@Serializable`-DTOs (§4) existieren in
   `shared/src/main/kotlin/net/devemperor/dictate/shared/config/`, jede mit
   co-lokierter `Validation<T>`; `CatalogCodec.encode/decode` ist die einzige
   v3-Tür. `SharedPurityTest` bleibt grün (kein Android/okhttp-Import).
2. **Kanonik-Stabilität.** Snapshot-Tests fixieren die Byte-Identität der kanonischen
   Form je Entitätstyp. `contentHash` erfüllt: gleicher Payload ⇒ gleicher Hash;
   Reihenfolge der Deklaration/Key-Position irrelevant ⇒ gleicher Hash; jede
   Werteänderung ⇒ neuer Hash (§12).
3. **v3-Round-Trip.** `CatalogCodec` round-trippt v3→v3 byte-stabil; v1/v2-Prompt-Dateien
   sind über den Android-Legacy-Pfad (§10.4) importierbar (ADR-0024-Regeln erhalten).
4. **Room v11→v12.** Migration erzeugt `provider_configs`, `api_credentials`,
   `model_refs`, `profiles`, `profile_prompts` und erweitert `prompts` um die
   Provenienz-Spalten; alle Finite-Set-Spalten tragen `CHECK`-Constraints;
   `MigrationTo12Test` (Instrumented) prüft Annahme gültiger + Ablehnung ungültiger
   Enum-Werte.
5. **Prefs→Entitäten verlustfrei.** `ConfigEntityMigrationTest` mit einer befüllten
   v11-Fixture (alle Provider-Slots, Keys, Modelle, Parameter, Prompts) erzeugt ein
   Default-Profil, dessen Auflösung über `ProfileResolver` **byte-gleich** dieselbe
   Runner-Konfiguration liefert wie der alte pref-basierte `RunnerFactory` (§9.4
   Charakterisierungs-Test).
6. **Key-Sicherheit.** Nach der Migration enthält keine Config-Tabelle einen
   Klartext-Key; die migrierten `*ApiKey*`-Prefs sind aus SharedPreferences entfernt
   (grep-Test auf die Pref-Konstanten, gemeinsam mit Block B2).
7. **Backup vorhanden.** Vor der Migration liegt ein vollständiger Prefs-Backup-JSON
   in App-privatem Speicher; ein zweiter Migrationslauf ist ein No-Op (Idempotenz-Test).
8. **UI-Umbau.** `APISettingsActivity` (bzw. Nachfolge-Screens) arbeiten
   entitätenbasiert (Provider → Modelle → Profile), inkl. Duplizieren/Verschieben der
   Profil-Liste analog `PromptsOverview`; kein UI-Code referenziert mehr eine der
   migrierten Pref-Konstanten (grep-Test); Robolectric-Smoke für die neue Navigation
   grün.
9. **Pill-Parität.** Das Prompt-Pill-Verhalten am Phone (ADR-0024) ist unverändert:
   `prompts.type` existiert weiter, `PromptsOverview` verhält sich identisch plus
   Herkunfts-Badge.

## 3. Ist-Inventar Konfiguration

### 3.1 DictatePrefs — Migrations-Kategorisierung

Quelle: `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt:12-317`.
Kategorie „→ Entität" = wandert per §8 ins Entitätenmodell; „Global" = bleibt
SharedPreferences (F17: gerätegebunden/UX, nicht teil des teilbaren Profils).

| Pref (Konstante) | Typ | Zweck | Kategorie |
|---|---|---|---|
| `TranscriptionProvider` | String | aktiver Transkriptions-Provider | → Entität (Default-Profil → transcription ModelRef → ProviderConfig) |
| `RewordingProvider` | String | aktiver Completion-Provider | → Entität (completion ModelRef → ProviderConfig) |
| `TranscriptionApiKeyOpenAI/Groq/Custom/OpenRouter/ElevenLabs` | String | Klartext-Keys | → **SecretStore** + ApiCredential (§8.2, Block B2) |
| `RewordingApiKeyOpenAI/Groq/Anthropic/OpenRouter/Custom` | String | Klartext-Keys | → **SecretStore** + ApiCredential |
| `TranscriptionOpenAIModel/GroqModel/ElevenLabsModel/CustomModel` | String | Modell-IDs | → ModelRef.modelId (function=TRANSCRIPTION) |
| `RewordingOpenAIModel/GroqModel/AnthropicModel/OpenRouterModel/CustomModel` | String | Modell-IDs | → ModelRef.modelId (function=COMPLETION) |
| `TranscriptionCustomHost` / `RewordingCustomHost` | String | Custom-Base-URL | → ProviderConfig.baseUrl |
| `ElevenLabsKeytermsRaw` / `ElevenLabsKeytermsParsed` | String | ElevenLabs Key-Terms | → ModelRef.parameterDefaults (Transcription, §4.5) |
| `TemperatureOpenAI/Groq/Anthropic/OpenRouter` | Float | Parameter-Override | → ModelRef.parameterDefaults / Profile.parameterOverrides (§8.3) |
| `MaxTokensOpenAI/Groq/Anthropic/OpenRouter` | Int | Parameter-Override | → ModelRef.parameterDefaults / Profile.parameterOverrides |
| `ReasoningEffortOpenAI` | String | Parameter-Override | → ModelRef.parameterDefaults / Profile.parameterOverrides |
| `StylePromptSelection` / `StylePromptCustomText` | Int/String | Transkriptions-Style-Prompt (PromptMode) | → Profile.stylePromptMode/-CustomText |
| `SystemPromptSelection` / `SystemPromptCustomText` | Int/String | Rewording-System-Prompt (PromptMode) | → Profile.systemPromptMode/-CustomText |
| `AmbiguityMode` | String | Prüfmodus (ADR-0013) | → Profile.ambiguityMode |
| `ProxyEnabled` / `ProxyHost` | Bool/String | Proxy | **Global** (Port `ProxyConfig`, Block A — nicht Teil des Profils) |
| `RewordingEnabled`, `AutoFormattingEnabled`, `InstantOutput`, `AutoEnter*`, `InstantRecording`, `ResendButton`, `Vibration`, `AudioFocus`, `UseBluetoothMic`, `Animations`, `SmallMode`, `SingleRowMode`, `AccessibilityContextEnabled` | div. | IME-Feature-Toggles | **Global** |
| `Theme`, `AccentColor`, `AppLanguage`, `OverlayCharacters`, `OutputSpeed`, `WidgetOpacity`, `HistoryPanelHeightDp`, `Overlay*` | div. | UI/Theme/Overlay | **Global** |
| `Windows*` (5 Prefs) | div. | PC-Dispatch-Pairing (ADR-0017) | **Global** |
| `UserId`, `OnboardingComplete`, `LastVersionCode`, `Flag*`, `InputLanguages`, `InputLanguagePos` | div. | System/State | **Global** |
| `LastFileName`, `TranscriptionAudioFile`, `QueuedPromptIds`, `LegacyAudioPurgedV4`, Cleanup-/Rolling-/Cache-Prefs | div. | interner Pipeline-State | **Global** |
| **NEU** `ActiveProfileId` | String | Zeiger aufs aktive Profil | **Global** (Pointer, kein teilbarer Inhalt) |
| **NEU** `ConfigEntityMigrationDone` | Int | Idempotenz-Flag der §8-Migration | **Global** |

> [!NOTE]
> **Proxy bleibt global.** Der Proxy ist Geräte-/Netz-Kontext, kein teilbares
> Profil-Attribut, und wird in Block A ohnehin über den Port `ProxyConfig` gelesen.
> Er wandert **nicht** in eine Entität. Falls sich später ein Bedarf zeigt, ihn pro
> Profil zu setzen, ist das eine neue Entscheidung (§14).

### 3.2 APISettingsActivity — Ist-Struktur

Quelle: `app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java`
(783 Zeilen). Zwei parallele, fast identische Sektionen:

- **Transcription-Sektion** (`setupTranscriptionSection`, Z. 155-308): Provider-Spinner
  (`AIProvider.withTranscription()`), API-Key-`EditText` (Watcher → Pref), Custom-Host/
  Model-Felder, Modell-Spinner (hardcoded ODER `ModelFetcher.fetchModels`), pro
  Provider ein eigener Key-/Model-Pref (`getTranscriptionApiKeyPref`,
  `getSavedTranscriptionModel`).
- **Rewording-Sektion** (`setupRewordingSection`, Z. 312-455): analog, plus dynamische
  **Parameter-UI** (`updateParameterUI`, Z. 465-500) aus `ParameterRegistry`
  gegen `PARAM_PREFS`-Map (FLOAT_RANGE-SeekBar, INT_RANGE-EditText, ENUM-Spinner,
  `mutuallyExclusiveWith`-Logik).

Jedes Feld schreibt **direkt** in eine Pref via `DictatePrefsKt.put`. Der Umbau (§10)
ersetzt diese Direkt-Schreibpfade durch Entitäts-CRUD, behält aber `ModelFetcher`,
`ParameterRegistry` und `ParameterDef`-Rendering als Bausteine.

### 3.3 Runner-Config-Auflösung (Ist)

Quelle: `app/.../ai/factory/RunnerFactory.kt` + `ai/AIOrchestrator.kt`. Der
`RunnerFactory` liest heute **direkt** aus `SharedPreferences`:

- `getProvider(function)` ← `Pref.TranscriptionProvider` / `Pref.RewordingProvider`
  (RunnerFactory.kt:50-56)
- `getModelName(function)` ← per-Provider Model-Pref (RunnerFactory.kt:58-81)
- `getApiKey(provider, function)` ← per-(Provider,Function) Key-Pref (RunnerFactory.kt:83-103)
- `getBaseUrl(provider, function)` ← `CustomHost`-Pref für CUSTOM, sonst
  `provider.defaultBaseUrl` (RunnerFactory.kt:105-113)
- Parameter: `AIOrchestrator.resolveParameters` ← `ParameterRegistry` ∩ `PARAMETER_PREFS`
  (AIOrchestrator.kt:168-205), Sentinel `-1`/`""` = Server-Default

**Genau dieser Auflösungspfad** ist das Ziel des `AiConfig`-Ports (Block A) und wird in
§9 durch den `ProfileResolver` byte-gleich nachgebildet.

### 3.4 PromptEntity / Schema v11 / PromptImportExport

- Room-Version **11** (`DictateDatabase.kt:51`), Schema-Asset `app/schemas/…/11.json`.
- `prompts`-Tabelle (11.json): `id INTEGER PK AUTOINCREMENT, pos, name, prompt,
  requires_selection, auto_apply, type TEXT NOT NULL` mit
  `CHECK (type IN ('PROMPT','TEXT'))` (ADR-0024, `MIGRATION_10_11`).
- `PromptEntity.kt` — Double-Enum `type`/`typeEnum` (PromptType PROMPT|TEXT).
- **PromptImportExport.java** — `EXPORT_VERSION = 2`. Export: `{version:2, prompts:[
  {name, prompt, requiresSelection, autoApply, type}]}`. Import akzeptiert v2
  (type verbatim, normalisiert) und v1 (kein `type` → `PromptTypeClassifier.classify`).
  TEXT-Pills werden bei Import auf `requiresSelection=false, autoApply=false` geklemmt.

> [!IMPORTANT]
> ADR-0024 bleibt in Block C **unangetastet**. `prompts.type`, `PromptTypeClassifier`,
> die v1/v2-Import-Regeln und das Pill-Pressverhalten ändern sich nicht. Der v3-Codec
> ist additiv; v1/v2-Import läuft weiter über den bestehenden Android-Pfad (§10.4).

## 4. Architecture Specification — Entitäten (`:shared`)

Alle Typen leben in
`shared/src/main/kotlin/net/devemperor/dictate/shared/config/`, sind
`@Serializable` (kotlinx-serialization, bereits `api`-Dependency von `:shared`,
build.gradle:31) und tragen eine co-lokierte `Validation<T>` in
`ConfigValidations.kt` (Konform 0.11.1, ADR-0016-Muster).

### 4.1 Envelope + gemeinsame Werttypen

```kotlin
package net.devemperor.dictate.shared.config

import kotlinx.serialization.Serializable

/** Provenienz einer bezogenen Kopie (F14). null bei lokal erzeugten Entitäten. */
@Serializable
data class SourceRef(
    val peerId: String,
    val originalId: String,
    /** contentHash des Originals zum Zeitpunkt der Übernahme — Basis des „Update"-Vergleichs. */
    val originalContentHash: String,
)

@Serializable
enum class Visibility { PRIVATE, SHARED }

/** Bezugs-Zustand. In Block C immer LOCAL; SUBSCRIBE/ONE_SHOT-Semantik ist Block E. */
@Serializable
enum class SubscriptionMode { LOCAL, SUBSCRIBE, ONE_SHOT }
```

Die Envelope-Felder werden **nicht** als eigenes Wrapper-Objekt modelliert, sondern
als gleichnamige Felder jeder Entität (`id`, `contentHash`, `updatedAt`,
`visibility`, `sourceRef`, `subscriptionMode`). Grund: die kanonische Serialisierung
schließt sie über eine **feste Feld-Namensliste** aus (§5.2) — ein Wrapper würde die
Payload verschachteln und die Kanonik verkomplizieren.

### 4.2 Payload-vs-Envelope-Konvention

Jede ConfigEntity trennt intern **Payload** (hash-relevant) von **Envelope**
(Metadaten). Umgesetzt über eine gemeinsame Konstante:

```kotlin
/** Feldnamen, die aus der kanonischen (hash-relevanten) Form ausgeschlossen werden. */
val ENVELOPE_FIELDS: Set<String> =
    setOf("id", "contentHash", "updatedAt", "visibility", "sourceRef", "subscriptionMode")
```

`CanonicalJson` (§5.1) entfernt diese Keys rekursiv auf der obersten Objekt-Ebene der
Entität vor dem Hashen. So bleibt der `contentHash` stabil, egal ob eine Kopie eine
andere `id`, `visibility` oder `sourceRef` hat — genau die Eigenschaft, die Block E
für Fork-Dedup braucht.

### 4.3 ProviderConfig

```kotlin
@Serializable
data class ProviderConfigEntity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val providerType: ProviderType,
    /** LOCAL = direkte Vendor-API; GATEWAY reserviert (F31), in v1 nicht wählbar. */
    val kind: ProviderKind = ProviderKind.LOCAL,
    val label: String,
    /** null → providerType.defaultBaseUrl; nur für CUSTOM/GATEWAY inhaltlich relevant. */
    val baseUrl: String? = null,
    /** uuid einer ApiCredentialEntity, oder null (z. B. lokaler Custom-Endpoint ohne Key). */
    val credentialRef: String? = null,
)
```

### 4.4 ApiCredential

```kotlin
@Serializable
data class ApiCredentialEntity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val providerType: ProviderType,
    val label: String,
    /**
     * Abdruck des Schlüssels: sha256(key)-Hex, erste 16 Zeichen. Damit ändert ein
     * Key-Wechsel den contentHash, OHNE dass der Key im Payload/Index steht (F12).
     * Der Klartext-Key liegt AUSSCHLIESSLICH im SecretStore unter SecretRef(id).
     */
    val keyFingerprint: String,
)
```

> [!CAUTION]
> Es gibt **kein** Feld für den Schlüsselwert. Der Klartext lebt nur im SecretStore
> (Block B), adressiert über `SecretRef` = die `id` dieser Entität. Ein Reviewer, der
> hier ein `apiKey`/`secret`-Feld findet, hat einen F12-Verstoß gefunden.

### 4.5 ModelRef

```kotlin
@Serializable
data class ModelRefEntity(
    // ── Envelope ── (id, contentHash, updatedAt, visibility, sourceRef, subscriptionMode)
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val providerRef: String,           // uuid einer ProviderConfigEntity
    val modelId: String,               // z. B. "gpt-4o-mini"
    val function: ModelFunction,       // TRANSCRIPTION | COMPLETION
    val label: String? = null,
    /**
     * Parameter-Defaults als kanonische String-Werte (sortiert), z. B.
     * {"temperature":"0.7","max_tokens":"4096"}. String statt Zahl bewusst — vermeidet
     * IEEE-754-Kanonik (§5.1). Interpretation über ParameterRegistry (Block A).
     * Transcription-spezifisch: {"keyterms":"<parsed-json>"} für ElevenLabs.
     */
    val parameterDefaults: Map<String, String> = emptyMap(),
)
```

### 4.6 Prompt

```kotlin
@Serializable
data class PromptV3Entity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val name: String,
    val text: String,
    val requiresSelection: Boolean = false,
    val autoApply: Boolean = false,
)
```

> [!NOTE]
> **Kein Pill-`type`.** Die geteilte Prompt-Entität modelliert nur den teilbaren
> Nachbearbeitungs-Prompt. Der Android-`prompts.type` (PROMPT/TEXT, ADR-0024) bleibt
> eine Room-only-Spalte; TEXT-Pills sind literale Snippets, kein teilbarer AI-Prompt,
> und werden nicht als `PromptV3Entity` exportiert (§13 D3). Beim v3-Import entsteht
> eine Android-`prompts`-Zeile mit `type = PROMPT`.

### 4.7 Profile

```kotlin
@Serializable
data class ProfilePromptRef(
    val promptRef: String,   // uuid → PromptV3Entity (bzw. Android prompts.uuid, §7.3)
    val autoApply: Boolean = false,
)

@Serializable
enum class PromptSelectionMode { NONE, PREDEFINED, CUSTOM }  // Spiegel von PromptMode (0/1/2)

@Serializable
data class ProfileEntity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload (F17) ──
    val name: String,
    val transcriptionModelRef: String? = null,  // uuid → ModelRefEntity (function=TRANSCRIPTION)
    val completionModelRef: String? = null,      // uuid → ModelRefEntity (function=COMPLETION)
    /** Geordnete Nachbearbeitungs-Prompts (Reihenfolge signifikant für den Hash). */
    val orderedPrompts: List<ProfilePromptRef> = emptyList(),
    val stylePromptMode: PromptSelectionMode = PromptSelectionMode.PREDEFINED,
    val stylePromptCustomText: String = "",
    val systemPromptMode: PromptSelectionMode = PromptSelectionMode.PREDEFINED,
    val systemPromptCustomText: String = "",
    val ambiguityMode: AmbiguityModeValue = AmbiguityModeValue.ALWAYS_INSERT,
    /** Completion-Parameter-Overrides über die ModelRef-Defaults hinaus. */
    val parameterOverrides: Map<String, String> = emptyMap(),
)
```

> [!NOTE]
> `is_active` ist **kein** Profil-Feld (es ist kein teilbarer Inhalt und würde den
> Hash verunreinigen). Das aktive Profil ist ein globaler Pointer `Pref.ActiveProfileId`
> (§3.1, §9.2).

### 4.8 Spiegel-Enums + Paritäts-Anforderung

Wegen des Layering-Constraints (§1a.0) definiert `:shared` eigene Enums; die Parität
zu den `:shared-ai`/`:app`-Originalen wird per Test erzwungen:

```kotlin
@Serializable enum class ProviderType { OPENAI, GROQ, ANTHROPIC, ELEVENLABS, OPENROUTER, CUSTOM }
@Serializable enum class ProviderKind { LOCAL, GATEWAY }   // GATEWAY reserviert (F31)
@Serializable enum class ModelFunction { TRANSCRIPTION, COMPLETION }
@Serializable enum class AmbiguityModeValue { ALWAYS_INSERT, AUTO, ALWAYS_REVIEW }
```

Paritäts-Tests (im Modul, das beide Seiten sieht — `:shared-ai` oder `:app`):
- `ProviderType.entries.map{it.name}` == `AIProvider.entries.map{it.name}`
  (AIProvider hat exakt diese 6 Werte, AIProvider.kt).
- `AmbiguityModeValue.entries.map{it.name}` == `AmbiguityMode.entries.map{it.persistKey}`.
- `ModelFunction.entries.map{it.name}` == `AIFunction.entries.map{it.name}`.
- `PromptSelectionMode.entries` korrespondiert mit `PromptMode.value` 0/1/2.

Mapper (in `:shared-ai`/`:app`, nicht in `:shared`): `AIProvider.toProviderType()` /
`ProviderType.toAIProvider()` etc.

> [!IMPORTANT]
> **ENTSCHIEDEN (Plan §3 D5.a, 2026-07-20):** Der Spiegel-Ansatz dieses
> Abschnitts ist verbindlich — `AIProvider`/`AmbiguityMode`/`AIFunction`
> bleiben in `:shared-ai` (Block A führt bewusst keine
> `:shared-ai`→`:shared`-Kante ein), `:shared` definiert die Wire-Enums
> selbst. Das entspricht der bestehenden Wire-vs-Domain-Doktrin
> (ADR-0016: `SessionOriginWire` ↔ `SessionOrigin` + Mapper +
> Paritäts-Tests). Die früher hier notierte Alternative (Originale nach
> `:shared` verschieben) ist verworfen; Paritäts-Tests + Mapper leben in
> `:app` und sind Pflicht-Gate. Details: §13 D6, Plan §3 D5.a.

## 5. Kanonische Serialisierung + contentHash + v3-Format

### 5.1 CanonicalJson — die deterministische Byte-Form

Die kanonische Form folgt einer **Teilmenge von RFC 8785 (JSON Canonicalization
Scheme)** — genug für Byte-Stabilität, ohne die volle Number-Kanonik zu brauchen
(weil alle nicht-ganzzahligen Werte als Strings modelliert sind, §4.5):

1. **Serialisiere** den Entitäts-Payload mit kotlinx zu einem `JsonElement`.
2. **Entferne** rekursiv auf der Top-Objekt-Ebene die `ENVELOPE_FIELDS` (§4.2).
3. **Kanonisiere** den `JsonElement`-Baum:
   - `JsonObject`: Member nach Key **sortiert**, Sortierung = Vergleich der
     UTF-16-Code-Unit-Sequenzen der Key-Strings (Kotlin `String.compareTo`, entspricht
     JCS).
   - `JsonArray`: Reihenfolge **erhalten** (signifikant — z. B. `orderedPrompts`).
   - `JsonPrimitive` String: JSON-Minimal-Escaping (RFC 8259 §7; nur die
     Pflicht-Escapes `" \ \b \f \n \r \t` und `\u00XX` für Steuerzeichen < 0x20;
     keine unnötigen `\u`-Escapes).
   - `JsonPrimitive` Zahl: nur **Ganzzahlen** kommen vor → dezimal ohne Vorzeichen-Plus,
     ohne führende Nullen, ohne Exponent. (Fließkommazahlen existieren im Modell nicht;
     Temperatur o. Ä. sind Strings in `parameterDefaults`/`parameterOverrides`.)
   - `Boolean`/`null`: `true`/`false`/`null` (letzteres nur, falls ein nullbares
     Payload-Feld gesetzt ist — siehe 4.).
4. **Null-Handling:** `explicitNulls = false` — ein `null`-Payload-Feld ist ein
   **abwesender** Key, kein `"feld":null`. Defaults werden mit `encodeDefaults = true`
   materialisiert, damit der Hash nicht davon abhängt, ob ein Wert explizit oder per
   Default gesetzt wurde.
5. **Emit** kompakt (kein Whitespace), **UTF-8**-Bytes.

```kotlin
object CanonicalJson {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun <T> canonicalBytes(value: T, serializer: KSerializer<T>): ByteArray {
        val tree = json.encodeToJsonElement(serializer, value)
        val stripped = stripEnvelope(tree)           // entfernt ENVELOPE_FIELDS top-level
        return canonicalize(stripped).toByteArray(Charsets.UTF_8)
    }
    // canonicalize: rekursiv, Objekt-Keys sortiert, Arrays in-order, Minimal-Escaping
}
```

> [!WARNING]
> `encodeDefaults` und `explicitNulls` müssen **exakt** so gesetzt sein und dürfen sich
> zwischen `:app` (Android) und `:companion` (Desktop) nie unterscheiden — sonst
> driften die Hashes und der Peer-Sync (Block E) bricht. Deshalb lebt `CanonicalJson`
> als **einzige** Instanz in `:shared`, nicht als per-Plattform-Kopie.

### 5.2 contentHash

```kotlin
fun <T> contentHash(value: T, serializer: KSerializer<T>): String {
    val bytes = CanonicalJson.canonicalBytes(value, serializer)
    return MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }   // lowercase Hex, 64 Zeichen
}
```

Da die `ENVELOPE_FIELDS` entfernt sind, gilt automatisch: zwei Entitäten mit gleichem
Payload, aber verschiedener `id`/`visibility`/`sourceRef` haben **denselben**
`contentHash`. Das ist die von F27/Block E gewünschte Eigenschaft (Fork-Dedup,
Drift-Erkennung).

### 5.3 Recompute-on-write-Invariante

`contentHash` und `updatedAt` sind **denormalisierte Cache-Spalten** (docs/DATABASE-PATTERNS.md
„Denormalized Cache Columns"). Regel:

- Bei **jedem** Schreibpfad (create, edit, import, migration) wird `contentHash` neu aus
  dem aktuellen Payload berechnet, nie aus einer Datei/einem Peer übernommen.
- Beim **Import** (v3-Datei oder Peer): den mitgelieferten `contentHash` neu berechnen
  und mit dem Datei-Wert vergleichen; bei Abweichung → Warnung/Ablehnung (Integritäts-
  Check, Block E verschärft das für Peers).
- `updatedAt = System.currentTimeMillis()` bei jeder inhaltlichen Änderung.

Der Schreib-Choke-Point auf Android ist ein `ConfigRepository` (§7.4), das vor jedem
DAO-`upsert` `contentHash`/`updatedAt` setzt — analog zu `SessionManager` in den
Denormalized-Cache-Regeln.

### 5.4 v3-Format + CatalogCodec

```kotlin
@Serializable
data class CatalogFileV3(
    val version: Int = 3,
    val entities: List<CatalogEntry>,
)

/** Getaggte Union über den kind-Diskriminator; die eine Codec-Tür (ADR-0016). */
@Serializable
sealed interface CatalogEntry {
    @Serializable @SerialName("provider")   data class Provider(val entity: ProviderConfigEntity) : CatalogEntry
    @Serializable @SerialName("credential") data class Credential(val entity: ApiCredentialEntity) : CatalogEntry
    @Serializable @SerialName("model")      data class Model(val entity: ModelRefEntity) : CatalogEntry
    @Serializable @SerialName("prompt")     data class Prompt(val entity: PromptV3Entity) : CatalogEntry
    @Serializable @SerialName("profile")    data class Profile(val entity: ProfileEntity) : CatalogEntry
}

object CatalogCodec {
    /** Validiert + serialisiert; wirft ProtocolViolationException bei Verstoß (ADR-0016). */
    fun encode(file: CatalogFileV3): String
    /** Deserialisiert + validiert; DecodeResult<CatalogFileV3> (Ok/Malformed/Invalid). */
    fun decode(raw: String): DecodeResult<CatalogFileV3>
}
```

- Der **v3-Datei-Export** (SAF, §10.5) und das **Peer-Wire** (Block E) nutzen
  **denselben** `CatalogCodec` — das ist die „eine Codec-Implementierung" aus F23.
- Der Export-Datei-Body wird über `CanonicalJson` erzeugt (byte-reproduzierbar); die
  per-Entity-`contentHash`-Felder tragen den über den **Payload** berechneten Hash
  (§5.2), sodass ein Empfänger sie unabhängig nachrechnen kann.
- `ApiCredentialEntity` im Katalog trägt nur Metadaten (`keyFingerprint`), **nie** den
  Schlüsselwert (F12). Die Secret-Auslieferung ist ein separater, autorisierter Call in
  Block E — nicht Teil der v3-Datei.

> [!IMPORTANT]
> **v1/v2-Prompt-Dateien laufen NICHT durch `CatalogCodec`.** Sie sind Legacy-Prompt-
> Exporte (ADR-0024) und werden vom Android-Import-Dispatcher (§10.4) an den
> bestehenden `PromptImportExport`-Pfad geroutet. `CatalogCodec` ist ausschließlich
> v3. Damit bleibt die Pill-Klassifikation (ADR-0024) exakt erhalten und der v3-Codec
> sauber (ein Format).

## 6. Directory Layout

```
shared/src/main/kotlin/net/devemperor/dictate/shared/config/          [NEW]  (C1)
├── Entities.kt                       [NEW]  die fünf @Serializable-DTOs + Envelope-Typen
├── ConfigEnums.kt                    [NEW]  ProviderType, ProviderKind, ModelFunction,
│                                            AmbiguityModeValue, PromptSelectionMode, Visibility, SubscriptionMode
├── ConfigValidations.kt             [NEW]  Konform Validation<T> je DTO (ADR-0016)
├── CanonicalJson.kt                  [NEW]  kanonische Byte-Form + ENVELOPE_FIELDS
├── ContentHash.kt                    [NEW]  sha256-Hex über CanonicalJson
└── CatalogCodec.kt                   [NEW]  v3-Format (CatalogFileV3, CatalogEntry, encode/decode)

shared/src/test/kotlin/net/devemperor/dictate/shared/config/          [NEW]
├── CanonicalJsonTest.kt              [NEW]  Byte-Snapshots, Key-Sort, Envelope-Ausschluss
├── ContentHashTest.kt                [NEW]  Determinismus-Matrix (§12)
├── ConfigValidationsTest.kt          [NEW]  je DTO ≥1 Verstoß + GATEWAY-Ablehnung
└── CatalogCodecTest.kt               [NEW]  v3-Round-Trip, Malformed/Invalid

app/src/main/java/net/devemperor/dictate/config/                      [NEW]  (C2)
├── entity/                           [NEW]  Room-Entities: ProviderConfigRoom, ApiCredentialRoom,
│                                            ModelRefRoom, ProfileRoom, ProfilePromptRoom + Enum-Klassen
├── dao/                              [NEW]  ProviderConfigDao, ApiCredentialDao, ModelRefDao,
│                                            ProfileDao, ProfilePromptDao
├── ConfigEntityMapper.kt            [NEW]  Room-Row ⇄ :shared-DTO (dünne Mapper, Vorbild SessionEntityMapper)
├── ConfigRepository.kt              [NEW]  Schreib-Choke-Point: setzt contentHash/updatedAt (§5.3)
├── ConfigEntityMigration.kt         [NEW]  Prefs→Entitäten, Backup, Idempotenz (§8)
├── PrefsBackup.kt                    [NEW]  vollständiger Prefs→JSON-Dump (§8.4)
└── ProfileResolver.kt               [NEW]  implementiert AiConfig-Port (Block A) (§9)

app/src/main/java/net/devemperor/dictate/database/migration/
└── MigrationTo12.kt                  [NEW]  v11→v12 (§7.2)

app/src/main/java/net/devemperor/dictate/database/
├── DictateDatabase.kt               [EDIT] version=12, +5 Entities, +MIGRATION_11_12
app/schemas/net.devemperor.dictate.database.DictateDatabase/
└── 12.json                          [NEW]  exportiertes Schema v12 (KSP-generiert)

app/src/main/java/net/devemperor/dictate/preferences/
├── DictatePrefs.kt                  [EDIT] +ActiveProfileId, +ConfigEntityMigrationDone
app/src/main/java/net/devemperor/dictate/settings/
├── APISettingsActivity.java         [EDIT/REPLACE] Umbau auf Entitätenmodell (§10)
├── ProvidersActivity.*              [NEW]  Provider-Verwaltung (§10.1)
├── ProfilesActivity.*               [NEW]  Profil-Liste + Editor (§10.3)
app/src/main/java/net/devemperor/dictate/rewording/
├── PromptsOverviewActivity.java     [EDIT] Herkunfts-Badge (§10.6)
├── PromptImportExport.java          [KEEP] v1/v2-Legacy-Import unverändert (§10.4)
```

**File-Delta:** ~6 neue `:shared`-Dateien + 4 Tests · ~10 neue `:app/config`-Dateien ·
1 neue Migration + Schema-Asset · 1 Pref-Edit · 3-4 neue/umgebaute Settings-Screens.

## 7. Room-Persistenz v11→v12 (`:app`)

### 7.1 Grundsatz

Die `:shared`-DTOs sind **nicht** Room-Entities (Room kann keine `:shared`-Klassen
annotieren, und die Envelope/Payload-Trennung passt nicht auf `@Entity`). Stattdessen
je Entität eine **eigene Room-Klasse** in `config/entity/` + dünner **Mapper**
(`ConfigEntityMapper`) ⇄ `:shared`-DTO — dasselbe Muster wie `SessionEntityMapper`
(Plan-D3).

### 7.2 Neue Tabellen + Double-Enum-CHECKs

`MigrationTo12.kt`, Muster wie `MigrationTo11.kt` (Table-Create statt Alter für CHECKs).
Alle Finite-Set-Spalten tragen `CHECK` (docs/DATABASE-PATTERNS.md):

```sql
CREATE TABLE provider_configs (
    id TEXT NOT NULL PRIMARY KEY,
    provider_type TEXT NOT NULL
        CHECK (provider_type IN ('OPENAI','GROQ','ANTHROPIC','ELEVENLABS','OPENROUTER','CUSTOM')),
    kind TEXT NOT NULL DEFAULT 'LOCAL' CHECK (kind IN ('LOCAL','GATEWAY')),
    label TEXT NOT NULL,
    base_url TEXT,
    credential_ref TEXT,
    -- Provenienz + Envelope
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL'
        CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE api_credentials (
    id TEXT NOT NULL PRIMARY KEY,
    provider_type TEXT NOT NULL CHECK (provider_type IN ('OPENAI','GROQ','ANTHROPIC','ELEVENLABS','OPENROUTER','CUSTOM')),
    label TEXT NOT NULL,
    key_fingerprint TEXT NOT NULL,
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL' CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE model_refs (
    id TEXT NOT NULL PRIMARY KEY,
    provider_ref TEXT NOT NULL,
    model_id TEXT NOT NULL,
    function TEXT NOT NULL CHECK (function IN ('TRANSCRIPTION','COMPLETION')),
    label TEXT,
    parameter_defaults TEXT NOT NULL DEFAULT '{}',   -- kanonisches JSON (Map<String,String>)
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL' CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX index_model_refs_provider_ref ON model_refs(provider_ref);

CREATE TABLE profiles (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    transcription_model_ref TEXT,
    completion_model_ref TEXT,
    style_prompt_mode TEXT NOT NULL DEFAULT 'PREDEFINED' CHECK (style_prompt_mode IN ('NONE','PREDEFINED','CUSTOM')),
    style_prompt_custom_text TEXT NOT NULL DEFAULT '',
    system_prompt_mode TEXT NOT NULL DEFAULT 'PREDEFINED' CHECK (system_prompt_mode IN ('NONE','PREDEFINED','CUSTOM')),
    system_prompt_custom_text TEXT NOT NULL DEFAULT '',
    ambiguity_mode TEXT NOT NULL DEFAULT 'ALWAYS_INSERT'
        CHECK (ambiguity_mode IN ('ALWAYS_INSERT','AUTO','ALWAYS_REVIEW')),
    parameter_overrides TEXT NOT NULL DEFAULT '{}',
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL' CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE profile_prompts (
    profile_id TEXT NOT NULL,
    pos INTEGER NOT NULL,
    prompt_ref TEXT NOT NULL,
    auto_apply INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (profile_id, pos),
    FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
);
CREATE INDEX index_profile_prompts_prompt_ref ON profile_prompts(prompt_ref);
```

### 7.3 `prompts`-Tabelle erweitern

Die bestehende `prompts`-Tabelle (v11) wird **behalten** (ADR-0024, Pill-`type` bleibt)
und um Provenienz/Envelope-Spalten ergänzt — Table-Recreate (SQLite kann kein
`ADD CHECK`), Daten kopieren:

```sql
CREATE TABLE prompts_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    uuid TEXT NOT NULL DEFAULT '',           -- stabile Identität für Profil-Referenzen + v3
    pos INTEGER NOT NULL, name TEXT, prompt TEXT,
    requires_selection INTEGER NOT NULL, auto_apply INTEGER NOT NULL,
    type TEXT NOT NULL DEFAULT 'PROMPT' CHECK (type IN ('PROMPT','TEXT')),   -- ADR-0024, unverändert
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL' CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0
);
INSERT INTO prompts_new (id, pos, name, prompt, requires_selection, auto_apply, type)
    SELECT id, pos, name, prompt, requires_selection, auto_apply, type FROM prompts;
DROP TABLE prompts; ALTER TABLE prompts_new RENAME TO prompts;
-- danach: uuid + content_hash für alle Zeilen backfillen (§8, im Migrations-Code, nicht SQL)
```

> [!NOTE]
> `prompts.id` bleibt der Room-Autoincrement-PK (`PromptEntity.id: Int`,
> abwärtskompatibel zu allen bestehenden Referenzen). Die neue `uuid`-Spalte ist die
> **teilbare** Identität, die Profile (`profile_prompts.prompt_ref`) und v3-Export
> verwenden. Der Backfill (§8.5) vergibt je Zeile eine UUID + `content_hash`.

### 7.4 DAOs + ConfigRepository

- DAOs takes `String` für alle Enum-Spalten (Double-Enum-Regel), Convenience-Accessor
  `xxxEnum` mit `getOrDefault`-Fallback auf jeder Room-Entity (docs/DATABASE-PATTERNS.md).
- `ConfigRepository` ist der einzige Schreibpfad: es mappt DTO→Room, setzt
  `content_hash = ContentHash.of(payload)` + `updated_at = now` (§5.3), und schreibt
  transaktional (Profil + `profile_prompts` zusammen).

### 7.5 DictateDatabase-Registrierung

`DictateDatabase.kt`: `version = 12`; `entities`-Array um die 5 Room-Entities ergänzt;
`.addMigrations(..., MIGRATION_10_11, MIGRATION_11_12)`; `exportSchema` bleibt `true`
→ `12.json` wird generiert und committet (Migrations-Test braucht das Asset).

## 8. Prefs→Entitäten-Migration

### 8.1 Ort, Trigger, Idempotenz

- Klasse `ConfigEntityMigration` (`app/config/`), braucht `SharedPreferences` +
  `DictateDatabase` + `SecretStore` (Block B) → kann **nicht** in der SP-only
  `PrefsMigration.kt` liegen. Sie läuft **nach** DB-Build beim App-/IME-Start (dort, wo
  heute `LegacyAudioFileMigration` läuft), **nach** `PrefsMigration.migrateProviderPrefs`
  (die int→string-Provider-Migration muss zuerst laufen).
- **Idempotenz:** `Pref.ConfigEntityMigrationDone` (Int, Default 0). Läuft nur, wenn
  `< CURRENT_MIGRATION_VERSION` (=1). Muster wie `LegacyAudioPurgedV4`.
- Reihenfolge relativ zu Block B2: Diese Migration erzeugt `api_credentials`-Zeilen und
  legt die Schlüssel via `SecretStore.put` ab — d. h. sie **ist** der Ort, an dem die
  Klartext-Keys aus den Prefs in den SecretStore wandern (deckt sich mit B2). Der
  `depends_on: C2→{C1,B2,A3}` stellt sicher, dass der SecretStore-Port existiert.

### 8.2 Mapping — Provider + Credential + Model

Für die Migration wird `RunnerFactory`-Logik (§3.3) rückwärts gelesen. Pro **Funktion**
(Transcription, Completion) den aktiven Provider ermitteln und die Entitäten erzeugen:

| Schritt | Quelle (Pref) | Ziel-Entität |
|---|---|---|
| 1. Für jeden Provider mit **nicht-leerem** Key: ApiCredential | `*ApiKey*` | `ApiCredentialEntity{providerType, label="<Provider> Key", keyFingerprint=sha256(key)[..16]}`; Key → `SecretStore.put(SecretRef(id), keyBytes)` |
| 2. Für jeden Provider mit Key oder aktiver Auswahl: ProviderConfig | Provider + `*CustomHost` | `ProviderConfigEntity{providerType, kind=LOCAL, label, baseUrl=CustomHost?|null, credentialRef=<cred.id>|null}` |
| 3. Transcription-Modell | `TranscriptionProvider` + `Transcription<Prov>Model` (+ `ElevenLabsKeytermsParsed`) | `ModelRefEntity{providerRef, modelId, function=TRANSCRIPTION, parameterDefaults={keyterms:…}?}` |
| 4. Completion-Modell | `RewordingProvider` + `Rewording<Prov>Model` | `ModelRefEntity{providerRef, modelId, function=COMPLETION, parameterDefaults=<§8.3>}` |

`label`-Erzeugung deterministisch aus `providerType.displayName`. Custom-Provider:
`baseUrl` aus `TranscriptionCustomHost`/`RewordingCustomHost` (getrennt — falls beide
Custom mit unterschiedlichem Host, entstehen **zwei** ProviderConfigs; dokumentiert im
Migrations-Code).

### 8.3 Mapping — Parameter

Die Parameter-Prefs (`PARAMETER_PREFS`, AIOrchestrator.kt:199-205) werden mit der
Sentinel-Regel (`< 0` / leer = Server-Default weglassen) in `parameterDefaults` des
**Completion-ModelRef** übernommen (nicht ins Profil — sie sind provider-/modell-nah):

- `temperature` (Float ≥ 0) → `"temperature":"<toCanonicalDecimal(v)>"`
- `max_tokens`/`max_completion_tokens` (Int > 0) → `"max_tokens":"<v>"`
  (Schlüsselname pro Provider gemäß `ParameterRegistry`, Anthropic `max_tokens` sonst
  `max_completion_tokens`)
- `reasoning_effort` (nicht leer) → `"reasoning_effort":"<v>"`

`toCanonicalDecimal(Float)`: kürzeste verlustfreie Dezimaldarstellung, `.` als
Trenner, kein Exponent (z. B. `0.7`, `1`, `1.5`). Nur diese String-Form geht in den
Hash (§5.1) — deshalb hier festgelegt.

### 8.4 Prefs-Backup (Rollback-Pfad, F22/D4.7)

**Vor** jeder Entitäts-Erzeugung: vollständiger Dump aller SharedPreferences nach
`context.filesDir/backups/prefs-backup-v11-<epochMillis>.json`. Format: flaches
JSON-Objekt `{ "<key>": <value> }` über `sp.all` (Typen: Boolean/Int/Long/Float/String/
Set<String>). Der Dump ist der dokumentierte Rollback: kein Auto-Restore, aber ein
vollständiges, lesbares Abbild. `PrefsBackup.write(sp, dir)` ist idempotent (überschreibt
nicht, schreibt genau einmal pro Migrationslauf).

> [!CAUTION]
> Der Backup-Dump enthält die **Klartext-Keys** (er spiegelt die Prefs 1:1). Er liegt in
> App-privatem Speicher (`filesDir`, nicht extern), wird **nie** geteilt/exportiert, und
> sollte nach erfolgreicher Migration + Verifikation gelöscht werden dürfen (optionaler
> Aufräum-Schritt, §14 Gap 4). Er ist bewusst kein `SecretStore`-Inhalt — er ist der
> Vor-Migrations-Snapshot.

### 8.5 Default-Profil + Prompt-Backfill

1. **Prompt-Backfill:** jede bestehende `prompts`-Zeile bekommt eine `uuid` (v4) +
   `content_hash` (über die `PromptV3Entity`-Projektion; TEXT-Pills werden dabei als
   `PromptV3`-Payload mit `type` **ignoriert** — der Hash deckt nur name/text/flags).
   `visibility=PRIVATE`, `subscription_mode=LOCAL`.
2. **Default-Profil erzeugen:** `ProfileEntity{name="Default", transcriptionModelRef=
   <§8.2 Schritt3>, completionModelRef=<Schritt4>, orderedPrompts=[alle prompts nach
   pos, autoApply=<Zeile.auto_apply>], stylePromptMode=fromValue(StylePromptSelection),
   stylePromptCustomText, systemPromptMode=fromValue(SystemPromptSelection),
   systemPromptCustomText, ambiguityMode=fromPersistKey(AmbiguityMode),
   parameterOverrides={}}`. `content_hash`/`updated_at` via `ConfigRepository`.
3. **Aktivierung:** `Pref.ActiveProfileId = <default.id>`.
4. **Key-Cleanup:** die migrierten `*ApiKey*`-Prefs aus SharedPreferences entfernen
   (gemeinsam mit B2; Acceptance §2.6). Die übrigen migrierten Prefs (Provider/Model/
   Param/Prompt-Selection/Ambiguity) werden **nicht** sofort gelöscht (nur nicht mehr
   gelesen) — sie sind im Backup ohnehin gesichert, und ein späteres `removeObsoletePrefs`
   (PrefsMigration-Muster) kann sie räumen. **Ausnahme Keys**: die müssen weg (§2.6).
5. **Flag setzen:** `Pref.ConfigEntityMigrationDone = 1`.

Alle Schritte in **einer** DB-Transaktion (außer SecretStore-`put`, das idempotent pro
SecretRef ist). Bei Absturz mitten drin: der zweite Lauf sieht `Done=0`, das Backup
existiert bereits (idempotent), und die Entitäts-Erzeugung ist idempotent über
deterministische Keys (§8.6).

### 8.6 Idempotenz-Details

- Entitäts-`id`s werden bei der Migration **deterministisch** aus einem Namespace +
  Quell-Merkmal abgeleitet (`UUID.nameUUIDFromBytes("providerconfig:openai".toByteArray())`
  etc.), nicht zufällig — so erzeugt ein zweiter (Teil-)Lauf dieselben ids statt
  Duplikate. (Prompts behalten ihre bereits vergebene `uuid`.)
- `SecretStore.put` mit derselben SecretRef ist ein Overwrite, kein Fehler.

## 9. ProfileResolver (AiConfig-Port)

### 9.1 Port-Anforderung (abstrakt, Block A besitzt die Signatur)

Block A definiert das `AiConfig`-Port (§ Plan A3). Die **Anforderung** aus Sicht von
Block C: „liefert die effektive Konfiguration aus aktivem Profil + Credentials" — pro
`AIFunction` (TRANSCRIPTION/COMPLETION):

- `provider(function): ProviderType/AIProvider`
- `modelName(function): String`
- `apiKey(function): String` (bzw. `ByteArray?` aus SecretStore)
- `baseUrl(function): String`
- `parameters(function, modelId): Map<String, Any>` (Register-interpretiert)
- plus Profil-Aspekte, die heute Prefs sind: `stylePrompt`-Auswahl, `systemPrompt`-
  Auswahl, `ambiguityMode`.

> [!NOTE]
> Die **exakte** Methodensignatur, Paket und Rückgabetypen von `AiConfig` gehören Block A
> (`research/shared-ai-extraktion.md`, bewusst hier nicht gelesen). Diese Spec
> beschreibt nur, was der `ProfileResolver` liefern muss; die Adapter-Signatur wird bei
> C2-Implementierung gegen den dann existierenden Port angeglichen (§14 Gap 1).

### 9.2 Auflösung

`ProfileResolver(sp, db, secretStore)`:

1. `activeId = sp.get(Pref.ActiveProfileId)`; `profile = profileDao.byId(activeId)`.
2. Für `function`: `modelRef = modelRefDao.byId(function==TRANSCRIPTION ?
   profile.transcriptionModelRef : profile.completionModelRef)`.
3. `provider = providerConfigDao.byId(modelRef.providerRef)`.
4. `modelName = modelRef.modelId`.
5. `baseUrl = provider.baseUrl ?: provider.providerType.toAIProvider().defaultBaseUrl`.
6. `apiKey = provider.credentialRef?.let { secretStore.get(SecretRef(it)) }?.decodeToString() ?: ""`.
7. `parameters = modelRef.parameterDefaults ⊕ profile.parameterOverrides` (Profil
   gewinnt), dann durch `ParameterRegistry` interpretiert (Typ/Range wie heute).
8. `ambiguityMode = profile.ambiguityMode.toAmbiguityMode()`; Style-/System-Prompt aus
   `profile.*PromptMode/*CustomText` + `PromptTemplates` (Block A).

### 9.3 Fallback-Semantik

- **Kein aktives Profil** (`ActiveProfileId` leer oder Profil fehlt): Resolver liefert
  eine **leere** Konfiguration — `provider = OPENAI` (heutiger Default), `modelName`/
  `apiKey = ""`, `baseUrl = default`. Das reproduziert exakt den heutigen
  „nicht konfiguriert"-Zustand (leerer Key ⇒ bestehende „API-Key fehlt"-UX greift,
  APISettingsActivity/Pipeline unverändert).
- **Profil ohne ModelRef** (z. B. Completion nie konfiguriert): `modelName/apiKey = ""`
  für diese Funktion; Transcription kann trotzdem funktionieren. Byte-gleich zum
  heutigen „Provider gewählt, aber kein Modell/Key".
- **Credential fehlt im SecretStore** (Referenz da, Wert weg — z. B. Keystore-Verlust,
  Block B): `apiKey = ""` → dieselbe „Key fehlt"-UX. Kein Crash.

### 9.4 Charakterisierungs-Test (Verhaltensneutralität)

Kern-Akzeptanz von C2 (§2.5): Für eine Matrix von Pref-Konstellationen (jeder Provider
als Transcription/Completion, mit/ohne Custom-Host, mit/ohne Parameter):

1. Prefs-Fixture setzen.
2. `ConfigEntityMigration` laufen lassen.
3. Alten `RunnerFactory(sp)` (pref-basiert) **und** neuen `RunnerFactory(profileResolver)`
   dieselbe `getProvider/getModelName/getApiKey/getBaseUrl` + `resolveParameters`
   abfragen.
4. Assert: **identische** Werte (Provider, Modell, Key, BaseUrl, Parameter-Map).

Dieser Test ist der Verhaltensneutralitäts-Beweis (test-first: erst gegen den
unmigrierten Code die Erwartung fixieren, dann Migration + Resolver bauen).

## 10. Settings-UI-Umbau (C3)

Ziel-Zuschnitt: **Provider → Modelle → Profile**, statt der zwei parallelen
Provider-Sektionen. Bausteine `ModelFetcher`, `ParameterRegistry`, `ParameterDef`-Rendering
bleiben; die **Schreibpfade** wechseln von Pref→Entität (via `ConfigRepository`).

### 10.1 Provider-Verwaltung

Liste aller `ProviderConfigEntity` (Label, providerType, „Key gesetzt"-Badge,
Herkunfts-Badge lokal/Peer). Editor: providerType-Auswahl, `label`, für CUSTOM `baseUrl`,
Credential anhängen (neuer Key → `SecretStore.put` + `ApiCredentialEntity`; oder
vorhandenes Credential referenzieren). `kind=GATEWAY` ist im UI **nicht wählbar** (F31).

### 10.2 Modell-Verwaltung (zweistufig, datengetrieben)

Pro Provider: Modell-Auswahl aus **Vereinigung** von (a) `ModelFetcher.fetchModels`/
`getHardcodedModels` (live), (b) vorhandenen `ModelRefEntity` (bezogen/lokal),
(c) Freitext (Anthropic/Custom — §14 Gap 2 Anthropic hat keinen `/models`-Endpoint).
Auswahl legt eine `ModelRefEntity` an/aktualisiert sie. Parameter-UI wie heute
(`updateParameterUI`) — Werte schreiben in `ModelRefEntity.parameterDefaults` bzw.
`ProfileEntity.parameterOverrides` (Editor-Kontext entscheidet).

### 10.3 Profil-Verwaltung (UX-Vorbild PromptsOverview)

Profil-Liste mit **Duplizieren** und **Verschieben** analog zum frisch überarbeiteten
`PromptsOverviewActivity` + `PromptListMutations.kt` (`copyOf`/`resequenced`-Muster):

- Liste: Name, aktives Profil markiert, Herkunfts-Badge.
- Aktionen: neu, duplizieren (`ProfileListMutations.copyOf` analog `PromptListMutations`),
  verschieben (Reorder), aktiv setzen (`Pref.ActiveProfileId`), löschen.
- Editor: Transcription-ModelRef wählen, Completion-ModelRef wählen, Prompts ordnen
  (`orderedPrompts` mit autoApply-Toggle), Style-/System-Prompt-Auswahl, `AmbiguityMode`,
  Parameter-Overrides.

**Kein** Profil-Switcher in der Keyboard-UI (D4.4) — Profilwahl ausschließlich hier.

### 10.4 Import-Dispatcher (v1/v2/v3)

SAF-Import liest die Datei, dispatcht nach Versions-Erkennung:

- **v3** (`{"version":3,...}`) → `CatalogCodec.decode` → Entitäten (mit
  `contentHash`-Recompute-Check, §5.3) → `ConfigRepository`-Upsert.
- **v2/v1** (Prompt-Datei) → bestehender `PromptImportExport.parse` → Android
  `prompts`-Rows **mit** Pill-`type` (ADR-0024 unverändert) → anschließend
  `uuid`/`content_hash`-Backfill (§8.5-Logik als Helper).

### 10.5 v3-Export (SAF)

Export sammelt die zu teilenden Entitäten (Auswahl im UI: einzelnes Profil samt
referenzierter ModelRefs/ProviderConfigs/Prompts, oder ganze Kategorien),
serialisiert via `CatalogCodec.encode(CatalogFileV3(...))`. **Credentials**: nur
Metadaten (`keyFingerprint`), nie der Schlüsselwert (F12) — für lokalen Datei-Export
bedeutet das, ein exportiertes `ApiCredentialEntity` ist ohne begleitende
Secret-Auslieferung (Block E) nur eine leere Hülle; im v1-Datei-Export empfiehlt sich,
Credentials **wegzulassen** (Datei-Export teilt Prompts/Profile/Modelle, nicht Keys).
Entscheidung dokumentiert als §13 D5.

### 10.6 PromptsOverview + Herkunfts-Badge

`PromptsOverviewActivity` bekommt einen Herkunfts-Badge (lokal/Peer) aus den neuen
`prompts`-Provenienz-Spalten. Sonst unverändert (Pill-Verhalten, ADR-0024). Read-only-
Explorer für Peer-Prompts ist Block E3.

## 11. Migration Plan (Chunk-Schnitt)

Diese Spec deckt die Plan-Chunks **C1, C2, C3**. Jeder Chunk kompiliert und testet
isoliert.

1. **C1 — `:shared/config` (Scaffold-Chunk).** Enums, DTOs, Validations, CanonicalJson,
   ContentHash, CatalogCodec + Tests (§4, §5). Kein Android-Bezug. Compile-State:
   `:shared` grün, `SharedPurityTest` grün, Kanonik-/Hash-/Codec-Tests grün.
   *Abhängigkeit:* keine (parallel zu A3/B1 möglich, Plan §7).
2. **C2 — Persistenz + Migration + Resolver (Consume-Chunk).** Room v11→v12
   (§7), `ConfigEntityMapper`, `ConfigRepository`, `PrefsBackup`, `ConfigEntityMigration`
   (§8), `ProfileResolver` (§9). `RunnerFactory`/`AIOrchestrator` lesen `AiConfig`
   (Naht zu A3/B2). Compile-State: `:app` grün; MigrationTest + Charakterisierungs-Test
   grün. *Abhängigkeit:* C1, B2 (SecretStore), A3 (AiConfig-Port).
3. **C3 — UI-Umbau (Consume-Chunk).** Provider-/Modell-/Profil-Screens (§10),
   Import-Dispatcher, v3-Export, PromptsOverview-Badge. Compile-State: `:app` grün;
   Robolectric-Smoke grün; grep-Test „kein migrierter Pref-Key im UI-Code". *Abhängigkeit:*
   C2.

## 12. Testing Approach

**Kanonik + Hash (`:shared`, C1):**
- `CanonicalJsonTest`: Byte-Snapshot je Entitätstyp (fixierte Fixtures); Key-Sortierung
  (semantisch gleiches Objekt mit anderer Deklarations-/Insertion-Reihenfolge ⇒ gleiche
  Bytes); Envelope-Ausschluss (verschiedene `id`/`visibility`/`sourceRef` ⇒ gleiche
  kanonische Bytes); Unicode/Escaping (Umlaute, Steuerzeichen).
- `ContentHashTest`: Determinismus-Matrix — gleicher Payload ⇒ gleicher Hash;
  Werteänderung (jedes Payload-Feld einzeln) ⇒ neuer Hash; `orderedPrompts`-Umsortierung
  ⇒ **neuer** Hash (Array-Order signifikant); Envelope-Änderung ⇒ **gleicher** Hash.
- `ConfigValidationsTest`: je DTO ≥1 Verstoß (leerer `label`, ungültige `modelId`,
  leerer `keyFingerprint`); **`GATEWAY`-Ablehnung** als aktiver Test (Erzeugung
  `kind=GATEWAY` ⇒ `Invalid`).
- `CatalogCodecTest`: v3-Round-Trip byte-stabil; Malformed (kein JSON, unbekannter
  `kind`-Diskriminator) vs. Invalid (Contract-Verstoß) korrekt getrennt (ADR-0016).

**Room-Migration (`:app`, C2, Instrumented):**
- `MigrationTo12Test` (`MigrationTestHelper`, v11→v12): befüllte v11-Fixture; nach
  Migration: neue Tabellen existieren, `prompts`-Daten erhalten + `uuid`/`content_hash`
  gebackfillt; je CHECK-Spalte je ein „gültiger Wert akzeptiert" + „ungültiger Wert ⇒
  `SQLiteConstraintException`" (Double-Enum-Testtemplate, docs/DATABASE-PATTERNS.md).

**Prefs→Entitäten (`:app`, C2):**
- `ConfigEntityMigrationTest`: Fixture mit allen Provider-Slots/Keys/Modellen/Parametern
  → Default-Profil korrekt; Keys im SecretStore abrufbar, `*ApiKey*`-Prefs leer;
  Backup-JSON existiert; **Idempotenz** (zweiter Lauf = No-Op, keine Duplikate).
- `ProfileResolverCharacterizationTest` (§9.4): Matrix — alte vs. neue RunnerConfig
  byte-gleich.

**UI (`:app`, C3, Robolectric):**
- Smoke der neuen Navigation (Provider→Modell→Profil), Profil-Duplizieren/Verschieben
  (Unit-Test auf `ProfileListMutations`, Vorbild `PromptListMutations`-Tests).
- grep-Test: kein UI-Code referenziert eine migrierte Pref-Konstante.

**Pending:**
- `GATEWAY`-Runner-Auflösung (`pending: block-e-gateway`) — Enum reserviert, Pipeline
  lehnt heute ab; aktiver Ablehnungstest oben deckt die Nicht-Wählbarkeit.

## 13. Decision Log

### D1 — Entitäten als flache DTOs mit Envelope-Feld-Ausschlussliste, kein Wrapper

**Trigger:** contentHash muss Envelope-Metadaten ausschließen, aber die kanonische Form
soll simpel bleiben.
**Decision:** Envelope-Felder liegen flach auf jeder Entität; `CanonicalJson` entfernt
sie über die feste `ENVELOPE_FIELDS`-Namensliste (§4.2), statt Payload in ein
`Envelope{payload}`-Wrapper zu verschachteln.
**Rationale:** flache DTOs sind leichter zu serialisieren/validieren/mappen; ein Wrapper
würde jede Konsumentenstelle (Room-Mapper, UI) um eine Ebene verschachteln, ohne Gewinn.
Der Ausschluss ist eine 6-Element-Konstante — trivial testbar.

### D2 — Spiegel-Enums in `:shared` statt Referenz auf `:shared-ai`-Originale

**Trigger:** `AIProvider`/`AmbiguityMode`/`AIFunction` liegen (nach Block A) in
`:shared-ai`, das **über** `:shared` liegt — aus `:shared` nicht referenzierbar.
**Decision:** `:shared` definiert `ProviderType`/`ProviderKind`/`ModelFunction`/
`AmbiguityModeValue`/`PromptSelectionMode` selbst; Paritäts-Tests (§4.8) erzwingen
Werte-Gleichheit; Mapper leben in `:shared-ai`/`:app`.
**Alternatives:** (a) `AIProvider` nach `:shared` verschieben — Block-A-Entscheidung,
nicht Block C; als Gap 1 an Block A eskaliert. (b) Ohne Parität mirror — verworfen, weil
Drift den contentHash unbemerkt bräche.

### D3 — Geteilte `Prompt`-Entität ohne Pill-`type`; Android-`prompts.type` bleibt Room-only

**Trigger:** ADR-0024 hat `prompts.type` (PROMPT/TEXT); die teilbare Prompt-Entität soll
laut Konzept keine Pill-Felder haben.
**Decision:** `PromptV3Entity` = {name, text, requiresSelection, autoApply}. Der
Android-`prompts.type` bleibt eine Room-only-Spalte (ADR-0024 unverändert); TEXT-Pills
werden nicht als v3-Entität exportiert; v3-Import erzeugt `type=PROMPT`.
**Rationale:** TEXT-Pills sind literale Snippets, kein teilbarer AI-Prompt (Konzept §6).
Hält ADR-0024 (Pill-Verhalten, Klassifikation) vollständig unberührt und den v3-Codec
sauber (ein Format). v1/v2-Prompt-Import bleibt der Android-Legacy-Pfad (§10.4).

### D4 — Aktives Profil als globaler Pref-Pointer, nicht als `is_active`-Spalte

**Trigger:** genau ein Profil ist aktiv; `is_active` auf jeder Zeile wäre eine
Mehrzeilen-Invariante.
**Decision:** `Pref.ActiveProfileId` (String). `is_active` ist **kein** Profil-Feld
(würde den Hash verunreinigen und ist kein teilbarer Inhalt).
**Rationale:** ein Pointer hat keine Invariante zu wahren; das aktive Profil ist
Geräte-lokal, nicht teil des teilbaren Inhalts.

### D5 — v3-Datei-Export lässt Credentials als leere Hüllen aus (Keys nur über Block-E-Call)

**Trigger:** `ApiCredentialEntity` trägt nur Metadaten; ein Datei-Export ohne
Secret-Auslieferung wäre wertlos und verwirrend.
**Decision:** Der SAF-Datei-Export (§10.5) teilt Prompts/Profile/Modelle/Provider, aber
**keine** Credentials (F12: Key-Auslieferung ist ein separater autorisierter Peer-Call,
Block E). Ein Profil, das ein Credential referenziert, exportiert die Referenz-Metadaten;
der Empfänger muss den Key selbst setzen.
**Rationale:** verhindert die Illusion, ein Datei-Export übertrage Keys; hält die
Key-Auslieferung ausschließlich im auditierbaren Block-E-Pfad.

### D6 — Enum-Layering entschieden: Wire-Enums bleiben, kein Move nach `:shared` (Plan D5.a)

**Trigger:** Cross-Spec-Entscheidung des Plan-Architekten 2026-07-20 (Auftrag
Team-Lead); §14 Gap 1 / §4.8-TIP hatten die Nahtstelle an Block A eskaliert.
**Decision:** Der Spiegel-Ansatz aus §4.8/D2 ist verbindlich. `AIProvider`/
`AmbiguityMode`/`AIFunction` bleiben in `:shared-ai` (package-erhaltender Move
per shared-ai-Spec §3.4); `:shared` behält seine eigenen Wire-Enums
(`ProviderType`/`ProviderKind`/`ModelFunction`/`AmbiguityModeValue`/
`PromptSelectionMode`). Paritäts-Tests + Mapper leben in `:app` (sieht beide
Module) und sind Pflicht-Gate.
**Rationale:** Exakt die bestehende Wire-vs-Domain-Doktrin (ADR-0016;
`SessionOriginWire` ↔ `SessionOrigin` + `SessionEntityMapper`): Domain-Enums
tragen Verhalten (Capabilities, `forcesTurn`), das nicht ins Wire-Modul
gehört; ein Move hätte zudem die von Block A bewusst vermiedene Modul-Kopplung
(`:shared-ai`→`:shared`) eingeführt und das Package-erhaltende Move-Konzept
gebrochen. Drift ist test-verhindert — wie im Bestand.
**Alternatives:** Move der Originale nach `:shared` (verworfen, s. o.);
Spiegel ohne Paritäts-Test (verworfen bereits in D2).

### Freshness-Pass 2026-07-20 — as-built Struktur (Post-Implementation)

**Trigger:** Integrations-Check nach Abschluss Block A–E (Finding `integ-1`,
green) — Abgleich der fünf Block-Specs gegen den gebauten Stand vor der
F-Stage-Archivierung/EN-Übersetzung.
**As-built vs. Spec:**
1. **Wire-Enum-Heim konsolidiert.** D6 nennt die Config-Familie
   (`ProviderType`/`ProviderKind`/`ModelFunction`/`AmbiguityModeValue`/
   `PromptSelectionMode`) in `:shared`. Gebaut wurde EIN gemeinsames Heim
   `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ConfigEnums.kt`,
   das zusätzlich die von peer-katalog §5.2/§5.3 gebrauchten Katalog-Wire-Enums
   trägt — als `Visibility` (`PRIVATE`/`SHARED`) und `SubscriptionMode`
   (`LOCAL`/`SUBSCRIBE`/`ONE_SHOT`), **nicht** als separate `shared.catalog.*Wire`-
   Kopien. Der „Wire"-Namenszusatz entfällt (ein Heim ⇒ keine Domain-/Wire-
   Namenskollision zu entschärfen). D6s Aufzählung war die Config-Teilmenge; die
   Konsolidierung ist der D5.a-Doktrin treu (ein SSoT-Enum-Modul), superseded also
   die verstreute Platzierung, nicht die Entscheidung selbst.
2. **Verwaltungs-UI (Companion-Seite).** Der companion-seitige Entitäts-Editor
   landete konsolidiert in `companion/.../ui/config/ManagementScreen.kt` +
   `ConfigViewModel.kt` (ein Screen), nicht in getrennten `ui/profiles`/`ui/models`/
   `ui/prompts` — Detail liegt bei desktop-host §9.2 (Freshness-Pass-Eintrag dort).
   Die Android-Settings (§10 C3) sind davon unberührt.
**Bewertung:** Kein Code-Impact, D5-endorsed und paritäts-getestet
(`ConfigEntityCheckParityTest`). Body unverändert; dieser Eintrag ist die
normative As-built-Korrektur der Enum-Package-Referenzen.

## 14. Information Gaps

1. ~~**`AiConfig`-Port-Signatur (Block A) / Enum-Placement**~~ — **teilweise
   geschlossen 2026-07-20 (D6 / Plan D5.a):** Enum-Placement ist entschieden
   (Originale in `:shared-ai`, Wire-Enums in `:shared`, Spiegel-Ansatz
   verbindlich). Offen bleibt nur die exakte `AiConfig`-Port-Signatur —
   definiert in `research/shared-ai-extraktion.md` §4; der C2-Adapter gleicht
   sich an den dort spezifizierten Port an. *Owner:* Block-A-Agent (Signatur),
   C2-Agent (Adapter).
2. **Anthropic-Modell-Liste.** Kein `/models`-Endpoint im OpenAI-Format → Freitext bleibt
   (Plan §10 Gap 4). *Owner:* C3. *Fallback:* ModelRef-Entitäten (teilbare Kuration)
   mildern das; Freitext-Feld im Modell-Selektor.
3. **SecretRef-Format (Block B).** Ob `SecretRef` = Credential-UUID-String oder ein
   strukturierter Namespace ist, besitzt Block B1. *Owner:* Block-B-Agent. *Fallback:*
   §8/§9 nehmen `SecretRef(credentialId)` an; anpassbar ohne Modelländerung.
4. **Backup-Aufräumung.** Ob/wann das Prefs-Backup (§8.4) automatisch gelöscht wird
   (nach N Tagen? nach erstem erfolgreichen Resolver-Lauf?), ist offen. *Owner:* C2-Agent.
   *Fallback:* Backup bleibt liegen (App-privat) bis zu einer expliziten Aufräum-Entscheidung.
5. **Zwei Custom-Provider mit gleichem Host.** Falls Transcription- und
   Rewording-CustomHost identisch sind, könnte die Migration **einen** statt zwei
   ProviderConfigs erzeugen (Dedup). *Owner:* C2-Agent. *Fallback:* konservativ zwei
   ProviderConfigs (§8.2) — nie falsch, nur evtl. redundant; Dedup optional.

## 15. References

- **Plan:** `~/.claude/plans/desktop-companion-v1.md` — Block C (§5), Entscheidungen
  F14/F17/F22/F23/F24/F27/F31, D3/D4.4/D4.7.
- **Konzept:** `research/konzept-skizze.md` (§4 Entitätenmodell, §5 Profil-Name, §6
  „nicht portiert"), `research/bestandsaufnahme.md`, `research/fragenkatalog.md`.
- **Schwester-Specs:** `research/shared-ai-extraktion.md` (Block A, AiConfig-Port),
  `research/secretstore.md` (Block B, SecretStore/SecretRef) — beide parallel entstehend,
  Nahtstellen in §14.
- **ADRs (bindend):** ADR-0016 (Wire-DTO + Konform + ProtocolCodec — Muster für
  CatalogCodec), ADR-0024 (Prompt-Pill-Typen — unangetastet), ADR-0012 (Modell-Auflösung
  via Conversation), ADR-0013 (AmbiguityMode). Plan-scoped: `adr-config-entity-model`,
  `adr-secret-store`, `adr-shared-ai-module`.
- **Konventionen:** `docs/DATABASE-PATTERNS.md` (Double-Enum, Denormalized Cache,
  Data-preservation-rule), `~/.claude/snippets/test-first-patterns.md`.
- **Schlüssel-Code:** `app/.../preferences/DictatePrefs.kt` (Ist-Inventar),
  `app/.../ai/factory/RunnerFactory.kt` + `ai/AIOrchestrator.kt` (Config-Auflösung),
  `app/.../settings/APISettingsActivity.java` (UI-Umbauziel),
  `app/.../rewording/PromptImportExport.java` + `PromptListMutations.kt` (v1/v2 + UX-Vorbild),
  `app/.../database/migration/MigrationTo11.kt` (Migrations-Muster),
  `shared/.../protocol/{ProtocolCodec,Dtos,Validations}.kt` (Codec-Vorbild).
