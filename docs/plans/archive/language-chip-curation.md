<!-- EXECUTION-PLAN -->
## Execution Plan

**Erstellt:** 2026-04-17
**Überarbeitet:** 2026-04-18 — Vereinfachung (ein einziges `InputLanguagesPlugin` statt zwei separater Plugins) + gruppierte PopupMenu-Liste (kuratiert oben + alle anderen unten)
**Erweitert:** 2026-04-27 — Phase 5 hinzugefügt: Bugfix für Resend-Button (kurzer Klick → Insert schlägt fehl bei Status COMPLETED). Lösung via robuster InputConnection-Capture + 3-stufigem Fallback. Außerdem Verhaltensänderung: FAILED → No-Op (statt automatischem Resume).
**Quality-Gate-Review:** 2026-04-27 — 27 valide Findings eingearbeitet (8 Kritisch, 12 Wichtig, 7 Nice-to-have). Wesentliche Änderungen: (a) `KeyboardUiController` auf `addCallback`/`removeCallback`-Liste umgestellt (löst Composite-Wrapper-Workaround); (b) `PipelineUiStateReader`-Interface entkoppelt `LanguageController` von der konkreten UI-Klasse (DIP-konform); (c) Test-Setup auf JUnit 4 + Fake-Pattern umgestellt (Projekt hat kein Mockito); (d) `commitTextToInputConnection` mit explizitem `InputConnection`-Parameter refactored (eliminiert Mirror-Code in Phase 5, löst InstantOutput-Drift, Audit-Sessionsbindung, getSelectedText-Inkonsistenz in einem Schritt); (e) `setPersistent(false)` auf `MultiSelectListPreference` (verhindert StringSet-Persist-Override des Envelopes); (f) Settings-UI ruft `LanguageController.setCuratedLanguages` (Single-Source-of-Truth bleibt erhalten); (g) korrekter SharedPreferences-Accessor `getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE)`; (h) Init-Reihenfolge in der existenten `DictateApplication.onCreate()` festgenagelt; (i) Pos-Resync-Helper `persistCuratedAndPos` extrahiert (DRY für drei Schreibpfade).
**Implementation-Start:** 2026-04-27 — Reihenfolge: **5 → 0 → 1 → 2 → 3 → 4** (Phase 5 zuerst wegen Scope-Isolation und schnellem User-Wert; danach Phase 0-4 als zusammenhängende Sprach-Iteration). Branch: `feature/language-chip-curation` (von `main` nach Vorab-Commit `0d8eb73` der PopupMenu-BadTokenException-Fix). State-Tracking: `docs/plans/language-chip-curation.state.md`. Chunking: `docs/plans/language-chip-curation.chunks.json`. Plan-Strukturanpassung: 6 Phase-Headings von H3 auf H2 promoted (Container `## Implementierungsplan` entfernt) — notwendig damit `plan-reader.ts` (H2-only) die Phasen als individuelle Chunks erfassen kann.
**Geschätzte Chunks:** 6

### Meine Strategie

Der Plan wird in 6 logische Chunks aufgeteilt. Chunk 0 schafft die generische Versioned-Envelope-Infrastruktur (portiert aus excel_ekl). Chunk 1 führt die konkrete Nutzung (`InputLanguagesPlugin` v1 + Legacy-StringSet-Helper) plus den `LanguageController` ein. Chunk 2 baut die Chip-UI plus die gruppierte PopupMenu-Liste. Chunk 3 passt das Settings-UI an. Chunk 4 schreibt End-to-End-Tests. Chunk 5 fixt den Resend-Button-Bug separat — er ist scope-isoliert und hängt nicht von den anderen Phasen ab, kann also unabhängig erst-implementiert oder nachgezogen werden.

### Geplante Chunks

| # | Chunk | Plan-Abschnitte | Warum diese Gruppierung? |
|---|-------|-----------------|--------------------------|
| 0 | Versioned-Envelope-Foundation | Phase 0 | Portiert das generische System aus excel_ekl — Basis für alle folgenden Phasen |
| 1 | InputLanguagesPlugin + LanguageController + Legacy-Migration | Phase 1 | Eine kuratierte Sprach-Liste (v1) + zentrale Sprach-Domain-Logik + einmaliger StringSet→Envelope-Helper |
| 2 | Chip-UI + Gruppierte PopupMenu | Phase 2 | Always-visible Chip, PopupMenu zeigt kuratierte Sprachen oben + alle anderen unten mit Trenner |
| 3 | Settings-UI-Anpassung | Phase 3 | Bestehende MultiSelectListPreference auf VersionedPrefs-Storage umstellen |
| 4 | Tests & Validation | Phase 4 | End-to-End-Tests (alle Unit-Tests + manuelle Verification-Checklist) |
| 5 | Bugfix Resend-Button — InputConnection-Capture + Status-Matrix | Phase 5 | Kurzer Klick auf Resend muss bei COMPLETED-Status den Output zuverlässig einfügen, FAILED → No-Op, robust gegen Fokus-Wechsel |

### Abhängigkeiten & Risiken

- **Chunk 0 → 1:** `VersionedPlugin<T>`, `VersionedPrefs`, `VersionedMigrator`, `StringListCodec` müssen existieren
- **Chunk 1 → 2:** `LanguageController.getCuratedLanguages()` muss PopupMenu füttern können; **`PipelineUiStateReader`-Interface + `KeyboardUiController.addCallback`-Refactor** sind Voraussetzung (Quality-Gate K-2, W-4)
- **Chunk 2 → 3:** Settings-UI liest jetzt denselben Key, aber als Envelope-String statt StringSet — erfordert Anpassung; zusätzlich braucht Phase 3 die `LanguageController`-Erreichbarkeit aus dem Settings-Process (via `DictateApplication.getOrCreateLanguageController()`)
- **Chunk 5 ist scope-unabhängig, hat aber einen vorgelagerten Refactor-Schritt (5.0):** `commitTextToInputConnection`-Methode mit explizitem `InputConnection`-Parameter (Quality-Gate K-8, W-3). Empfehlung: zuerst Phase 5 (kleiner Scope, schneller User-Wert), danach Phase 0-4
- **Risiko A:** SharedPreferences-Key `input_languages` wechselt Typ (StringSet → String). Downgrade auf alte App-Version nach Migration = Datenverlust (Rollback-Risiko). **Mitigation:** Self-Heal-Backup-Pfad als forensische Reserve (Quality-Gate W-8 Edge-Case dokumentiert)
- **Risiko B:** `MultiSelectListPreference.setValues()` ruft intern `persistStringSet()` und überschreibt damit den JSON-Envelope. **Quality-Gate K-5 GELÖST** durch `setPersistent(false)` auf der Preference; Schreiben läuft ausschließlich über `LanguageController.setCuratedLanguages` (Quality-Gate K-6)
- **Risiko C:** Kotlin `Any?`-basierte Migration-Functions sind compile-zeit-unsicher — jede Migration braucht einen Unit-Test, der sie mit realen Alt-Payloads füttert
- **Risiko D:** `PopupMenu.setGroupDividerEnabled(true)` ist API 28+ — minSdk ist 26. Auf Android 8 (API 26-27) fehlt der visuelle Trenner. Fallback: disabled Trenner-Label-Item
- **Risiko E:** Captured InputConnection in Phase 5 kann **stale** sein — Android markiert sie nicht sofort als invalid, wenn der User in derselben App das Feld wechselt. Erkennung nur über `commitText() returns false`, nicht über null-Check
- **Risiko F (Verhaltensänderung):** FAILED-Status → No-Op statt automatischem Resume. Kann User irritieren, der vorher den automatischen Resume kannte. Wird als bewusste UX-Verbesserung dokumentiert (kein "silent retry" bei API-Fehlern)
- **Risiko G (Test-Stack):** Projekt hat JUnit 4 und keine Mocking-Library. **Quality-Gate K-1 GELÖST** durch konsistente Verwendung handgeschriebener Fakes (`FakePipelineUiStateReader`, `FakeSharedPreferences`, `FakeInputConnection`); kein Mockito/MockK/Robolectric notwendig
- **Risiko H (Pos-Drift bei Locale-Änderung):** Pos-Index in label-sortierte Liste kann zu falscher Sprache zeigen, wenn Display-Labels in einer zukünftigen Version anders sortiert werden. Akzeptiert für diesen Plan; Schema-Wechsel auf `CurrentLanguageCode: Pref<String>` als Follow-up dokumentiert

---
<!-- /EXECUTION-PLAN -->

# Feature: Always-visible Language-Chip + Kuration + Versioned Storage

## Context

Das Projekt braucht drei zusammengehörende Verbesserungen der Sprach-Verwaltung, die in einer Iteration sinnvoll zusammen umgesetzt werden:

### Problem 1 — Chip nur während Reprocess sichtbar

Der Language-Chip in der Prompts-Leiste wird heute ausschließlich während `PipelineUiState.ReprocessStaging` eingeblendet. Der User kann im Normalmodus die Sprache nur über einen Long-Press auf den Record-Button (Cycle) ändern — das ist wenig entdeckbar und erlaubt nur Rotation durch die gespeicherte Subset-Liste.

### Problem 2 — Keine Kuration aus dem Keyboard heraus

Aktuell zeigt das ReprocessStaging-PopupMenu alle 62 Sprachen aus `R.array.dictate_input_languages`. Der User wünscht sich, die Liste der sichtbaren Sprachen **im Keyboard** steuern zu können: "Welche Sprachen werden im Picker überhaupt angeboten?" Der heutige Weg (Settings-Activity → MultiSelectListPreference) erfordert einen Context-Switch.

### Problem 3 — Persistenz ohne Versionierung

Die bestehende `InputLanguages`-Pref ist ein `StringSet` (ordnungslos, kein Versions-Feld). Für jede zukünftige Format-Änderung müsste manuell migriert werden. Das Schwesterprojekt excel_ekl hat bereits einen bewährten **Versioned-Envelope-Port mit Auto-Migrations**, der hier übernommen werden kann und gleichzeitig eine wiederverwendbare Infrastruktur für zukünftige Config-Prefs schafft.

### Ziel

- **Chip always-visible:** Beide Modi (normal + ReprocessStaging) zeigen den Chip. Klick öffnet PopupMenu mit allen Sprachen, gruppiert.
- **Gruppierte PopupMenu-Liste:** Zwei visuell getrennte Blöcke:
  1. **Oberer Block:** Kuratierte Sprachen (aus `InputLanguagesPlugin`), alphabetisch nach Display-Label sortiert
  2. **Trenner** (via `setGroupDividerEnabled(true)` auf API 28+, Fallback: disabled Unicode-Label auf API 26/27)
  3. **Unterer Block:** Alle anderen Sprachen aus `R.array.dictate_input_languages_values`, alphabetisch nach Display-Label sortiert
- **Dual-Mode-Schreibverhalten:** Im Normalmodus permanent (persistiert), in ReprocessStaging temporär (transient).
- **Kuration aus dem Keyboard:** Letzter PopupMenu-Eintrag "⚙ Sprachen verwalten…" öffnet die bestehende Settings-Activity mit Fokus auf die Sprach-Auswahl (für das explizite Kuratieren).
- **Versioned Storage:** Generische Portierung des excel_ekl-`Versioned<T>`-Systems nach Kotlin/Android, angewendet zunächst auf die `InputLanguages`-Pref (Legacy-StringSet → v1-Envelope).

---

## Design-Prinzipien

### 1. Schichten-Architektur

```
┌──────────────────────────────────────────────────────────────┐
│  UI-Schicht                                                  │
│  ├─ Language-Chip (always visible, reuses adapter infra)     │
│  ├─ Gruppierte PopupMenu:                                    │
│  │    ├─ Oberer Block: kuratierte Sprachen (alphabetisch)    │
│  │    ├─ Visueller Trenner (API 28+) / Label (API 26-27)     │
│  │    ├─ Unterer Block: alle anderen (alphabetisch)          │
│  │    └─ "⚙ Sprachen verwalten…" am Ende                    │
│  └─ Settings: MultiSelectListPreference (Storage-Wrap)       │
├──────────────────────────────────────────────────────────────┤
│  Service-/Controller-Layer (faktisch core/)                  │
│  ├─ LanguageController                                       │
│  │   ├─ getEffectiveLanguage(): String                       │
│  │   ├─ setLanguage(code): Unit   ← dispatcht Mode          │
│  │   ├─ getCuratedLanguages(): List<String>                  │
│  │   └─ setCuratedLanguages(codes: List<String>)             │
│  │   • depends on: PipelineUiStateReader (Interface)         │
│  ├─ PipelineUiStateReader (Interface, neu)                   │
│  │   ├─ val state: PipelineUiState                           │
│  │   └─ updateReprocessLanguage(code: String)                │
│  │   • implementiert von KeyboardUiController                │
│  └─ LanguageLabelResolver (Resource-Adapter, in preferences/)│
│      ├─ resolveLabel(code): String                           │
│      ├─ allCodes(): List<String>                             │
│      └─ sortByLabel(codes): List<String>  (alphabetisch)     │
├──────────────────────────────────────────────────────────────┤
│  Persistence-Schicht                                         │
│  ├─ DictatePrefs (Primitive, unverändert)                    │
│  └─ VersionedPrefs (neu — JSON-Envelope + Migrations)        │
│      ├─ Versioned<T>, VersionedPlugin<T>, JsonCodec<T>       │
│      ├─ VersionedMigrator, VersionedSerializer               │
│      └─ InputLanguagesPlugin (v1, die einzige kurated-Liste) │
└──────────────────────────────────────────────────────────────┘
```

**Hinweis zur Layer-Terminologie (Quality-Gate W-4):** Die ursprüngliche Bezeichnung "Domain-Schicht" wurde zurückgenommen, weil das Projekt kein dediziertes `domain/`-Paket hat — alle Klassen liegen pragmatisch in `core/`. Die Klassen sind weiterhin schichten-konform organisiert (Service/Controller-Layer kennt nur Interfaces zur UI-Schicht), aber das Vokabular "Service-/Controller-Layer" passt besser zum tatsächlichen Code-Layout. `LanguageLabelResolver` wandert nach `preferences/` (statt `core/`), damit `InputLanguagesPlugin` keinen `core/`-Import braucht (Persistence → Resource-Adapter ist die richtige Richtung).

### 2. Wiederverwendbarer Versioned-Envelope (portiert aus excel_ekl)

Das excel_ekl-Projekt implementiert unter `shared/versioned/` ein generisches Versionierungs-System für persistierte JSON-Daten. Wir portieren es 1:1 nach Kotlin mit folgenden Anpassungen:

- **Serialisierung:** `org.json.JSONObject` statt `JSON.stringify` (keine neue Dependency)
- **Schema-Validation:** Kotlin-Data-Classes statt Zod-Schemas (Compile-Time statt Runtime)
- **Storage:** `SharedPreferences` statt `localStorage`/`Database`
- **Migration-Funktionen:** `(Any?) -> Any?` statt `(unknown) => unknown` (Kotlin-Äquivalent)

### 3. Single-Source-of-Truth für "effektive Sprache"

Der `LanguageController` löst die "welche Quelle gewinnt"-Frage zentral:
- Ist `PipelineUiState` gerade `ReprocessStaging` mit `selectedLanguage != null` → temporärer Override gewinnt
- Sonst → Permanent-Pref (`InputLanguagesPlugin[InputLanguagePos]` — Envelope-Liste + Int-Index)

Der Controller **duplicziert** den Override-State **nicht** — er liest ihn aus `pipelineUiStateReader.state`. Schreibpfad ist konsequent getrennt:
- Permanent-Schreiben → `VersionedPrefs.save(...)` über `LanguageController.setCuratedLanguages` / `setLanguage`
- Temporär-Schreiben → `pipelineUiStateReader.updateReprocessLanguage(code)`

**WICHTIG:** Auch das Settings-UI schreibt **nur** über den Controller (siehe Phase 3.1) — keine direkten `VersionedPrefs.save`-Aufrufe außerhalb des Controllers. Sonst bleibt `lastEffective` stale und die Pos-Resync-Logik dupliziert.

### 3a. Dependency-Inversion: PipelineUiStateReader-Interface

**Quality-Gate W-4:** Der ursprüngliche Plan-Entwurf ließ `LanguageController` direkt von der konkreten UI-Klasse `KeyboardUiController` abhängen. Das verletzt das Dependency-Inversion-Principle (Service-Layer importiert UI-Klasse) und macht Tests umständlich (View-Konstruktor-Parameter, Handler, MaterialButton).

