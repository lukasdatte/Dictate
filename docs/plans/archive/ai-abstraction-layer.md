# Implementierungsplan: Dictate AI-Abstraktionsschicht

## Zusammenfassung

Dieser Plan beschreibt den Umbau der monolithischen AI-Integration in `DictateInputMethodService.java` (2252 Zeilen God-Class) zu einer SOLID-konformen, erweiterbaren Abstraktionsschicht in Kotlin. Die bestehende int-basierte Provider-Auswahl (0=OpenAI, 1=Groq, 2=Custom) wird durch ein typsicheres Enum ersetzt, duplizierter API-Code in Runner-Interfaces konsolidiert, hardcodierte Modell-Listen durch eine hybride Registry (API + lokale Known-Models) abgeloest, und die zwei separaten SQLite-Datenbanken (usage.db, prompts.db) in eine einzige Room-Datenbank migriert.

Das Ziel ist maximale Rueckwaertskompatibilitaet: Bestehender Java-Code (insbesondere DictateInputMethodService.java) wird schrittweise angepasst, nicht komplett neu geschrieben. Neuer Code wird in Kotlin geschrieben und ist von Java aus aufrufbar.

## Architektur-Entscheidungen

1. **Kotlin neben Java (kein Rewrite)**: Die God-Class bleibt vorerst in Java. Neuer Code wird in Kotlin geschrieben. Java ruft Kotlin auf – das funktioniert nahtlos, solange Kotlin-Klassen `@JvmStatic`, `@JvmField` und `@JvmOverloads` korrekt verwenden. Ein vollstaendiger Rewrite der 2252-Zeilen-Klasse waere zu riskant.

2. **OpenAI-kompatibles Protokoll als Basis**: Groq und Custom-Provider verwenden das OpenAI-API-Format (nur andere Base-URL). Daher implementiert ein einziger `OpenAICompatibleRunner` alle drei Varianten. Anthropic bekommt einen separaten Runner, da Claude ein voellig anderes API-Format nutzt.

3. **Runner-Pattern statt Strategy-Pattern**: Runner sind zustandslose Ausfuehrungseinheiten mit einer klaren Methode (`transcribe` / `complete`). Sie werden von einer Factory erstellt und vom Orchestrator koordiniert. Das ist einfacher als eine volle Strategy-Hierarchie und passt besser zur Android-Lifecycle-Realitaet.

4. **Room statt SQLiteOpenHelper**: Room bietet compile-time Query-Verifikation, TypeConverter-Support und ein sauberes Migrations-Framework. Die Migration der Altdaten (usage.db v2, prompts.db v2) erfolgt beim ersten Start ueber einen einmaligen Migrationsschritt ausserhalb von Room.

5. **Hybride Model-Registry**: Rein dynamisches Fetching (API) ist zu langsam fuer eine Keyboard-App (Latenz beim Oeffnen der Settings). Rein statische Listen (aktueller Stand) veralten schnell. Die Loesung: Lokale Known-Models als sofort verfuegbarer Fallback + periodischer API-Sync im Hintergrund.

6. **SharedPreferences-Keys bleiben kompatibel**: Bestehende Keys (`net.devemperor.dictate.transcription_provider`, `net.devemperor.dictate.transcription_api_key_openai` etc.) werden weiterhin gelesen. Neue Keys werden nur fuer neue Features eingefuehrt. So funktioniert ein App-Update ohne Datenverlust.

7. **Java 8 Source-Kompatibilitaet beibehalten**: Das Projekt nutzt `sourceCompatibility = JavaVersion.VERSION_1_8`. Kotlin's `jvmTarget = "1.8"` ist kompatibel. Room 2.6.x unterstuetzt Java 8.

8. **Kein Dependency Injection Framework**: Fuer eine Keyboard-App (kein Activity-Lifecycle-Management noetig) ist Hilt/Dagger Overkill. Stattdessen manuelle Constructor-Injection + Factory-Pattern.

9. **Anthropic SDK hinzufuegen, OpenAI SDK updaten**: Das Anthropic Java SDK (`com.anthropic:anthropic-java`) nutzt dasselbe OkHttp-Muster wie das OpenAI SDK. OpenAI wird von 4.13.0 auf 4.26.0 aktualisiert (neue Modelle, Bug-Fixes).

10. **Retry-Logik zentralisieren**: Aktuell ist die Retry-Logik (3 Retries, 3s Backoff) identisch in `startWhisperApiRequest()` (Zeile 1516-1533) und `requestRewordingFromApi()` (Zeile 1757-1773) dupliziert. Diese wird in eine gemeinsame Utility-Funktion extrahiert.

## Package-Struktur

```
app/src/main/java/net/devemperor/dictate/
├── core/
│   └── DictateInputMethodService.java          # MODIFY: API-Logik → Orchestrator-Aufrufe
├── ai/                                          # CREATE: Neues Package
│   ├── AIProvider.kt                            # CREATE: Provider-Enum
│   ├── AIOrchestrator.kt                        # CREATE: Zentrale Orchestrierung
│   ├── AIProviderException.kt                   # CREATE: Unified Exception
│   ├── runner/                                  # CREATE: Runner-Package
│   │   ├── TranscriptionRunner.kt               # CREATE: Interface
│   │   ├── CompletionRunner.kt                  # CREATE: Interface
│   │   ├── TranscriptionResult.kt               # CREATE: Ergebnis-Datenklasse
│   │   ├── CompletionResult.kt                  # CREATE: Ergebnis-Datenklasse
│   │   ├── TranscriptionOptions.kt              # CREATE: Options-Datenklasse
│   │   ├── CompletionOptions.kt                 # CREATE: Options-Datenklasse
│   │   ├── RunnerDescriptor.kt                  # CREATE: Self-Description fuer Settings-UI
│   │   ├── OpenAICompatibleRunner.kt            # CREATE: OpenAI/Groq/Custom
│   │   ├── AnthropicCompletionRunner.kt         # CREATE: Claude-only
│   │   └── RetryExecutor.kt                     # CREATE: Gemeinsame Retry-Logik
│   ├── factory/
│   │   └── RunnerFactory.kt                     # CREATE: Erstellt Runner aus Preferences
│   └── model/                                   # CREATE: Model-Registry
│       ├── ModelCapability.kt                    # CREATE: Enum (TRANSCRIPTION, COMPLETION)
│       ├── ModelDescriptor.kt                    # CREATE: Modell-Metadaten
│       ├── KnownModels.kt                       # CREATE: Statische Known-Models-Map
│       ├── ModelCacheManager.kt                  # CREATE: Sync-Logik
│       └── PricingCalculator.kt                 # CREATE: Ersetzt DictateUtils.calcModelCost()
├── database/                                    # CREATE: Room-Package
│   ├── DictateDatabase.kt                       # CREATE: Room Database
│   ├── entity/
│   │   ├── UsageEntity.kt                       # CREATE: Ersetzt UsageModel
│   │   ├── PromptEntity.kt                      # CREATE: Ersetzt PromptModel
│   │   ├── CachedModelEntity.kt                 # CREATE: Model-Cache
│   │   └── ModelSelectionEntity.kt              # CREATE: Aktive Modell-Auswahl
│   ├── dao/
│   │   ├── UsageDao.kt                          # CREATE: Ersetzt UsageDatabaseHelper
│   │   ├── PromptDao.kt                         # CREATE: Ersetzt PromptsDatabaseHelper
│   │   ├── CachedModelDao.kt                    # CREATE: Model-Cache CRUD
│   │   └── ModelSelectionDao.kt                 # CREATE: Aktive Auswahl CRUD
│   ├── converter/
│   │   └── Converters.kt                        # CREATE: TypeConverter fuer Enums
│   └── migration/
│       └── LegacyDatabaseMigrator.kt            # CREATE: usage.db + prompts.db → Room
├── usage/
│   ├── UsageDatabaseHelper.java                 # DEPRECATE (bleibt fuer Migration lesbar)
│   └── UsageModel.java                          # DEPRECATE
├── rewording/
│   ├── PromptsDatabaseHelper.java               # DEPRECATE
│   └── PromptModel.java                         # DEPRECATE
├── settings/
│   └── APISettingsActivity.java                 # MODIFY: Dynamische Provider/Modelle
└── DictateUtils.java                            # MODIFY: calcModelCost() → PricingCalculator
```

Modifizierte Build-Dateien:
```
build.gradle (project)                           # MODIFY: Kotlin + KSP Plugins
app/build.gradle                                 # MODIFY: Kotlin, Room, Anthropic deps
gradle/libs.versions.toml                        # MODIFY: Neue Versionen + Libraries
app/src/main/res/values/arrays.xml               # MODIFY: Provider-Liste erweitern (Anthropic)
```

## Implementierungs-Tasks

---

### Gruppe 1: Foundation (Parallel, keine Dependencies)

Diese Tasks sind voneinander unabhaengig und koennen gleichzeitig bearbeitet werden.

---

**Task 1.1: Kotlin + KSP + Room in Gradle einrichten**

- **Beschreibung**: Kotlin-Plugin, KSP (fuer Room Annotation Processing) und Room-Dependencies zum Build hinzufuegen. Java-Kompatibilitaet sicherstellen.

- **Dateien**:
  - MODIFY `/home/lukas/WebStorm/Dictate/gradle/libs.versions.toml`
  - MODIFY `/home/lukas/WebStorm/Dictate/app/build.gradle`
  - MODIFY `/home/lukas/WebStorm/Dictate/build.gradle` (falls Project-Level existiert, sonst `settings.gradle`)

- **Details**:

  `gradle/libs.versions.toml` – neue Eintraege:
  ```toml
  [versions]
  kotlin = "2.1.20"
  ksp = "2.1.20-1.0.32"
  room = "2.6.1"
  anthropicJava = "4.3.0"
  openaiJava = "4.26.0"      # Update von 4.13.0

  [libraries]
  room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
  room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
  room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
  anthropic-java = { module = "com.anthropic:anthropic-java", version.ref = "anthropicJava" }

  [plugins]
  kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
  ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
  ```

  `app/build.gradle` – Aenderungen:
  ```groovy
  plugins {
      alias(libs.plugins.android.application)
      alias(libs.plugins.kotlin.android)
      alias(libs.plugins.ksp)
  }

  android {
      // Bestehende Config...
      compileOptions {
          sourceCompatibility JavaVersion.VERSION_1_8
          targetCompatibility JavaVersion.VERSION_1_8
      }
      kotlinOptions {
          jvmTarget = '1.8'
      }
  }

  dependencies {
      // Bestehende deps...
      implementation libs.openai.java          // Version-Update via TOML
      implementation libs.anthropic.java       // NEU
      implementation libs.room.runtime
      implementation libs.room.ktx
      ksp libs.room.compiler
  }
  ```

  Room Schema-Export aktivieren:
  ```groovy
  ksp {
      arg("room.schemaLocation", "$projectDir/schemas")
  }
  ```

- **Abhaengigkeiten**: Keine
- **Erfolgskriterium**: `./gradlew assembleDebug` kompiliert erfolgreich. Ein leeres `.kt`-File im Projekt wird ohne Fehler kompiliert.

---

**Task 1.2: AIProvider Enum erstellen**

