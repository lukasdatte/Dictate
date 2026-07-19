# `:shared-ai`-Extraktion — Implementer-Spec für Block A

---
date: 2026-07-19
author: Lukas + Claude Code
type: Spec
status: Spec — programmer-ready
context: Verbindliche Bau-Anleitung für Block A des Plans desktop-companion-v1 — Extraktion des AI-Kerns aus `:app` in ein neues pure-JVM-Modul `:shared-ai`, hinter Ports, verhaltensneutral für Android.
related-plan: ../../../../.claude/plans/desktop-companion-v1.md
related-adrs: docs/decisions/0015-companion-monorepo-topology.md, docs/decisions/0016-wire-protocol-typed-dtos-konform.md
---

Diese Spec ist die programmierfertige Konkretisierung der Chunks **A2 (Pure
Moves)** und **A3 (Ports + Runner/Orchestrator)** aus dem Plan
`desktop-companion-v1`. Sie beschreibt _was gebaut wird_: das neue Gradle-Modul
`:shared-ai`, seinen exakten Datei-Inventar-Zuschnitt, die vier Ports und ihre
App-Adapter, eine kompilierfähige Move-Sequenz und die Charakterisierungs-Tests,
die die Verhaltensneutralität beweisen. Sie ist **kein** Ersatz für die
ADR-Drafts (A1) — die Grundsatzentscheidung „viertes Modul, Package unverändert"
lebt in `adr-shared-ai-module`; diese Spec setzt sie um. Chunk **A1** (ADR-Drafts
+ Konzept-Research einchecken) ist reine Autoren-Arbeit und hier nicht behandelt.

## Table of Contents