Lösung: schmales Interface, das nur die wirklich benötigten Operationen exponiert:

```kotlin
package net.devemperor.dictate.core

interface PipelineUiStateReader {
    val state: PipelineUiState
    fun updateReprocessLanguage(code: String)
    fun addCallback(callback: PipelineUiCallback)
    fun removeCallback(callback: PipelineUiCallback)
}
```

`KeyboardUiController` implementiert das trivial — alle vier Operationen sind dort schon vorhanden bzw. werden im selben Refactor (siehe Design-Prinzip 7) hinzugefügt. `LanguageController` hängt nur an dieser Schnittstelle, Test-Fakes (siehe Phase 4 Test-Setup) sind 5-10 Zeilen lang.

### 3b. Repository-Trade-off (W-5, optional, post-Phase-1)

Ein dünnes `LanguageRepository`-Interface mit `loadCurated`/`saveCurated`/`loadActivePosition`/`saveActivePosition` würde `LanguageController` von `SharedPreferences` und vom Mix der zwei Storage-Konzepte (Envelope-Plugin + Primitive-Pref) entkoppeln. Das ist **konsequenter SOLID**, aber kostet ~30 zusätzliche Zeilen und einen weiteren Test-Helper.

**Entscheidung für diesen Plan:** Nicht-blockierend, kann nach Phase 1 als Refactor nachgereicht werden. Die `PipelineUiStateReader`-Abstraktion (Design-Prinzip 3a) ist die wichtigere Entkopplung; Repository ist nur Storage-Backend-Wechsel-Vorbereitung. Phase 1 nutzt direkten `SharedPreferences`-Zugriff im Controller — wenn später ein zweites Backend (z.B. DataStore) eingeführt wird, lohnt sich der Refactor; bis dahin ist es Vokabular-Schmuck. Als Follow-up dokumentiert.

### 4. Main-Thread-Contract

`LanguageController` ist Main-Thread-only (wie `KeyboardUiController`, `RecordingStateController`). `VersionedPrefs.load/save` ist dagegen thread-sicher (SharedPreferences ist thread-sicher), kann aus jedem Thread gerufen werden.

**Worker-Thread-Lese-Pfad (z.B. `PipelineOrchestrator` aus `dbExecutor`):** Direkter Zugriff auf `VersionedPrefs.load(prefs, InputLanguagesPlugin)` ist erlaubt (Read-Only). **Schreiben** muss zwingend über den Controller laufen — die Verantwortung liegt beim Aufrufer. Diese Asymmetrie ist Doku-Vertrag, kein Compiler-Vertrag.

### 5. Fail-Safe statt Fail-Hard für Sprach-Daten

`InputLanguagesPlugin` nutzt `OnMissingMigration.RESET_TO_DEFAULT` — Sprach-Daten sind nicht kritisch, "zurück auf Default" ist akzeptables UX bei Datenkorruption. Für zukünftige kritische Prefs (z.B. API-Keys in einem Envelope-Format) wäre `THROW` die richtige Wahl.

### 6. Warum `LanguageController` keinen eigenen Sealed-Class-State hat

Im Gegensatz zu `RecordingStateController` (mit `RecordingState`-Sealed-Class) und `KeyboardUiController` (mit `PipelineUiState`-Sealed-Class) hat `LanguageController` **keine eigene Sealed-Class-Hierarchie** für seinen State. Begründung:

- Der "effektive Sprache"-Zustand ist **abgeleitet**, nicht primär: er ergibt sich aus `pipelineUiStateReader.state` (das bereits sealed ist) plus den persistierten SharedPreferences. Eine eigene Sealed Class wäre Duplikation.
- Es gibt keine endlichen, sich gegenseitig ausschließenden Modi für "Sprache" — der Wert ist immer ein einzelner String, ggf. überschrieben durch ReprocessStaging-Override.
- Die "Tell-don't-ask"-Helfer-Methoden (`getEffectiveLanguage()`, `setLanguage()`) genügen, weil der Controller die Quelle-Logik intern kapselt. Das deckt sich mit Design-Prinzip 3 (Single-Source-of-Truth, keine Override-Duplikation).

Bewusste Abweichung vom Pattern, dokumentiert hier für künftige Leser, die die Inkonsistenz bemerken könnten.

### 7. Multi-Callback-Support auf KeyboardUiController (Quality-Gate K-2)

**Problem:** `KeyboardUiController.setCallback(PipelineUiCallback)` akzeptiert genau einen Callback. Heute ist dort eine anonyme Service-Innerklasse registriert. Phase 1 fügt einen zweiten Konsumenten (`LanguageController`) hinzu — naheliegend wäre ein handgeschriebener Composite-Wrapper im Service. Das ist aber Open/Closed-Verstoß: Jeder zukünftige Konsument (Toolbar-Indicator, Debug-Overlay) muss den Composite anpassen, der Service wächst zur zentralen Pipeline-Hub-Klasse.

**Lösung:** `KeyboardUiController` von Single-Slot auf Listener-Liste umstellen.

```kotlin
// In KeyboardUiController.kt — ersetzt das bisherige callback: PipelineUiCallback?
private val callbacks = java.util.concurrent.CopyOnWriteArrayList<PipelineUiCallback>()

fun addCallback(cb: PipelineUiCallback) { callbacks.addIfAbsent(cb) }
fun removeCallback(cb: PipelineUiCallback) { callbacks.remove(cb) }

// Bisherige callback?.onPipelineUiStateChanged(...)-Aufrufe werden zu:
callbacks.forEach { it.onPipelineUiStateChanged(old, newState) }
```

`CopyOnWriteArrayList` schützt gegen Concurrent-Modification, falls ein Callback während eines `forEach` einen weiteren Callback an-/abmeldet (selten, aber möglich beim onCreateInputView-Refresh).

`setCallback(...)` bleibt für Source-Kompatibilität als Convenience-Wrapper (`callbacks.clear(); addCallback(cb)`) erhalten und wird im Migrationspfad durch `addCallback` ersetzt; ist aber **deprecated** markiert und entfällt nach erfolgreicher Migration aller Aufrufer.

**Service-Wiring nach diesem Refactor:**
```java
// In DictateInputMethodService.onCreateInputView, nach Erstellung von uiController + languageController:
uiController.addCallback(servicePipelineCallback);  // Service-Logik: Chip refresh, QWERTZ-Reset
uiController.addCallback(languageController);       // LanguageController.lastEffective-Sync
```

Composite-Wrapper entfällt komplett. Im teardown (`onDestroy` des IME bzw. View-Recreate) wird `removeCallback` für jeden registrierten Callback aufgerufen, damit kein Leak entsteht.

---

## Phase 0: Versioned-Envelope-Foundation *(portiert aus excel_ekl)*

Neue Package-Struktur: `app/src/main/java/net/devemperor/dictate/preferences/versioned/`

#### 0.1) Core-Types — `Versioned.kt`

```kotlin
package net.devemperor.dictate.preferences.versioned

data class Versioned<T>(val version: Int, val value: T)

typealias MigrationFn = (oldValue: Any?) -> Any?

enum class OnMissingMigration { THROW, RESET_TO_DEFAULT }

data class MigrationResult<T>(
    val data: Versioned<T>,
    val migrated: Boolean,
    val fromVersion: Int,
    val toVersion: Int
)
```

#### 0.2) JSON-Codec — `JsonCodec.kt`

Weil wir `org.json` statt kotlinx.serialization nutzen, brauchen wir Codecs pro Datentyp.

```kotlin
interface JsonCodec<T> {
    fun encode(value: T): Any   // JSONObject/JSONArray/primitive
    fun decode(raw: Any?): T
}

object StringListCodec : JsonCodec<List<String>> {
    override fun encode(value: List<String>): Any = JSONArray(value)
    override fun decode(raw: Any?): List<String> = when (raw) {
        is JSONArray -> List(raw.length()) { raw.getString(it) }
        null -> emptyList()
        else -> throw IllegalArgumentException("Expected JSONArray, got ${raw::class.simpleName}")
    }
}

object IntListCodec : JsonCodec<List<Int>> { /* analog */ }
```

#### 0.3) Plugin-Basisklasse — `VersionedPlugin.kt`

```kotlin
abstract class VersionedPlugin<T>(
    val name: String,
    val currentVersion: Int,
    val defaultValue: T,
    val codec: JsonCodec<T>,
    val onMissingMigration: OnMissingMigration = OnMissingMigration.RESET_TO_DEFAULT
) {
    /** Map von Source-Version → Migrations-Funktion v(N) → v(N+1). Leer bei v1-only. */
    abstract val migrations: Map<Int, MigrationFn>
    
    /** Optional: Post-Migration-Sanitizer (z.B. Allowlist-Filter). */
    open fun sanitize(value: T): T = value
}
```

#### 0.4) Migration-Engine — `VersionedMigrator.kt`

```kotlin
object VersionedMigrator {
    fun <T> migrate(plugin: VersionedPlugin<T>, envelope: Versioned<Any?>): MigrationResult<T> {
        val fromVersion = envelope.version
        val targetVersion = plugin.currentVersion
        
        // Future-Version-Detection (Quality-Gate W-8): respektiert die Plugin-Strategie statt
        // hart zu werfen. Im Dictate-Kontext ohne Backend-Sync kann ein App-Downgrade nach v2→v1
        // sonst den Sprach-Picker crashen. RESET_TO_DEFAULT ist die Default-Strategie für
        // unkritische Daten wie Sprach-Listen (siehe Design-Prinzip 5).
        if (fromVersion > targetVersion) {
            return when (plugin.onMissingMigration) {
                OnMissingMigration.THROW -> throw IllegalStateException(
                    "${plugin.name}: data version $fromVersion > current $targetVersion. Update the app."
                )
                OnMissingMigration.RESET_TO_DEFAULT -> resetToDefault(plugin, fromVersion, targetVersion)
            }
        }
        
        // No migration needed
        if (fromVersion == targetVersion) {
            return MigrationResult(
                data = Versioned(fromVersion, plugin.codec.decode(envelope.value)),
                migrated = false,
                fromVersion = fromVersion,
                toVersion = fromVersion
            )
        }
        
        // Sequential migration: v(from) → v(from+1) → ... → v(target)
        var currentRaw: Any? = envelope.value
        var currentVersion = fromVersion
        
        while (currentVersion < targetVersion) {
            val migration = plugin.migrations[currentVersion]
                ?: return applyMissingMigrationStrategy(plugin, fromVersion, targetVersion)
            
            try {
                currentRaw = migration(currentRaw)
                currentVersion++
            } catch (e: Throwable) {
                return when (plugin.onMissingMigration) {
                    OnMissingMigration.THROW -> throw IllegalStateException(
                        "Migration failed for '${plugin.name}' v$currentVersion → v${currentVersion + 1}", e
                    )
                    OnMissingMigration.RESET_TO_DEFAULT -> resetToDefault(plugin, fromVersion, targetVersion)
                }
            }
        }
        
        return MigrationResult(
            data = Versioned(currentVersion, plugin.codec.decode(currentRaw)),
            migrated = currentVersion > fromVersion,
            fromVersion = fromVersion,
            toVersion = currentVersion
        )
    }
    
    private fun <T> applyMissingMigrationStrategy(
        plugin: VersionedPlugin<T>, from: Int, to: Int
    ): MigrationResult<T> = when (plugin.onMissingMigration) {
        OnMissingMigration.THROW -> throw IllegalStateException(
            "Missing migration for '${plugin.name}' from v$from to v$to"
        )
        OnMissingMigration.RESET_TO_DEFAULT -> resetToDefault(plugin, from, to)
    }
    
    private fun <T> resetToDefault(
        plugin: VersionedPlugin<T>, from: Int, to: Int
    ): MigrationResult<T> = MigrationResult(
        data = Versioned(plugin.currentVersion, plugin.defaultValue),
        migrated = true,
        fromVersion = from,
        toVersion = plugin.currentVersion
    )
}
```

#### 0.5) Serializer mit Raw-als-v1-Fallback — `VersionedSerializer.kt`

```kotlin
class VersionedSerializer<T>(private val plugin: VersionedPlugin<T>) {
    
    fun serialize(value: T): String {
        val sanitized = plugin.sanitize(value)
        val envelope = JSONObject().apply {
            put("version", plugin.currentVersion)
            put("value", plugin.codec.encode(sanitized))
        }
        return envelope.toString()
    }
    
    fun deserialize(json: String): T {
        val parsed: Any? = try {
            JSONTokener(json).nextValue()
        } catch (e: Throwable) {
            return plugin.defaultValue
        }
        
        val envelope = if (isVersionedEnvelope(parsed)) {
            val obj = parsed as JSONObject
            Versioned<Any?>(obj.getInt("version"), obj.get("value"))
        } else {
            // Raw-legacy: als v1 behandeln
            Versioned<Any?>(1, parsed)
        }
        
        val result = VersionedMigrator.migrate(plugin, envelope)
        return plugin.sanitize(result.data.value)
    }
    
    private fun isVersionedEnvelope(parsed: Any?): Boolean =
        parsed is JSONObject
            && parsed.has("version") && parsed.has("value")
            && parsed.get("version") is Number  // Quality-Gate W-10: org.json liefert Integer/Long/Double je nach JSON-Quelle.
                                                // is Int verfehlt z.B. {"version": 1.0, ...} oder Long-Werte → silent
                                                // Korruptions-Datenverlust. is Number ist robust; getInt unten wirft
                                                // bei non-int Number eine JSONException, die catched wird.
}
```

#### 0.6) Storage-Binding — `VersionedPrefs.kt`

```kotlin
object VersionedPrefs {
    private const val TAG = "VersionedPrefs"
    
    fun <T> load(prefs: SharedPreferences, plugin: VersionedPlugin<T>): T {
        val json = prefs.getString(plugin.name, null) ?: return plugin.defaultValue
        val serializer = VersionedSerializer(plugin)
        return try {
            val value = serializer.deserialize(json)
            // Self-heal: persist migrated/sanitized version if changed
            if (serializer.serialize(value) != json) {
                save(prefs, plugin, value)
            }
            value
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load ${plugin.name}, using default", e)
            plugin.defaultValue
        }
    }
    
    fun <T> save(prefs: SharedPreferences, plugin: VersionedPlugin<T>, value: T): Boolean {
        val serializer = VersionedSerializer(plugin)
        return try {
            val json = serializer.serialize(value)
            prefs.edit().putString(plugin.name, json).apply()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save ${plugin.name}", e)
            false
        }
    }
}
```

#### 0.7) Plugin-Registry — `VersionedPluginRegistry.kt`

```kotlin
object VersionedPluginRegistry {
    private const val TAG = "VersionedPluginRegistry"
    private val plugins = mutableMapOf<String, VersionedPlugin<*>>()

    fun register(plugin: VersionedPlugin<*>) {
        val existing = plugins[plugin.name]
        if (existing != null && existing !== plugin) {
            throw IllegalStateException("Duplicate plugin registration: '${plugin.name}'")
        }
        plugins[plugin.name] = plugin
    }

    fun all(): Collection<VersionedPlugin<*>> = plugins.values
    fun findByName(name: String): VersionedPlugin<*>? = plugins[name]

    /**
     * Eager-migriert alle registrierten Plugins. Aufrufen beim App-Start.
     *
     * Quality-Gate W-9: Ein Plugin-Fehler darf andere Plugins nicht blockieren —
     * deshalb per-Plugin Try/Catch mit Log statt unbehandelter Exception.
     */
    fun migrateAll(prefs: SharedPreferences) {
        all().forEach { plugin ->
            try {
                VersionedPrefs.load(prefs, plugin)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to eager-migrate '${plugin.name}'", e)
            }
        }
    }

    /**
     * Test-Seam: räumt die Singleton-Registry auf.
     * Wird ausschließlich von Unit-Tests in @Before/@After aufgerufen.
     * Pattern: analog zu ActiveJobRegistry.resetRegistry().
     */
    @VisibleForTesting
    internal fun reset() {
        plugins.clear()
    }
}
```

#### 0.8) Backup vor Self-Heal (Quality-Gate W-8 Edge-Case)

**Risiko-Mitigation:** Wenn `VersionedPrefs.load()` einen unparseablen JSON liest und mit `RESET_TO_DEFAULT` heilt, schreibt es den Default-Wert zurück — die ursprünglichen, möglicherweise nur temporär unlesbaren Daten sind dann **weg**. Beispiel: User installiert v3-App (currentVersion=2), schreibt v2-Daten; downgraded auf v2-App (currentVersion=1) → Self-Heal überschreibt mit Default → späteres Re-Upgrade auf v3 hat Original-Daten verloren.