- **Beschreibung**: Typsicheres Enum fuer Provider, ersetzt die int-basierte Auswahl (0, 1, 2) die aktuell in `APISettingsActivity.java` (Zeile 106, 197) und `DictateInputMethodService.java` (Zeile 1484, 1705) verwendet wird.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIProvider.kt`

- **Details**:
  ```kotlin
  package net.devemperor.dictate.ai

  enum class AIProvider(
      val displayName: String,
      val defaultBaseUrl: String,
      val supportsTranscription: Boolean,
      val supportsCompletion: Boolean,
      val legacyIndex: Int  // Rueckwaertskompatibilitaet mit SharedPreferences int-Werten
  ) {
      OPENAI(
          displayName = "OpenAI",
          defaultBaseUrl = "https://api.openai.com/v1/",
          supportsTranscription = true,
          supportsCompletion = true,
          legacyIndex = 0
      ),
      GROQ(
          displayName = "Groq",
          defaultBaseUrl = "https://api.groq.com/openai/v1/",
          supportsTranscription = true,
          supportsCompletion = true,
          legacyIndex = 1
      ),
      ANTHROPIC(
          displayName = "Anthropic",
          defaultBaseUrl = "https://api.anthropic.com/v1/",
          supportsTranscription = false,
          supportsCompletion = true,
          legacyIndex = 3  // Neuer Index, kein Konflikt mit bestehenden
      ),
      CUSTOM(
          displayName = "Custom",
          defaultBaseUrl = "",
          supportsTranscription = true,
          supportsCompletion = true,
          legacyIndex = 2
      );

      /** SharedPreferences-Key fuer den API-Key dieses Providers (pro Funktionsbereich). */
      fun apiKeyPrefKey(function: AIFunction): String {
          val suffix = name.lowercase()
          return when (function) {
              AIFunction.TRANSCRIPTION -> "net.devemperor.dictate.transcription_api_key_$suffix"
              AIFunction.COMPLETION -> "net.devemperor.dictate.rewording_api_key_$suffix"
          }
      }

      companion object {
          /** Konvertiert den alten int-Index aus SharedPreferences zum Enum. */
          @JvmStatic
          fun fromLegacyIndex(index: Int): AIProvider =
              entries.find { it.legacyIndex == index } ?: OPENAI

          /** Alle Provider die Transcription unterstuetzen. */
          @JvmStatic
          fun withTranscription(): List<AIProvider> =
              entries.filter { it.supportsTranscription }

          /** Alle Provider die Completion unterstuetzen. */
          @JvmStatic
          fun withCompletion(): List<AIProvider> =
              entries.filter { it.supportsCompletion }
      }
  }

  enum class AIFunction {
      TRANSCRIPTION,
      COMPLETION
  }
  ```

- **Abhaengigkeiten**: Keine
- **Erfolgskriterium**: Enum kompiliert. `AIProvider.fromLegacyIndex(0)` liefert `OPENAI`. `AIProvider.fromLegacyIndex(2)` liefert `CUSTOM`. Von Java aufrufbar: `AIProvider.fromLegacyIndex(0)`.

---

**Task 1.3: AI-Exceptions und Result-Typen definieren**

- **Beschreibung**: Einheitliche Exception-Hierarchie und Result-Datenklassen, die den duplizierten Error-Handling-Code in `startWhisperApiRequest()` (Zeilen 1570-1596) und `startGPTApiRequest()` (Zeilen 1664-1694) ersetzen.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIProviderException.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/TranscriptionResult.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/CompletionResult.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/TranscriptionOptions.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/CompletionOptions.kt`

- **Details**:

  `AIProviderException.kt`:
  ```kotlin
  package net.devemperor.dictate.ai

  /**
   * Einheitliche Exception fuer alle AI-Provider-Fehler.
   * Ersetzt die verteilte error-message-Analyse in der God-Class.
   */
  class AIProviderException(
      val errorType: ErrorType,
      message: String,
      cause: Throwable? = null
  ) : RuntimeException(message, cause) {

      enum class ErrorType {
          INVALID_API_KEY,
          QUOTA_EXCEEDED,
          CONTENT_SIZE_LIMIT,
          FORMAT_NOT_SUPPORTED,
          TIMEOUT,
          NETWORK_ERROR,
          CANCELLED,
          UNKNOWN
      }

      /** Erzeugt den passenden UI-Info-String (wie bisher showInfo() erwartet). */
      fun toInfoKey(): String = when (errorType) {
          ErrorType.INVALID_API_KEY -> "invalid_api_key"
          ErrorType.QUOTA_EXCEEDED -> "quota_exceeded"
          ErrorType.CONTENT_SIZE_LIMIT -> "content_size_limit"
          ErrorType.FORMAT_NOT_SUPPORTED -> "format_not_supported"
          ErrorType.TIMEOUT -> "timeout"
          ErrorType.NETWORK_ERROR -> "internet_error"
          ErrorType.CANCELLED -> "cancelled"
          ErrorType.UNKNOWN -> "internet_error"
      }

      val isRetryable: Boolean
          get() = errorType in setOf(
              ErrorType.NETWORK_ERROR,
              ErrorType.TIMEOUT,
              ErrorType.UNKNOWN
          )

      companion object {
          /**
           * Analysiert eine OpenAI/Groq RuntimeException und klassifiziert den Fehler.
           * Ersetzt die duplizierte message.contains()-Logik in Zeilen 1522-1524 und 1763-1764.
           */
          @JvmStatic
          fun fromOpenAIException(e: RuntimeException): AIProviderException {
              val msg = (e.message ?: "").lowercase()
              val type = when {
                  msg.contains("api key") -> ErrorType.INVALID_API_KEY
                  msg.contains("quota") -> ErrorType.QUOTA_EXCEEDED
                  msg.contains("audio duration") || msg.contains("content size limit") -> ErrorType.CONTENT_SIZE_LIMIT
                  msg.contains("format") -> ErrorType.FORMAT_NOT_SUPPORTED
                  msg.contains("timeout") || msg.contains("failed to connect") -> ErrorType.TIMEOUT
                  e.cause is java.io.InterruptedIOException -> {
                      val causeMsg = (e.cause?.message ?: "").lowercase()
                      if (causeMsg.contains("timeout") || causeMsg.contains("failed to connect")) {
                          ErrorType.TIMEOUT
                      } else {
                          ErrorType.CANCELLED
                      }
                  }
                  else -> ErrorType.NETWORK_ERROR
              }
              return AIProviderException(type, e.message ?: "Unknown error", e)
          }
      }
  }
  ```

  `TranscriptionResult.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  data class TranscriptionResult(
      val text: String,
      val audioDurationSeconds: Long,
      val modelName: String
  )
  ```

  `CompletionResult.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  data class CompletionResult(
      val text: String,
      val promptTokens: Long,
      val completionTokens: Long,
      val modelName: String
  )
  ```

  `TranscriptionOptions.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  import java.io.File

  data class TranscriptionOptions(
      val audioFile: File,
      val model: String,
      val language: String? = null,      // null = auto-detect
      val stylePrompt: String? = null
  )
  ```

  `CompletionOptions.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  data class CompletionOptions(
      val prompt: String,
      val model: String,
      val systemPrompt: String? = null
  )
  ```

- **Abhaengigkeiten**: Keine
- **Erfolgskriterium**: Alle Klassen kompilieren. Von Java aus instanziierbar und zugreifbar.

---

**Task 1.4: RetryExecutor erstellen**

- **Beschreibung**: Zentralisiert die identische Retry-Logik aus `startWhisperApiRequest()` (Zeilen 1516-1533) und `requestRewordingFromApi()` (Zeilen 1757-1773). Aktuell: 3 Retries, 3000ms Backoff, bestimmte Fehler nicht retryable.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/RetryExecutor.kt`

- **Details**:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  import net.devemperor.dictate.ai.AIProviderException

  object RetryExecutor {

      private const val DEFAULT_MAX_RETRIES = 3
      private const val DEFAULT_BACKOFF_MS = 3000L

      /**
       * Fuehrt [block] aus mit Retry-Logik.
       * Wirft AIProviderException bei endgueltigem Fehler.
       *
       * @param maxRetries Maximale Anzahl Wiederholungen (default 3)
       * @param backoffMs Wartezeit zwischen Retries in ms (default 3000)
       * @param block Der auszufuehrende API-Call
       * @return Ergebnis von [block]
       * @throws AIProviderException wenn alle Retries fehlschlagen oder Fehler nicht retryable
       */
      @JvmStatic
      @JvmOverloads
      fun <T> executeWithRetry(
          maxRetries: Int = DEFAULT_MAX_RETRIES,
          backoffMs: Long = DEFAULT_BACKOFF_MS,
          block: () -> T
      ): T {
          var lastException: AIProviderException? = null
          for (attempt in 0..maxRetries) {
              try {
                  return block()
              } catch (e: RuntimeException) {
                  val aiException = if (e is AIProviderException) e
                      else AIProviderException.fromOpenAIException(e)

                  if (!aiException.isRetryable || attempt == maxRetries) {
                      throw aiException
                  }
                  lastException = aiException
                  try {
                      Thread.sleep(backoffMs)
                  } catch (_: InterruptedException) {
                      throw AIProviderException(
                          AIProviderException.ErrorType.CANCELLED,
                          "Retry interrupted",
                          e
                      )
                  }
              }
          }
          // Sollte nicht erreicht werden, aber Compiler braucht es
          throw lastException ?: AIProviderException(
              AIProviderException.ErrorType.UNKNOWN,
              "Retry failed"
          )
      }
  }
  ```

- **Abhaengigkeiten**: Task 1.3 (AIProviderException)
- **Erfolgskriterium**: `RetryExecutor.executeWithRetry { ... }` fuehrt Block aus. Bei retryable Exceptions wird bis zu 3x wiederholt. Bei non-retryable Exceptions wird sofort AIProviderException geworfen.

---

### Gruppe 2: Core AI Layer (nach Gruppe 1)

---

**Task 2.1: Runner-Interfaces definieren**

- **Beschreibung**: Abstrakte Contracts fuer Transcription und Completion. Jeder Runner beschreibt sich selbst via `RunnerDescriptor` (fuer dynamische Settings-UI).

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/TranscriptionRunner.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/CompletionRunner.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/RunnerDescriptor.kt`

- **Details**:

  `TranscriptionRunner.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  import net.devemperor.dictate.ai.AIProviderException

  /**
   * Transkribiert Audio-Dateien zu Text.
   * Implementierungen: OpenAICompatibleRunner (OpenAI, Groq, Custom).
   */
  interface TranscriptionRunner {
      /**
       * @throws AIProviderException bei API-Fehlern
       * @throws IllegalStateException bei Konfigurationsfehlern
       */
      fun transcribe(options: TranscriptionOptions): TranscriptionResult

      /** Beschreibt die Konfigurationsanforderungen dieses Runners. */
      fun describe(): RunnerDescriptor
  }
  ```

  `CompletionRunner.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  import net.devemperor.dictate.ai.AIProviderException

  /**
   * Fuehrt Chat-Completions aus (Rewording, Auto-Formatting).
   * Implementierungen: OpenAICompatibleRunner, AnthropicCompletionRunner.
   */
  interface CompletionRunner {
      /**
       * @throws AIProviderException bei API-Fehlern
       * @throws IllegalStateException bei Konfigurationsfehlern
       */
      fun complete(options: CompletionOptions): CompletionResult

      /** Beschreibt die Konfigurationsanforderungen dieses Runners. */
      fun describe(): RunnerDescriptor
  }
  ```

  `RunnerDescriptor.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  import net.devemperor.dictate.ai.AIProvider

  /**
   * Self-Description eines Runners fuer die dynamische Settings-UI.
   * Ermoeglicht es, die Settings-UI automatisch aus den Faehigkeiten
   * der registrierten Runner zu generieren.
   */
  data class RunnerDescriptor(
      val provider: AIProvider,
      val requiredSettings: List<SettingField>,
      val optionalSettings: List<SettingField> = emptyList()
  )

  data class SettingField(
      val key: String,                   // SharedPreferences-Key
      val labelResId: Int,               // R.string.xxx fuer Label
      val type: SettingFieldType,
      val defaultValue: String = ""
  )

  enum class SettingFieldType {
      API_KEY,        // Passwort-Eingabefeld
      TEXT,           // Normales Textfeld
      URL,            // URL-Eingabefeld
      MODEL_PICKER    // Model-Auswahl (Spinner/Dropdown)
  }
  ```

- **Abhaengigkeiten**: Task 1.2 (AIProvider), Task 1.3 (Result-Typen)
- **Erfolgskriterium**: Interfaces kompilieren. Koennen in Java implementiert werden (fuer den Fall, dass spaeter Java-Runner noetig sind).

---

**Task 2.2: OpenAICompatibleRunner implementieren**

- **Beschreibung**: Implementiert sowohl `TranscriptionRunner` als auch `CompletionRunner` fuer alle OpenAI-kompatiblen Provider (OpenAI, Groq, Custom). Extrahiert die Logik aus `startWhisperApiRequest()` (Zeilen 1498-1533) und `requestRewordingFromApi()` (Zeilen 1740-1778).

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/OpenAICompatibleRunner.kt`