- [Glossary](#glossary)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Ist-Inventar `ai/` (erschöpfend)](#3-ist-inventar-ai-erschöpfend)
- [§4 Port-Design (Kotlin-Signaturen)](#4-port-design-kotlin-signaturen)
- [§5 Gradle-Setup `:shared-ai`](#5-gradle-setup-shared-ai)
- [§6 Move-Sequenz (kompilierfähige Schritte)](#6-move-sequenz-kompilierfähige-schritte)
- [§7 Directory Layout](#7-directory-layout)
- [§8 Charakterisierungs- und Regressionstests](#8-charakterisierungs--und-regressionstests)
- [§9 Footguns / Anti-Patterns](#9-footguns--anti-patterns)
- [§10 Information Gaps](#10-information-gaps)
- [§11 Change History](#11-change-history)
- [§12 References](#12-references)

## Glossary

### Module & Topologie
- **`:shared-ai`** — neues viertes Gradle-Modul, pure `kotlin("jvm")`,
  jvmTarget 1.8, eigene Dependency-Policy (SDKs erlaubt, Android/Ktor verboten).
  Definiert in §5.
- **Port** — plattformneutrale Schnittstelle in `:shared-ai`, deren Implementierung
  die Plattform (`:app` mit SharedPreferences/Room; später `:companion` mit
  SQLDelight) stellt. Die vier Ports: §4.
- **Adapter** — App-seitige Port-Implementierung (`AndroidAiConfig`,
  `RoomUsageSink`, `SharedPrefsProxyConfig`, `MediaMetadataAudioDurationReader`).
  Reine Delegation an bestehende SharedPreferences/Room/DictateUtils-Logik.

### Bewegungs-Kategorien (siehe §3)
- **Kategorie (a) — 0-Dependency-Move** — Klasse ohne Android/SharedPreferences/
  Room/`org.json`; wandert unverändert (nur `git mv`), Package bleibt
  `net.devemperor.dictate.ai.*`.
- **Kategorie (b) — Port-Migration** — Klasse mit heute Android-/Room-/
  `org.json`-Kopplung; wandert erst nach Einführung eines Ports (Signatur-Edit).
- **Kategorie (c) — bleibt in `:app`** — Klasse, die Android-UI oder pill-
  spezifisch ist und vom Desktop-Pipeline-Pfad nicht gebraucht wird.

### Verhaltensneutralität
- **Charakterisierungs-Test** — Test, der _bestehendes_ Verhalten (Runner-
  Konfiguration aus Prefs, Proxy-Anwendung, Keyterms-Serialisierung) fixiert,
  **vor** dem Move geschrieben, danach unverändert gegen die Port-Adapter grün.
- **Purity-Test** — `SharedAiPurityTest`, Analogon zu `SharedPurityTest`; verbietet
  `android.`/`androidx.`/`io.ktor`-Importe im `:shared-ai`-Quellbaum. §5.3.

## 1. Vision and Motivation

### 1.1 Warum diese Extraktion existiert

Der AI-Kern (Provider-Enum, Runner, Orchestrator, Prompt-/Conversation-Logik)
liegt heute vollständig in `:app` und ist an Android gekoppelt — jedoch **nur an
drei Stellen echt**: `SharedPreferences` als Key/Model/Proxy-Wertspeicher, die
Room-`UsageDao` fürs Usage-Tracking, und `android.media.MediaMetadataRetriever`
für die Audio-Dauer. Der Desktop-Companion (Block D) braucht **denselben**
AI-Kern, ohne diese Android-APIs. Ihn zu duplizieren würde das DRY-Kernziel des
Vorhabens verletzen und Provider-Logik dauerhaft zwischen zwei Plattformen
driften lassen — genau die Drift, die ADR-0016 für das Wire-Protokoll bereits
strukturell ausschließt.

### 1.2 Was diese Extraktion liefert

1. Genau **eine** Implementierung von Providern, Runnern, Orchestrator-Kern,
   Prompt- und Conversation-Logik — pure JVM, von `:app` **und** `:companion`
   konsumierbar.
2. Vier **Ports** (`AiConfig`, `UsageSink`, `ProxyConfig`, `AudioDurationReader`),
   die die drei Android-Kopplungen kapseln; `:app` implementiert sie als dünne
   Adapter mit **byte-identischem Verhalten** zum heutigen Zustand.
3. Ein maschinell erzwungener **Reinheits-Test** (`SharedAiPurityTest`), der
   verhindert, dass der AI-Kern je wieder eine Android-Abhängigkeit einschleppt.

### 1.3 Discarded Alternatives

- **AI-Kern in `:shared` statt neues Modul.** Verworfen: `:shared` hat den
  strengen `SharedPurityTest` (verbietet u. a. das Durchreichen von okhttp-API)
  und trägt nur `implementation libs.okhttp`; die AI-SDKs (openai-java,
  anthropic-java) und ihr okhttp-Client-Builder auf der öffentlichen Runner-
  Oberfläche würden diese Reinheit verwässern. Separates Modul = separate
  Dependency-Policy, gleiche Prinzipien (Plan D1, ADR-0015-Erweiterung).
- **Enums in `:app` lassen und in `:shared-ai` duplizieren.** Verworfen:
  `MessageRole`/`ResponseFormatKind`/`AmbiguityMode` sind geteiltes Vokabular des
  AI-Kerns; Duplikate driften. Sie wandern mit (§3.4, §6 Schritt A2.0).
- **Package umbenennen (`net.devemperor.dictate.sharedai.*`).** Verworfen per
  Plan-D1: Package bleibt `net.devemperor.dictate.ai`, damit die `:app`-Diffs
  fast nur Build-Dateien betreffen und `git log --follow` sauber bleibt.

## 1a. Architecture Walkthrough

### 1a.0 ASCII Stack Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  :app (Android)                          :companion (JVM 17, später) │
│  Port-Adapter:                            Port-Adapter (Block D):     │
│   AndroidAiConfig     (SharedPreferences)  CompanionAiConfig          │
│   RoomUsageSink       (UsageDao)           SqlDelightUsageSink        │
│   SharedPrefsProxyConfig (DictateUtils)    CompanionProxyConfig       │
│   MediaMetadataAudioDurationReader         JavaSoundAudioDurationRdr  │
└───────────────────────────┬───────────────────────┬─────────────────┘
                            │ implementiert Ports    │ implementiert Ports
                            ▼                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  :shared-ai (NEU, pure JVM, jvmTarget 1.8)                          │
│  Ports:   AiConfig · UsageSink · ProxyConfig · AudioDurationReader   │
│  Kern:    AIProvider/AIFunction · AIProviderException · AIOrchestrator│
│           RunnerFactory · OpenAICompatibleRunner · Anthropic…Runner   │
│           ElevenLabsTranscriptionRunner · ModelFetcher · Prompt*/     │
│           Conversation* (StructuredResponseCodec, ReviewDecision …)   │
│  Enums:   MessageRole · ResponseFormatKind · AmbiguityMode           │
│  Deps:    openai-java · anthropic-java · okhttp · kotlinx-serialization│
└───────────────────────────┬─────────────────────────────────────────┘
                            │ nutzt (NICHT umgekehrt)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  :shared (bestehend, pure JVM, jvmTarget 1.8) — Wire-Protokoll      │
│  KEINE Abhängigkeit auf :shared-ai. :shared-ai hängt NICHT auf :shared│
│  (Block A führt keine Kopplung ein — beide sind unabhängige Blätter). │
└─────────────────────────────────────────────────────────────────────┘
```

> **Modul-Kanten (Block A):** `:app → :shared-ai`, `:app → :shared`,
> `:companion → :shared`. `:shared-ai → :shared` wird in Block A **nicht**
> eingeführt (der AI-Kern braucht das Wire-Protokoll nicht). `:companion →
> :shared-ai` kommt erst in Block D.

### 1a.1 Layer `:shared-ai` — der geteilte Kern

- **Purpose:** genau eine AI-Implementierung für beide Plattformen; alle
  Plattform-Zugriffe laufen über Ports.
- **File:** `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/`
- **Contract:** vier Ports (§4); `SharedAiPurityTest` (§5.3) pinnt die Reinheit.
- **Detail:** §3–§6.

### 1a.2 Layer Port-Adapter — die Plattform-Naht

- **Purpose:** `:app` (und später `:companion`) füllen die Ports mit echten
  Werten, ohne dass der Kern die Plattform kennt.
- **File:** `app/src/main/java/net/devemperor/dictate/ai/adapter/` (NEU, §7).
- **Contract:** vier Adapter, reine Delegation; Verhalten byte-identisch zum
  Ist-Zustand (Charakterisierungs-Tests §8 sind der Beweis).

### 1a.3 Read-this-before-implementing checklist

- [ ] `:shared-ai` compiliert auf **jvmTarget 1.8** — NICHT 17. `:app` konsumiert
  es und Kotlin inlint keine 11/17-Bytecode in 8-Bytecode (§5.1, ADR-0015-
  Failure-Mode). Der `java { }`-Block muss `VERSION_1_8` setzen.
- [ ] Package aller bewegten Dateien bleibt `net.devemperor.dictate.ai.*`
  (Plan-D1). Kein Rename.
- [ ] Jede bewegte Datei per `git mv` (Move als Move, `git log --follow` intakt).
- [ ] Kein `import android.*` / `androidx.*` / `io.ktor` im `:shared-ai`-Baum —
  `SharedAiPurityTest` schlägt sonst an (Negativ-Test-Pflicht, §5.3).
- [ ] Charakterisierungs-Tests (§8) sind VOR dem A3-Move geschrieben und laufen
  rot-frei sowohl vor (gegen Prefs) als auch nach (gegen Ports) dem Move.
- [ ] `org.json` ist auf purem JVM **nicht** verfügbar → `ElevenLabsKeytermsParser`
  und der ElevenLabs-Response-Parse auf kotlinx-serialization umstellen (§6 A3.4).

## 2. Acceptance Criteria

Erfüllt Plan-§2 Kriterium 1 (Build-Invariante) und 2 (Verhaltensneutralität).

1. **Modul existiert & compiliert.** `shared-ai/build.gradle` +
   `settings.gradle`-Eintrag vorhanden; `./gradlew :shared-ai:compileKotlin`
   grün; `:shared-ai` steht auf jvmTarget 1.8.
2. **Build-Invariante.** `./gradlew build` grün über `:app`, `:shared`,
   `:shared-ai`, `:companion`.
3. **Purity grün + scharf.** `SharedAiPurityTest` grün; sein Negativ-Selbsttest
   (fabrizierter `android.`-Import wird erkannt) grün; ein testweise eingefügter
   `import android.content.Context` in einer `:shared-ai`-Datei lässt den Test
   rot werden (manuell verifiziert, dann zurückgenommen).
4. **Verhaltensneutralität.** Alle bestehenden `:app`-Unit-Tests grün ohne
   Assertion-Änderung; die Charakterisierungs-Tests (§8) sind grün vor UND nach
   A3. Kein Diff im aufgebauten API-Traffic (Runner-Konfiguration, Proxy,
   Multipart-Body — durch §8-Tests abgesichert).
5. **Move-Sauberkeit.** `git log --follow` auf mindestens je einer bewegten
   Datei pro Paket zeigt die Historie über den Move hinweg.
6. **Kein toter Pfad.** In `:app` liest kein Code des AI-Kerns mehr direkt
   `SharedPreferences`/`UsageDao`/`MediaMetadataRetriever` — der Zugriff läuft
   über die vier Adapter (grep-Prüfung auf die alten Direktzugriffe im
   `ai/`-Baum außerhalb `ai/adapter/`).

## 3. Ist-Inventar `ai/` (erschöpfend)

Basis: `app/src/main/java/net/devemperor/dictate/ai/`, 36 Dateien, 2501 LOC
(Stand 2026-07-19). Spalte **Kat.** = Bewegungs-Kategorie (Glossary).

### 3.1 Wurzel + Enums

| Datei | Zweck | Harte Abhängigkeiten | Kat. |
|---|---|---|---|
| `AIProvider.kt` (enthält auch `enum AIFunction`) | Provider-Enum mit Capability-Flags; `fromPersistKey` | keine | (a) |
| `AIProviderException.kt` | typisierte Fehlerklasse `ErrorType` | keine | (a) |
| `AIOrchestrator.kt` | zentrale Orchestrierung transcribe/complete/converse + Usage-Tracking + `resolveParameters` | `SharedPreferences`, `UsageDao`, `Pref.*` | (b) |
| `ElevenLabsKeytermsParser.kt` | Parsing/Serialisierung Keyterms | `org.json.JSONArray` | (b) |

### 3.2 `factory/` + `model/`

| Datei | Zweck | Harte Abhängigkeiten | Kat. |
|---|---|---|---|
| `factory/RunnerFactory.kt` | wählt Provider/Model/Key/BaseUrl aus Prefs, baut Runner; `open` als Test-Seam (K-1) | `SharedPreferences`, `Pref.*` | (b) |
| `model/ModelInfo.kt` | Daten-Holder (id, displayName) | keine | (a) |
| `model/ParameterDef.kt` | Parameter-Definition | keine | (a) |
| `model/ParameterRegistry.kt` | statische Parameter-Kataloge je Provider/Model | keine | (a) |
| `model/ModelFetcher.kt` | live `/models`-Fetch (OpenAI-kompatibel) + Hardcoded-Listen | `SharedPreferences`, `Pref.*`, `DictateUtils` (Proxy) | (b) |

### 3.3 `runner/` + `prompt/` + `conversation/`

| Datei | Zweck | Harte Abhängigkeiten | Kat. |
|---|---|---|---|
| `runner/TranscriptionRunner.kt`, `CompletionRunner.kt` | Runner-Interfaces | `MessageRole`/`ResponseFormatKind` in DTOs | (a)* |
| `runner/TranscriptionOptions.kt`, `TranscriptionResult.kt`, `CompletionOptions.kt`, `CompletionResult.kt`, `ConversationRequest.kt`, `ConversationResult.kt` | DTOs | teils `MessageRole`/`ResponseFormatKind` | (a)* |
| `runner/StructuredOutputGuards.kt` | Truncation-Guard | `AIProvider` | (a) |
| `runner/OpenAICompatibleRunner.kt` | OpenAI/Groq/OpenRouter/Custom | `SharedPreferences`, `Pref.*`, `DictateUtils` (Proxy + `getAudioDuration`), `MessageRole`, `ResponseFormatKind`, openai-java SDK | (b) |
| `runner/AnthropicCompletionRunner.kt` | Anthropic Claude | `SharedPreferences`, `Pref.*`, `DictateUtils` (Proxy), `MessageRole`, `ResponseFormatKind`, anthropic-java SDK | (b) |
| `runner/ElevenLabsTranscriptionRunner.kt` | ElevenLabs Scribe (okhttp direkt) | `SharedPreferences`, `Pref.*`, `DictateUtils` (Proxy + `getAudioDuration`), `org.json`, okhttp | (b) |
| `prompt/PromptContext.kt`, `PromptMode.kt`, `PromptBuilder.kt`, `PromptTemplates.kt` | XML-Prompt-Bau, Templates | keine | (a) |
| `prompt/PromptService.kt` | Prompt-Bau je Kontext | `SharedPreferences`, `Pref.*`, `DictateUtils.getPunctuationPromptForLanguage` | (b) |
| `prompt/SystemPromptResolver.kt` | System-Prompt-Auflösung | `SharedPreferences`, `Pref.*` | (b) |
| `prompt/PromptTypeClassifier.kt` | klassifiziert PROMPT vs TEXT (Pill-Semantik) | `database.entity.PromptType` | **(c)** |
| `conversation/*` (10 Dateien: `ConversationMessage`, `ConversationReconstructor`, `ConversationTurnBuilder`, `PostProcessingInputs`, `PostProcessingReview`, `ReviewDecision`, `StructuredResponse`, `StructuredResponseCodec` …) | Structured-Output-Wire-Authority, Review-Verdikt, Turn-Rekonstruktion | `MessageRole` (Reconstructor, Message), `AmbiguityMode` (ReviewDecision); sonst pure | (a)* |

`*` = pure bis auf die geteilten Enums aus §3.4, die als Teil desselben Moves
mitwandern.

### 3.4 Geteilte Enums — wandern mit (kritischer Befund)

Der AI-Kern importiert vier plattformneutrale Enums aus fremden Paketen. Alle
vier sind **bewusst Android-frei** geschrieben (Doc-Kommentare in `MessageRole`
und `AmbiguityMode` sagen das explizit), liegen aber physisch in `:app`-Paketen.
Da `:app → :shared-ai` (nicht umgekehrt), **müssen** die vom Kern gebrauchten
Enums nach `:shared-ai` mitwandern:

| Enum | Heutiger Ort | Vom Kern gebraucht? | `:app`-Ripple (Dateien) | Aktion |
|---|---|---|---|---|
| `MessageRole` | `database/entity/` | ja (Runner, `ConversationMessage`, Reconstructor) | 10 (u. a. `ConversationMessageEntity`, `MigrationTo8`, `PipelineOrchestrator`, `SessionManager`) | **Move → `:shared-ai`** |
| `ResponseFormatKind` | `database/entity/` | ja (Runner-Results) | 6 (u. a. `ProcessingStepEntity`, `MigrationTo8`) | **Move → `:shared-ai`** |
| `AmbiguityMode` | `preferences/` | ja (`ReviewDecision`) | 9 (u. a. `JobExecutor`, `DictatePrefs`, `PcDictationActivity`, `DictateInputMethodService.java`) | **Move → `:shared-ai`** |
| `PromptType` | `database/entity/` | nein (nur `PromptTypeClassifier`, Kat. c) | 16 (Pill-UI, DAO, Migrationen) | **bleibt in `:app`** |

Package-Entscheidung für die drei bewegten Enums: **Package unverändert lassen**
(`net.devemperor.dictate.database.entity` bzw. `.preferences`) — sie ziehen nur
physisch nach `shared-ai/src/…`, ihre Import-Statements in `:app` bleiben
unverändert (Split-Package über die Modulgrenze, bei jvmTarget 1.8 ohne
`module-info` unproblematisch und exakt das Muster, das Plan-D1 fürs `ai`-Paket
wählt). Das hält den `:app`-Diff bei **null** Import-Zeilen für die Enum-Nutzer.
Alternative (eigenes Package unter `ai/`) würde 25+ Dateien in `:app` anfassen —
verworfen als unnötiger Eingriff. Siehe §9 zum Split-Package-Trade-off.

> **Room-Konsequenz:** `MessageRole`/`ResponseFormatKind`/`PromptType` sind
> Double-Enum-Werte mit SQL-CHECK-Constraints (docs/DATABASE-PATTERNS.md). Der
> **Enum-Move ändert kein Schema** — die Room-`@TypeConverter`/Entity-Spalten
> referenzieren die Enums nur; solange Package + Konstanten-Namen gleich bleiben,
> bleiben die CHECK-Constraints und Konverter unberührt. Verifiziert durch die
> bestehenden Migrations- und DAO-Tests (§8.3).

### 3.5 App-Kopplungen ausserhalb `ai/`

- **`DictateUtils.java`** (Java, `:app`) liefert dem Kern vier Dinge:
  Proxy-Anwendung (`applyProxy`, `applyProxyToAnthropic`, `createProxy`,
  `applyProxyAuthenticator`, `isValidProxy`), Audio-Dauer (`getAudioDuration` via
  `android.media.MediaMetadataRetriever`), und `getPunctuationPromptForLanguage`.
  Die **Proxy-Logik selbst ist purer JVM-Code** (java.net.Proxy, Regex,
  java.net.Authenticator) — nur der Wertspeicher ist `SharedPreferences`. Die
  Audio-Dauer ist die **einzige echte Android-API** im Kernpfad. →
  `ProxyConfig`- und `AudioDurationReader`-Ports (§4).
- `getPunctuationPromptForLanguage` ist reine Sprach-Tabellen-Logik (keine
  Android-API im Rumpf, nur physisch in einer Java-Klasse mit anderen
  Android-Methoden). Zwei Optionen: (i) die Tabellen mit `PromptTemplates` nach
  `:shared-ai` ziehen und `PromptService` direkt darauf zugreifen lassen, oder
  (ii) sie hinter dem `AiConfig`-Port als `punctuationPrompt(lang)` durchreichen.
  **Empfehlung (i)** — die Punctuation-Tabellen gehören fachlich zu
  `PromptTemplates` (bereits Kat. a); der Move zieht `PROMPT_PUNCTUATION_*` aus
  `DictateUtils` nach `PromptTemplates` und lässt `DictateUtils` (falls noch
  woanders gebraucht) daran delegieren. Details §6 A3.5.

## 4. Port-Design (Kotlin-Signaturen)

Alle Ports liegen in `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/port/`.
Sie sind **schmal** und liefern _bereits aufgelöste_ Werte — der Kern kennt weder
`Pref`-Schlüssel noch die Room-Threading-Details.

### 4.1 `AiConfig` — Konfigurations-Auflösung

Ersetzt die heutige `RunnerFactory(sp)` + `AIOrchestrator.resolveParameters`-
Prefs-Zugriffe. Der Port liefert pro `AIFunction` den bereits ausgewählten
Provider, Model, Key, BaseUrl und die aufgelösten Parameter.

```kotlin
package net.devemperor.dictate.ai.port

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider

/**
 * Resolves the effective AI configuration for a function slot. The platform
 * (Android: SharedPreferences via DictatePrefs; Companion: active Profile in
 * SQLDelight) implements it. The AI core never sees preference keys.
 *
 * Behaviour parity note: `apiKey` MUST reproduce today's
 * `RunnerFactory.getApiKey` exactly, including the non-ASCII strip
 * (`replace(Regex("[^ -~]"), "")`) — see AIProvider-key characterization test.
 */
interface AiConfig {
    fun provider(function: AIFunction): AIProvider
    fun modelName(function: AIFunction): String
    /** Effective key for the function's provider, already ASCII-stripped. */
    fun apiKey(function: AIFunction): String
    /** Base URL; for CUSTOM the resolved host, else provider.defaultBaseUrl. */
    fun baseUrl(function: AIFunction): String
    /**
     * Completion parameters already filtered against sentinels (temp < 0,
     * maxTokens <= 0, empty reasoning_effort dropped) — reproduces
     * AIOrchestrator.resolveParameters + ParameterRegistry.
     */
    fun completionParameters(provider: AIProvider, model: String): Map<String, Any>
    /** Parsed ElevenLabs keyterms for the active transcription config, or null. */
    fun elevenLabsKeyterms(): List<String>?
}
```

> **Schnitt-Begründung:** `resolveParameters` und die Keyterms-Auflösung waren
> bisher im Orchestrator und lasen Prefs direkt. Sie wandern hinter `AiConfig`,
> damit der Orchestrator prefs-frei wird. `ParameterRegistry` (Kat. a) bleibt im
> Kern und wird vom Adapter benutzt, um die Parameter-Namen zu kennen.

### 4.2 `UsageSink` — Usage-Tracking

Ersetzt die direkte `UsageDao`-Kopplung im Orchestrator.

```kotlin
package net.devemperor.dictate.ai.port

/**
 * Sink for post-call usage accounting. Android backs it with the Room UsageDao;
 * the Companion with a SQLDelight table. Called AFTER a successful runner call,
 * on the same background thread as today.
 *
 * Threading parity: the Android adapter MUST preserve today's call semantics —
 * UsageDao.addUsage runs synchronously on the caller's background thread
 * (speechApiThread / rewordingApiThread). The adapter adds no threading.
 */
interface UsageSink {
    fun addUsage(
        modelName: String,
        audioDurationSeconds: Long,
        promptTokens: Long,
        completionTokens: Long,
        providerName: String,
    )
}
```

### 4.3 `ProxyConfig` — okhttp-Proxy-Anwendung

Kapselt die vier `DictateUtils`-Proxy-Methoden. Der Port bekommt den jeweiligen
SDK-Client-Builder und wendet den Proxy an (oder nicht). Das hält die
SDK-spezifischen Builder-Typen (openai/anthropic) aus dem Port heraus, indem er
je eine typspezifische Methode anbietet — analog zu heute zwei `DictateUtils`-
Methoden.

```kotlin
package net.devemperor.dictate.ai.port

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import java.net.Proxy

/**
 * Applies the user's proxy configuration to an SDK/okhttp client. Android backs
 * it with DictateUtils (SharedPreferences-driven); the Companion with its own
 * settings. A no-op when no valid proxy is configured — reproduces today's
 * `if (ProxyEnabled && isValidProxy(host))` guard.
 *
 * Note: the openai/anthropic builder types come from the SDKs, which are
 * `:shared-ai` dependencies — allowed here (unlike in `:shared`).
 */
interface ProxyConfig {
    fun applyTo(builder: OpenAIOkHttpClient.Builder)
    fun applyTo(builder: AnthropicOkHttpClient.Builder)
    /** For the raw-okhttp ElevenLabs runner: the resolved Proxy or null, plus
     *  installing the process-wide Authenticator (today's createProxy +
     *  applyProxyAuthenticator pair). */
    fun rawProxy(): Proxy?
    fun installAuthenticator()
}
```

> **Trade-off:** `rawProxy()`/`installAuthenticator()` bilden den ElevenLabs-Pfad
> ab, der okhttp direkt baut (kein SDK-Builder). Das ist etwas breiter als ideal,
> aber es spiegelt exakt die heutige `DictateUtils`-Oberfläche 1:1 und hält die
> Verhaltensneutralität nachweisbar. Eine spätere Vereinheitlichung (ein
> `configureOkHttp(OkHttpClient.Builder)`) ist möglich, sobald die SDK-Builder
> ihren okhttp-Builder exponieren — heute tun sie das nicht einheitlich, daher
> die drei Methoden.

### 4.4 `AudioDurationReader` — Audio-Dauer

Kapselt die einzige echte Android-Media-API im Kernpfad.

```kotlin
package net.devemperor.dictate.ai.port

import java.io.File

/**
 * Returns the audio duration in whole seconds, or -1 if unknown — reproduces
 * DictateUtils.getAudioDuration (android.media.MediaMetadataRetriever) exactly,
 * including the -1 fallback on any error. The Companion backs it with a
 * javax.sound / WAV-header reader (Block D).
 */
interface AudioDurationReader {
    fun durationSeconds(file: File): Long
}
```

### 4.5 Verdrahtung — wer konstruiert wen

`AIOrchestrator` und `RunnerFactory` bekommen die Ports per Konstruktor. Der
`open RunnerFactory`-Test-Seam (K-1) bleibt erhalten — Tests subklassen weiter.

```kotlin
// :shared-ai
class AIOrchestrator(
    private val config: AiConfig,
    private val usageSink: UsageSink,
    private val factory: RunnerFactory,
)

open class RunnerFactory(
    private val config: AiConfig,
    private val proxy: ProxyConfig,
    private val audioDuration: AudioDurationReader,
)
```

App-Seite (Adapter, in `app/.../ai/adapter/`):

```kotlin
// :app — dünne Delegation, kein neues Verhalten
class AndroidAiConfig(private val sp: SharedPreferences) : AiConfig { /* heutige RunnerFactory-Prefs-Logik */ }
class RoomUsageSink(private val usageDao: UsageDao) : UsageSink { override fun addUsage(...) = usageDao.addUsage(...) }
class SharedPrefsProxyConfig(private val sp: SharedPreferences) : ProxyConfig { /* delegiert an DictateUtils */ }
class MediaMetadataAudioDurationReader : AudioDurationReader { override fun durationSeconds(f) = DictateUtils.getAudioDuration(f) }
```

Der bisherige `AIOrchestrator(sp, usageDao)`-Konstruktor hat genau einen
Aufrufer-Kontext im IME-Service; er wird auf `AIOrchestrator(AndroidAiConfig(sp),
RoomUsageSink(usageDao), RunnerFactory(AndroidAiConfig(sp), SharedPrefsProxyConfig(sp), MediaMetadataAudioDurationReader()))`
umgestellt. Ein `:app`-seitiger Convenience-Factory (`fun androidOrchestrator(sp,
usageDao): AIOrchestrator`) kapselt die Verdrahtung an einer Stelle.

## 5. Gradle-Setup `:shared-ai`

### 5.1 `settings.gradle`

Nach der `:shared`/`:companion`-Include-Gruppe ergänzen:

```groovy
// :shared-ai — pure kotlin("jvm") module (jvmTarget 1.8), consumed by BOTH :app
//              and :companion. Android-free & Ktor-free (own purity test), but —
//              unlike :shared — the AI SDKs (openai-java, anthropic-java) and
//              okhttp ARE allowed. See ADR adr-shared-ai-module / ADR-0015.
include ':shared-ai'
```

### 5.2 `shared-ai/build.gradle`

```groovy
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// jvmTarget 1.8 — DO NOT raise without raising :app with it. :app consumes this
// module and Kotlin refuses to inline 11/17-bytecode into 8-bytecode (ADR-0015).
// The AI SDKs are compiled for Java 8 and already run inside :app today, so a
// 1.8 target is safe for them.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
    }
}

// No `repositories { }` — settings.gradle sets FAIL_ON_PROJECT_REPOS.

dependencies {
    // AI SDKs — the whole reason this is a separate module from :shared.
    api libs.openai.java
    api libs.anthropic.java
    // okhttp: raw client for the ElevenLabs runner + proxy plumbing.
    implementation libs.okhttp
    // kotlinx-serialization replaces org.json (ElevenLabs parse + keyterms).
    implementation libs.kotlinx.serialization.json

    testImplementation libs.junit
    testImplementation libs.okhttp.mockwebserver
}
```

> **`api` vs `implementation` für die SDKs:** `api`, weil Runner-DTOs/Exceptions
> und die `ProxyConfig`-Port-Signaturen SDK-Builder-Typen auf der öffentlichen
> Oberfläche tragen (`OpenAIOkHttpClient.Builder` etc.). `:app` compiliert heute
> schon gegen diese Typen — `api` hält das transitiv verfügbar. Verifikation im
> Chunk: falls die Runner-Oberfläche keine SDK-Typen leakt, auf `implementation`
> zurückstufen (schmaler ist besser). Der `ProxyConfig`-Port leakt sie definitiv
> → mindestens er braucht `api` auf die SDKs.

### 5.3 `SharedAiPurityTest`

`shared-ai/src/test/kotlin/net/devemperor/dictate/ai/SharedAiPurityTest.kt` —
1:1 nach `SharedPurityTest`-Vorbild, mit **abweichender** verbotener Liste:

```kotlin
private val forbiddenImports = mapOf(
    "android." to "Android APIs — :shared-ai must stay consumable from the desktop companion",
    "androidx." to "AndroidX APIs — :shared-ai must stay consumable from the desktop companion",
    "io.ktor" to "Ktor — the server lives in :companion only; :shared-ai compiles at jvmTarget 1.8",
    // NOTE: coroutines are NOT forbidden here by necessity, but :shared-ai is
    // blocking-by-design (SDKs on background executors) — keep it coroutine-free
    // to stay consumable from :app's 1.7.3 line. Add "kotlinx.coroutines" if the
    // team wants it pinned (recommended — mirrors :shared).
    "org.json" to "org.json — not on pure JVM; use kotlinx-serialization",
)
```

Der `theTestItself_findsAViolationWhenThereIsOne`-Selbsttest wird übernommen
(Scanner-liest-wirklich-Garantie). **Empfehlung:** `kotlinx.coroutines` und
`org.json` in die verbotene Liste aufnehmen — ersteres spiegelt die
`:shared`-Doktrin, letzteres pinnt die A3.4-Umstellung dauerhaft.

## 6. Move-Sequenz (kompilierfähige Schritte)

Jeder Schritt ist ein Commit; nach jedem Schritt muss `./gradlew build` grün
sein. Präfix `[A.2]` bzw. `[A.3]` (Plan-Conventions). `[MOVE]`/`[EDIT]`/`[NEW]`.

### Chunk A2 — Pure Moves (verhaltensneutral, keine Signaturänderung)

**A2.0 — Modul-Scaffold + Purity-Test [NEW].**
`settings.gradle`-Include, `shared-ai/build.gradle` (§5.2), `SharedAiPurityTest`
(§5.3). `:app` bekommt `implementation project(':shared-ai')` in `app/build.gradle`.
Build grün (leeres Modul). Der Purity-Test läuft (scannt zunächst nur sich selbst
— sein `sources.size > 5`-Guard ggf. lockern, bis Dateien da sind, ODER A2.0 mit
A2.1 zusammenlegen, damit sofort echte Quellen vorhanden sind — **Empfehlung:
zusammenlegen**).

**A2.1 — Enums zuerst [MOVE].**
`git mv` von `MessageRole.kt`, `ResponseFormatKind.kt` (aus
`app/.../database/entity/`) und `AmbiguityMode.kt` (aus `app/.../preferences/`)
nach `shared-ai/src/main/kotlin/net/devemperor/dictate/{database/entity,preferences}/`.
**Package-Zeile unverändert.** Zielverzeichnis spiegelt das Package. Keine
Import-Änderung in `:app` nötig (Split-Package). Build grün. Grund für „zuerst":
die conversation/runner-Moves in A2.2 referenzieren sie; sind sie schon im
Zielmodul, entsteht keine transiente Rückwärts-Kante `:shared-ai → :app`.

**A2.2 — 0-Dependency-Kern [MOVE].** `git mv` (Package unverändert
`net.devemperor.dictate.ai.*`, Zielbaum `shared-ai/src/main/kotlin/…/ai/`):
- Wurzel: `AIProvider.kt` (inkl. `AIFunction`), `AIProviderException.kt`.
- `model/`: `ModelInfo`, `ParameterDef`, `ParameterRegistry`.
- `runner/` Interfaces + DTOs: `TranscriptionRunner`, `CompletionRunner`,
  `TranscriptionOptions/Result`, `CompletionOptions/Result`,
  `ConversationRequest/Result`, `StructuredOutputGuards`.
- `prompt/`: `PromptContext`, `PromptMode`, `PromptBuilder`, `PromptTemplates`.
- `conversation/`: **alle 10 Dateien** (`ConversationMessage`,
  `ConversationReconstructor`, `ConversationTurnBuilder`, `PostProcessingInputs`,
  `PostProcessingReview`, `ReviewDecision`, `StructuredResponse`,
  `StructuredResponseCodec`, …).

Build grün: alles hier hängt nur an §3.4-Enums (jetzt im selben Modul) und
untereinander. **Bleibt in `:app`:** `prompt/PromptTypeClassifier.kt` (Kat. c,
braucht `PromptType`, das in `:app` bleibt).

**A2.3 — pure Tests mitziehen [MOVE].** Die reinen JUnit-Tests (kein Robolectric,
keine SharedPreferences) nach `shared-ai/src/test/…`:
`conversation/ConversationReconstructorTest`, `ConversationTurnBuilderTest`,
`ReviewDecisionTest`, `StructuredResponseCodecTest`,
`runner/StructuredOutputGuardsTest`, `runner/StructuredOutputSupportTest`.
`PromptTypeClassifierTest` bleibt in `:app` (testet die in `:app` verbleibende
Klasse). Build + `:shared-ai:test` grün.

### Chunk A3 — Ports + Runner/Orchestrator (Signaturänderungen)

> Reihenfolge: **erst** Charakterisierungs-Tests (§8) in `:app` schreiben und
> grün sehen, **dann** Ports einführen, **dann** die (b)-Klassen migrieren.

**A3.1 — Ports definieren [NEW].** Die vier Port-Interfaces (§4) in
`shared-ai/.../ai/port/`. Build grün (nur Interfaces).

**A3.2 — Runner migrieren [MOVE+EDIT].** `OpenAICompatibleRunner`,
`AnthropicCompletionRunner`, `ElevenLabsTranscriptionRunner` nach `:shared-ai`;
Konstruktor `sp: SharedPreferences` → `proxy: ProxyConfig` (+ `audioDuration:
AudioDurationReader` für die zwei Transkriptions-Runner). Rumpf-Edits:
`sp.get(Pref.Proxy*) + DictateUtils.applyProxy…` → `proxy.applyTo(builder)`;
`DictateUtils.getAudioDuration(f)` → `audioDuration.durationSeconds(f)`. Keine
sonstige Logikänderung.

**A3.3 — RunnerFactory migrieren [MOVE+EDIT].** `factory/RunnerFactory.kt` →
`:shared-ai`; `sp`-Zugriffe (Provider/Model/Key/BaseUrl) → `config: AiConfig`.
Die Key/Model/BaseUrl-Auswahl-Logik wandert **in den `AndroidAiConfig`-Adapter**
(`:app`), die Factory ruft nur noch `config.provider/modelName/apiKey/baseUrl`.
`open`-Seam bleibt.

**A3.4 — ModelFetcher + ElevenLabsKeytermsParser [MOVE+EDIT].**
`ModelFetcher`: `sp`→`ProxyConfig`. `ElevenLabsKeytermsParser`: `org.json.JSONArray`
→ kotlinx-serialization (`Json.encodeToString`/`decodeFromString` einer
`List<String>`; `toJson`/`fromJson`-Kontrakt unverändert — leere/`"[]"`-Eingabe
→ leere Liste, defensiver try/catch bleibt). Der ElevenLabs-Response-`text`-Parse
im Runner (`JSONObject(body).optString("text")`) ebenfalls auf
kotlinx-serialization (`Json.parseToJsonElement`).

**A3.5 — Prompt-Service + Punctuation-Tabellen [MOVE+EDIT].** `PromptService`,
`SystemPromptResolver` → `:shared-ai`; `sp`-Zugriffe → `AiConfig` (bzw. ein
schmaler Prompt-Config-Zugang; die Prompt-Prefs `StylePromptSelection`,
`SystemPromptSelection`, `*CustomText` in den Adapter). `PROMPT_PUNCTUATION_*`-
Tabellen + `getPunctuationPromptForLanguage`-Logik von `DictateUtils` nach
`PromptTemplates` (§3.5 Option i). **Prüfen:** ob `getPunctuationPromptForLanguage`
noch andere `:app`-Aufrufer hat — falls ja, `DictateUtils` daran delegieren
lassen (Rückwärtskompatibilität), sonst entfernen.

**A3.6 — Orchestrator migrieren [MOVE+EDIT].** `AIOrchestrator` → `:shared-ai`;
`sp`/`usageDao` → `AiConfig`/`UsageSink`. `resolveParameters` +
`elevenLabsKeyterms`-Auflösung wandern in `AndroidAiConfig`
(`completionParameters`/`elevenLabsKeyterms`). Der Orchestrator wird prefs-frei.

**A3.7 — Adapter + Verdrahtung [NEW+EDIT].** `app/.../ai/adapter/`:
`AndroidAiConfig`, `RoomUsageSink`, `SharedPrefsProxyConfig`,
`MediaMetadataAudioDurationReader` (§4.5). IME-Service-Aufrufstelle auf den neuen
Orchestrator-Konstruktor umstellen (Convenience-Factory). `AIOrchestratorConverseTest`
(Robolectric, bleibt in `:app`) auf die neue Signatur anpassen — Assertions
unverändert. Build + alle Tests grün = Kriterium §2.4.

## 7. Directory Layout

```
Dictate/
├── settings.gradle                         [EDIT]  + include ':shared-ai'
├── shared-ai/                              [NEW]   viertes Modul
│   ├── build.gradle                        [NEW]   §5.2 (jvmTarget 1.8, SDKs)
│   └── src/
│       ├── main/kotlin/net/devemperor/dictate/
│       │   ├── ai/                         [MOVE]  Kern (AIProvider … Orchestrator)
│       │   │   ├── port/                   [NEW]   AiConfig/UsageSink/ProxyConfig/AudioDurationReader
│       │   │   ├── factory/RunnerFactory   [MOVE+EDIT]
│       │   │   ├── model/                  [MOVE]  ModelInfo/ParameterDef/Registry/ModelFetcher(EDIT)
│       │   │   ├── prompt/                  [MOVE]  (ohne PromptTypeClassifier)
│       │   │   ├── conversation/            [MOVE]  alle 10
│       │   │   └── runner/                  [MOVE+EDIT]  Runner + DTOs
│       │   ├── database/entity/            [MOVE]  MessageRole, ResponseFormatKind (Package unverändert)
│       │   └── preferences/                [MOVE]  AmbiguityMode (Package unverändert)
│       └── test/kotlin/net/devemperor/dictate/ai/
│           ├── SharedAiPurityTest          [NEW]   §5.3
│           ├── conversation/               [MOVE]  4 pure Tests
│           └── runner/                     [MOVE]  StructuredOutput* Tests
├── app/
│   ├── build.gradle                        [EDIT]  + implementation project(':shared-ai')
│   └── src/main/java/net/devemperor/dictate/
│       └── ai/
│           ├── adapter/                     [NEW]   4 Port-Adapter (§4.5)
│           └── prompt/PromptTypeClassifier  [KEEP]  bleibt (Kat. c, PromptType)
```

**File-Delta:** ~30 Moves (26 `ai/`-Dateien minus PromptTypeClassifier + 3 Enums
+ pure Tests), 2 neue Build-Einträge, 1 neuer Purity-Test, 4 neue Ports, 4 neue
Adapter, ~8 (b)-Klassen mit Signatur-Edit.

## 8. Charakterisierungs- und Regressionstests

Konvention: `~/.claude/snippets/test-first-patterns.md`. Die Charakterisierungs-
Tests (8.1) werden **vor** A3 geschrieben, gegen den heutigen Prefs-basierten
Code, und laufen nach dem Move unverändert gegen die Adapter — das ist der
Verhaltensneutralitäts-Beweis (Plan-§8 Punkt 1).

### 8.1 Neue Charakterisierungs-Tests (VOR A3, in `:app`)

| Test (neu) | Fixiert | Warum load-bearing |
|---|---|---|
| `AiConfigParityTest` | Für je eine Fixture-Prefs-Konstellation pro Provider (OPENAI/GROQ/ANTHROPIC/OPENROUTER/ELEVENLABS/CUSTOM): `provider/modelName/apiKey/baseUrl` = exakt die heutigen `RunnerFactory`-Werte, inkl. **Non-ASCII-Key-Strip** und CUSTOM-Host-Auflösung | die Key/Model/BaseUrl-Auswahl wandert von `RunnerFactory` in `AndroidAiConfig` — dieser Test beweist Gleichheit |
| `ParameterResolutionParityTest` | `completionParameters` = heutiges `AIOrchestrator.resolveParameters` inkl. Sentinel-Filter (temp<0, maxTokens≤0, leeres reasoning_effort) für alle `PARAMETER_PREFS`-Provider | `resolveParameters` wandert in den Adapter |
| `ProxyConfigParityTest` | Für proxy-on/off × http/socks5 × mit/ohne user:pass: `rawProxy()` liefert denselben `Proxy` wie `DictateUtils.createProxy`; `applyTo` setzt `.proxy(...)` genau dann, wenn heute | Proxy-Pfad ist das, was bei Runnern „silent" bricht |
| `ElevenLabsKeytermsSerializationParityTest` | `toJson`/`fromJson` nach kotlinx-Umstellung = org.json-Verhalten (leere Eingabe → `[]`/leer; Round-Trip Unicode-Terme) | A3.4 tauscht die JSON-Lib |

### 8.2 Bestehende Tests, die den AI-Layer bereits abdecken (bleiben grün)

- `ai/runner/ElevenLabsTranscriptionRunnerTest` — Multipart-Body-Wire-Format
  (keyterms als repeated parts, model_id). Nach A3.2 gegen `ProxyConfig`-Fake
  statt `FakeSharedPreferences`; Assertions unverändert. **Wandert nach
  `:shared-ai`.**
- `ai/AIOrchestratorConverseTest` — converse-Passthrough + Usage-Tracking über
  `open RunnerFactory`-Seam. **Bleibt in `:app`** (Robolectric), auf neue
  Konstruktor-Signatur angepasst, Assertions unverändert.
- `ai/conversation/*Test` (4×), `ai/runner/StructuredOutput*Test` (2×) — pure,
  **wandern nach `:shared-ai`** (A2.3).
- `ai/ElevenLabsKeytermsParserTest` — wandert nach `:shared-ai`; nach A3.4 ohne
  Robolectric (org.json entfällt).
- `ai/prompt/PromptTypeClassifierTest` — **bleibt in `:app`** (testet die dort
  verbleibende Klasse).

### 8.3 Enum-Move-Absicherung (bestehende Tests, unverändert grün)

`database/migration/MigrationTo8Test`/`MigrationTo11Test`, `dao/PromptDaoAutoApplyTest`,
`dao/Session*Test` referenzieren die Double-Enum-Werte. Bleiben unverändert grün,
weil Package + Konstanten der bewegten Enums gleich bleiben (§3.4) — sie sind der
Beweis, dass der Enum-Move das Room-Schema nicht berührt.

### 8.4 Purity-Negativtest

`SharedAiPurityTest.theTestItself_findsAViolationWhenThereIsOne` (übernommen) +
manuelle Einmal-Verifikation aus §2.3 (fabrizierter `android.`-Import macht rot).

## 9. Footguns / Anti-Patterns

| Anti-pattern | Warum schlecht | Korrektur |
|---|---|---|
| `:shared-ai` auf jvmTarget 17 setzen (wie `:companion`) | `:app` (1.8) kann den Bytecode nicht inlinen; Fehler erscheint beim nächsten `:app`-Compile, weit weg von `shared-ai/build.gradle` | jvmTarget **1.8** + Inline-Kommentar (§5.2), ADR-0015-Failure-Mode |
| Enum-Package auf `ai.*` umbenennen beim Move | 25+ `:app`-Dateien + Room-Konverter/CHECK-Bezüge müssten mit; Migrations-Tests brechen | Package unverändert lassen, nur physisch verschieben (Split-Package, §3.4) |
| `PromptTypeClassifier` mit nach `:shared-ai` ziehen (Plan-A2 listet es) | schleppt `PromptType` (16 `:app`-Pill-Dateien) mit; Pills sind bewusst Desktop-fremd | Kat. (c): in `:app` lassen; Abweichung von Plan-A2 in §12/Bericht vermerkt |
| Ports „dick" machen (Pref-Schlüssel durchreichen) | der Kern kennt wieder Plattform-Details; Companion müsste Pref-Semantik nachbauen | Ports liefern **aufgelöste** Werte (§4); Auflösung im Adapter |
| Charakterisierungs-Tests erst nach dem Move schreiben | „Test, der nie rot war" — beweist keine Neutralität | Tests VOR A3 gegen Prefs-Code, danach unverändert gegen Adapter (§8.1) |
| `org.json` im `:shared-ai`-Baum lassen | auf purem JVM (Companion) nicht vorhanden → NoClassDefFound zur Laufzeit | kotlinx-serialization (A3.4); optional per Purity-Test gepinnt (§5.3) |
| Split-Package als Dauerlösung akzeptieren, ohne es zu benennen | zwei Module teilen `database.entity`/`preferences` — bei späterem JPMS/`module-info` ein Konflikt | bewusst dokumentierter Trade-off; §10 Gap 1 als Owner |

## 10. Information Gaps

1. **Split-Package `database.entity`/`preferences` über Modulgrenze.** Die drei
   bewegten Enums teilen ihr Package künftig zwischen `:app` und `:shared-ai`.
   Bei jvmTarget 1.8 ohne `module-info` unkritisch (Android hat kein JPMS), aber
   ein latenter Smell. *Owner:* A3-Agent dokumentiert es im ADR-Draft
   `adr-shared-ai-module`; *Fallback:* akzeptiert für v1, spätere Konsolidierung
   der geteilten Enums nach `:shared` (bei Block C, wo Entitäten ohnehin nach
   `:shared` wandern) als dokumentierte Option.
2. **`api` vs `implementation` für die SDKs.** Ob die Runner-Oberfläche
   SDK-Typen leakt (→ `api` nötig) oder nur `ProxyConfig` (→ SDKs `implementation`,
   Port `api`), zeigt der Compile. *Owner:* A2.0-Agent verifiziert am grünen
   Build; *Fallback:* `api` (breiter, sicher), im Chunk auf `implementation`
   verschmälern falls möglich (§5.2).
3. **Weitere `DictateUtils.getPunctuationPromptForLanguage`-Aufrufer.** Ob die
   Punctuation-Tabellen nach `PromptTemplates` ziehen können, ohne `:app`-Rest zu
   brechen. *Owner:* A3.5-Agent (grep + Delegations-Fallback); *Fallback:*
   `DictateUtils` delegiert an die verschobenen Tabellen.
4. **Anthropic/openai-java-Bytecode-Target.** Annahme: beide für Java 8
   kompiliert (laufen heute in `:app` @1.8). *Owner:* A2.0-Agent verifiziert am
   Build; *Fallback:* falls ein SDK 11-Bytecode trägt, ist das ein echter
   Blocker → Eskalation (würde `:app`-jvmTarget-Bump erzwingen, außerhalb Block A).

## 11. Change History

### 2026-07-19 — Initialfassung

- **Trigger:** Team-Lead-Auftrag „Spec-Recherche Block A: shared-ai" auf Basis
  des implementierungsbereiten Plans `desktop-companion-v1`.
- **Reasoning:** Erschöpfende Ist-Code-Recherche (`app/.../ai/` 36 Dateien,
  `DictateUtils`, geteilte Enums, bestehende Tests) + Port-Design + kompilierfähige
  Move-Sequenz, damit A2/A3 ohne weitere Grundsatzfragen ausführbar sind.
- **What changed:** Erstfassung — Inventar (§3), 4 Ports (§4), Gradle-Setup (§5),
  7-Schritt-Move-Sequenz (§6), Charakterisierungs-Tests (§8).

### 2026-07-20 — Cross-Spec-Entscheidungen eingearbeitet (Plan §3 D5)

- **Trigger:** Plan-Verfeinerung durch den Architekten nach Vorliegen aller
  fünf Block-Specs; drei Entscheidungen betreffen diese Spec.
- **Reasoning:** (a) **Enum-Placement bestätigt (D5.a):** Die Moves aus §3.4
  bleiben wie spezifiziert — `AIProvider`/`AmbiguityMode`/`MessageRole`/
  `ResponseFormatKind` nach `:shared-ai`, KEIN Move nach `:shared`, KEINE
  `:shared-ai`→`:shared`-Kante; `:shared` definiert eigene Wire-Enums
  (entitaetenmodell §4.8/D6), Paritäts-Tests + Mapper in `:app` — konsistent
  mit der ADR-0016-Wire-vs-Domain-Doktrin. (b) **PromptTypeClassifier-
  Abweichung bestätigt (D5.d):** bleibt in `:app` (Kat. c), die Plan-A2-Liste
  wurde entsprechend korrigiert — der §9-Footgun-Eintrag ist damit
  plan-konform. (c) **Scope-Erweiterung A2 (D5.e):** `core/AmplitudeProcessor.kt`
  (pures `kotlin.math`, heute `app/.../core/`) wird ZUSÄTZLICH package-erhaltend
  nach `:shared-ai` gemovt (Split-Package `net.devemperor.dictate.core`,
  gleiches Muster wie §3.4) — statt der in desktop-host §15 Gap 4 zunächst
  vorgesehenen `:companion`-Kopie; Begründung: die Amplituden-Kurvenparameter
  sind die F19-Design-Spec, eine Kopie driftet unsichtbar.
- **What changed:** Kein Body-Umbau nötig (a/b bestätigen den Spec-Stand);
  A2-Move-Umfang um `AmplitudeProcessor` erweitert (dieser Eintrag ist die
  normative Quelle dafür; §3-Inventar listet nur `ai/`-Dateien).

## 12. References

- **Related Plan:** `~/.claude/plans/desktop-companion-v1.md` — §5 Block A
  (Chunks A1–A3), §3 D1, §7 depends_on (A3→A2, B1→A2), Plan-Conventions-Block.
- **Related ADRs:** `docs/decisions/0015-companion-monorepo-topology.md`
  (Monorepo-Topologie, Kotlin-Ceiling 2.1.20, jvmTarget-1.8-Inline-Constraint,
  `SharedPurityTest`-Vorbild), `docs/decisions/0016-wire-protocol-typed-dtos-konform.md`
  (SSoT-Doktrin, warum Duplikation vermieden wird). Plan-scoped ADR-Draft
  `adr-shared-ai-module` (A1) trägt die Grundsatzentscheidung; diese Spec setzt sie um.
- **Quell-Code (Extraktions-Basis):**
  - `app/src/main/java/net/devemperor/dictate/ai/` (36 Dateien, §3)
  - `app/.../ai/AIOrchestrator.kt:29-33` (Konstruktor sp/usageDao),
    `:168-182` (resolveParameters), `:50-52` (Keyterms)
  - `app/.../ai/factory/RunnerFactory.kt:83-113` (getApiKey/getBaseUrl),
    `:102` (Non-ASCII-Strip)
  - `app/.../ai/runner/OpenAICompatibleRunner.kt:43-57` (buildClient/Proxy),
    `:98` (getAudioDuration)
  - `app/.../ai/runner/ElevenLabsTranscriptionRunner.kt:31-49` (Proxy),
    `:118-147` (buildMultipartBody), `:98` (org.json-Parse)
  - `app/.../DictateUtils.java:157-171` (getAudioDuration),
    `:205-276` (createProxy/applyProxyAuthenticator/applyProxy/applyProxyToAnthropic),
    `:100-116` (getPunctuationPromptForLanguage)
  - Geteilte Enums: `database/entity/MessageRole.kt`, `ResponseFormatKind.kt`,
    `PromptType.kt`, `preferences/AmbiguityMode.kt`
- **Vorbilder:** `shared/build.gradle` (jvmTarget-1.8-Muster),
  `shared/src/test/kotlin/net/devemperor/dictate/shared/SharedPurityTest.kt`
  (Purity-Test-Vorlage), `settings.gradle:24-27` (Include-Muster)
- **Konventionen:** `docs/DATABASE-PATTERNS.md` (Double-Enum + CHECK),
  `~/.claude/snippets/test-first-patterns.md` (Charakterisierung/Regression),
  CLAUDE.md (AI geht nur über `AIOrchestrator`, neue Kotlin/legacy Java)