**Strategie:** Vor dem Self-Heal-Save eine einmalige Backup-Kopie unter `<key>__backup_<timestamp>` ablegen, falls noch keine existiert. Nicht als Auto-Restore-Logik (wäre komplex und fehleranfällig), sondern als **forensische Reserve** — User-Support kann manuell aus dem Backup wiederherstellen, wenn sich später herausstellt, dass Daten verloren sind.

```kotlin
// In VersionedPrefs.load(...), bevor save() das Self-Heal schreibt:
private fun <T> writeBackupIfFirstTime(prefs: SharedPreferences, plugin: VersionedPlugin<T>, originalJson: String) {
    val backupKey = "${plugin.name}__backup"
    if (prefs.contains(backupKey)) return  // Backup existiert bereits, nicht überschreiben
    prefs.edit()
        .putString(backupKey, originalJson)
        .putLong("${backupKey}_timestamp", System.currentTimeMillis())
        .apply()
}
```

Backup-Aufruf vor Self-Heal-`save`:

```kotlin
val value = serializer.deserialize(json)
val newJson = serializer.serialize(value)
if (newJson != json) {
    writeBackupIfFirstTime(prefs, plugin, json)  // Backup vor Überschreibung
    save(prefs, plugin, value)
}
```

Implementierung als Follow-up dokumentiert; minimal-invasive Edit-Punkt steht fest.

#### 0.9) Unit-Tests — `VersionedMigratorTest.kt`, `VersionedSerializerTest.kt`, `StringListCodecTest.kt`, `VersionedPluginRegistryTest.kt`

**Test-Stack-Hinweis (Quality-Gate K-1):** Das Dictate-Projekt nutzt **JUnit 4.13.2** mit `@Before`/`@Test`-Annotationen, **kein** Mockito/MockK/Robolectric. Bestehende Tests (`SessionTrackerTest.kt`, `JobExecutorTest.kt`, `ActiveJobRegistryTest.kt`) verwenden handgeschriebene Fakes. Alle Test-Snippets in diesem Plan respektieren diese Konvention. JUnit-5-Annotationen (`@BeforeEach`) und Mockito-DSL (`mock { ... } doReturn ...`) sind **nicht** zu verwenden.

**Zusätzliche Test-Infrastruktur:**
- `app/build.gradle`: `testImplementation 'org.json:json:20240303'` (Quality-Gate W-2) — ohne diese Dependency sind `JSONObject`/`JSONArray`/`JSONTokener` im JVM-Unit-Test-Classpath nur Stubs (siehe `ElevenLabsKeytermsParserTest.kt:133-135`-Note). Mit der Dependency ist das Roundtrip-Tests gegen echtes JSON möglich.
- `unitTests.returnDefaultValues = true` bleibt — `Log.*`-Aufrufe sind dann No-Ops, was für Tests OK ist.

**Szenarien:**
- **Migrator:** forward (v1→v3 über v2), idempotent (v3==v3), **future-version mit RESET_TO_DEFAULT → Default zurück (W-8)**, future-version mit THROW → Exception, missing-migration-throws-when-strategy-is-THROW, missing-migration-resets-when-strategy-is-RESET, migration-throws-and-resets
- **Serializer:** round-trip (serialize → deserialize == identity), envelope-detect (Int, Long, Double — Quality-Gate W-10), raw-value-as-v1, malformed-JSON-returns-default
- **StringListCodec:** encode-empty, encode-with-values, decode-JSONArray, decode-null-returns-empty, decode-wrong-type-throws
- **Self-Heal-im-Getter:** Lade-mit-altem-Format schreibt bereinigte Version zurück
- **VersionedPluginRegistry (W-9):** register/lookup/duplicate-throws/reset; `migrateAll` mit fehlerwerfendem Plugin → andere Plugins migrieren weiter
- **`@Before` ruft `VersionedPluginRegistry.reset()`** in jedem Test, der Plugin-Registration triggert (Test-Isolation)

---

## Phase 1: LanguageLabelResolver + InputLanguagesPlugin + LanguageController + Legacy-Helper

#### 1.1) LanguageLabelResolver (Resource-Adapter)

**Quality-Gate W-4:** Verschoben von `core/` nach `preferences/` — `LanguageLabelResolver` ist faktisch ein Resource-Adapter (liest `R.array.*`); damit `InputLanguagesPlugin` keinen `core/`-Import braucht (Persistence-Code → Resource-Adapter ist die richtige Richtung).

Neue Datei: `app/src/main/java/net/devemperor/dictate/preferences/LanguageLabelResolver.kt`

Zentrale Stelle für alle Übersetzungen zwischen ISO-Code und Display-Label, plus die Allowlist. Ersetzt die `ALLOWED_LANGUAGES`-Companion aus dem früheren Plan-Entwurf und vermeidet Code-Duplikation in Service + Plugin.

```kotlin
/**
 * Single source of truth für:
 *  - Welche Sprach-Codes existieren überhaupt (R.array.dictate_input_languages_values)
 *  - Welches Label gehört zu welchem Code (R.array.dictate_input_languages)
 *  - Alphabetisches Sortieren nach Label (nicht nach Code — "Deutsch" kommt vor "English")
 *
 * Thread-safe: initialisiert einmal beim App-Start, danach read-only.
 */
object LanguageLabelResolver {
    private lateinit var codes: Array<String>      // ISO-Codes in Resource-Reihenfolge
    private lateinit var labels: Array<String>     // Labels parallel dazu
    private lateinit var allowedSet: Set<String>   // Schnell-Lookup
    private lateinit var labelByCode: Map<String, String>

    fun initialize(context: Context) {
        if (::codes.isInitialized) return
        val res = context.resources
        codes = res.getStringArray(R.array.dictate_input_languages_values)
        labels = res.getStringArray(R.array.dictate_input_languages)
        check(codes.size == labels.size) {
            "dictate_input_languages_values (${codes.size}) and dictate_input_languages " +
                "(${labels.size}) must have same size"
        }
        allowedSet = codes.toSet()
        labelByCode = codes.zip(labels).toMap()
    }

    fun allCodes(): List<String> = codes.toList()

    fun allowed(): Set<String> = allowedSet

    fun resolveLabel(code: String): String = labelByCode[code] ?: code

    /** Alphabetisch nach Display-Label (Deutsch vor English vor Français). */
    fun sortByLabel(codes: Collection<String>): List<String> =
        codes.sortedBy { resolveLabel(it).lowercase() }

    /** Alle anderen Codes als die übergebenen, alphabetisch sortiert. */
    fun othersThan(curated: Collection<String>): List<String> {
        val curatedSet = curated.toSet()
        return sortByLabel(codes.filter { it !in curatedSet })
    }
}
```

#### 1.2) InputLanguagesPlugin (v1, die einzige kuratierte Liste)

Neue Datei: `app/src/main/java/net/devemperor/dictate/preferences/InputLanguagesPlugin.kt`

Wir starten bei **v1** (nicht v2), weil noch nie ein Envelope-Format unter diesem Key gespeichert wurde. Die Legacy-StringSet-Daten werden durch einen **Pre-Migration-Helper** (1.4) einmalig in das v1-Envelope-Format umgewandelt — das sind keine Migration-Functions im Sinne des Versioned-Systems, sondern ein einmaliger Typ-Wechsel-Boostrap.

```kotlin
object InputLanguagesPlugin : VersionedPlugin<List<String>>(
    name = "net.devemperor.dictate.input_languages",  // gleicher Key wie Legacy-StringSet
    currentVersion = 1,
    defaultValue = listOf("detect", "en"),
    codec = StringListCodec,
    onMissingMigration = OnMissingMigration.RESET_TO_DEFAULT
) {
    override val migrations: Map<Int, MigrationFn> = emptyMap()

    /**
     * Entferne Duplikate + unbekannte ISO-Codes, sortiere nach Display-Label.
     * Fallback auf (sortierten) Default wenn leer.
     *
     * Vertrag: Die persistierte Liste ist IMMER label-sortiert — damit
     * Cycle-Button und PopupMenu konsistente Reihenfolge haben.
     * Auch der Default-Pfad wird sortiert, damit der Vertrag wirklich
     * IMMER hält (kein Default-Path-Loophole).
     */
    override fun sanitize(value: List<String>): List<String> {
        val allowed = LanguageLabelResolver.allowed()
        val clean = value.distinct().filter { it in allowed }
        return LanguageLabelResolver.sortByLabel(clean.ifEmpty { defaultValue })
    }

    init { VersionedPluginRegistry.register(this) }
}
```

#### 1.3) LanguageController

Neue Datei: `app/src/main/java/net/devemperor/dictate/core/LanguageController.kt`

**Quality-Gate-Hinweise zu dieser Sektion:**
- **K-2 + W-4:** Konstruktor nimmt `PipelineUiStateReader`-Interface statt `KeyboardUiController` (DIP-konform, Test-fähig). `LanguageController` implementiert `PipelineUiCallback` und wird via `pipelineUiStateReader.addCallback(this)` registriert — kein Composite-Wrapper im Service.
- **K-3:** Pos-Resync nach `VersionedPrefs.save` (das intern `Plugin.sanitize()` läuft) — extrahiert in `persistCuratedAndPos`-Helper, der von drei Aufrufern (`writePermanent`, `setCuratedLanguages`, Legacy-Migration) genutzt wird.
- **W-11:** `setLanguage()` ruft `notifyIfChanged()` bedingungslos nach if-else (idempotent durch `lastEffective`-Guard, robust gegen Callback-Pfad-Versagen).

```kotlin
/**
 * Zentrale Sprach-Logik (Service-/Controller-Layer).
 *
 * Exposed die "effektive Sprache" als Single-Source-of-Truth:
 * - Temporärer Override während ReprocessStaging (aus PipelineUiState)
 * - Permanente Default-Sprache aus SharedPreferences (VersionedPrefs)
 *
 * Threading: Main-thread-only (wie KeyboardUiController).
 *
 * Vertrag: Nach jedem setLanguage/setCuratedLanguages-Aufruf ist lastEffective
 * aktuell und Callbacks sind gefeuert.
 */
class LanguageController(
    private val prefs: SharedPreferences,
    private val pipelineUiStateReader: PipelineUiStateReader
) : PipelineUiCallback {

    interface Callback {
        fun onEffectiveLanguageChanged(oldCode: String, newCode: String)
    }

    private var callback: Callback? = null
    private var lastEffective: String = computeEffective()

    init {
        // Quality-Gate K-2: Direkte Selbst-Registrierung beim Reader; kein Composite-Wrapper.
        // Reader nutzt CopyOnWriteArrayList (siehe Design-Prinzip 7), zusätzliche Konsumenten
        // (Service-Logik) werden separat via addCallback registriert.
        pipelineUiStateReader.addCallback(this)
    }

    fun setCallback(cb: Callback) { this.callback = cb }

    /** Im Service-onDestroy / View-Recreate aufrufen, um Leak zu vermeiden. */
    fun dispose() {
        pipelineUiStateReader.removeCallback(this)
    }

    fun getEffectiveLanguage(): String = computeEffective()

    fun getCuratedLanguages(): List<String> = VersionedPrefs.load(prefs, InputLanguagesPlugin)

    fun setCuratedLanguages(codes: List<String>, preferActive: String? = null) {
        // Quality-Gate K-3: Pos-Resync nach Sanitize. preferActive optional — wenn der
        // Aufrufer (z.B. Settings-UI) den vorher aktiven Code kennt, wird der Pos-Index
        // beibehalten (sofern noch in der gefilterten/sortierten Liste enthalten).
        persistCuratedAndPos(codes, preferActive ?: getEffectiveLanguageOrNull())
        notifyIfChanged()  // effektive Sprache kann sich ändern, falls aktuelle aus Liste fällt
    }

    /**
     * Setzt die Sprache — entscheidet selbst, ob permanent oder temporär.
     *
     * Während ReprocessStaging → temporär (pipelineUiStateReader.updateReprocessLanguage).
     * Sonst → permanent (VersionedPrefs).
     *
     * Wenn der Code nicht in der kuratierten Liste ist, wird er automatisch
     * hinzugefügt (Auto-Curation durch Nutzung — nur im Idle-Pfad).
     */
    fun setLanguage(code: String) {
        val state = pipelineUiStateReader.state
        if (state is PipelineUiState.ReprocessStaging) {
            pipelineUiStateReader.updateReprocessLanguage(code)  // feuert onPipelineUiStateChanged
        } else {
            writePermanent(code)
        }
        notifyIfChanged()  // Quality-Gate W-11: bedingungslos, idempotent durch lastEffective-Guard
    }

    override fun onPipelineUiStateChanged(oldState: PipelineUiState, newState: PipelineUiState) {
        notifyIfChanged()
    }

    private fun computeEffective(): String {
        val state = pipelineUiStateReader.state
        if (state is PipelineUiState.ReprocessStaging) {
            val override = state.selectedLanguage
            if (!override.isNullOrBlank()) return override
        }
        return readPermanent()
    }

    private fun getEffectiveLanguageOrNull(): String? {
        val curated = getCuratedLanguages()
        if (curated.isEmpty()) return null
        val pos = prefs.get(Pref.InputLanguagePos)
        return if (pos in curated.indices) curated[pos] else null
    }

    private fun readPermanent(): String {
        // InputLanguagePos (Int-Index) bleibt als "welche der kuratierten Sprachen ist gerade aktiv" erhalten.
        val langs = getCuratedLanguages()
        if (langs.isEmpty()) return "en"
        val pos = prefs.get(Pref.InputLanguagePos).coerceIn(0, langs.size - 1)
        return langs[pos]
    }

    private fun writePermanent(code: String) {
        // Quality-Gate K-3 + N-6: persistCuratedAndPos kümmert sich um Sanitize-Round-Trip,
        // Sortierung und Pos-Index. Wir reichen die unsortierte Liste rein und vertrauen
        // dem Plugin-Vertrag (sanitize() sortiert). Auto-Curation für unbekannte Codes
        // im Idle-Pfad: Code wird angefügt, falls nicht enthalten.
        val curated = getCuratedLanguages().toMutableList()
        if (code !in curated) curated.add(code)
        persistCuratedAndPos(curated, preferActive = code)
    }

    /**
     * Quality-Gate K-3: Persistiert die kuratierte Liste UND syncht den Pos-Index.
     *
     * Vorgehen:
     *  1. VersionedPrefs.save → Plugin.sanitize transformiert (dedupliziert, filtert, sortiert).
     *  2. Liste neu lesen (jetzt sanitized + sortiert).
     *  3. Pos auf Index von preferActive setzen, falls noch enthalten; sonst 0 (sicherer Default).
     *
     * Drei Aufrufer: writePermanent, setCuratedLanguages, Legacy-Migration.
     */
    private fun persistCuratedAndPos(codes: List<String>, preferActive: String?) {
        VersionedPrefs.save(prefs, InputLanguagesPlugin, codes)
        val persisted = VersionedPrefs.load(prefs, InputLanguagesPlugin)
        val newPos = preferActive
            ?.let { persisted.indexOf(it).takeIf { idx -> idx >= 0 } }
            ?: 0
        prefs.edit().put(Pref.InputLanguagePos, newPos).apply()
    }

    private fun notifyIfChanged() {
        val new = computeEffective()
        if (new != lastEffective) {
            callback?.onEffectiveLanguageChanged(lastEffective, new)
            lastEffective = new
        }
    }
}
```

#### 1.4) Legacy-StringSet-Helper

Der Typ-Wechsel des Keys (StringSet → String) erfordert einen **einmaligen Pre-Migration-Schritt**, weil SharedPreferences im XML zwischen `<set>`-Element und `<string>`-Element unterscheidet. Der Helper läuft VOR dem Versioned-Load.

Neue Datei: `app/src/main/java/net/devemperor/dictate/preferences/InputLanguagesLegacyMigration.kt`