- **Details**:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  import android.content.SharedPreferences
  import com.openai.client.okhttp.OpenAIOkHttpClient
  import com.openai.models.audio.AudioResponseFormat
  import com.openai.models.audio.transcriptions.TranscriptionCreateParams
  import com.openai.models.chat.completions.ChatCompletionCreateParams
  import net.devemperor.dictate.DictateUtils
  import net.devemperor.dictate.ai.AIProvider
  import net.devemperor.dictate.ai.AIProviderException
  import java.time.Duration

  /**
   * Runner fuer alle OpenAI-API-kompatiblen Provider.
   * Deckt OpenAI, Groq und Custom ab (unterscheiden sich nur in Base-URL und API-Key).
   *
   * Zustandslos: Wird bei jedem Provider-Wechsel neu erstellt.
   */
  class OpenAICompatibleRunner(
      private val provider: AIProvider,
      private val apiKey: String,
      private val baseUrl: String,
      private val sp: SharedPreferences,    // Fuer Proxy-Einstellungen
      private val timeoutSeconds: Long = 120
  ) : TranscriptionRunner, CompletionRunner {

      private fun buildClient(): OpenAIOkHttpClient {
          val builder = OpenAIOkHttpClient.builder()
              .apiKey(apiKey)
              .baseUrl(baseUrl)
              .timeout(Duration.ofSeconds(timeoutSeconds))

          if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
              val proxyHost = sp.getString("net.devemperor.dictate.proxy_host", "") ?: ""
              if (DictateUtils.isValidProxy(proxyHost)) {
                  DictateUtils.applyProxy(builder, sp)
              }
          }
          return builder.build()
      }

      override fun transcribe(options: TranscriptionOptions): TranscriptionResult {
          val client = buildClient()

          val paramsBuilder = TranscriptionCreateParams.builder()
              .file(options.audioFile.toPath())
              .model(options.model)
              .responseFormat(AudioResponseFormat.JSON)

          options.language?.let { if (it != "detect") paramsBuilder.language(it) }
          options.stylePrompt?.let { if (it.isNotEmpty()) paramsBuilder.prompt(it) }

          val transcription = RetryExecutor.executeWithRetry {
              client.audio().transcriptions().create(paramsBuilder.build()).asTranscription()
          }

          val audioDuration = DictateUtils.getAudioDuration(options.audioFile)

          return TranscriptionResult(
              text = transcription.text().strip(),
              audioDurationSeconds = audioDuration,
              modelName = options.model
          )
      }

      override fun complete(options: CompletionOptions): CompletionResult {
          val client = buildClient()

          val params = ChatCompletionCreateParams.builder()
              .addUserMessage(options.prompt)
              .model(options.model)
              .build()

          val chatCompletion = RetryExecutor.executeWithRetry {
              client.chat().completions().create(params)
          }

          val promptTokens = chatCompletion.usage()
              .map { it.promptTokens() }.orElse(0L)
          val completionTokens = chatCompletion.usage()
              .map { it.completionTokens() }.orElse(0L)

          return CompletionResult(
              text = chatCompletion.choices()[0].message().content().orElse(""),
              promptTokens = promptTokens,
              completionTokens = completionTokens,
              modelName = options.model
          )
      }

      override fun describe(): RunnerDescriptor {
          // Dynamisch basierend auf Provider
          val required = mutableListOf(
              SettingField(
                  key = "api_key",
                  labelResId = 0, // R.string wird spaeter verknuepft
                  type = SettingFieldType.API_KEY
              )
          )
          if (provider == AIProvider.CUSTOM) {
              required.add(SettingField(
                  key = "base_url",
                  labelResId = 0,
                  type = SettingFieldType.URL
              ))
              required.add(SettingField(
                  key = "model",
                  labelResId = 0,
                  type = SettingFieldType.TEXT
              ))
          }
          return RunnerDescriptor(
              provider = provider,
              requiredSettings = required
          )
      }
  }
  ```

- **Abhaengigkeiten**: Task 1.2, 1.3, 1.4, 2.1
- **Erfolgskriterium**: Runner kann eine Transcription und Completion durchfuehren. Retry-Logik greift bei transienten Fehlern. AIProviderException wird korrekt geworfen.

---

**Task 2.3: AnthropicCompletionRunner implementieren**

- **Beschreibung**: Implementiert `CompletionRunner` fuer Anthropic Claude. Anthropic hat kein Whisper-aequivalent, daher nur Completion.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/runner/AnthropicCompletionRunner.kt`

- **Details**:
  ```kotlin
  package net.devemperor.dictate.ai.runner

  import android.content.SharedPreferences
  import com.anthropic.client.okhttp.AnthropicOkHttpClient
  import com.anthropic.models.messages.MessageCreateParams
  import com.anthropic.models.messages.ContentBlock
  import net.devemperor.dictate.ai.AIProvider
  import net.devemperor.dictate.ai.AIProviderException

  /**
   * Runner fuer Anthropic Claude API.
   * Nur Completion – kein Transcription-Support.
   */
  class AnthropicCompletionRunner(
      private val apiKey: String,
      private val sp: SharedPreferences,
      private val maxTokens: Long = 4096
  ) : CompletionRunner {

      private fun buildClient(): AnthropicOkHttpClient {
          return AnthropicOkHttpClient.builder()
              .apiKey(apiKey)
              .build()
      }

      override fun complete(options: CompletionOptions): CompletionResult {
          val client = buildClient()

          val paramsBuilder = MessageCreateParams.builder()
              .model(options.model)
              .maxTokens(maxTokens)
              .addUserMessage(options.prompt)

          options.systemPrompt?.let { if (it.isNotEmpty()) paramsBuilder.system(it) }

          val message = RetryExecutor.executeWithRetry {
              try {
                  client.messages().create(paramsBuilder.build())
              } catch (e: RuntimeException) {
                  // Anthropic SDK wirft eigene Exceptions – uebersetzen
                  throw classifyAnthropicException(e)
              }
          }

          val text = message.content()
              .filterIsInstance<ContentBlock.Text>()
              .joinToString("") { it.text() }

          return CompletionResult(
              text = text,
              promptTokens = message.usage().inputTokens(),
              completionTokens = message.usage().outputTokens(),
              modelName = options.model
          )
      }

      override fun describe(): RunnerDescriptor {
          return RunnerDescriptor(
              provider = AIProvider.ANTHROPIC,
              requiredSettings = listOf(
                  SettingField(
                      key = "api_key",
                      labelResId = 0,
                      type = SettingFieldType.API_KEY
                  )
              )
          )
      }

      private fun classifyAnthropicException(e: RuntimeException): AIProviderException {
          val msg = (e.message ?: "").lowercase()
          val type = when {
              msg.contains("api_key") || msg.contains("authentication") ->
                  AIProviderException.ErrorType.INVALID_API_KEY
              msg.contains("rate_limit") || msg.contains("quota") ->
                  AIProviderException.ErrorType.QUOTA_EXCEEDED
              msg.contains("timeout") ->
                  AIProviderException.ErrorType.TIMEOUT
              msg.contains("overloaded") ->
                  AIProviderException.ErrorType.NETWORK_ERROR
              else -> AIProviderException.ErrorType.UNKNOWN
          }
          return AIProviderException(type, e.message ?: "Anthropic error", e)
      }
  }
  ```

- **Abhaengigkeiten**: Task 1.2, 1.3, 1.4, 2.1
- **Erfolgskriterium**: Runner fuehrt Completion via Claude API durch. Anthropic-spezifische Fehler werden korrekt auf `AIProviderException.ErrorType` gemappt.

---

**Task 2.4: RunnerFactory implementieren**

- **Beschreibung**: Factory, die aus SharedPreferences + AIProvider die passenden Runner-Instanzen erzeugt. Ersetzt die inline `switch(transcriptionProvider)`-Bloecke in der God-Class.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/factory/RunnerFactory.kt`

- **Details**:
  ```kotlin
  package net.devemperor.dictate.ai.factory

  import android.content.Context
  import android.content.SharedPreferences
  import net.devemperor.dictate.R
  import net.devemperor.dictate.ai.AIFunction
  import net.devemperor.dictate.ai.AIProvider
  import net.devemperor.dictate.ai.runner.AnthropicCompletionRunner
  import net.devemperor.dictate.ai.runner.CompletionRunner
  import net.devemperor.dictate.ai.runner.OpenAICompatibleRunner
  import net.devemperor.dictate.ai.runner.TranscriptionRunner

  /**
   * Erstellt Runner-Instanzen basierend auf der aktuellen Provider-Konfiguration.
   * Liest SharedPreferences, um API-Keys, Base-URLs und Modelle zu bestimmen.
   */
  class RunnerFactory(
      private val context: Context,
      private val sp: SharedPreferences
  ) {

      /**
       * Erstellt einen TranscriptionRunner fuer den aktuellen Provider.
       * @throws IllegalStateException wenn der Provider keine Transcription unterstuetzt.
       */
      fun createTranscriptionRunner(): TranscriptionRunner {
          val provider = getProvider(AIFunction.TRANSCRIPTION)
          require(provider.supportsTranscription) {
              "${provider.displayName} does not support transcription"
          }
          return createOpenAICompatibleRunner(provider, AIFunction.TRANSCRIPTION)
      }

      /**
       * Erstellt einen CompletionRunner fuer den aktuellen Provider.
       */
      fun createCompletionRunner(): CompletionRunner {
          val provider = getProvider(AIFunction.COMPLETION)
          return when (provider) {
              AIProvider.ANTHROPIC -> createAnthropicRunner()
              else -> createOpenAICompatibleRunner(provider, AIFunction.COMPLETION)
          }
      }

      /**
       * Liest den aktuellen Provider aus SharedPreferences.
       */
      fun getProvider(function: AIFunction): AIProvider {
          val prefKey = when (function) {
              AIFunction.TRANSCRIPTION -> "net.devemperor.dictate.transcription_provider"
              AIFunction.COMPLETION -> "net.devemperor.dictate.rewording_provider"
          }
          val legacyIndex = sp.getInt(prefKey, 0)
          return AIProvider.fromLegacyIndex(legacyIndex)
      }

      /**
       * Liest den aktuellen Modellnamen aus SharedPreferences.
       */
      fun getModelName(function: AIFunction): String {
          val provider = getProvider(function)
          return when (function) {
              AIFunction.TRANSCRIPTION -> getTranscriptionModel(provider)
              AIFunction.COMPLETION -> getCompletionModel(provider)
          }
      }

      private fun getTranscriptionModel(provider: AIProvider): String = when (provider) {
          AIProvider.OPENAI -> sp.getString(
              "net.devemperor.dictate.transcription_openai_model",
              sp.getString("net.devemperor.dictate.transcription_model", "gpt-4o-mini-transcribe")
          ) ?: "gpt-4o-mini-transcribe"
          AIProvider.GROQ -> sp.getString(
              "net.devemperor.dictate.transcription_groq_model",
              "whisper-large-v3-turbo"
          ) ?: "whisper-large-v3-turbo"
          AIProvider.CUSTOM -> sp.getString(
              "net.devemperor.dictate.transcription_custom_model",
              ""
          ) ?: ""
          AIProvider.ANTHROPIC -> throw IllegalStateException("Anthropic does not support transcription")
      }

      private fun getCompletionModel(provider: AIProvider): String = when (provider) {
          AIProvider.OPENAI -> sp.getString(
              "net.devemperor.dictate.rewording_openai_model",
              sp.getString("net.devemperor.dictate.rewording_model", "gpt-4o-mini")
          ) ?: "gpt-4o-mini"
          AIProvider.GROQ -> sp.getString(
              "net.devemperor.dictate.rewording_groq_model",
              "llama-3.3-70b-versatile"
          ) ?: "llama-3.3-70b-versatile"
          AIProvider.ANTHROPIC -> sp.getString(
              "net.devemperor.dictate.rewording_anthropic_model",
              "claude-sonnet-4-20250514"
          ) ?: "claude-sonnet-4-20250514"
          AIProvider.CUSTOM -> sp.getString(
              "net.devemperor.dictate.rewording_custom_model",
              ""
          ) ?: ""
      }

      private fun getApiKey(provider: AIProvider, function: AIFunction): String {
          val key = sp.getString(
              provider.apiKeyPrefKey(function),
              sp.getString("net.devemperor.dictate.api_key", "")
          ) ?: ""
          return key.replace(Regex("[^ -~]"), "") // Strip non-ASCII (wie original)
      }

      private fun getBaseUrl(provider: AIProvider, function: AIFunction): String {
          if (provider == AIProvider.CUSTOM) {
              val prefKey = when (function) {
                  AIFunction.TRANSCRIPTION -> "net.devemperor.dictate.transcription_custom_host"
                  AIFunction.COMPLETION -> "net.devemperor.dictate.rewording_custom_host"
              }
              return sp.getString(prefKey, "") ?: ""
          }
          return provider.defaultBaseUrl
      }

      private fun createOpenAICompatibleRunner(
          provider: AIProvider,
          function: AIFunction
      ): OpenAICompatibleRunner {
          return OpenAICompatibleRunner(
              provider = provider,
              apiKey = getApiKey(provider, function),
              baseUrl = getBaseUrl(provider, function),
              sp = sp
          )
      }

      private fun createAnthropicRunner(): AnthropicCompletionRunner {
          return AnthropicCompletionRunner(
              apiKey = getApiKey(AIProvider.ANTHROPIC, AIFunction.COMPLETION),
              sp = sp
          )
      }
  }
  ```

- **Abhaengigkeiten**: Task 2.1, 2.2, 2.3
- **Erfolgskriterium**: `factory.createTranscriptionRunner()` liefert je nach SP-Config den richtigen Runner. `factory.createCompletionRunner()` liefert AnthropicRunner wenn Provider=ANTHROPIC. Alle SharedPreferences-Keys sind mit dem Bestand kompatibel.

---

**Task 2.5: AIOrchestrator implementieren**

- **Beschreibung**: Zentrale Fassade, die von der God-Class aufgerufen wird. Kapselt Runner-Erstellung, Usage-Tracking und Fehlerbehandlung. Ersetzt die direkten API-Aufrufe in `startWhisperApiRequest()` und `requestRewordingFromApi()`.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIOrchestrator.kt`

- **Details**:
  ```kotlin
  package net.devemperor.dictate.ai

  import android.content.Context
  import android.content.SharedPreferences
  import net.devemperor.dictate.ai.factory.RunnerFactory
  import net.devemperor.dictate.ai.runner.CompletionOptions
  import net.devemperor.dictate.ai.runner.CompletionResult
  import net.devemperor.dictate.ai.runner.TranscriptionOptions
  import net.devemperor.dictate.ai.runner.TranscriptionResult
  import net.devemperor.dictate.usage.UsageDatabaseHelper
  import java.io.File

  /**
   * Zentrale Orchestrierung aller AI-Operationen.
   *
   * Aufgaben:
   * - Runner aus Factory beziehen
   * - Transcription/Completion ausfuehren
   * - Usage-Tracking nach erfolgreichem Aufruf
   *
   * Thread-Safety: Methoden sind blocking und muessen von Background-Threads
   * aufgerufen werden (wie bisher speechApiThread / rewordingApiThread).
   */
  class AIOrchestrator(
      private val context: Context,
      private val sp: SharedPreferences,
      private val usageDb: UsageDatabaseHelper  // Spaeter durch Room DAO ersetzen
  ) {

      private val factory = RunnerFactory(context, sp)

      /**
       * Transkribiert eine Audio-Datei.
       * Entspricht der Logik in startWhisperApiRequest(), Zeilen 1498-1537.
       *
       * @throws AIProviderException bei API-Fehlern
       */
      fun transcribe(
          audioFile: File,
          language: String?,
          stylePrompt: String?
      ): TranscriptionResult {
          val model = factory.getModelName(AIFunction.TRANSCRIPTION)
          val runner = factory.createTranscriptionRunner()
          val provider = factory.getProvider(AIFunction.TRANSCRIPTION)

          val result = runner.transcribe(
              TranscriptionOptions(
                  audioFile = audioFile,
                  model = model,
                  language = language,
                  stylePrompt = stylePrompt
              )
          )

          // Usage tracking (wie Zeile 1537)
          usageDb.edit(
              result.modelName,
              result.audioDurationSeconds,
              0, 0,
              provider.legacyIndex.toLong()
          )

          return result
      }

      /**
       * Fuehrt eine Chat-Completion aus (Rewording / Auto-Formatting).
       * Entspricht der Logik in requestRewordingFromApi(), Zeilen 1702-1779.
       *
       * @throws AIProviderException bei API-Fehlern
       */
      fun complete(prompt: String): CompletionResult {
          val model = factory.getModelName(AIFunction.COMPLETION)
          val runner = factory.createCompletionRunner()
          val provider = factory.getProvider(AIFunction.COMPLETION)

          val result = runner.complete(
              CompletionOptions(
                  prompt = prompt,
                  model = model
              )
          )

          // Usage tracking (wie Zeilen 1774-1777)
          usageDb.edit(
              result.modelName,
              0,
              result.promptTokens,
              result.completionTokens,
              provider.legacyIndex.toLong()
          )

          return result
      }

      /**
       * Gibt den aktuellen Provider fuer eine Funktion zurueck.
       * Nuetzlich fuer UI-Anzeige.
       */
      fun getProvider(function: AIFunction): AIProvider = factory.getProvider(function)

      /**
       * Gibt den aktuellen Modellnamen fuer eine Funktion zurueck.
       */
      fun getModelName(function: AIFunction): String = factory.getModelName(function)
  }
  ```

- **Abhaengigkeiten**: Task 2.4, Task 1.3
- **Erfolgskriterium**: `orchestrator.transcribe(file, lang, prompt)` liefert TranscriptionResult und trackt Usage. `orchestrator.complete(prompt)` liefert CompletionResult und trackt Usage. Von Java aufrufbar.

---

### Gruppe 3: Datenbank (nach Gruppe 1)

Parallel zu Gruppe 2 ausfuehrbar, da keine Abhaengigkeiten zwischen den Gruppen.

---

**Task 3.1: Room Entities definieren**

- **Beschreibung**: Room-Entities fuer alle Tabellen. UsageEntity und PromptEntity bilden die bestehenden Datenbanken ab. CachedModelEntity und ModelSelectionEntity sind neu.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/UsageEntity.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/PromptEntity.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/CachedModelEntity.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/entity/ModelSelectionEntity.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/converter/Converters.kt`

- **Details**:

  `UsageEntity.kt` (bildet bestehende USAGE-Tabelle ab):
  ```kotlin
  package net.devemperor.dictate.database.entity

  import androidx.room.ColumnInfo
  import androidx.room.Entity
  import androidx.room.PrimaryKey

  @Entity(tableName = "usage")
  data class UsageEntity(
      @PrimaryKey
      @ColumnInfo(name = "model_name")
      val modelName: String,

      @ColumnInfo(name = "audio_time")
      val audioTime: Long = 0,

      @ColumnInfo(name = "input_tokens")
      val inputTokens: Long = 0,

      @ColumnInfo(name = "output_tokens")
      val outputTokens: Long = 0,

      @ColumnInfo(name = "model_provider")
      val modelProvider: Long = 0
  )
  ```

  `PromptEntity.kt` (bildet bestehende PROMPTS-Tabelle ab):
  ```kotlin
  package net.devemperor.dictate.database.entity

  import androidx.room.ColumnInfo
  import androidx.room.Entity
  import androidx.room.PrimaryKey

  @Entity(tableName = "prompts")
  data class PromptEntity(
      @PrimaryKey(autoGenerate = true)
      @ColumnInfo(name = "id")
      val id: Int = 0,

      @ColumnInfo(name = "pos")
      val pos: Int,

      @ColumnInfo(name = "name")
      val name: String?,

      @ColumnInfo(name = "prompt")
      val prompt: String?,

      @ColumnInfo(name = "requires_selection")
      val requiresSelection: Boolean = false,

      @ColumnInfo(name = "auto_apply")
      val autoApply: Boolean = false
  )
  ```

  `CachedModelEntity.kt` (NEU: Model-Cache):
  ```kotlin
  package net.devemperor.dictate.database.entity

  import androidx.room.ColumnInfo
  import androidx.room.Entity
  import androidx.room.PrimaryKey

  @Entity(tableName = "model_cache")
  data class CachedModelEntity(
      @PrimaryKey
      @ColumnInfo(name = "model_id")
      val modelId: String,            // z.B. "gpt-4o-mini-transcribe"

      @ColumnInfo(name = "provider")
      val provider: String,            // AIProvider.name (OPENAI, GROQ, etc.)

      @ColumnInfo(name = "display_name")
      val displayName: String,

      @ColumnInfo(name = "capability")
      val capability: String,          // "TRANSCRIPTION", "COMPLETION", "BOTH"

      @ColumnInfo(name = "input_price_per_token")
      val inputPricePerToken: Double = 0.0,

      @ColumnInfo(name = "output_price_per_token")
      val outputPricePerToken: Double = 0.0,

      @ColumnInfo(name = "audio_price_per_second")
      val audioPricePerSecond: Double = 0.0,

      @ColumnInfo(name = "is_available")
      val isAvailable: Boolean = true,

      @ColumnInfo(name = "last_synced_at")
      val lastSyncedAt: Long = 0,      // Epoch millis

      @ColumnInfo(name = "is_known_model")
      val isKnownModel: Boolean = false // true = aus KnownModels, false = via API entdeckt
  )
  ```

  `ModelSelectionEntity.kt` (NEU: Aktive Modell-Auswahl):
  ```kotlin
  package net.devemperor.dictate.database.entity

  import androidx.room.ColumnInfo
  import androidx.room.Entity
  import androidx.room.PrimaryKey

  /**
   * Speichert die aktive Modell-Auswahl pro Provider und Funktion.
   * Ersetzt langfristig die SharedPreferences-Keys wie
   * "transcription_openai_model", "rewording_groq_model" etc.
   */
  @Entity(tableName = "model_selection")
  data class ModelSelectionEntity(
      @PrimaryKey
      @ColumnInfo(name = "selection_key")
      val selectionKey: String,   // z.B. "OPENAI_TRANSCRIPTION"

      @ColumnInfo(name = "model_id")
      val modelId: String
  )
  ```

  `Converters.kt`:
  ```kotlin
  package net.devemperor.dictate.database.converter

  import androidx.room.TypeConverter

  class Converters {
      @TypeConverter
      fun fromBoolean(value: Boolean): Int = if (value) 1 else 0

      @TypeConverter
      fun toBoolean(value: Int): Boolean = value != 0
  }
  ```

- **Abhaengigkeiten**: Task 1.1 (Room-Dependency)
- **Erfolgskriterium**: Alle Entities kompilieren. Room erkennt die Annotationen.

---

**Task 3.2: Room DAOs erstellen**

- **Beschreibung**: Data Access Objects mit compile-time-verifizierten Queries. Muessen von Java aufrufbar sein (fuer die noch-Java-Klassen wie DictateInputMethodService).

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/UsageDao.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/PromptDao.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/CachedModelDao.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/dao/ModelSelectionDao.kt`