```kotlin
object InputLanguagesLegacyMigration {
    private const val TAG = "InputLanguagesLegacy"
    private const val KEY = "net.devemperor.dictate.input_languages"

    /**
     * Einmalige Migration von StringSet → String (JSON-Envelope).
     * Idempotent: wenn der Key bereits String ist, passiert nichts.
     *
     * Erhält die aktuell aktive Sprache (InputLanguagePos), soweit das aus
     * dem unordered Set rekonstruierbar ist. Alter Pos-Index gilt für die
     * unordered Set-Iteration; nach Migration ist die Liste label-sortiert,
     * also muss der alte aktive Code identifiziert und in der neuen Liste
     * neu gefunden werden.
     */
    fun migrateFromLegacyStringSet(prefs: SharedPreferences) {
        // 1. Bereits String? → nichts tun (schon migriert)
        val alreadyMigrated = try {
            prefs.getString(KEY, null) != null
        } catch (_: ClassCastException) {
            false
        }
        if (alreadyMigrated) return

        // 2. Versuch: als StringSet lesen (Legacy)
        val legacySet: Set<String>? = try {
            prefs.getStringSet(KEY, null)
        } catch (_: ClassCastException) {
            null
        }
        if (legacySet == null) return

        // 3. Alte aktive Sprache identifizieren (best-effort).
        //    Der alte Code iterierte das Set in interner Hash-Reihenfolge — die
        //    ist nicht garantiert stabil über App-Updates, aber wir nutzen sie
        //    als pragmatischen Fallback, weil es die einzige verfügbare Quelle ist.
        val oldPos = prefs.get(Pref.InputLanguagePos)
        val legacyList = legacySet.toList()
        val oldActive: String? = legacyList.getOrNull(oldPos)

        // 4. Neue label-sortierte Liste persistieren
        val ordered = LanguageLabelResolver.sortByLabel(legacySet)
        prefs.edit().remove(KEY).apply()
        VersionedPrefs.save(prefs, InputLanguagesPlugin, ordered)

        // 5. Pos in der NEUEN Liste neu setzen
        val newPos = oldActive
            ?.let { ordered.indexOf(it).takeIf { idx -> idx >= 0 } }
            ?: 0
        prefs.edit().put(Pref.InputLanguagePos, newPos).apply()

        Log.i(TAG, "Migrated ${ordered.size} languages from StringSet to versioned envelope; " +
                "pos $oldPos ($oldActive) -> $newPos")
    }
}
```

#### 1.5) Application-Init

**Quality-Gate W-1 + K-4:** `DictateApplication` existiert bereits (`DictateApplication.java:13-37`), die Init wird **dort** festgenagelt — nicht im Service-Fallback. Zudem MUSS der korrekte SharedPreferences-Accessor verwendet werden, denn das gesamte Projekt nutzt durchgängig `getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE)` — `PreferenceManager.getDefaultSharedPreferences()` greift in das **falsche Pref-File** und macht die Migration zum stillen No-Op.

```java
// In DictateApplication.onCreate() — bestehende Methode erweitern:
@Override
public void onCreate() {
    super.onCreate();
    SharedPreferences sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);

    // 1. Bestehend: Provider-Pref-Migration (TLS, Anthropic-Keys)
    PrefsMigration.migrateProviderPrefs(sp);

    // 2. NEU — in dieser Reihenfolge:
    LanguageLabelResolver.initialize(this);                        // a. Arrays laden
    InputLanguagesLegacyMigration.migrateFromLegacyStringSet(sp);  // b. StringSet → Envelope
    VersionedPluginRegistry.migrateAll(sp);                        // c. Alle Plugins eager-migrieren

    // 3. Bestehend: App-Locale + DB-Init
    DictateUtils.applyApplicationLocale(this);
    // ... (bestehende DB-Singleton + Duration-Healing)
}
```

**Race-Window-Eliminierung:** `PipelineOrchestrator` läuft auf `dbExecutor` und kann via `JobKind.RESUME` aus `HistoryDetailActivity` `VersionedPrefs.load(prefs, InputLanguagesPlugin)` lesen, **bevor** das IME je geöffnet wurde. Ohne Init in `DictateApplication.onCreate()` wäre `LanguageLabelResolver` un-initialisiert und das Plugin würde beim `sanitize()` werfen. Mit der Init in der Application ist das ausgeschlossen — `Application.onCreate()` läuft VOR jedem anderen Component-Lifecycle.

**Defense-in-Depth (Quality-Gate W-1):** `LanguageLabelResolver` bekommt einen `lateinit`-Init-Check und einen Test-Reset:

```kotlin
// In LanguageLabelResolver:
fun allCodes(): List<String> {
    check(::codes.isInitialized) { "LanguageLabelResolver.initialize(context) must be called first" }
    return codes.toList()
}
// Analog für allowed(), resolveLabel(), sortByLabel(), othersThan()

@VisibleForTesting
internal fun reset() {
    if (::codes.isInitialized) {
        // Werte zurücksetzen via Reflection-trick oder durch private setter
        // Realistischer: Singleton-Object umschreiben in eine Klasse + Companion-Holder.
        // Pragmatisch: initializeForTest immer mit den gewünschten Werten erneut rufen.
    }
}

@VisibleForTesting
internal fun initializeForTest(codes: Array<String>, labels: Array<String>) {
    this.codes = codes
    this.labels = labels
    require(codes.size == labels.size)
    this.allowedSet = codes.toSet()
    this.labelByCode = codes.zip(labels).toMap()
    this.codeToIndex = codes.mapIndexed { i, c -> c to i }.toMap()
}
```

**Reihenfolge ist kritisch**: Der Legacy-Helper nutzt `LanguageLabelResolver.sortByLabel()` → Label-Resolver muss zuerst initialisiert sein.

**LanguageController-Lifecycle:** Der Controller wird **in `onCreateInputView()`** instanziiert, NACH `uiController` — denn der Controller braucht `PipelineUiStateReader` als Konstruktor-Dependency, und `uiController` (der das Interface implementiert) selbst wird auch in `onCreateInputView()` erzeugt. Bei jedem View-Recreate (z.B. Rotation) wird also auch der `LanguageController` neu gebaut. Vor der Re-Erzeugung MUSS der alte Controller via `dispose()` von `pipelineUiStateReader.removeCallback(...)` deregistriert werden, sonst leakt die Callback-Liste.

```java
// In DictateInputMethodService.onCreateInputView, nach Erstellung von uiController:
if (languageController != null) {
    languageController.dispose();  // alter Controller deregistriert
}
languageController = new LanguageController(sp, uiController);
languageController.setCallback((oldCode, newCode) -> {
    refreshLanguageChip();
    // Record-Button-Label aktualisieren (zeigt Sprache an)
    if (mainButtonsController != null) {
        mainButtonsController.updateRecordButtonText(getDictateButtonText());
    }
});
```

**Init-Reihenfolge in onCreateInputView (Quality-Gate W-12):**
1. `promptsAdapter` erzeugen
2. `uiController` (KeyboardUiController) erzeugen
3. Alten `languageController` per `dispose()` deregistrieren (falls View-Recreate)
4. `languageController = new LanguageController(sp, uiController)` (registriert sich selbst beim Reader)
5. Service-Pipeline-Callback via `uiController.addCallback(servicePipelineCallback)` registrieren
6. `promptsAdapter.setLanguageChipListener(this::showLanguagePicker)`
7. `languageController.setCallback(...)` registrieren
8. Initial `refreshLanguageChip()` aufrufen, damit der Chip beim ersten Frame befüllt ist

#### 1.5b) Test-Setup (JUnit 4 + Fake-Pattern, Quality-Gate K-1)

**Stack-Hinweis:** Das Dictate-Projekt nutzt **JUnit 4** mit `@Before`/`@Test` und **handgeschriebene Fakes** (`FakeSessionDao`, `NoopRunner`). **Kein** Mockito/MockK/Robolectric ist verfügbar. Alle Test-Snippets in diesem Plan respektieren diese Konvention.

**Test-Seam für LanguageLabelResolver (Quality-Gate K-1, K-4):** Statt eines Context-Mocks (würde Mockito brauchen) nutzen wir die explizite Test-Init-Methode:

```kotlin
@Before
fun setUp() {
    LanguageLabelResolver.initializeForTest(
        codes = arrayOf("detect", "en", "de", "fr", "es"),  // Test-Subset
        labels = arrayOf("Auto-Detect", "English", "Deutsch", "Français", "Español")
    )
    VersionedPluginRegistry.reset()  // Test-Isolation für Plugin-Singleton
}
```

**Fakes für Phase-1/4/5-Tests:**

`FakePipelineUiStateReader` (für `LanguageControllerTest`, `CompositePipelineCallbackTest`):

```kotlin
class FakePipelineUiStateReader : PipelineUiStateReader {
    override var state: PipelineUiState = PipelineUiState.Idle
    private val callbacks = mutableListOf<PipelineUiCallback>()
    var lastUpdateLanguage: String? = null

    override fun updateReprocessLanguage(code: String) {
        lastUpdateLanguage = code
        val s = state
        if (s is PipelineUiState.ReprocessStaging) {
            val newState = s.copy(selectedLanguage = code)
            val old = state
            state = newState
            callbacks.forEach { it.onPipelineUiStateChanged(old, newState) }
        }
    }

    override fun addCallback(cb: PipelineUiCallback) { callbacks.add(cb) }
    override fun removeCallback(cb: PipelineUiCallback) { callbacks.remove(cb) }

    /** Test-Helper: simuliert State-Wechsel mit Callback-Forwarding. */
    fun simulateStateChange(newState: PipelineUiState) {
        val old = state
        state = newState
        callbacks.forEach { it.onPipelineUiStateChanged(old, newState) }
    }
}
```

`FakeSharedPreferences` (für alle Storage-Tests — In-Memory-Map, no Mockito needed):

```kotlin
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getString(key: String?, def: String?): String? = (data[key] as? String) ?: def
    override fun getStringSet(key: String?, def: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? Set<String>)?.toMutableSet() ?: def
    override fun getInt(key: String?, def: Int): Int = (data[key] as? Int) ?: def
    override fun getBoolean(key: String?, def: Boolean): Boolean = (data[key] as? Boolean) ?: def
    override fun getLong(key: String?, def: Long): Long = (data[key] as? Long) ?: def
    override fun getFloat(key: String?, def: Float): Float = (data[key] as? Float) ?: def
    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data, listeners)
    // OnChangeListener-Methoden trivial implementieren
}
class FakeEditor(...) : SharedPreferences.Editor { /* In-Memory-Mutationen */ }
```

`FakeInputConnection` (für `OnResendClickedTest`, `InsertOrFallbackTest`):

```kotlin
class FakeInputConnection(
    private val commitTextResult: Boolean = true,
    private val selectedText: String? = null,
    private val throwOnGetSelected: Boolean = false
) : InputConnection {
    val committedTexts = mutableListOf<String>()

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (commitTextResult) committedTexts.add(text.toString())
        return commitTextResult
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        if (throwOnGetSelected) throw IllegalStateException("simulated stale IC")
        return selectedText
    }
    // andere Methoden: throw NotImplementedError() — Tests rufen nur commitText/getSelectedText
}
```

Test-Komplexität bleibt einfach (~50 Zeilen pro Fake), keine externe Test-Dependency notwendig.

#### 1.6) Unit-Tests

**`InputLanguagesPluginTest.kt`**
- Sanitize mit bekannten Codes → identisch + label-sortiert
- Sanitize mit unbekanntem Code → gefiltert
- Sanitize mit Duplikaten → dedupliziert
- Sanitize mit leerer Liste → Default (`["detect", "en"]`) + label-sortiert
- Plugin-Sortier-Vertrag: `sanitize(["fr", "de", "en"])` liefert label-sortierte Liste

**`InputLanguagesLegacyMigrationTest.kt`**
- Legacy-StringSet mit 3 Einträgen → Migration schreibt nach Label sortiert als Envelope v1
- Legacy-StringSet leer → Default-Werte (`["detect", "en"]`) persistiert
- Bereits migriert (String-Wert vorhanden) → zweiter Aufruf ist No-Op
- Kein Key vorhanden (Fresh-Install) → No-Op, Default aus Plugin greift beim ersten Load
- **Pos-Erhaltung:** Aktiv-Code aus Set wird in der neuen Liste neu lokalisiert (best-effort)
- Ungültiger oldPos (out of range) → newPos = 0 (Default-Fallback)

**`LanguageControllerTest.kt`** (Quality-Gate K-1 Fakes-Stil)
- Fresh install → `getEffectiveLanguage()` == "detect" (Default)
- Permanent setzen → Prefs geschrieben, Callback feuert, `getEffectiveLanguage()` == neuer Wert
- Permanent setzen mit Code, der NICHT in Liste ist → Liste wird automatisch erweitert (Auto-Curation)
- **Quality-Gate K-3:** Permanent setzen mit Duplikat-Code → Pos zeigt nach Sanitize auf den korrekten Index (nicht out-of-bounds)
- ReprocessStaging aktiv → `setLanguage("fr")` schreibt NICHT in Prefs, ruft `pipelineUiStateReader.updateReprocessLanguage("fr")`
- **Quality-Gate W-11:** ReprocessStaging-Mehrfach-Wechsel → `notifyIfChanged` feuert auch wenn Callback-Pfad ausfällt
- ReprocessStaging → Verlassen → `getEffectiveLanguage()` kehrt zum Permanent-Wert zurück, Callback feuert
- `setCuratedLanguages` mit Liste, die aktuelle Sprache nicht enthält → Pos auf 0, Callback feuert
- `setCuratedLanguages` mit `preferActive`-Argument → wenn enthalten, Pos zeigt darauf

**`LanguageLabelResolverTest.kt`**
- `resolveLabel("de")` == "Deutsch" (oder was auch immer im Array steht)
- `resolveLabel("xyz")` == "xyz" (Fallback auf Code selbst)
- `sortByLabel(["en", "de", "fr"])` liefert alphabetische Reihenfolge nach DISPLAY-Label
- `othersThan(["de", "en"])` liefert alle anderen Codes, alphabetisch nach Label
- Init-Check: Aufruf vor `initialize` wirft `IllegalStateException` (Defense-in-Depth W-1)

---

## Phase 2: UI-Integration — Always-visible Chip + Gruppierte PopupMenu

#### 2.1) Chip-Rendering generalisieren

Modifiziert: `DictateInputMethodService.java`

Neue Methode:
```java
private void refreshLanguageChip() {
    if (promptsAdapter == null || languageController == null) return;
    String code = languageController.getEffectiveLanguage();
    String label = resolveLanguageLabel(code);
    promptsAdapter.setLanguageChipVisible(true, label);
}
```

`refreshLanguageChip()` wird aufgerufen von:
- `onCreateInputView()` nach Controller-Erstellung (initialer Zustand)
- `LanguageController.Callback.onEffectiveLanguageChanged(...)` (reaktive Updates)
- `applyReprocessStagingAdapter()` beim Mode-Wechsel — aber nur noch für die Queue-Logik; der Chip bleibt durchgehend sichtbar

Der bestehende `applyReprocessStagingAdapter()`-Pfad wird vereinfacht — er ruft nicht mehr `setLanguageChipVisible(false, null)` beim Verlassen von ReprocessStaging auf.

#### 2.2) Gruppierte PopupMenu-Liste

`showReprocessLanguageDialog()` wird zu `showLanguagePicker(View anchor)` umbenannt und zeigt jetzt **alle** Sprachen — gruppiert in zwei Blöcke: kuratierte oben, alle anderen unten.

**Technik-Rahmen für die visuelle Gruppierung:**

Android-`PopupMenu` basiert auf dem Standard-`Menu`-System. Für visuelle Trenner zwischen Gruppen gibt es drei Wege:

| Ansatz | Verfügbarkeit | Qualität |
|--------|---------------|----------|
| `Menu.setGroupDividerEnabled(true)` | **API 28+** (Android 9+) | ✅ Native horizontale Linie |
| Disabled "Trenner-Label" mit Unicode-Dashes | Alle APIs | ⚠ Hacky, aber funktioniert |
| `ListPopupWindow` mit custom Adapter | Alle APIs | ✅ Volle Kontrolle, aber viel Code |

**Entscheidung:** `setGroupDividerEnabled(true)` auf API 28+, Fallback auf disabled Unicode-Label (`"────── andere Sprachen ──────"`) für API 26/27. Das ist ein eleganter Kompromiss — auf modernen Geräten (> 95% Marktanteil) sehen User einen nativen Trenner, auf älteren Geräten ist ein bescheiden aber funktional aussehendes Label-Item sichtbar.

**Menu-Group-IDs** (stabile Konstanten):
- `GROUP_CURATED = 1` — oberer Block (kuratierte Sprachen)
- `GROUP_OTHERS = 2` — unterer Block (alle anderen)
- `GROUP_ACTION = 3` — Verwalten-Eintrag am Ende

**Menu-Item-IDs:** Der Index im `allCodes()`-Array dient als stabile Item-ID. Zusätzlich eine Sonder-ID für "Verwalten":
```java
private static final int MENU_ID_MANAGE = -1;
```

**Implementierung:**