- **Details**:

  `UsageDao.kt`:
  ```kotlin
  package net.devemperor.dictate.database.dao

  import androidx.room.Dao
  import androidx.room.Insert
  import androidx.room.OnConflictStrategy
  import androidx.room.Query
  import net.devemperor.dictate.database.entity.UsageEntity

  @Dao
  interface UsageDao {

      @Query("SELECT * FROM usage")
      fun getAll(): List<UsageEntity>

      @Query("SELECT * FROM usage WHERE model_name = :modelName")
      fun getByModelName(modelName: String): UsageEntity?

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      fun upsert(entity: UsageEntity)

      /**
       * Inkrementiert Usage-Werte (wie UsageDatabaseHelper.edit()).
       * Nutzt UPSERT-Semantik: Insert bei Nicht-Existenz, Update bei Existenz.
       */
      @Query("""
          INSERT INTO usage (model_name, audio_time, input_tokens, output_tokens, model_provider)
          VALUES (:modelName, :audioTime, :inputTokens, :outputTokens, :provider)
          ON CONFLICT(model_name) DO UPDATE SET
              audio_time = audio_time + :audioTime,
              input_tokens = input_tokens + :inputTokens,
              output_tokens = output_tokens + :outputTokens
      """)
      fun addUsage(modelName: String, audioTime: Long, inputTokens: Long, outputTokens: Long, provider: Long)

      @Query("DELETE FROM usage")
      fun deleteAll()

      @Query("SELECT SUM(audio_time) FROM usage")
      fun getTotalAudioTime(): Long?
  }
  ```

  `PromptDao.kt`:
  ```kotlin
  package net.devemperor.dictate.database.dao

  import androidx.room.Dao
  import androidx.room.Delete
  import androidx.room.Insert
  import androidx.room.OnConflictStrategy
  import androidx.room.Query
  import androidx.room.Update
  import net.devemperor.dictate.database.entity.PromptEntity

  @Dao
  interface PromptDao {

      @Query("SELECT * FROM prompts ORDER BY pos ASC")
      fun getAll(): List<PromptEntity>

      @Query("SELECT * FROM prompts WHERE id = :id")
      fun getById(id: Int): PromptEntity?

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      fun insert(entity: PromptEntity): Long

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      fun insertAll(entities: List<PromptEntity>)

      @Update
      fun update(entity: PromptEntity)

      @Query("DELETE FROM prompts WHERE id = :id")
      fun deleteById(id: Int)

      @Query("DELETE FROM prompts")
      fun deleteAll()

      @Query("SELECT id FROM prompts WHERE auto_apply = 1 ORDER BY pos ASC")
      fun getAutoApplyIds(): List<Int>

      @Query("SELECT COUNT(*) FROM prompts")
      fun count(): Int
  }
  ```

  `CachedModelDao.kt`:
  ```kotlin
  package net.devemperor.dictate.database.dao

  import androidx.room.Dao
  import androidx.room.Insert
  import androidx.room.OnConflictStrategy
  import androidx.room.Query
  import net.devemperor.dictate.database.entity.CachedModelEntity

  @Dao
  interface CachedModelDao {

      @Query("SELECT * FROM model_cache WHERE provider = :provider AND capability IN (:capabilities)")
      fun getByProviderAndCapability(provider: String, capabilities: List<String>): List<CachedModelEntity>

      @Query("SELECT * FROM model_cache WHERE provider = :provider AND is_available = 1")
      fun getAvailableByProvider(provider: String): List<CachedModelEntity>

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      fun upsertAll(entities: List<CachedModelEntity>)

      @Query("UPDATE model_cache SET is_available = 0 WHERE provider = :provider AND model_id NOT IN (:activeModelIds)")
      fun markUnavailable(provider: String, activeModelIds: List<String>)

      @Query("DELETE FROM model_cache WHERE provider = :provider")
      fun deleteByProvider(provider: String)

      @Query("SELECT * FROM model_cache WHERE model_id = :modelId")
      fun getByModelId(modelId: String): CachedModelEntity?
  }
  ```

  `ModelSelectionDao.kt`:
  ```kotlin
  package net.devemperor.dictate.database.dao

  import androidx.room.Dao
  import androidx.room.Insert
  import androidx.room.OnConflictStrategy
  import androidx.room.Query
  import net.devemperor.dictate.database.entity.ModelSelectionEntity

  @Dao
  interface ModelSelectionDao {

      @Query("SELECT * FROM model_selection WHERE selection_key = :key")
      fun getSelection(key: String): ModelSelectionEntity?

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      fun setSelection(entity: ModelSelectionEntity)
  }
  ```

- **Abhaengigkeiten**: Task 3.1
- **Erfolgskriterium**: DAOs kompilieren. Room generiert Implementierungen ohne Fehler. Queries sind syntaktisch korrekt (compile-time Check).

---

**Task 3.3: Room Database + Legacy Migration erstellen**

- **Beschreibung**: DictateDatabase als Single-Source-of-Truth. Beim ersten Start werden Daten aus den alten SQLiteOpenHelper-Datenbanken (usage.db v2, prompts.db v2) migriert.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/DictateDatabase.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/database/migration/LegacyDatabaseMigrator.kt`

- **Details**:

  `DictateDatabase.kt`:
  ```kotlin
  package net.devemperor.dictate.database

  import android.content.Context
  import androidx.room.Database
  import androidx.room.Room
  import androidx.room.RoomDatabase
  import androidx.room.TypeConverters
  import net.devemperor.dictate.database.converter.Converters
  import net.devemperor.dictate.database.dao.CachedModelDao
  import net.devemperor.dictate.database.dao.ModelSelectionDao
  import net.devemperor.dictate.database.dao.PromptDao
  import net.devemperor.dictate.database.dao.UsageDao
  import net.devemperor.dictate.database.entity.CachedModelEntity
  import net.devemperor.dictate.database.entity.ModelSelectionEntity
  import net.devemperor.dictate.database.entity.PromptEntity
  import net.devemperor.dictate.database.entity.UsageEntity

  @Database(
      entities = [
          UsageEntity::class,
          PromptEntity::class,
          CachedModelEntity::class,
          ModelSelectionEntity::class
      ],
      version = 1,
      exportSchema = true
  )
  @TypeConverters(Converters::class)
  abstract class DictateDatabase : RoomDatabase() {

      abstract fun usageDao(): UsageDao
      abstract fun promptDao(): PromptDao
      abstract fun cachedModelDao(): CachedModelDao
      abstract fun modelSelectionDao(): ModelSelectionDao

      companion object {
          private const val DATABASE_NAME = "dictate.db"

          @Volatile
          private var instance: DictateDatabase? = null

          @JvmStatic
          fun getInstance(context: Context): DictateDatabase {
              return instance ?: synchronized(this) {
                  instance ?: buildDatabase(context).also { instance = it }
              }
          }

          private fun buildDatabase(context: Context): DictateDatabase {
              return Room.databaseBuilder(
                  context.applicationContext,
                  DictateDatabase::class.java,
                  DATABASE_NAME
              )
              .allowMainThreadQueries()  // Fuer Legacy-Kompatibilitaet (bestehender Code nutzt Main-Thread-Queries)
              .build()
          }
      }
  }
  ```

  `LegacyDatabaseMigrator.kt`:
  ```kotlin
  package net.devemperor.dictate.database.migration

  import android.content.Context
  import android.content.SharedPreferences
  import android.database.sqlite.SQLiteDatabase
  import android.util.Log
  import net.devemperor.dictate.database.DictateDatabase
  import net.devemperor.dictate.database.entity.PromptEntity
  import net.devemperor.dictate.database.entity.UsageEntity

  /**
   * Migriert Daten aus den alten SQLiteOpenHelper-Datenbanken in die neue Room-DB.
   *
   * Alte Datenbanken:
   * - usage.db (v2): Tabelle USAGE (MODEL_NAME PK, AUDIO_TIME, INPUT_TOKENS, OUTPUT_TOKENS, MODEL_PROVIDER)
   * - prompts.db (v2): Tabelle PROMPTS (ID PK, POS, NAME, PROMPT, REQUIRES_SELECTION, AUTO_APPLY)
   *
   * Ablauf:
   * 1. Pruefen ob Migration noetig (SharedPreferences-Flag)
   * 2. Alte DBs oeffnen (read-only)
   * 3. Daten in Room schreiben
   * 4. Flag setzen (Migration erledigt)
   * 5. Alte DB-Dateien NICHT loeschen (Safety: falls etwas schief geht)
   */
  object LegacyDatabaseMigrator {

      private const val TAG = "LegacyDbMigrator"
      private const val PREF_MIGRATION_DONE = "net.devemperor.dictate.room_migration_v1_done"

      @JvmStatic
      fun migrateIfNeeded(context: Context, db: DictateDatabase) {
          val sp = context.getSharedPreferences("net.devemperor.dictate", Context.MODE_PRIVATE)
          if (sp.getBoolean(PREF_MIGRATION_DONE, false)) return

          try {
              migrateUsageDb(context, db)
              migratePromptsDb(context, db)
              sp.edit().putBoolean(PREF_MIGRATION_DONE, true).apply()
              Log.i(TAG, "Legacy database migration completed successfully")
          } catch (e: Exception) {
              Log.e(TAG, "Legacy database migration failed", e)
              // Migration fehlgeschlagen – wird beim naechsten Start erneut versucht
          }
      }

      private fun migrateUsageDb(context: Context, db: DictateDatabase) {
          val usageDbFile = context.getDatabasePath("usage.db")
          if (!usageDbFile.exists()) {
              Log.i(TAG, "No legacy usage.db found – skipping usage migration")
              return
          }

          val legacyDb = SQLiteDatabase.openDatabase(
              usageDbFile.path, null, SQLiteDatabase.OPEN_READONLY
          )
          try {
              val cursor = legacyDb.rawQuery("SELECT * FROM USAGE", null)
              val usageDao = db.usageDao()
              while (cursor.moveToNext()) {
                  usageDao.upsert(
                      UsageEntity(
                          modelName = cursor.getString(0),
                          audioTime = cursor.getLong(1),
                          inputTokens = cursor.getLong(2),
                          outputTokens = cursor.getLong(3),
                          modelProvider = cursor.getLong(4)
                      )
                  )
              }
              cursor.close()
              Log.i(TAG, "Migrated usage.db successfully")
          } finally {
              legacyDb.close()
          }
      }

      private fun migratePromptsDb(context: Context, db: DictateDatabase) {
          val promptsDbFile = context.getDatabasePath("prompts.db")
          if (!promptsDbFile.exists()) {
              Log.i(TAG, "No legacy prompts.db found – skipping prompts migration")
              return
          }

          val legacyDb = SQLiteDatabase.openDatabase(
              promptsDbFile.path, null, SQLiteDatabase.OPEN_READONLY
          )
          try {
              val cursor = legacyDb.rawQuery("SELECT * FROM PROMPTS ORDER BY POS", null)
              val promptDao = db.promptDao()
              while (cursor.moveToNext()) {
                  promptDao.insert(
                      PromptEntity(
                          id = cursor.getInt(0),
                          pos = cursor.getInt(1),
                          name = cursor.getString(2),
                          prompt = cursor.getString(3),
                          requiresSelection = cursor.getInt(4) == 1,
                          autoApply = cursor.getInt(5) == 1
                      )
                  )
              }
              cursor.close()
              Log.i(TAG, "Migrated prompts.db successfully")
          } finally {
              legacyDb.close()
          }
      }
  }
  ```

- **Abhaengigkeiten**: Task 3.1, 3.2
- **Erfolgskriterium**: `DictateDatabase.getInstance(context)` liefert DB-Instanz. Migration laeuft bei erstem Start durch. Alte Daten sind in neuer DB verfuegbar. SharedPreferences-Flag verhindert doppelte Migration.

---

### Gruppe 4: Model Registry (nach Gruppe 1)

Parallel zu Gruppe 2 und 3 ausfuehrbar.

---

**Task 4.1: KnownModels und PricingCalculator erstellen**

- **Beschreibung**: Ersetzt die hardcodierten `switch`-Bloecke in `DictateUtils.calcModelCost()` (Zeilen 150-204) und `DictateUtils.translateModelName()` (Zeilen 206-264) durch eine datengetriebene Registry.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ModelCapability.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ModelDescriptor.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/KnownModels.kt`
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/PricingCalculator.kt`

- **Details**:

  `ModelCapability.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.model

  enum class ModelCapability {
      TRANSCRIPTION,
      COMPLETION,
      BOTH
  }
  ```

  `ModelDescriptor.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.model

  import net.devemperor.dictate.ai.AIProvider

  /**
   * Beschreibt ein bekanntes Modell mit Pricing und Faehigkeiten.
   */
  data class ModelDescriptor(
      val modelId: String,
      val displayName: String,
      val provider: AIProvider,
      val capability: ModelCapability,
      val inputPricePerToken: Double = 0.0,
      val outputPricePerToken: Double = 0.0,
      val audioPricePerSecond: Double = 0.0
  )
  ```

  `KnownModels.kt` (alle Werte aus DictateUtils.calcModelCost und arrays.xml):
  ```kotlin
  package net.devemperor.dictate.ai.model

  import net.devemperor.dictate.ai.AIProvider

  /**
   * Statische Registry aller bekannten Modelle mit Pricing.
   * Dient als sofort verfuegbarer Fallback, wenn API-Sync noch nicht erfolgt ist.
   *
   * Quelle der Pricing-Daten: DictateUtils.calcModelCost() (Zeilen 150-204).
   */
  object KnownModels {

      @JvmField
      val ALL: Map<String, ModelDescriptor> = buildMap {
          // --- OpenAI Transcription ---
          put("gpt-4o-mini-transcribe", ModelDescriptor(
              modelId = "gpt-4o-mini-transcribe",
              displayName = "GPT-4o mini transcribe",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.TRANSCRIPTION,
              audioPricePerSecond = 0.00005
          ))
          put("gpt-4o-transcribe", ModelDescriptor(
              modelId = "gpt-4o-transcribe",
              displayName = "GPT-4o transcribe",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.TRANSCRIPTION,
              audioPricePerSecond = 0.0001
          ))
          put("whisper-1", ModelDescriptor(
              modelId = "whisper-1",
              displayName = "Whisper V2",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.TRANSCRIPTION,
              audioPricePerSecond = 0.0001
          ))

          // --- OpenAI Completion ---
          put("o4-mini", ModelDescriptor(
              modelId = "o4-mini",
              displayName = "OpenAI o4 mini",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.0000011,
              outputPricePerToken = 0.0000044
          ))
          put("o3-mini", ModelDescriptor(
              modelId = "o3-mini",
              displayName = "OpenAI o3 mini",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.0000011,
              outputPricePerToken = 0.0000044
          ))
          put("o1", ModelDescriptor(
              modelId = "o1",
              displayName = "OpenAI o1",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.000015,
              outputPricePerToken = 0.00006
          ))
          put("o1-mini", ModelDescriptor(
              modelId = "o1-mini",
              displayName = "OpenAI o1 mini",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.0000011,
              outputPricePerToken = 0.0000044
          ))
          put("gpt-5.2", ModelDescriptor(
              modelId = "gpt-5.2",
              displayName = "GPT-5.2",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000175,
              outputPricePerToken = 0.000014
          ))
          put("gpt-5", ModelDescriptor(
              modelId = "gpt-5",
              displayName = "GPT-5",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000125,
              outputPricePerToken = 0.00001
          ))
          put("gpt-5-mini", ModelDescriptor(
              modelId = "gpt-5-mini",
              displayName = "GPT-5 mini",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000025,
              outputPricePerToken = 0.000002
          ))
          put("gpt-4o", ModelDescriptor(
              modelId = "gpt-4o",
              displayName = "GPT-4o",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.0000025,
              outputPricePerToken = 0.00001
          ))
          put("gpt-4o-mini", ModelDescriptor(
              modelId = "gpt-4o-mini",
              displayName = "GPT-4o mini",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000015,
              outputPricePerToken = 0.0000006
          ))
          put("gpt-4-turbo", ModelDescriptor(
              modelId = "gpt-4-turbo",
              displayName = "GPT-4 Turbo",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00001,
              outputPricePerToken = 0.00003
          ))
          put("gpt-4", ModelDescriptor(
              modelId = "gpt-4",
              displayName = "GPT-4",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00003,
              outputPricePerToken = 0.00006
          ))
          put("gpt-3.5-turbo", ModelDescriptor(
              modelId = "gpt-3.5-turbo",
              displayName = "GPT-3.5 Turbo",
              provider = AIProvider.OPENAI,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.0000005,
              outputPricePerToken = 0.0000015
          ))

          // --- Groq Transcription ---
          put("whisper-large-v3-turbo", ModelDescriptor(
              modelId = "whisper-large-v3-turbo",
              displayName = "Whisper Large V3 Turbo",
              provider = AIProvider.GROQ,
              capability = ModelCapability.TRANSCRIPTION,
              audioPricePerSecond = 0.000011
          ))
          put("whisper-large-v3", ModelDescriptor(
              modelId = "whisper-large-v3",
              displayName = "Whisper Large V3",
              provider = AIProvider.GROQ,
              capability = ModelCapability.TRANSCRIPTION,
              audioPricePerSecond = 0.000031
          ))

          // --- Groq Completion ---
          put("llama-3.1-8b-instant", ModelDescriptor(
              modelId = "llama-3.1-8b-instant",
              displayName = "LLaMA 3.1 8B Instant",
              provider = AIProvider.GROQ,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000005,
              outputPricePerToken = 0.00000008
          ))
          put("llama-3.3-70b-versatile", ModelDescriptor(
              modelId = "llama-3.3-70b-versatile",
              displayName = "LLaMA 3.3 70B Versatile",
              provider = AIProvider.GROQ,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000059,
              outputPricePerToken = 0.00000079
          ))
          put("meta-llama/llama-guard-4-12b", ModelDescriptor(
              modelId = "meta-llama/llama-guard-4-12b",
              displayName = "LLaMA Guard 4 12B",
              provider = AIProvider.GROQ,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000020,
              outputPricePerToken = 0.00000020
          ))
          put("openai/gpt-oss-120b", ModelDescriptor(
              modelId = "openai/gpt-oss-120b",
              displayName = "GPT-OSS 120B",
              provider = AIProvider.GROQ,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000015,
              outputPricePerToken = 0.00000075
          ))
          put("openai/gpt-oss-20b", ModelDescriptor(
              modelId = "openai/gpt-oss-20b",
              displayName = "GPT-OSS 20B",
              provider = AIProvider.GROQ,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.00000010,
              outputPricePerToken = 0.00000050
          ))

          // --- Anthropic Completion ---
          put("claude-sonnet-4-20250514", ModelDescriptor(
              modelId = "claude-sonnet-4-20250514",
              displayName = "Claude Sonnet 4",
              provider = AIProvider.ANTHROPIC,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.000003,
              outputPricePerToken = 0.000015
          ))
          put("claude-3-5-haiku-20241022", ModelDescriptor(
              modelId = "claude-3-5-haiku-20241022",
              displayName = "Claude 3.5 Haiku",
              provider = AIProvider.ANTHROPIC,
              capability = ModelCapability.COMPLETION,
              inputPricePerToken = 0.0000008,
              outputPricePerToken = 0.000004
          ))
      }

      /**
       * Heuristische Erkennung der Faehigkeit eines unbekannten Modells.
       * Wird fuer dynamisch entdeckte Modelle verwendet, die nicht in KnownModels sind.
       */
      @JvmStatic
      fun guessCapability(modelId: String): ModelCapability {
          val lower = modelId.lowercase()
          return when {
              lower.contains("whisper") || lower.contains("transcribe") -> ModelCapability.TRANSCRIPTION
              else -> ModelCapability.COMPLETION
          }
      }

      /** Alle Modelle eines Providers mit einer bestimmten Faehigkeit. */
      @JvmStatic
      fun forProvider(provider: AIProvider, capability: ModelCapability): List<ModelDescriptor> {
          return ALL.values.filter {
              it.provider == provider && (it.capability == capability || it.capability == ModelCapability.BOTH)
          }
      }
  }
  ```

  `PricingCalculator.kt`:
  ```kotlin
  package net.devemperor.dictate.ai.model

  /**
   * Ersetzt DictateUtils.calcModelCost() (Zeilen 150-204).
   * Nutzt KnownModels als Pricing-Quelle.
   */
  object PricingCalculator {

      /**
       * Berechnet die Kosten fuer eine Modellnutzung.
       * @return Kosten in USD. 0.0 wenn Modell unbekannt.
       */
      @JvmStatic
      fun calcCost(modelName: String, audioTime: Long, inputTokens: Long, outputTokens: Long): Double {
          val descriptor = KnownModels.ALL[modelName] ?: return 0.0
          return audioTime * descriptor.audioPricePerSecond +
                 inputTokens * descriptor.inputPricePerToken +
                 outputTokens * descriptor.outputPricePerToken
      }

      /**
       * Uebersetzt einen Modellnamen in einen Display-Namen.
       * Ersetzt DictateUtils.translateModelName() (Zeilen 206-264).
       */
      @JvmStatic
      fun translateModelName(modelName: String): String {
          return KnownModels.ALL[modelName]?.displayName ?: modelName
      }
  }
  ```

- **Abhaengigkeiten**: Task 1.2 (AIProvider)
- **Erfolgskriterium**: `PricingCalculator.calcCost("gpt-4o-mini-transcribe", 60, 0, 0)` liefert `0.003` (60 * 0.00005). `KnownModels.forProvider(AIProvider.OPENAI, ModelCapability.TRANSCRIPTION)` liefert 3 Modelle. Von Java aufrufbar.

---

**Task 4.2: ModelCacheManager erstellen**

- **Beschreibung**: Verwaltet den Model-Cache in Room. Synchronisiert dynamisch via API, faellt auf KnownModels zurueck.

- **Dateien**:
  - CREATE `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/model/ModelCacheManager.kt`

- **Details**:
  ```kotlin
  package net.devemperor.dictate.ai.model

  import android.content.SharedPreferences
  import android.util.Log
  import com.openai.client.okhttp.OpenAIOkHttpClient
  import net.devemperor.dictate.DictateUtils
  import net.devemperor.dictate.ai.AIProvider
  import net.devemperor.dictate.database.dao.CachedModelDao
  import net.devemperor.dictate.database.entity.CachedModelEntity
  import java.time.Duration

  /**
   * Verwaltet den Model-Cache.
   *
   * Strategie:
   * 1. Beim Start: KnownModels in Cache laden (falls leer)
   * 2. Im Hintergrund: API-Sync (client.models().list())
   * 3. Neue Modelle aus API mit Heuristik klassifizieren
   * 4. Veraltete Modelle als nicht-verfuegbar markieren
   */
  class ModelCacheManager(
      private val cachedModelDao: CachedModelDao,
      private val sp: SharedPreferences
  ) {

      companion object {
          private const val TAG = "ModelCacheManager"
          private const val SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24h
      }

      /**
       * Laedt KnownModels in den Cache (einmalig).
       */
      fun seedKnownModels() {
          val existing = cachedModelDao.getByModelId(KnownModels.ALL.keys.first())
          if (existing != null) return // Bereits geseeded

          val entities = KnownModels.ALL.values.map { it.toEntity(isKnownModel = true) }
          cachedModelDao.upsertAll(entities)
          Log.i(TAG, "Seeded ${entities.size} known models into cache")
      }

      /**
       * Gibt verfuegbare Modelle fuer einen Provider und eine Faehigkeit zurueck.
       * Sofort verfuegbar (kein Netzwerk).
       */
      fun getModels(provider: AIProvider, capability: ModelCapability): List<CachedModelEntity> {
          val capabilities = when (capability) {
              ModelCapability.TRANSCRIPTION -> listOf("TRANSCRIPTION", "BOTH")
              ModelCapability.COMPLETION -> listOf("COMPLETION", "BOTH")
              ModelCapability.BOTH -> listOf("TRANSCRIPTION", "COMPLETION", "BOTH")
          }
          return cachedModelDao.getByProviderAndCapability(provider.name, capabilities)
              .filter { it.isAvailable }
      }

      /**
       * Synchronisiert Modelle via API (blocking, muss auf Background-Thread laufen).
       * Nur fuer OpenAI-kompatible Provider (haben models.list() Endpoint).
       */
      fun syncModels(provider: AIProvider, apiKey: String) {
          if (provider == AIProvider.ANTHROPIC || provider == AIProvider.CUSTOM) return

          try {
              val client = OpenAIOkHttpClient.builder()
                  .apiKey(apiKey)
                  .baseUrl(provider.defaultBaseUrl)
                  .timeout(Duration.ofSeconds(30))
                  .also { builder ->
                      if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
                          DictateUtils.applyProxy(builder, sp)
                      }
                  }
                  .build()

              val apiModels = client.models().list().data()
              val now = System.currentTimeMillis()

              val entities = apiModels.map { model ->
                  val modelId = model.id()
                  val known = KnownModels.ALL[modelId]
                  CachedModelEntity(
                      modelId = modelId,
                      provider = provider.name,
                      displayName = known?.displayName ?: modelId,
                      capability = (known?.capability ?: KnownModels.guessCapability(modelId)).name,
                      inputPricePerToken = known?.inputPricePerToken ?: 0.0,
                      outputPricePerToken = known?.outputPricePerToken ?: 0.0,
                      audioPricePerSecond = known?.audioPricePerSecond ?: 0.0,
                      isAvailable = true,
                      lastSyncedAt = now,
                      isKnownModel = known != null
                  )
              }

              cachedModelDao.upsertAll(entities)
              val activeIds = entities.map { it.modelId }
              cachedModelDao.markUnavailable(provider.name, activeIds)

              Log.i(TAG, "Synced ${entities.size} models for ${provider.name}")
          } catch (e: Exception) {
              Log.w(TAG, "Model sync failed for ${provider.name}", e)
              // Kein Fehler – Cache bleibt bestehen
          }
      }

      private fun ModelDescriptor.toEntity(isKnownModel: Boolean) = CachedModelEntity(
          modelId = modelId,
          provider = provider.name,
          displayName = displayName,
          capability = capability.name,
          inputPricePerToken = inputPricePerToken,
          outputPricePerToken = outputPricePerToken,
          audioPricePerSecond = audioPricePerSecond,
          isAvailable = true,
          lastSyncedAt = System.currentTimeMillis(),
          isKnownModel = isKnownModel
      )
  }
  ```

- **Abhaengigkeiten**: Task 3.2 (CachedModelDao), Task 4.1 (KnownModels)
- **Erfolgskriterium**: `seedKnownModels()` befuellt den Cache. `getModels(AIProvider.OPENAI, ModelCapability.TRANSCRIPTION)` liefert Ergebnisse ohne Netzwerk. `syncModels()` aktualisiert den Cache im Hintergrund.

---

### Gruppe 5: Integration (nach Gruppe 2 + 3 + 4)

---

**Task 5.1: DictateInputMethodService refactoren – API-Aufrufe**

- **Beschreibung**: Die API-Logik in der God-Class durch AIOrchestrator-Aufrufe ersetzen. Dies ist der kritische Integrations-Schritt.

- **Dateien**:
  - MODIFY `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`

- **Details**:

  **Neues Feld hinzufuegen** (nach Zeile 225, neben `usageDb`):
  ```java
  private AIOrchestrator aiOrchestrator;
  ```

  **Initialisierung in onCreateInputView()** (nach Zeile 247):
  ```java
  aiOrchestrator = new AIOrchestrator(this, sp, usageDb);
  ```

  **startWhisperApiRequest() vereinfachen** (Zeilen 1482-1606):
  Der gesamte Block innerhalb von `speechApiThread.execute(() -> { ... })` wird ersetzt:
  ```java
  // ALT: Zeilen 1484-1537 (Provider-Switch, Client-Builder, Retry-Loop, Usage-Tracking)
  // NEU:
  try {
      TranscriptionResult result = aiOrchestrator.transcribe(audioFile, currentInputLanguageValue, stylePrompt);
      String resultText = result.getText().strip();
      resultText = applyAutoFormattingIfEnabled(resultText);
      // ... Rest bleibt gleich (ab Zeile 1539)
  } catch (AIProviderException e) {
      // Vereinfachtes Error-Handling:
      if (e.getErrorType() != AIProviderException.ErrorType.CANCELLED) {
          logException(e);
          if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
          mainHandler.post(() -> {
              resendButton.setVisibility(View.VISIBLE);
              showInfo(e.toInfoKey());
          });
      }
  }
  ```

  **requestRewordingFromApi() vereinfachen** (Zeilen 1702-1779):
  ```java
  // ALT: 77 Zeilen mit Provider-Switch, Client-Builder, Retry-Loop
  // NEU:
  private String requestRewordingFromApi(String prompt) {
      CompletionResult result = aiOrchestrator.complete(prompt);
      return result.getText();
  }
  ```

  **Error-Handling in startGPTApiRequest() vereinfachen** (Zeilen 1664-1694):
  ```java
  // ALT: message.contains()-Checks
  // NEU:
  } catch (AIProviderException e) {
      if (e.getErrorType() != AIProviderException.ErrorType.CANCELLED) {
          logException(e);
          if (vibrationEnabled) vibrator.vibrate(...);
          mainHandler.post(() -> {
              resendButton.setVisibility(View.VISIBLE);
              showInfo(e.toInfoKey());
          });
      }
      // ... callback-Logik bleibt
  }
  ```

  **Imports hinzufuegen**:
  ```java
  import net.devemperor.dictate.ai.AIOrchestrator;
  import net.devemperor.dictate.ai.AIProviderException;
  import net.devemperor.dictate.ai.runner.TranscriptionResult;
  import net.devemperor.dictate.ai.runner.CompletionResult;
  ```

  **Entfernte Imports** (nicht mehr direkt benoetigt):
  ```java
  // Koennen entfernt werden, wenn kein anderer Code sie nutzt:
  import com.openai.client.okhttp.OpenAIOkHttpClient;
  import com.openai.models.audio.AudioResponseFormat;
  import com.openai.models.audio.transcriptions.Transcription;
  import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
  import com.openai.models.chat.completions.ChatCompletion;
  import com.openai.models.chat.completions.ChatCompletionCreateParams;
  ```

- **Abhaengigkeiten**: Task 2.5 (AIOrchestrator)
- **Erfolgskriterium**: `startWhisperApiRequest()` nutzt `aiOrchestrator.transcribe()`. `requestRewordingFromApi()` nutzt `aiOrchestrator.complete()`. Keine direkten OpenAI-SDK-Aufrufe mehr in der God-Class. Error-Handling ist vereinheitlicht. Code-Reduktion: ca. 100 Zeilen.

---

**Task 5.2: DictateUtils Pricing-Migration**

- **Beschreibung**: `DictateUtils.calcModelCost()` und `translateModelName()` delegieren an PricingCalculator. Die alten Methoden bleiben als Wrapper (fuer bestehende Java-Aufrufer).

- **Dateien**:
  - MODIFY `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/DictateUtils.java`

- **Details**:

  `calcModelCost()` (Zeilen 150-204) ersetzen:
  ```java
  public static double calcModelCost(String modelName, long audioTime, long inputTokens, long outputTokens) {
      return PricingCalculator.calcCost(modelName, audioTime, inputTokens, outputTokens);
  }
  ```

  `translateModelName()` (Zeilen 206-264) ersetzen:
  ```java
  public static String translateModelName(String modelName) {
      return PricingCalculator.translateModelName(modelName);
  }
  ```

  Import hinzufuegen:
  ```java
  import net.devemperor.dictate.ai.model.PricingCalculator;
  ```

- **Abhaengigkeiten**: Task 4.1
- **Erfolgskriterium**: Bestehende Aufrufer (`UsageDatabaseHelper.getCost()`, UI-Code) funktionieren unveraendert. Pricing-Werte sind identisch.

---

**Task 5.3: arrays.xml um Anthropic erweitern**

- **Beschreibung**: Provider-Liste in arrays.xml um Anthropic erweitern. Anthropic-Modelle als neue Arrays hinzufuegen.

- **Dateien**:
  - MODIFY `/home/lukas/WebStorm/Dictate/app/src/main/res/values/arrays.xml`

- **Details**:

  `dictate_api_providers` (Zeile 261-265) erweitern:
  ```xml
  <string-array name="dictate_api_providers">
      <item>OpenAI</item>
      <item>Groq</item>
      <item>Anthropic</item>
      <item>@string/dictate_custom_provider</item>
  </string-array>
  <string-array name="dictate_api_providers_values">
      <item>https://api.openai.com/v1/</item>
      <item>https://api.groq.com/openai/v1/</item>
      <item>https://api.anthropic.com/v1/</item>
      <item>custom_server</item>
  </string-array>
  ```

  Neue Anthropic-Modell-Arrays:
  ```xml
  <string-array name="dictate_rewording_models_anthropic">
      <item>Claude Sonnet 4 ($3.00 / $15.00 / 1M)</item>
      <item>Claude 3.5 Haiku ($0.80 / $4.00 / 1M)</item>
  </string-array>
  <string-array name="dictate_rewording_models_anthropic_values">
      <item>claude-sonnet-4-20250514</item>
      <item>claude-3-5-haiku-20241022</item>
  </string-array>
  ```

  **WICHTIG**: Anthropic hat KEINE Transcription-Modelle. Die Transcription-Provider-Auswahl muss Anthropic ausblenden (wird in Task 6.1 behandelt).

- **Abhaengigkeiten**: Keine (kann parallel)
- **Erfolgskriterium**: Arrays enthalten Anthropic. Indices stimmen: OpenAI=0, Groq=1, Anthropic=2 (NEU), Custom=3 (verschoben!).

  **ACHTUNG BREAKING CHANGE**: Custom war bisher Index 2, wird jetzt Index 3. Das erfordert:
  - `AIProvider.CUSTOM.legacyIndex` ist bereits auf 2 gesetzt → muss auf 3 geaendert werden
  - `AIProvider.ANTHROPIC.legacyIndex` ist 3 → muss auf 2 geaendert werden
  - ODER: Die SharedPreferences-Werte bestehender User muessen migriert werden

  **Empfehlung**: Anthropic bekommt Index 3 (am Ende), Custom bleibt bei 2. Das vermeidet die Migration bestehender SharedPreferences. Die Reihenfolge in arrays.xml wird angepasst:
  ```xml
  <string-array name="dictate_api_providers">
      <item>OpenAI</item>        <!-- 0 -->
      <item>Groq</item>          <!-- 1 -->
      <item>@string/dictate_custom_provider</item>  <!-- 2 (unveraendert!) -->
      <item>Anthropic</item>     <!-- 3 (NEU) -->
  </string-array>
  ```

---

### Gruppe 6: UI Anpassungen (nach Gruppe 5)

---

**Task 6.1: APISettingsActivity fuer Anthropic erweitern**

- **Beschreibung**: Die APISettingsActivity muss den neuen Anthropic-Provider unterstuetzen. Da Anthropic keine Transcription hat, darf Anthropic nur im Rewording-Provider-Dropdown erscheinen.

- **Dateien**:
  - MODIFY `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java`

- **Details**:

  **Transcription-Provider-Dropdown**: Anthropic ausfiltern. Statt `dictate_api_providers` ein neues Array `dictate_transcription_providers` verwenden, oder programmatisch filtern:
  ```java
  // Zeile 123-124: Separates Array fuer Transcription (ohne Anthropic)
  transcriptionProviderAdapter = ArrayAdapter.createFromResource(this,
      R.array.dictate_transcription_providers, android.R.layout.simple_spinner_item);
  ```

  Neues Array in arrays.xml:
  ```xml
  <string-array name="dictate_transcription_providers">
      <item>OpenAI</item>
      <item>Groq</item>
      <item>@string/dictate_custom_provider</item>
  </string-array>
  ```

  **Rewording-Provider-Dropdown**: Alle Provider inkl. Anthropic. Bei Auswahl von Anthropic:
  - Modell-Spinner zeigt Anthropic-Modelle
  - Custom-Fields ausblenden

  **Neue Felder** (nach Zeile 61):
  ```java
  private String rewordingAPIKeyAnthropic;
  private ArrayAdapter<CharSequence> rewordingModelAnthropicAdapter;
  ```

  **Neue Adapter** (nach Zeile 211):
  ```java
  rewordingModelAnthropicAdapter = ArrayAdapter.createFromResource(this,
      R.array.dictate_rewording_models_anthropic, android.R.layout.simple_spinner_item);
  rewordingModelAnthropicAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  ```

  **updateRewordingModels()** erweitern (Zeile 319):
  ```java
  // Position 3 = Anthropic
  } else if (position == 3) {
      rewordingAPIKeyEt.setText(rewordingAPIKeyAnthropic);
      rewordingModelSpn.setAdapter(rewordingModelAnthropicAdapter);
      int pos = IntStream.range(0, rewordingModelAnthropicAdapter.getCount())
              .filter(i -> getResources().getStringArray(R.array.dictate_rewording_models_anthropic_values)[i]
                  .equals(sp.getString("net.devemperor.dictate.rewording_anthropic_model", "claude-sonnet-4-20250514")))
              .findFirst()
              .orElse(0);
      rewordingModelSpn.setSelection(pos);
  }
  ```

- **Abhaengigkeiten**: Task 5.3 (arrays.xml Anthropic)
- **Erfolgskriterium**: Anthropic erscheint im Rewording-Dropdown. Modell-Auswahl funktioniert. Anthropic erscheint NICHT im Transcription-Dropdown. API-Key wird korrekt gespeichert.

---

### Gruppe 7: Migration + Cleanup (nach Gruppe 6)

---

**Task 7.1: Room-Migration im App-Start triggern**

- **Beschreibung**: LegacyDatabaseMigrator beim App-Start aufrufen. Model-Cache seeden.

- **Dateien**:
  - MODIFY `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/DictateApplication.java` (falls vorhanden)
  - ALTERNATIV: In `DictateInputMethodService.onCreateInputView()` (Zeile 235ff)

- **Details**:

  In `onCreateInputView()` nach Room-DB-Instanzierung:
  ```java
  // Nach Zeile 247 (usageDb = new UsageDatabaseHelper(this)):
  DictateDatabase dictateDb = DictateDatabase.getInstance(this);
  LegacyDatabaseMigrator.migrateIfNeeded(this, dictateDb);

  // Model-Cache initial befuellen
  ModelCacheManager cacheManager = new ModelCacheManager(dictateDb.cachedModelDao(), sp);
  cacheManager.seedKnownModels();
  ```

  Spaeter: `usageDb = new UsageDatabaseHelper(this)` durch Room-basierte Loesung ersetzen. In dieser Phase wird der Orchestrator noch den alten UsageDatabaseHelper nutzen.

- **Abhaengigkeiten**: Task 3.3, Task 4.2
- **Erfolgskriterium**: Beim ersten Start nach Update werden Daten aus usage.db und prompts.db migriert. KnownModels sind im Cache. Bei nachfolgenden Starts wird die Migration uebersprungen.

---

**Task 7.2: UsageDatabaseHelper → Room DAO migrieren**

- **Beschreibung**: Alle Aufrufer von `UsageDatabaseHelper` auf den Room UsageDao umstellen.

- **Dateien**:
  - MODIFY `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
  - MODIFY `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/ai/AIOrchestrator.kt`
  - Alle weiteren Aufrufer von `usageDb` (Usage-Anzeige in Settings etc.)