**Quality-Gate N-1:** Aufrufe der Form `LanguageLabelResolverKt.sortByLabel(LanguageLabelResolver.INSTANCE, ...)` sind syntaktisch falsch — `sortByLabel` ist eine Member-Funktion auf dem `object LanguageLabelResolver`, kein Top-Level. Korrekte Java-Bridge: `LanguageLabelResolver.INSTANCE.sortByLabel(...)` (auch konsistent in Phase 3.1).

**Quality-Gate N-6:** Da `Plugin.sanitize()` bereits sortiert, vertraut der Code dem Plugin-Vertrag — `getCuratedLanguages()` liefert bereits label-sortiert. Die UI muss nur `othersThan(curatedLangs)` aufrufen, das selbst sortiert.

```java
private void showLanguagePicker(View anchor) {
    // Quality-Gate N-6: getCuratedLanguages() liefert bereits label-sortiert (Plugin-Vertrag).
    List<String> curatedOrdered = languageController.getCuratedLanguages();
    List<String> othersOrdered = LanguageLabelResolver.INSTANCE.othersThan(curatedOrdered);

    android.widget.PopupMenu popup = new android.widget.PopupMenu(
        new ContextThemeWrapper(this, R.style.Theme_Dictate), anchor);
    Menu menu = popup.getMenu();

    // --- Oberer Block: kuratierte Sprachen ---
    int order = 0;
    for (String code : curatedOrdered) {
        String label = LanguageLabelResolver.INSTANCE.resolveLabel(code);
        MenuItem item = menu.add(GROUP_CURATED, stableIdForCode(code), order++, label);
    }

    // --- Visueller Trenner ---
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {  // API 28 = ANDROID_9_PIE
        // Nativer horizontaler Divider zwischen Groups
        menu.setGroupDividerEnabled(true);
    } else if (!othersOrdered.isEmpty()) {
        // Fallback: disabled Label-Item mit Unicode-Dashes
        MenuItem sep = menu.add(GROUP_OTHERS, Menu.NONE, order++,
            getString(R.string.dictate_language_other_separator));
        sep.setEnabled(false);
    }

    // --- Unterer Block: alle anderen Sprachen ---
    for (String code : othersOrdered) {
        String label = LanguageLabelResolver.INSTANCE.resolveLabel(code);
        menu.add(GROUP_OTHERS, stableIdForCode(code), order++, label);
    }

    // --- Action am Ende: Verwalten ---
    menu.add(GROUP_ACTION, MENU_ID_MANAGE, order++,
        getString(R.string.dictate_language_manage));

    popup.setOnMenuItemClickListener(item -> {
        int id = item.getItemId();
        if (id == MENU_ID_MANAGE) {
            openLanguageSettings();
            return true;
        }
        String code = codeForStableId(id);
        if (code != null) {
            languageController.setLanguage(code);
            return true;
        }
        return false;
    });
    popup.show();
}

// Quality-Gate N-7: codeToIndex-Map im Resolver + MENU_ID_OFFSET-Konstante
// statt fragile +1-Magic-Number-Kollisionsvermeidung.
private static final int MENU_ID_OFFSET = 100;

private static int stableIdForCode(String code) {
    int idx = LanguageLabelResolver.INSTANCE.indexOfCode(code);
    return idx >= 0 ? idx + MENU_ID_OFFSET : MENU_ID_INVALID;
}

private static String codeForStableId(int id) {
    if (id < MENU_ID_OFFSET) return null;
    int idx = id - MENU_ID_OFFSET;
    List<String> all = LanguageLabelResolver.INSTANCE.allCodes();
    return (idx < all.size()) ? all.get(idx) : null;
}

private void openLanguageSettings() {
    Intent intent = new Intent(this, DictateSettingsActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.putExtra(DictateSettingsActivity.EXTRA_SCROLL_TO,
        "net.devemperor.dictate.input_languages");
    startActivity(intent);
}
```

**Im `LanguageLabelResolver` ergänzen (zur Unterstützung von N-7):**
```kotlin
private lateinit var codeToIndex: Map<String, Int>  // gefüllt in initialize()
fun indexOfCode(code: String): Int = codeToIndex[code] ?: -1
```

**Group-ID-Konstanten** (oben in der Klasse):
```java
private static final int GROUP_CURATED = 1;
private static final int GROUP_OTHERS = 2;
private static final int GROUP_ACTION = 3;
```

**Auto-Curation (Quality-Gate N-5 — Klarstellung):** Wenn der User im **Idle-Pfad** eine Sprache aus dem unteren Block auswählt (also bisher nicht-kuratiert), fügt `LanguageController.setLanguage(code)` sie automatisch zur kuratierten Liste hinzu (`writePermanent()`). Beim nächsten Öffnen des PopupMenus steht sie dann oben. Das ist intuitives UX — "was ich häufig wähle, wandert nach oben".

**Während ReprocessStaging gilt diese Regel NICHT.** Der `LanguageController` routet die Wahl als transienten Override (`pipelineUiStateReader.updateReprocessLanguage(code)`), ohne die kuratierte Liste zu mutieren. Eine im ReprocessStaging einmalig gewählte "neue" Sprache erscheint also beim nächsten Idle-Picker wieder im unteren Block. Begründung: "Hier einmalig mit Französisch reprocessen" soll die Normalmodus-Präferenzen nicht beeinflussen.

#### 2.2a) Settings-Activity Scroll-Target (Quality-Gate K-7)

**Problem:** Die "⚙ Sprachen verwalten…"-Action öffnet `DictateSettingsActivity` und übergibt `intent.putExtra(DictateSettingsActivity.EXTRA_SCROLL_TO, "net.devemperor.dictate.input_languages")`. Eine vollständige Suche im Code ergibt aber **keinen Treffer** für `EXTRA_SCROLL_TO`, `scrollToPreference` oder `scroll_to`. Die Konstante existiert nicht und der Scroll-Effekt würde stillschweigend ausbleiben.

**Erforderliche Änderungen:**

1. In `DictateSettingsActivity.java` die Konstante einführen:
   ```java
   public static final String EXTRA_SCROLL_TO = "net.devemperor.dictate.scroll_to";
   ```

2. In `DictateSettingsActivity.onCreate()` aus dem Intent lesen und an das Fragment via Bundle weiterreichen:
   ```java
   String scrollTo = getIntent().getStringExtra(EXTRA_SCROLL_TO);
   if (scrollTo != null) {
       Bundle args = new Bundle();
       args.putString("scroll_to", scrollTo);
       PreferencesFragment fragment = new PreferencesFragment();
       fragment.setArguments(args);
       getSupportFragmentManager().beginTransaction()
           .replace(R.id.preferences_fragment_container, fragment).commit();
   }
   ```

3. In `PreferencesFragment.onCreatePreferences()` (oder `onViewCreated`) nach erfolgreichem Setup:
   ```java
   String scrollTo = getArguments() != null ? getArguments().getString("scroll_to") : null;
   if (scrollTo != null) {
       scrollToPreference(scrollTo);
   }
   ```

Damit funktioniert der Scroll-Effekt, der in 2.2 von `openLanguageSettings()` versprochen wird. Manual-Verification-Item in Phase 4: "Verwalten-Click öffnet Settings mit Sprach-Auswahl im Sichtbereich".

#### 2.3) Neue String-Resources

`app/src/main/res/values/strings.xml`:
```xml
<string name="dictate_language_manage">⚙ Sprachen verwalten…</string>
<string name="dictate_language_other_separator">────── andere Sprachen ──────</string>
```

Plus Übersetzungen in `values-de/`, `values-es/`, `values-pt/`.

#### 2.4) Chip-Click-Listener permanent wiring

`DictateInputMethodService.onCreateInputView()`:
```java
// Lesson-learned aus dem PipelineUiState-Plan: Listener wird EINMAL gesetzt
// und nicht zur Laufzeit pro Mode getauscht. Das spart einen Klassen-Bug-Pfad
// und verhindert die "Was-zeigt-der-Click-jetzt"-Mehrdeutigkeit.
promptsAdapter.setLanguageChipListener(this::showLanguagePicker);
```

#### 2.5) Record-Button-Label-Integration

`getDictateButtonText()` nutzt jetzt `languageController.getEffectiveLanguage()` statt direkt die Prefs zu lesen. Damit zeigt der Record-Button auch während ReprocessStaging das korrekte temporäre Sprach-Label.

#### 2.5b) Aufräumen der Service-Sprach-Felder (Quality-Gate W-7)

Wenn `LanguageController` Single-Source-of-Truth wird, sind die heutigen mutable Sprach-Felder im Service redundant und müssen entfernt werden — sonst gibt es zwei Wahrheiten ("was zeigt der Chip" vs. "was speichert der Pipeline-Aufruf").

**Konkrete Aufräum-Liste in `DictateInputMethodService.java`:**

| Element | Heute | Nach Refactor |
|---------|-------|---------------|
| `int currentInputLanguagePos` (Zeile 114) | Mutable Cache, in `onCreateInputView` (334), `onLanguageCycled` (2127), `getDictateButtonText` (1709-1710) gepflegt | **Entfernt.** Read-Pfad geht über `languageController.readPermanent()` (intern via `Pref.InputLanguagePos`-Lookup). Cycle aktualisiert Pos in `setLanguage`-Pfad. |
| `String currentInputLanguageValue` (Zeile 115) | Mutable Cache, in `getDictateButtonText` neu berechnet, gelesen in 1339-1341 (Recording-Start), Style-Prompt-Resolution | **Entfernt.** Alle Reads ersetzt durch `languageController.getEffectiveLanguage()`. |
| Self-Heal-Block in `getDictateButtonText` (Zeilen 1697-1706) | Sanitisiert `InputLanguages`-StringSet, validiert Pos-Index | **Entfernt** — `Plugin.sanitize()` und `LanguageController.readPermanent()` (mit `coerceIn`) decken das ab. |
| `dictate_record_different_languages` Lookup (Zeile 1718) | Liest Record-Button-Label aus separatem Array | **Bleibt funktional** — neuer Helper `LanguageLabelResolver.recordLabelFor(code)` kapselt den Lookup. Plus: Resolver bekommt zweites Array `recordLabels` analog zu `labels`. |

**Nach diesem Refactor reduziert sich `getDictateButtonText` auf:**
```java
private String getDictateButtonText() {
    String code = languageController.getEffectiveLanguage();
    return LanguageLabelResolver.INSTANCE.recordLabelFor(code);
}
```

Im `LanguageLabelResolver` ergänzen:
```kotlin
private lateinit var recordLabels: Array<String>  // R.array.dictate_record_different_languages
private lateinit var recordLabelByCode: Map<String, String>

// In initialize() zusätzlich:
recordLabels = res.getStringArray(R.array.dictate_record_different_languages)
check(codes.size == recordLabels.size) { ... }
recordLabelByCode = codes.zip(recordLabels).toMap()

fun recordLabelFor(code: String): String = recordLabelByCode[code] ?: code
```

#### 2.6) JobRequest-Konstruktion

Alle Stellen, die heute `currentInputLanguageValue` nutzen, lesen jetzt `languageController.getEffectiveLanguage()`. Im `TranscriptionPipeline`-Konstruktor: `selectedLanguage = languageController.getEffectiveLanguage()`.

#### 2.7) Chip-Visibility und Chip-Enabled separat (Quality-Gate W-6)

**Problem mit dem ursprünglichen Plan-Vorschlag:** Eine 3-Argument-Erweiterung `setLanguageChipVisible(boolean, String, boolean enabled)` würde die bestehende 2-Argument-Signatur brechen — alle Aufrufer in `applyReprocessStagingAdapter` (Zeilen 886, 889) müssten geändert werden, und Visibility/Enabled werden semantisch vermischt.

**Lösung:** Zwei separate Methoden im `PromptsKeyboardAdapter`:

```java
// Bestehende Methode bleibt unverändert:
public void setLanguageChipVisible(boolean visible, String label) { ... }

// NEUE Methode für Disabled-State:
public void setLanguageChipEnabled(boolean enabled) {
    if (this.chipEnabled != enabled) {
        this.chipEnabled = enabled;
        notifyItemChanged(0);  // Position 0 = Chip
    }
}

// Im LanguageChipViewHolder-Binding:
chipBtn.setEnabled(chipEnabled);
chipBtn.setAlpha(chipEnabled ? 1f : 0.5f);  // visuelles Feedback
```

**Service-Wiring:** Während Pipeline-Running wird der Chip visuell disabled gerendert. Der `PipelineUiCallback` (Service-Variante, registriert via `uiController.addCallback(servicePipelineCallback)`) schaltet beim State-Wechsel:

```java
@Override
public void onPipelineUiStateChanged(PipelineUiState old, PipelineUiState newState) {
    boolean pipelineRunning = newState instanceof PipelineUiState.Running
                          || newState instanceof PipelineUiState.Preparing;
    promptsAdapter.setLanguageChipEnabled(!pipelineRunning);
    // ... bestehende Service-Logik ...
}
```

Damit ist die Chip-Visibility (immer `true` außer in QWERTZ-Mode) komplett getrennt von der Klickbarkeit (state-abhängig).

#### 2.7b) `applyReprocessStagingAdapter` umbenennen → `syncQueueOrder`

**Quality-Gate W-6 Folge-Edit:** Nachdem die Chip-Visibility nicht mehr in dieser Methode lebt, hat sie nur noch eine Verantwortung — Queue-Sync. Umbenennung zu `syncQueueOrder(PipelineUiState newState)` macht das Single-Responsibility-Naming klar:

```java
private void syncQueueOrder(PipelineUiState newState) {
    if (newState instanceof PipelineUiState.ReprocessStaging) {
        promptsAdapter.setQueuedPromptOrder(
            ((PipelineUiState.ReprocessStaging) newState).getEditableQueue());
    } else {
        promptsAdapter.setQueuedPromptOrder(promptQueueManager.getQueuedIds());
    }
}
```

#### 2.8) Cycle-Button (Long-Press Record) anpassen

Der bestehende `onLanguageCycled()` iterierte historisch durch `InputLanguagePos` im StringSet. Nach der Migration ist die kuratierte Liste geordnet nach Label, der Cycle rotiert durch diese Liste:

```java
private void onLanguageCycled() {
    List<String> curated = languageController.getCuratedLanguages();
    if (curated.isEmpty()) return;
    int pos = DictatePrefsKt.get(sp, Pref.InputLanguagePos.INSTANCE);
    int next = (pos + 1) % curated.size();
    // setLanguage schreibt den konkreten Code — inklusive Auto-Curation
    // wenn der User die Liste inzwischen manipuliert hat
    languageController.setLanguage(curated.get(next));
}
```

---

## Phase 3: Settings-UI-Anpassung

#### 3.1) MultiSelectListPreference-Wrap

**Quality-Gate K-5 + K-6 — Zwei zusammengehörige Fixes:**

1. **K-5 (`setPersistent(false)`):** `MultiSelectListPreference.setValues(Set<String>)` ruft intern `persistStringSet(values)` und überschreibt damit den JSON-Envelope sofort durch ein StringSet. Beim nächsten `VersionedPrefs.load` wirft `getString()` `ClassCastException` und der Catch-Block fällt auf Default zurück — Kuration ist verloren. Lösung: `setPersistent(false)` (oder `app:persistent="false"` im XML) macht die Preference zur reinen UI-Komponente, das Schreiben übernimmt der Listener via Controller.
2. **K-6 (Schreiben via Controller, nicht direkt):** Settings-UI ruft `languageController.setCuratedLanguages(...)`, **nicht** `VersionedPrefs.save(...)`. Damit bleibt die Single-Source-of-Truth erhalten, der Service wird auch während laufender Pipeline informiert (Pos-Resync läuft im Controller), und die Pos-Erhaltungs-Logik existiert nur einmal (siehe `LanguageController.persistCuratedAndPos`).

**Bedingung dafür:** Phase 1 muss `LanguageController` als Service-erreichbares Objekt etabliert haben (Application-Singleton oder ähnlich). Im IME-Kontext gibt's keinen Service-Bind aus dem Settings-Activity-Process — beide Komponenten teilen aber denselben Process (Activity läuft IM IME-Process), daher reicht ein static `getOrCreate()`-Hook auf `DictateApplication`:

```java
// In DictateApplication:
public LanguageController getOrCreateLanguageController() {
    if (languageController == null) {
        languageController = new LanguageController(
            getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE),
            uiStateReader);  // ein No-Op-Reader für Headless-Kontext
    }
    return languageController;
}
```