- **Details**:

  AIOrchestrator-Konstruktor aendern:
  ```kotlin
  class AIOrchestrator(
      private val context: Context,
      private val sp: SharedPreferences,
      private val usageDao: UsageDao  // Statt UsageDatabaseHelper
  )
  ```

  Usage-Tracking in `transcribe()`:
  ```kotlin
  usageDao.addUsage(result.modelName, result.audioDurationSeconds, 0, 0, provider.legacyIndex.toLong())
  ```

  In DictateInputMethodService:
  ```java
  // ALT: usageDb = new UsageDatabaseHelper(this);
  // NEU:
  DictateDatabase dictateDb = DictateDatabase.getInstance(this);
  UsageDao usageDao = dictateDb.usageDao();
  aiOrchestrator = new AIOrchestrator(this, sp, usageDao);
  ```

- **Abhaengigkeiten**: Task 5.1 (Orchestrator-Integration), Task 3.2 (UsageDao)
- **Erfolgskriterium**: `UsageDatabaseHelper` wird nirgendwo mehr instanziiert. Alle Usage-Operationen laufen ueber Room.

---

**Task 7.3: PromptsDatabaseHelper → Room DAO migrieren**

- **Beschreibung**: Alle Aufrufer von `PromptsDatabaseHelper` auf den Room PromptDao umstellen.

- **Dateien**:
  - MODIFY `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (Zeile 220, 246)
  - MODIFY alle Activities die PromptsDatabaseHelper nutzen

- **Details**:

  Die PromptEntity muss in bestehenden Java-Code integriert werden. Da `PromptModel` an vielen Stellen verwendet wird (Adapter, Activities), wird ein Adapter/Extension geschrieben:

  ```kotlin
  // In PromptEntity.kt als Extension:
  fun PromptEntity.toPromptModel(): PromptModel =
      PromptModel(id, pos, name, prompt, requiresSelection, autoApply)

  fun PromptModel.toEntity(): PromptEntity =
      PromptEntity(id, pos, name, prompt, requiresSelection(), isAutoApply)
  ```

  Alternativ: `PromptModel` bleibt als UI-Modell bestehen. Room-Entity ist das DB-Modell. Mapping zwischen beiden.

- **Abhaengigkeiten**: Task 3.2 (PromptDao), Task 3.3 (Migration)
- **Erfolgskriterium**: `PromptsDatabaseHelper` wird nirgendwo mehr instanziiert. Alle Prompt-CRUD-Operationen laufen ueber Room.

---

## Migrations-Strategie

### Datenbank-Migration

1. **Neue Room-DB** (`dictate.db`) wird neben den alten DBs angelegt
2. **Erster Start nach Update**: `LegacyDatabaseMigrator` liest alte DBs read-only und schreibt in Room
3. **SharedPreferences-Flag** (`room_migration_v1_done`) verhindert doppelte Migration
4. **Alte DB-Dateien bleiben** fuer 2-3 Versionen erhalten (Safety)
5. **In spaeterer Version**: Alte DB-Dateien loeschen

### SharedPreferences-Migration

Keine Breaking Changes:
- Bestehende Keys (`transcription_provider`, `rewording_provider` etc.) werden weiter gelesen
- `AIProvider.fromLegacyIndex()` mappt die alten int-Werte auf das Enum
- Neue Keys (z.B. `rewording_api_key_anthropic`, `rewording_anthropic_model`) haben sinnvolle Defaults
- Custom bleibt Index 2, Anthropic bekommt Index 3 → keine Migration noetig

### Model-Arrays Migration

- Alte hardcodierte Arrays in `arrays.xml` bleiben vorerst bestehen (fuer Fallback)
- Model-Cache in Room wird parallel befuellt
- Langfristiges Ziel: Arrays entfernen, Settings-UI liest aus Room

### Phasen-Uebergang

| Phase | Zustand |
|-------|---------|
| Phase 1 (Gruppe 1-4) | Neuer Code existiert, wird aber noch nicht verwendet |
| Phase 2 (Gruppe 5) | God-Class nutzt AIOrchestrator, alte DBs laufen parallel |
| Phase 3 (Gruppe 6) | UI zeigt Anthropic, Model-Cache wird genutzt |
| Phase 4 (Gruppe 7) | Alte DB-Helper werden entfernt, Room ist einzige Quelle |

## Risiken & Mitigations

| Risiko | Wahrscheinlichkeit | Impact | Mitigation |
|--------|--------------------:|--------|------------|
| **OpenAI SDK 4.26.0 Breaking Changes** | Mittel | Hoch | Changelog lesen. SDK-Update als eigenen Commit. Falls Breaking: bei 4.13.0 bleiben und nur Anthropic SDK hinzufuegen |
| **Anthropic Java SDK Inkompatibilitaet** | Niedrig | Mittel | SDK nutzt OkHttp wie OpenAI SDK. Falls Konflikt: OkHttp-Version pinnen in Gradle |
| **Room + bestehende SQLite-Dateien Konflikt** | Niedrig | Hoch | Room nutzt `dictate.db` (neuer Name). Alte `usage.db` und `prompts.db` werden nicht von Room verwaltet |
| **Java-Kotlin Interop Probleme** | Mittel | Mittel | `@JvmStatic`, `@JvmField`, `@JvmOverloads` konsequent verwenden. Nullable-Typen beachten (Java-Aufrufer sehen `@Nullable`/`@NotNull`) |
| **SharedPreferences Provider-Index Verschiebung** | Hoch | Hoch | Anthropic bekommt Index 3 (am Ende). Custom bleibt bei 2. Keine Migration noetig |
| **allowMainThreadQueries Performance** | Niedrig | Niedrig | Bestehender Code macht schon Main-Thread-DB-Zugriffe (UsageDatabaseHelper). Langfristig auf Coroutines migrieren |
| **ProGuard/R8 mit Kotlin** | Niedrig | Mittel | `minifyEnabled` ist `false` (build.gradle Zeile 21). Kein Risiko in aktueller Config |
| **Retry-Logik Verhaltensaenderung** | Niedrig | Mittel | Bestehende Retry-Semantik exakt nachbilden (3 Retries, 3s, gleiche non-retryable Checks). Unit-Tests schreiben |

## Testing-Strategie

### Unit-Tests (JUnit, kein Android-Context noetig)

| Komponente | Testfaelle |
|------------|------------|
| `AIProvider` | `fromLegacyIndex()` fuer alle Werte (0-3), unbekannte Werte. `apiKeyPrefKey()` Korrektheit. `withTranscription()`/`withCompletion()` Filter |
| `AIProviderException` | `fromOpenAIException()` klassifiziert alle bekannten Fehlermeldungen korrekt. `isRetryable` ist korrekt |
| `RetryExecutor` | Erfolg ohne Retry. Retry bei transienten Fehlern. Sofortiger Fehler bei non-retryable. Max-Retry-Limit. Interrupt-Handling |
| `PricingCalculator` | Alle Known-Models liefern exakt die gleichen Werte wie `DictateUtils.calcModelCost()`. Unbekannte Modelle liefern 0.0 |
| `KnownModels` | `forProvider()` liefert korrekte Modelle. `guessCapability()` erkennt "whisper" und "transcribe" |
| `RunnerFactory` | Erstellt korrekten Runner fuer jeden Provider. Liest SharedPreferences korrekt |

### Integrations-Tests (androidTest, mit Context)

| Komponente | Testfaelle |
|------------|------------|
| `DictateDatabase` | Schema-Erstellung. CRUD fuer alle Entities. UPSERT-Semantik in UsageDao |
| `LegacyDatabaseMigrator` | Migration von Test-usage.db und Test-prompts.db. Leere DBs. Fehlende DBs. Doppelte Migration wird uebersprungen |
| `ModelCacheManager` | `seedKnownModels()` befuellt Cache. `getModels()` filtert korrekt |

### Manueller Smoketest (vor Release)

1. Fresh Install: App starten, Keyboard oeffnen, Transcription testen (OpenAI), Rewording testen
2. Update von Vorgaengerversion: Alte Settings bleiben erhalten, Usage-Daten sind migriert, Prompts sind migriert
3. Provider-Wechsel: OpenAI → Groq → Anthropic → Custom. Jeweils Rewording testen
4. Anthropic Transcription: Darf nicht auswaehlbar sein
5. Error-Cases: Unglueltiger API-Key, Netzwerk aus, Quota erschoepft
6. Settings UI: Alle Provider-Dropdowns zeigen korrekte Modelle

### Regression-Check

Sicherstellen, dass nach dem Refactoring exakt das gleiche Verhalten besteht:
- Gleiche API-Requests (Headers, Body) an OpenAI/Groq
- Gleiche Retry-Timing (3 Retries, 3s)
- Gleiche Error-Messages in der UI
- Gleiche Usage-Tracking-Werte
- Gleiche Proxy-Anwendung