Falls Settings als separater Process läuft (default ist same-process für Activities), funktioniert das. Falls nicht, fallback auf eine Broadcast-basierte Notification (siehe Edge Cases).

**Konkrete `PreferencesFragment.java`-Änderungen:**

**Im XML (`fragment_preferences.xml`)** zur Preference ergänzen:
```xml
<MultiSelectListPreference
    android:key="net.devemperor.dictate.input_languages"
    app:persistent="false"
    ...
    />
```

(Damit greift weder `app:defaultValue` als Initial-StringSet-Schreiben, noch `setValues` als Persist-Trigger.)

**Im Fragment-Code:**

```java
// Laden: VersionedPrefs als Source-of-Truth, setValues schreibt nicht mehr (persistent=false)
List<String> curated = VersionedPrefs.load(sp, InputLanguagesPlugin.INSTANCE);
multiSelectPref.setValues(new HashSet<>(curated));

// Speichern: über LanguageController
inputLanguagesPreference.setOnPreferenceChangeListener((preference, newValue) -> {
    @SuppressWarnings("unchecked")
    Set<String> selectedLanguages = (Set<String>) newValue;
    if (selectedLanguages.isEmpty()) {
        Toast.makeText(getContext(), R.string.dictate_input_languages_empty,
                Toast.LENGTH_SHORT).show();
        return false;
    }

    // 1. Aktuell aktiven Code bestimmen (für Pos-Erhaltung)
    List<String> oldList = VersionedPrefs.load(sp, InputLanguagesPlugin.INSTANCE);
    int oldPos = DictatePrefsKt.get(sp, Pref.InputLanguagePos.INSTANCE);
    String oldActive = (oldPos >= 0 && oldPos < oldList.size())
            ? oldList.get(oldPos) : null;

    // 2. Über Controller persistieren — Sortierung + Pos-Resync passieren intern
    //    (Quality-Gate N-1: korrekter Java-Bridge-Aufruf für Kotlin-object-Member)
    LanguageController controller =
            ((DictateApplication) requireActivity().getApplicationContext())
                    .getOrCreateLanguageController();
    controller.setCuratedLanguages(new ArrayList<>(selectedLanguages), oldActive);

    return false;  // Persistent=false ist gesetzt, dieser return-Wert ist defensiv
});
```

**Pos-Erhaltung:** Wenn der User "Französisch" als Cycle-Position hatte und in Settings nur "Spanisch" entfernt, bleibt "Französisch" aktiv. Nur wenn der aktive Code SELBST entfernt wird, springt Pos auf 0. Diese Logik lebt jetzt zentral in `LanguageController.persistCuratedAndPos` (DRY).

**SummaryProvider-Stale-State (Folge-Issue SEC-3-4):** Wird durch `setPersistent(false)` automatisch gelöst, weil `MultiSelectListPreference.getValues()` für den Summary-Refresh nun aus dem In-Memory-State der Preference liest, nicht aus der (nicht-persistierten) SharedPreferences.

#### 3.2) Entfernung der doppelten Sanitization in `getDictateButtonText`

**Quality-Gate W-7 (Folge-Edit aus Phase 2.5b):** Die bestehende Sanitization-Logik in `DictateInputMethodService.getDictateButtonText()` (Zeilen 1693-1719) ist durch den `sanitize()`-Hook auf `InputLanguagesPlugin` obsolet — und Phase 2.5b räumt schon die Service-Felder (`currentInputLanguagePos`, `currentInputLanguageValue`) auf. Phase 3.2 ist also reine **Konsolidierung**: nach Implementierung von Phase 2.5b prüfen, ob in `getDictateButtonText` noch tote Sanitization-Reste übrig sind, und entfernen.

Reduzierter Endzustand:
```java
private String getDictateButtonText() {
    String code = languageController.getEffectiveLanguage();
    return LanguageLabelResolver.INSTANCE.recordLabelFor(code);
}
```

---

## Phase 4: Tests & Validation

#### 4.1) Unit-Tests (bereits in den Phasen verteilt — Quality-Gate K-1: JUnit 4 + Fakes-Stil)

**Phase 0:**
- `VersionedMigratorTest` (inkl. Future-Version-mit-RESET-Strategie W-8, JUnit 4 mit `@Before`)
- `VersionedSerializerTest` (inkl. is-Number-Edge-Cases W-10, malformed JSON, Roundtrip)
- `StringListCodecTest`
- `VersionedPluginRegistryTest` (W-9: register/lookup/duplicate-throws/reset/migrateAll-Try-Catch)

**Phase 1:**
- `InputLanguagesPluginTest` (Sortier-Vertrag inkl.)
- `InputLanguagesLegacyMigrationTest` (Pos-Erhaltung, Idempotenz)
- `LanguageControllerTest` (mit `FakePipelineUiStateReader` + `FakeSharedPreferences` — kein Mockito; deckt K-3 Pos-Resync, W-11 unconditional notify, ReprocessStaging-Pfade ab)
- `LanguageLabelResolverTest` (via `initializeForTest`-Seam, inkl. Init-Check W-1)

**Phase 5:**
- `OnResendClickedTest` (mit `FakeInputConnection` — alle Status-Pfade COMPLETED/CANCELLED/RECORDED/FAILED, Doppel-Klick-Mitigation N-2)
- `InsertOrFallbackTest` (3-Stufen-Direkt-Tests inkl. Slow-Output-Pfad K-8 in Stufe 1 UND Stufe 2)
- `EditorIdentityTest` (pure-Function: gleicher fieldId + packageName, null-Cases, fieldId=0)

**Integration/Cross-Cutting:**
- `MultiCallbackForwardingTest` (Quality-Gate K-2): zwei Callbacks via `addCallback` registriert → State-Wechsel forwarded an beide. Ersetzt das ursprünglich geplante `CompositePipelineCallbackTest` (Composite-Wrapper entfällt).

#### 4.2) End-to-End Verification Checklist (manuell)

**Fresh Install**
- [ ] `defaultValue` (`["detect", "en"]`) ist im Picker sichtbar
- [ ] Chip zeigt "Auto-Detect" (oder entsprechende Lokalisierung)
- [ ] Record-Button-Label passt zur effektiven Sprache

**Upgrade von alter App-Version (mit Legacy-StringSet)**
- [ ] Beim ersten Start: Logcat zeigt "Migrated N languages from StringSet to versioned list"
- [ ] Nach Migration: Picker-Inhalt entspricht dem alten StringSet
- [ ] Re-Start der App: Migration läuft NICHT nochmal (Idempotenz)

**Always-visible Chip**
- [ ] Idle-State: Chip ist sichtbar
- [ ] Recording-State: Chip ist sichtbar, klickbar
- [ ] Pipeline Running: Chip ist sichtbar, **disabled**
- [ ] ReprocessStaging: Chip ist sichtbar, zeigt Override-Label
- [ ] Nach ReprocessStaging-Exit: Chip zeigt wieder Permanent-Label

**PopupMenu (gruppierte Liste)**
- [ ] Oberer Block: kuratierte Sprachen, alphabetisch nach Label sortiert (z.B. Deutsch, English, Français — nicht de/en/fr)
- [ ] Unterer Block: alle anderen Sprachen, alphabetisch nach Label sortiert
- [ ] Ende: "⚙ Sprachen verwalten…"
- [ ] **API 28+:** Visueller Trenner (horizontale Linie) zwischen oberem und unterem Block
- [ ] **API 26/27:** Disabled Label-Item "────── andere Sprachen ──────" als Trenner
- [ ] Auswahl aus oberem Block: permanent (Idle) oder transient (ReprocessStaging), Liste unverändert
- [ ] Auswahl aus unterem Block (im Idle): permanent — Sprache wird automatisch zur kuratierten Liste hinzugefügt (Auto-Curation), nach Reopen steht sie oben
- [ ] **Quality-Gate N-5:** Auswahl aus unterem Block (in ReprocessStaging): nur transient, Liste **bleibt unverändert** — beim nächsten Idle-Picker-Öffnen erneut im unteren Block
- [ ] Trenner-Item selbst ist nicht klickbar
- [ ] "Verwalten…" öffnet Settings-Activity
- [ ] **Quality-Gate K-7:** "Verwalten…" scrollt im Settings direkt zur Sprach-Auswahl (sichtbar ohne manuelles Scrollen)

**Settings-UI**
- [ ] MultiSelectListPreference zeigt aktuell kuratierte Auswahl korrekt an
- [ ] Änderung in Settings → nach zurück-zur-Keyboard: PopupMenu reflektiert neue Auswahl
- [ ] Setzen einer leeren Auswahl → Toast, keine Persistierung

**Dual-Mode-Schreiben**
- [ ] Chip-Click in Idle → permanenter Wechsel
- [ ] Chip-Click in ReprocessStaging → nur transient
- [ ] Nach ReprocessStaging-Exit: permanente Sprache unverändert

**Sanitization / Self-Heal**
- [ ] Manuell in adb per `setprop` oder sqlite3 einen ungültigen ISO-Code ins Prefs-XML schreiben → nächster App-Start filtert raus
- [ ] Prefs-XML mit leerer Liste → Default-Werte werden geladen (kein Crash)

**Cycle-Button (Long-Press Record)**
- [ ] Long-Press cycled durch kuratierte Liste (nicht mehr durch alte StringSet)
- [ ] Cycle-Action feuert `languageController.setLanguage(nextInCurated)` → Callback → Chip-Label updated
- [ ] Reihenfolge im Cycle entspricht alphabetischer Label-Sortierung (konsistent mit PopupMenu oberer Block)

#### 4.3) Regression-Tests

- [ ] Reprocess-Pipeline funktioniert nach Language-Change während Staging (JobRequest.language = override)
- [ ] Build erfolgreich (gradle assembleDebug)
- [ ] Alle vorhandenen Unit-Tests laufen (`gradle test`)
- [ ] Keine BadTokenException beim PopupMenu (IME-safe)

---

## Phase 5: Bugfix Resend-Button — robuste Insertion + neue Status-Matrix

#### 5.1) Bug-Beschreibung

**Symptom:** Kurzer Klick auf den Resend-Button (`resend_btn`, `activity_dictate_keyboard_view.xml:59`) fügt den letzten Output **nicht ein**, wenn die Session den Status `COMPLETED` hat. Long-Press funktioniert (führt zur ReprocessStaging und insert via Pipeline-Re-Run).

**Root Cause:** `onResendClicked()` (`DictateInputMethodService.java:1803-1840`) holt `lastSession` per `dbExecutor.execute()` (Worker-Thread). Der DB-Lookup kann Sekunden dauern. Anschließend `mainHandler.post(() -> commitTextToInputConnection(output, ...))` — diese ruft erst dann `getCurrentInputConnection()` (Zeile 1559). Wenn der User in der Zwischenzeit auf ein anderes Feld geklickt hat oder das Keyboard kurz geschlossen war, ist die `InputConnection` `null` → silent return Zeile 1560 → kein Insert.

#### 5.2) Lösung — Variante B (robust): InputConnection-Capture + 3-stufige Strategie

Die `InputConnection` wird **am Klick-Moment** auf dem Main-Thread eingefangen, BEVOR der DB-Lookup beginnt. Beim Insert werden drei Strategien in Reihenfolge probiert; die erste die funktioniert gewinnt.

**Stufen-Strategie:**

| Stufe | Bedingung | Aktion |
|-------|-----------|--------|
| 1 | Live-IC verfügbar UND Editor identisch zur Capture | `commitTextToInputConnection()` (bestehende Methode, alles inkl. Auto-Enter und DB-Log) |
| 2 | Editor hat gewechselt oder Live-IC null, Capture-IC `commitText()` returned `true` | Manuell Side-Effects nachziehen (Auto-Enter via `scheduleAutoEnter`, DB-Log via Helper) |
| 3 | Beide IC-Kanäle tot (Capture-IC `commitText()` returned `false`) | Toast "Fokus verloren" + `startResumeJob(sessionId)` als Last-Resort-Fallback |

#### 5.3) Neue Status-Matrix für `onResendClicked()`

| Status | Output vorhanden | Aktion |
|--------|------------------|--------|
| `COMPLETED` | ja | **3-stufige Strategie** (Stufen 1→2→3) |
| `COMPLETED` | nein/leer | No-Op (defensiv — sollte nicht vorkommen) |
| `CANCELLED` | ja | **3-stufige Strategie** (Stufen 1→2→3) |
| `CANCELLED` | nein | `startResumeJob(sessionId)` (unverändert) |
| `RECORDED` | n/a | `startResumeJob(sessionId)` (unverändert) |
| `FAILED` | n/a | **No-Op** (Verhaltensänderung) — kein automatischer Resume mehr; User muss explizit Long-Press → ReprocessStaging machen |

**Begründung "FAILED → No-Op":** API-Fehler (z.B. ungültiger API-Key, Rate-Limit) sollten keinen stillen Re-Run triggern, der wieder Geld/Tokens kostet. Long-Press auf den Resend-Button führt zur ReprocessStaging — das ist der explizite Re-Run-Pfad mit User-Bestätigung.

#### 5.0) Vorgelagerter Refactor — `commitTextToInputConnection` mit IC-Parameter

**Quality-Gate W-3 + K-8:** Der ursprüngliche Plan-Vorschlag führte `handlePostCommit` als expliziten "Mirror" der Side-Effects (Auto-Enter, DB-Log, getSelectedText) in `commitTextToInputConnection` ein. Vier Agents haben unabhängig auf den Drift-Risiko hingewiesen — und ein einziger Refactor löst gleich mehrere Folge-Findings:

- **K-8:** Stufe 2 ehrte `Pref.InstantOutput` (Slow-Output) nicht → UX-Drift zwischen den Stufen
- **W-3:** Mirror-Pattern ist Anti-Pattern, jede Änderung muss an zwei Stellen nachgezogen werden
- **Audit-Sessionsbindung:** `handlePostCommit` las `sessionTracker.getCurrentSessionId()`, das nach Pipeline-Ende `null` ist → Resend-Inserts hatten falsche/keine Session-Id im DB-Log
- **getSelectedText-Inkonsistenz:** Stufe 1 ohne try-catch, Stufe 2 mit — kein zentrales Pattern

**Refactor-Schritt VOR der eigentlichen Phase-5-Implementation:**

```java
/**
 * Commit-Text mit explizitem InputConnection-Parameter.
 *
 * Vorher: Methode rief intern getCurrentInputConnection(). Nach diesem Refactor
 * akzeptiert sie die IC als Parameter und wickelt alle Side-Effects (Auto-Enter,
 * DB-Log, replacedText-Capture, InstantOutput-Branch) konsistent ab.
 *
 * @param ic           die InputConnection (live oder captured); NULL → früher Return
 * @param editor       EditorInfo, der mit ic kompatibel ist (für Audit-Log: packageName)
 * @param text         der einzufügende Text
 * @param source       für Telemetrie/Audit (TRANSCRIPTION/REPROCESS/...)
 * @param sessionIdOverride NULL → liest sessionTracker.getCurrentSessionId();
 *                          sonst → nutzt diesen Override (für Resend, wo Pipeline-Ende
 *                          den currentSessionId-Tracker schon geleert hat).
 * @return true bei Erfolg, false wenn IC null oder commitText fehlschlägt.
 */
private boolean commitTextToInputConnection(
        InputConnection ic,
        EditorInfo editor,
        String text,
        InsertionSource source,
        @Nullable String sessionIdOverride) {
    if (ic == null) return false;

    // 1. ReplacedText-Capture (zentral mit try-catch — Quality-Gate W-3)
    String replacedText = safeReadSelectedText(ic);

    // 2. InstantOutput-Branch (Quality-Gate K-8) — funktioniert in beiden Stufen
    boolean success;
    if (DictatePrefsKt.get(sp, Pref.InstantOutput.INSTANCE)) {
        success = ic.commitText(text, 1);
    } else {
        success = commitSlowOutput(ic, text);  // bestehende Slow-Output-Logik, IC-parametrisiert
    }
    if (!success) return false;

    // 3. Auto-Enter
    if (isAutoEnterActive()) {
        scheduleAutoEnter(text);
    }

    // 4. DB-Log mit korrekter Session-Id
    if (text == null || text.isEmpty()) return true;
    final String fSessionId = sessionIdOverride != null
            ? sessionIdOverride : sessionTracker.getCurrentSessionId();
    final String fStepId = sessionTracker.getCurrentStepId();
    final String fTranscriptionId = sessionTracker.getCurrentTranscriptionId();
    final String pkg = editor != null ? editor.packageName : null;
    final String fReplaced = replacedText;
    final String fText = text;
    dbExecutor.execute(() -> {
        sessionManager.logTextInsertion(fSessionId, fText, fReplaced, pkg,
                null, fStepId, fTranscriptionId, InsertionMethod.COMMIT);
        if (fSessionId != null) {
            sessionManager.updateFinalOutputText(fSessionId, fText);
        }
    });
    return true;
}

/** Quality-Gate W-3: zentraler try-catch für stale-IC-Lese-Versuche. */
private static String safeReadSelectedText(InputConnection ic) {
    try {
        CharSequence sel = ic.getSelectedText(0);
        return (sel != null && sel.length() > 0) ? sel.toString() : null;
    } catch (Throwable ignored) {
        return null;
    }
}

/** Bestehender Aufrufer in Service-Body — backward-compat-Wrapper. */
private void commitTextToInputConnection(String text, InsertionSource source) {
    commitTextToInputConnection(
        getCurrentInputConnection(),
        getCurrentInputEditorInfo(),
        text, source, /* sessionIdOverride = */ null);
}
```

**Konsequenz:** `handlePostCommit` aus dem ursprünglichen Plan-Entwurf **entfällt komplett**. Beide Stufen 1 und 2 rufen denselben Refactor-Pfad — Slow-Output, Auto-Enter, DB-Log, getSelectedText laufen einheitlich.

#### 5.4) Implementierung

**Neue Methode `insertOrFallback(...)`** kapselt die 3-stufige Strategie. Nach dem 5.0-Refactor sind Stufe 1 und Stufe 2 nahezu identisch — sie unterscheiden sich nur darin, welche IC genutzt wird:

```java
/**
 * 3-stufige Insert-Strategie für den kurzen Resend-Klick.
 *
 * Wird auf Main-Thread aufgerufen, NACH dem DB-Lookup auf dem Worker-Thread.
 * Die InputConnection (capturedIc) und der EditorInfo (capturedEditor) wurden
 * im Klick-Moment eingefangen — also bevor der DB-Lookup begann.
 *
 * @param sessionId    lastSession.getId() — für korrektes Audit-Log binding (W-3)
 */
private void insertOrFallback(
        InputConnection capturedIc,
        EditorInfo capturedEditor,
        String output,
        String sessionId) {
    // Stufe 1: bevorzugt Live-IC, wenn Editor noch identisch
    InputConnection liveIc = getCurrentInputConnection();
    EditorInfo liveEditor = getCurrentInputEditorInfo();
    if (liveIc != null && EditorIdentity.isSame(liveEditor, capturedEditor)) {
        boolean ok = commitTextToInputConnection(
                liveIc, liveEditor, output, InsertionSource.TRANSCRIPTION, sessionId);
        if (ok) return;
        // Falls Live-IC trotz isSameEditor wirft → fall through zu Stufe 2
    }

    // Stufe 2: Capture-IC probieren (kann noch funktionieren, auch wenn der
    // aktuelle Editor woanders ist — Android markiert IC nicht sofort als invalid).
    // Quality-Gate K-8: derselbe Refactor-Pfad ehrt InstantOutput, ergo identische UX.
    if (capturedIc != null) {
        boolean ok = commitTextToInputConnection(
                capturedIc, capturedEditor, output, InsertionSource.TRANSCRIPTION, sessionId);
        if (ok) return;
    }

    // Stufe 3: beide IC-Kanäle tot — Pipeline-Re-Run als Fallback
    Toast.makeText(this, R.string.dictate_resend_focus_lost, Toast.LENGTH_SHORT).show();
    startResumeJob(sessionId);
}
```

**`EditorIdentity.kt`-Utility (Quality-Gate N-3 + N-4):** `isSameEditor` ist eine pure Function ohne Service-State. Statt im 2100-Zeilen-Service zu leben, wandert sie in eine eigene Datei in `core/`:

```kotlin
package net.devemperor.dictate.core

import android.view.inputmethod.EditorInfo
import java.util.Objects  // Quality-Gate N-4: expliziter Import in Kotlin nicht nötig, aber im Java-Wrapper unten

/**
 * Heuristik für "ist das derselbe Editor?".
 *
 * Limitationen:
 * - EditorInfo.fieldId ist von Android dokumentiert als "the identifier of the edit field" —
 *   typischerweise der View-Hash zum Erstellungszeitpunkt. Bei View-Recreate (z.B. Activity-Restart,
 *   Configuration-Change) ändert sich fieldId, auch wenn der User logisch im "selben" Feld ist.
 * - Auf manchen Geräten/APIs ist fieldId 0 oder gar nicht gesetzt → isSame liefert dann false,
 *   was zu Stufe-2-Fallback führt (auch korrekt — Stufe 2 ist robuster).
 */
object EditorIdentity {
    fun isSame(a: EditorInfo?, b: EditorInfo?): Boolean = a != null && b != null
            && a.fieldId == b.fieldId
            && a.packageName == b.packageName
}
```

Im Service: Import-Statement plus Aufruf via `EditorIdentity.INSTANCE.isSame(...)` (Java) oder direkt `EditorIdentity.isSame(...)` (Kotlin).

**Umbau von `onResendClicked()`:**

**Quality-Gate N-2:** Doppel-Klick-Mitigation als konkretes Plan-Item (nicht nur Edge-Case-Notiz). Der `resend_btn` wird im Klick-Moment auf Main-Thread disabled und in einem `try-finally` re-enabled, damit zwei aufeinanderfolgende Klicks nicht beide den DB-Lookup starten und parallel `insertOrFallback` rufen (was zu Doppel-Insert führen würde).

```java
@Override
public void onResendClicked() {
    // CAPTURE-Phase auf Main-Thread, sofort beim Klick
    final InputConnection capturedIc = getCurrentInputConnection();
    final EditorInfo capturedEditor = getCurrentInputEditorInfo();

    // Quality-Gate N-2: Doppel-Klick-Race verhindern
    if (mainButtonsController != null) {
        mainButtonsController.setResendEnabled(false);
    }

    dbExecutor.execute(() -> {
        try {
            SessionEntity lastSession = sessionTracker.getLastKeyboardSession();
            if (lastSession == null) return;

            SessionStatus status = lastSession.getStatusEnum();
            String output = lastSession.getFinalOutputText();
            final String fSessionId = lastSession.getId();

            switch (status) {
                case COMPLETED:
                    if (output != null && !output.isEmpty()) {
                        final String finalOutput = output;
                        mainHandler.post(() -> insertOrFallback(
                                capturedIc, capturedEditor, finalOutput, fSessionId));
                    }
                    break;

                case CANCELLED:
                    if (output != null && !output.isEmpty()) {
                        final String finalOutput = output;
                        mainHandler.post(() -> insertOrFallback(
                                capturedIc, capturedEditor, finalOutput, fSessionId));
                    } else {
                        mainHandler.post(() -> startResumeJob(fSessionId));
                    }
                    break;

                case RECORDED:
                    mainHandler.post(() -> startResumeJob(fSessionId));
                    break;

                case FAILED:
                    // No-Op pro Design-Entscheidung: kein silent re-run bei API-Fehlern.
                    // User muss explizit Long-Press machen → ReprocessStaging
                    break;

                default:
                    // Defensive: künftige Status-Werte → expliziter No-Op (kein silent fail-through)
                    break;
            }
        } finally {
            // Re-Enable nach kurzem Cooldown (Capture-IC + Pipeline-Start hatten Zeit zu laufen)
            mainHandler.postDelayed(() -> {
                if (mainButtonsController != null) {
                    mainButtonsController.setResendEnabled(true);
                }
            }, 500);  // 500ms Cooldown verhindert versehentlichen Doppel-Klick
        }
    });
}
```

#### 5.5) Neue String-Resource

`app/src/main/res/values/strings.xml`:
```xml
<string name="dictate_resend_focus_lost">Eingabefeld nicht mehr verfügbar — Pipeline wird neu gestartet</string>
```

Plus Übersetzungen in `values-de/`, `values-es/`, `values-pt/`.

#### 5.6) Unit-Tests

**`OnResendClickedTest.kt`** (neue Klasse, integriert mit Mock-`InputConnection`):

- COMPLETED + Output + Live-IC identisch zu Capture → Stufe 1, `commitTextToInputConnection` wird gerufen
- COMPLETED + Output + Live-IC null + Capture-IC `commitText` returns true → Stufe 2, `handlePostCommit` wird gerufen
- COMPLETED + Output + Live-IC null + Capture-IC `commitText` returns false → Stufe 3, `startResumeJob` wird gerufen
- COMPLETED + Output + Editor wechselt + Capture-IC `commitText` returns true → Stufe 2
- CANCELLED + Output + alle drei Stufen analog
- CANCELLED + ohne Output → `startResumeJob`
- RECORDED → `startResumeJob`
- FAILED → keine Aktion, kein Toast, kein Job-Start
- COMPLETED ohne Output → keine Aktion (defensiv)

**`InsertOrFallbackTest.kt`** (Direkt-Test der Methode):

- Live-IC verfügbar + Editor identisch → Stufe 1
- Live-IC null + Capture-IC erfolgreich → Stufe 2
- Live-IC null + Capture-IC scheitert → Stufe 3 (Toast + startResumeJob)
- Live-IC verfügbar + Editor unterschiedlich → Stufe 2 (nicht Stufe 1!)
- Stufe 2 mit Capture-IC `getSelectedText()` returns "alter Text" → `replacedText` korrekt im DB-Log
- Stufe 2 mit Capture-IC `getSelectedText()` wirft Exception → kein Crash, `replacedText` bleibt null
- Stufe 2 mit Capture-IC `getSelectedText()` returns null → `replacedText` bleibt null

#### 5.7) Verification (manuell)

**Bug-Reproduzieren-vor-Fix:**
- [ ] Mit alter Build-Version: kurze Aufnahme → Pipeline läuft → kurzer Klick auf Resend → KEIN Insert (bestätigt Bug)

**Fix-Verification:**
- [ ] COMPLETED + Klick OHNE Fokus-Wechsel → Insert klappt (Stufe 1)
- [ ] COMPLETED + nach Klick auf anderes Feld klicken → Insert klappt im ursprünglichen Feld (Stufe 2)
- [ ] COMPLETED + Ziel-App schließen während Klick → Toast erscheint, Resume läuft (Stufe 3)
- [ ] CANCELLED + Output da → Insert klappt
- [ ] CANCELLED + kein Output → Resume läuft (unverändert)
- [ ] RECORDED → Resume läuft (unverändert)
- [ ] FAILED → KEINE Aktion, kein Resume, kein Toast (neue Verhaltensweise)
- [ ] FAILED + Long-Press → ReprocessStaging öffnet (unverändert, alter Pfad)
- [ ] Auto-Enter-Wirkung nach Stufe-2-Insert: Enter wird ausgeführt
- [ ] DB-Log enthält Insertion auch nach Stufe 2 (Audit bleibt vollständig, korrekt an `lastSession.getId()` gebunden)
- [ ] **Quality-Gate K-8:** `Pref.InstantOutput=false` (Slow-Output) aktiv, COMPLETED + Klick mit Editor-Wechsel (forciert Stufe 2) → Char-by-Char-Animation läuft auch hier (UX konsistent zur Stufe 1)
- [ ] **Quality-Gate N-2:** Doppel-Klick auf Resend innerhalb 500ms → genau ein Insert (Re-Enable nach Cooldown verhindert Race)

---

## Dateien

| Datei | Änderung | Umfang |
|-------|----------|--------|
| `preferences/versioned/Versioned.kt` | **Neu** — Core-Types (`Versioned<T>`, `MigrationFn`, `OnMissingMigration`, `MigrationResult<T>`) | ~25 Zeilen |
| `preferences/versioned/JsonCodec.kt` | **Neu** — `JsonCodec<T>` Interface + `StringListCodec` + `IntListCodec` | ~40 Zeilen |
| `preferences/versioned/VersionedPlugin.kt` | **Neu** — abstract class | ~20 Zeilen |
| `preferences/versioned/VersionedMigrator.kt` | **Neu** — Migration-Engine (forward-loop + Error-Strategies) | ~65 Zeilen |
| `preferences/versioned/VersionedSerializer.kt` | **Neu** — Raw-als-v1-Fallback + Envelope-Detection | ~40 Zeilen |
| `preferences/versioned/VersionedPrefs.kt` | **Neu** — SharedPreferences-Binding + Self-Heal | ~40 Zeilen |
| `preferences/versioned/VersionedPluginRegistry.kt` | **Neu** — Plugin-Registry + `migrateAll()` | ~30 Zeilen |
| `preferences/InputLanguagesPlugin.kt` | **Neu** — v1-Plugin (die einzige kuratierte Liste) | ~30 Zeilen |
| `preferences/InputLanguagesLegacyMigration.kt` | **Neu** — Einmalige StringSet→String-Migration | ~35 Zeilen |
| `preferences/LanguageLabelResolver.kt` | **Neu** — Resource-Adapter (Code↔Label, Sortierung, Allowlist, codeToIndex, recordLabelFor) — verschoben aus `core/` (Quality-Gate W-4) | ~75 Zeilen |
| `core/PipelineUiStateReader.kt` | **Neu** — Interface zur DIP-konformen Entkopplung von `LanguageController` und `KeyboardUiController` (Quality-Gate W-4) | ~10 Zeilen |
| `core/KeyboardUiController.kt` | **Modify** — `setCallback` → `addCallback`/`removeCallback` mit `CopyOnWriteArrayList` (Quality-Gate K-2); implementiert `PipelineUiStateReader` | ~15 Zeilen netto |
| `core/EditorIdentity.kt` | **Neu** — Utility für `isSame(EditorInfo, EditorInfo)` extrahiert aus Service (Quality-Gate N-3) | ~20 Zeilen |
| `core/LanguageController.kt` | **Neu** — Service-Layer-Controller + Dual-Mode-Dispatch + `persistCuratedAndPos`-Helper + `dispose()` für View-Recreate-Cleanup | ~130 Zeilen |
| `core/DictateInputMethodService.java` | **Modify** — LanguageController-Wiring (mit `dispose()`), `refreshLanguageChip`, `showLanguagePicker` (mit Gruppierung), `onLanguageCycled`, init order in onCreateInputView, **Phase 5: `onResendClicked` Capture-Logik + Doppel-Klick-Mitigation + `insertOrFallback`**; Service-Sprach-Felder entfernt (Quality-Gate W-7); `commitTextToInputConnection` mit explizitem IC-Parameter refactored (Quality-Gate K-8/W-3) | ~180 Zeilen netto |
| `core/DictateApplication.java` | **Modify** — Init-Reihenfolge (LanguageLabelResolver → LegacyMigration → migrateAll), `getOrCreateLanguageController()` für Settings-Zugriff (Quality-Gate W-1, K-6) | ~25 Zeilen |
| `core/DictateSettingsActivity.java` | **Modify** — `EXTRA_SCROLL_TO`-Konstante + Intent-Read + Bundle-Forward (Quality-Gate K-7) | ~10 Zeilen netto |
| `settings/PreferencesFragment.java` | **Modify** — MultiSelectListPreference-Wrap via Controller, `scrollToPreference`-Hook (Quality-Gate K-5/K-6/K-7) | ~30 Zeilen netto |
| `rewording/PromptsKeyboardAdapter.java` | **Modify** — neue Methode `setLanguageChipEnabled(boolean)` separat von `setLanguageChipVisible` (Quality-Gate W-6) | ~10 Zeilen netto |
| `res/xml/fragment_preferences.xml` | **Modify** — `app:persistent="false"` auf input_languages-Preference (Quality-Gate K-5) | ~1 Zeile |
| `res/values/strings.xml` + Lokalisierungen | **Modify** — 3 neue Strings (`dictate_language_manage`, `dictate_language_other_separator`, `dictate_resend_focus_lost`) | ~12 Zeilen × 4 Locales |
| `app/build.gradle` | **Modify** — `testImplementation 'org.json:json:20240303'` (Quality-Gate W-2) | ~1 Zeile |
| `test/preferences/versioned/VersionedMigratorTest.kt` | **Neu** — Migration-Engine-Tests (JUnit 4, Fakes) | ~180 Zeilen |
| `test/preferences/versioned/VersionedSerializerTest.kt` | **Neu** — Serializer-Tests (inkl. is-Number-Edge-Cases W-10) | ~100 Zeilen |
| `test/preferences/versioned/StringListCodecTest.kt` | **Neu** — Codec-Tests | ~50 Zeilen |
| `test/preferences/versioned/VersionedPluginRegistryTest.kt` | **Neu** — Registry + reset() + migrateAll-Try-Catch (Quality-Gate W-9) | ~70 Zeilen |
| `test/core/LanguageControllerTest.kt` | **Neu** — Service-Layer-Logik-Tests mit `FakePipelineUiStateReader` + `FakeSharedPreferences` | ~150 Zeilen |
| `test/preferences/LanguageLabelResolverTest.kt` | **Neu** — Utility-Tests via `initializeForTest`-Seam | ~50 Zeilen |
| `test/preferences/InputLanguagesPluginTest.kt` | **Neu** — Plugin-Tests inkl. Sortier-Vertrag | ~70 Zeilen |
| `test/preferences/InputLanguagesLegacyMigrationTest.kt` | **Neu** — Legacy-Migration-Tests inkl. Pos-Erhaltung | ~80 Zeilen |
| `test/core/OnResendClickedTest.kt` | **Neu** — Status-Matrix mit `FakeInputConnection` (kein Mockito) | ~180 Zeilen |
| `test/core/InsertOrFallbackTest.kt` | **Neu** — Direkt-Tests für `insertOrFallback` inkl. Slow-Output-Pfad (K-8) | ~150 Zeilen |
| `test/core/EditorIdentityTest.kt` | **Neu** — pure-Function-Tests für `isSame` | ~30 Zeilen |
| `test/core/MultiCallbackForwardingTest.kt` | **Neu** — Verifiziert `addCallback`/`removeCallback`-Forwarding-Kette für mehrere Konsumenten (Quality-Gate K-2 + ersetzt CompositePipelineCallbackTest) | ~70 Zeilen |

---

## Edge Cases

### Rollback-Risiko nach App-Downgrade
Nach der Migration ist der Key `input_languages` ein String (Envelope), nicht mehr ein StringSet. Wenn der User auf die vorherige App-Version zurück-downgraded, würde der StringSet-Accessor `ClassCastException` werfen. **Dokumentation als bekannte Limitation**, kein aktives Gegensteuern.

### Empty-List-Fallback
`MultiSelectListPreference` erlaubt standardmäßig leere Selection. Das bestehende Toast-Warning (`dictate_input_languages_empty`) wird beibehalten. Zusätzlich fängt `sanitize()` im Plugin den Fall ab und setzt auf Default zurück — doppelter Schutz.

### Multi-Callback auf KeyboardUiController (Quality-Gate K-2 — gelöst)

Die ursprüngliche Composite-Wrapper-Lösung wurde durch ein Refactor des `KeyboardUiController` von Single-Slot-`setCallback` auf `addCallback`/`removeCallback` mit `CopyOnWriteArrayList` ersetzt. Siehe Design-Prinzip 7.

```java
// Im DictateInputMethodService.onCreateInputView, nach Erstellung von uiController + languageController:
PipelineUiCallback servicePipelineCallback = new PipelineUiCallback() {
    @Override
    public void onPipelineUiStateChanged(PipelineUiState old, PipelineUiState newState) {
        // Service-Logik: Chip-Enabled-State, QWERTZ-Reset, etc.
        boolean pipelineRunning = newState instanceof PipelineUiState.Running
                              || newState instanceof PipelineUiState.Preparing;
        promptsAdapter.setLanguageChipEnabled(!pipelineRunning);
        // ... weitere bestehende Service-Logik ...
    }
    @Override
    public void onPipelineTimerTick(PipelineUiState.Running state, long elapsedMs) {
        // ... bestehende Timer-Logik ...
    }
};

uiController.addCallback(servicePipelineCallback);
// languageController hat sich im Konstruktor selbst registriert (siehe Design-Prinzip 7)
```

**Cleanup beim View-Recreate:** Vor dem Neu-Erzeugen des `LanguageController` muss `languageController.dispose()` (das intern `removeCallback` ruft) und `uiController.removeCallback(servicePipelineCallback)` aufgerufen werden — sonst leakt die `CopyOnWriteArrayList` über mehrere View-Lifecycles.

**Test-Coverage:** `MultiCallbackForwardingTest` verifiziert, dass `addCallback` mit zwei Konsumenten den State-Wechsel an beide forwarded (ReprocessStaging-Enter → Chip + Record-Button-Label gleichzeitig zur Override-Sprache).

### Chip während Recording (Active/Paused)
Chip ist **aktiv und klickbar**. User kann Sprache wechseln, während eine Aufnahme läuft — die Änderung greift erst bei der nächsten Transkription, die gerade laufende bleibt auf der ursprünglichen Sprache. **Dokumentation des Verhaltens im Verification-Checklist.**

### Threading: VersionedPrefs aus Worker-Thread
`VersionedPrefs.load/save` ist thread-sicher (SharedPreferences nativ). **ABER:** Der `LanguageController` ist Main-Thread-only. Aufrufe wie `controller.getCuratedLanguages()` dürfen nur vom Main-Thread kommen. Worker-Code, der kuratierte Liste braucht, muss `VersionedPrefs.load(prefs, InputLanguagesPlugin)` direkt nutzen.

### ReprocessStaging-Override mit nicht-kuratiertem Code
Dank der gruppierten Liste kann der User **auch in ReprocessStaging** eine beliebige Sprache aus dem unteren Block wählen. Während ReprocessStaging wirkt die Auswahl aber nur transient — die kuratierte Liste wird **nicht** automatisch erweitert (Auto-Curation läuft nur im Permanent-Pfad). Das ist absichtlich: "hier einmalig mit Französisch reprocessen" soll die Normalmodus-Präferenzen nicht beeinflussen.

### Self-Heal-Write-Amplification
`VersionedPrefs.load()` schreibt nach Sanitization zurück, wenn der serialisierte Wert abweicht. Bei häufigem Laden könnte das zu unnötigen Writes führen. **Mitigation:** Self-Heal nur bei tatsächlichen Änderungen (`serialize(value) != json`) — ist bereits so implementiert.

### Gruppierung — Degenerate Cases
- **Alle 62 Sprachen kuratiert:** Der untere Block ist leer. Der Trenner (Group-Divider oder Fallback-Label) darf nicht gerendert werden. Implementierung: `if (!othersOrdered.isEmpty()) { ...Trenner... }`.
- **Keine kuratierten (nach Fresh-Install nur Default `["detect","en"]`):** Der obere Block hat zwei Einträge, der untere enthält die restlichen 60. Das ist OK — der Trenner ist sinnvoll sichtbar.
- **Genau eine kuratierte Sprache:** Völlig in Ordnung, oberer Block = 1 Item, unterer = 61, Trenner dazwischen.
- **Leere kuratierte Liste (sollte nicht vorkommen dank Sanitize):** Plugin-`sanitize()` fängt das ab → fällt auf `defaultValue` zurück.

### Auto-Curation: Ist sie immer gewünscht?
Wenn der User im unteren Block eine Sprache antippt, wandert sie beim nächsten Öffnen nach oben. Bei einem einmaligen Ausprobieren kann das stören. Entscheidungsoptionen:
- **(a) Immer auto-kuratieren** (Ground-Truth-Plan): Simple UX, "was ich wähle, ist wichtig für mich"
- **(b) Long-Press = kuratieren, Single-Tap = nur aktivieren ohne Hinzufügen**: Zwei Gesten, mehr Komplexität
- **(c) Never auto-kuratieren, Kuration nur via Settings**: Im PopupMenu bleibt die Liste unverändert

**Entscheidung: (a) — Auto-Curation**, weil es das simpelste Mental-Modell hat. Der User kann jederzeit via "⚙ Sprachen verwalten…" eine Sprache wieder entfernen.

### PopupMenu-Sortierung beim Sprachwechsel
Nachdem eine Sprache gewählt wurde, ist sie jetzt die aktuelle. Beim nächsten Öffnen des PopupMenus wird sie alphabetisch nach Label einsortiert — nicht "an oberster Stelle". Das ist konsistent mit der **stabilen Sortierung** und vermeidet verwirrende Sprünge in der Liste. Wer will, kann im Settings-UI eine manuelle Reihenfolge definieren — aber das ist Out-of-Scope für dieses Plan (siehe Follow-up "Drag-to-Reorder").

### Phase 5: Doppel-Klick auf Resend (Race-Condition) — Quality-Gate N-2 GELÖST
Wenn der User innerhalb von Millisekunden zweimal auf den Resend-Button klickt, werden zwei `dbExecutor.execute()`-Tasks gestartet. **Lösung im Plan-Item 5.4 implementiert:** `resendButton.setEnabled(false)` im Klick-Moment + `mainHandler.postDelayed(...re-enable..., 500ms)` im finally-Block. Das ist UX-freundlicher als das ursprünglich vorgeschlagene `AtomicBoolean`-Flag, weil der User visuell sieht, dass der Klick angekommen ist.

### Phase 5: Captured EditorInfo kann null sein
Wenn beim Klick kein Feld fokussiert ist (z.B. App im Hintergrund), liefert `getCurrentInputEditorInfo()` `null`. Der `isSameEditor`-Check liefert dann `false` → Stufe 2 wird probiert → bei null-Capture-IC fällt es direkt in Stufe 3. Das ist korrektes Verhalten (Toast + Resume), aber sollte explizit getestet werden.

### Phase 5: FAILED-Status — User-Erwartung an "Resend"
Vor dem Fix triggerte FAILED automatisch einen Resume. Manche User haben diesen "Auto-Retry" möglicherweise gewohnt. Mit der Verhaltensänderung (FAILED → No-Op) müssen sie explizit Long-Press → ReprocessStaging machen. **Mitigation:** Im InfoBar oder als Toast einmalig anzeigen, dass FAILED jetzt manueller Resume erfordert (nur einmal pro App-Lifetime, persistiert via DictatePrefs-Flag). **Out-of-Scope für diesen Plan**, aber als Follow-up dokumentiert.

### Phase 5: `EditorInfo.fieldId` ist eine Heuristik, keine Garantie
`isSameEditor()` vergleicht `fieldId` und `packageName`. `fieldId` ist von Android dokumentiert als "the identifier of the edit field" — typischerweise der View-Hash zum Erstellungszeitpunkt. **Limitierungen:**

- Wenn die View neu inflated wird (z.B. Activity-Restart, Configuration-Change), ändert sich `fieldId` — auch wenn der User logisch im "selben" Feld ist.
- Auf manchen Geräten/APIs ist `fieldId` 0 oder gar nicht gesetzt → `isSameEditor` liefert dann immer `false`, der Code fällt direkt in Stufe 2.

**Konsequenz:** `isSameEditor` ist ein **Best-Case-Optimierer** für Stufe 1. Falls er `false` liefert, ist das nicht schlimm — Stufe 2 (Capture-IC) ist der robustere Pfad und greift dann. Im Worst-Case-Pfad geht's via Stufe 3 (Pipeline-Re-Run).

### Phase 5: Pos-Erhaltung im Settings-UI hat einen subtilen Edge-Case
Wenn der User in den Settings die aktuelle Sprache ENT-haket (Pos zeigte auf "Französisch", User entfernt "Französisch" aus der Liste), springt Pos auf 0 — die erste verbleibende Sprache wird aktiv. Das ist konservativ und kommunikabel, aber alternativ wäre es möglich, **vor** der Pos-Resetting den User per Toast zu fragen oder einen "ähnlichen Fallback" zu wählen (z.B. dieselbe Sprachfamilie). **Out-of-Scope** für diesen Plan — Pos = 0 ist die sichere Default-Wahl.

### Pos-Drift bei Sortierungs-Änderungen (zukunftssicher)
Wenn in einer zukünftigen Version die Display-Labels sich ändern (z.B. neue Lokalisierung mit anderen Übersetzungen), kann sich die alphabetische Sortierung verschieben — und damit `Pref.InputLanguagePos` zeigt auf eine andere Sprache. Dieses Risiko ist **inhärent zur "Pos in label-sortierte Liste"-Architektur** und kann nicht ohne Schema-Änderung (z.B. Pos durch direkte `CurrentLanguageCode: Pref<String>` ersetzen) behoben werden. **Akzeptiert für diesen Plan**, dokumentiert als Follow-up.

---

## Offene Punkte (nach Abstimmung bereits entschieden)

- **Scope Versioned-Envelope:** Volle Portierung (~5.5h zusätzlich) — ✅ Bestätigt durch User-Wunsch
- **JSON-Library:** `org.json` (built-in, keine neue Dependency) — ✅ Default
- **`onMissingMigration` für Sprach-Prefs:** `RESET_TO_DEFAULT` (weich) — ✅ Default
- **Migration-Zeitpunkt:** Einmalig beim Application/Service-Start — ✅ Default
- **Settings-UI-Strategie:** MultiSelectListPreference wrappen, keine neue Activity — ✅ Default
- **Kurations-Reihenfolge:** Alphabetisch sortiert (MVP), Drag-to-Reorder als Follow-up — ✅ Default

## Aufwand (aktualisiert nach Quality-Gate-Review 2026-04-27)

| Phase | Aufwand | Quality-Gate-Zusatz |
|-------|---------|---------------------|
| Phase 0 — Versioned-Envelope-Foundation | ~6h (inkl. ~2h Tests) | +0.5h: Backup-Strategie, is-Number-Edge-Cases, RegistryTest, Try/Catch-Loop |
| Phase 1 — Resolver + Plugin + Controller + Legacy-Helper | ~6h (inkl. ~2.5h Tests) | +1h: PipelineUiStateReader-Interface, persistCuratedAndPos-Helper, Fakes (FakeSharedPreferences/FakePipelineUiStateReader), initializeForTest-Seam, Init-Defense |
| Phase 2 — Chip-UI + Gruppierte PopupMenu | ~5h | +1h: Sub-Sektion 2.2a (EXTRA_SCROLL_TO Konstante in Activity+Fragment), Sub-Sektion 2.5b (Service-Field-Cleanup), getrennte setLanguageChipEnabled-Methode, codeToIndex-Map, recordLabelFor |
| Phase 3 — Settings-UI-Anpassung | ~2h | +0.5h: setPersistent(false), Aufruf via Controller statt direkt |
| Phase 4 — Tests & Validation | ~3h | +1h: 4 zusätzliche Test-Klassen (RegistryTest, EditorIdentityTest, MultiCallbackForwardingTest), Manual-Verification erweitert |
| Phase 5 — Bugfix Resend-Button | ~5h (inkl. ~2h Tests + Manual) | +1.5h: Vorgelagerter `commitTextToInputConnection`-Refactor mit IC-Parameter, EditorIdentity-Utility-Extract, Doppel-Klick-Mitigation |
| **Architektur-Refactor (cross-phase)** | ~1.5h | KeyboardUiController von Single-Slot auf addCallback umstellen (Quality-Gate K-2) |
| **DictateApplication-Init-Erweiterung** | ~0.5h | Init-Reihenfolge in bestehender onCreate-Methode festnageln, getOrCreateLanguageController-Hook |
| **Total** | **~29.5h** | **+8h Quality-Gate-Aufwand** |

**Hinweis zur Reihenfolge:** Phase 5 ist scope-isoliert (kein Bezug zu Sprach-Feature) — aber durch den vorgelagerten `commitTextToInputConnection`-Refactor (5.0) hat Phase 5 jetzt minimale Voraussetzungen, die unabhängig von Phase 0-4 sind. Empfehlung **bleibt: erst Phase 5**, weil sie isoliert klein ist und sofortigen User-Wert liefert. Danach Phase 0-4 als zusammenhängende Sprach-Iteration (mit Architektur-Refactor in Phase 0 oder als Vorlauf-Schritt). Alternative: Alles in einer Branch — auch OK, weil keine Code-Konflikte zwischen Phase 5 und Phase 0-4.

**Reihenfolge der Korrekturen innerhalb der Sprach-Phasen (Quality-Gate-Empfehlung):**
1. K-4 (SharedPreferences-Accessor) — 1 Zeile, Datenverlust-Risiko
2. K-1 (Test-Setup) — Voraussetzung für alle Tests
3. W-1 (Application-Init) — Init-Reihenfolge festnageln
4. K-2 (KeyboardUiController.addCallback) — Architektur-Refactor
5. W-4 (PipelineUiStateReader-Interface) — löst Test-Setup für LanguageController
6. K-3 (Pos-Resync via persistCuratedAndPos) — gemeinsamer Helper
7. K-5/K-6 (Settings via Controller, setPersistent(false))
8. K-7 (EXTRA_SCROLL_TO) — Mini-Sub-Sektion
9. K-8/W-3 (commitTextToInputConnection-Refactor) — Phase 5 Vorlauf
