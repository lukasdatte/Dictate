# Phase B — S-7 Audio-File-Management: `cacheDir/audio.m4a`-Fixpfad → AudioFileFactory + `cacheDir/audio/`-Subdir + Cleanup-Routinen Migrations-Pfad-Review

**Erstellt:** 2026-05-13
**Reviewer:** Phase-B-Agent S-7 (Subsystem #5 von 9)
**Plan-Version vor Edits:** Stand nach S-4-Apply-Pass (Commit `c895695`, S-4-Report `phase-b-s4-orchestrator.md`)

---

## Summary

Der Migrationspfad für S-7 ist **konzeptionell ausgereift** — `interface AudioFileFactory` mit `allocate()` + `cleanupOrphans()` ist sauber, das Sub-Dir-Layout (`cacheDir/audio/`) isoliert Audio-Cleanup von Settings/Export-Caches, der 60s-Cutoff (KG-AFF-4) schließt das Race-Fenster gegen concurrent allocate, und die Five-Knowledge-Gaps (KG-AFF-1..5) sind alle als RESOLVED markiert. **Aber:** drei strukturelle Compile-Blocker-Drifts gegen die in S-3/S-4 nachgezogenen Action-Hierarchie + Sealed-Leaves-Indexing waren unentdeckt; eine vierte (Idempotenz-Lücke der Legacy-Migration) hätte Daten-Verlust bei Re-Run produziert.

**Drei Critical-Bugs:**

1. **`ButtonSlot.actionResolver`-Signatur-Drift gegen `resolveRecordAction`**: Der Spec-2-§3.2-Typ `actionResolver: (DictateUiState) -> Action?` ist 1-arg; Spec 2 §8.5 `resolveRecordAction` ist post-PENDING-1-Resolution 2-arg `(state, services)`. `::resolveRecordAction`-Methodenreferenz im LayoutCatalog scheitert am Typ-Mismatch beim ersten `./gradlew assembleDebug`. Same für Spec 3.

2. **Spec 3 `OVERLAY_RECORD`-actionResolver fehlt `audioFile`-Argument**: Z. 69 ruft `Action.RecordingAction.StartRecording(target = …)` ohne `audioFile` — `data class StartRecording(target, audioFile)` verlangt beide Felder (R.2-Pure-Reducer-Vertrag). Compile-Error im Widget-Pfad, der erst bei Block-6-Build auffallen würde.

3. **`LegacyAudioFileMigration` NICHT idempotent gegen Re-Marking**: DAO-Query setzt unkonditional `status = FAILED` auf alle Rows mit `audio_file_path = legacyPath` — überschreibt bei einem zweiten Migrations-Lauf (Pref-Wipe + Re-Upgrade) bestehende `last_error_message`-Information; im worst case sogar COMPLETED-Sessions auf FAILED gedowngraded. Daten-Verlust-Risiko.

**Sechs Important-Findings:**

4. **String-Resource `dictate_storage_full` fehlt** in `strings.xml` (verifiziert per Code-Read); Plan-§4.11.10 F1 erwähnt nur "neue String-Resource nötig", aber kein konkreter EN/DE-Wert; nicht in §11.2.2-Sub-Schritten.

5. **Recovery-Coupling-Tabelle (§4.11.6) deckt nur RECORDED**, nicht die v4-Stati RECORDING/TRANSCRIBING (post-S-2 DB-Schema-Migration). Drift gegen §11.6.2 + Block-3-Acceptance R.16a/b/c.

6. **`getByStatus("RECORDED")` vs. `getSessionsByStatuses(List<String>)`-DAO-Drift** in §11.6.2 Recovery-Snippet — S-2-F-2 hat andere Recovery-Lücken adressiert, aber diese Signatur-Drift nicht.

7. **`RecordingModule.reduceFailure`-Arm für `AllocateMediaRecorder`-Failure fehlt** (S-3-F-1-Follow-Up, S-4-Übersehen): ohne expliziten Arm würde State im `Preparing` hängen bei Hardware-Failure mid-prepare (z.B. extern weggeräumter Cache-Pfad, MIC-Permission-Revoke).

8. **`cleanupOrphanedTerminalAudio` Concurrency-Vertrag nicht explizit**: Trigger-Slot-Präzision, Dispatcher-Disziplin, Concurrent-Allocate-Verhalten, Double-Delete-Race mit `RecordingRepository.deleteBySessionId`.

9. **`LegacyAudioFileMigration` läuft synchron auf Main-Thread** — Pref-Read + File-Delete + DAO-UPDATE in Service.onCreate vor der FGS-5s-Frist. Worst-case ~200 ms bei >10k Sessions. Akzeptiert, aber Phase-2-Pfad sollte dokumentiert sein.

**Zwei Minor-Findings:**

10. **`PreferencesFragment.clearCacheRecursively` Race mit aktiver Recording**: `entry.delete()` auf offener MediaRecorder-FD verursacht `unlink()` → beim Recording-Stop ist die Audio-Datei verschwunden (Ghost-Session, FAILED). Defensive Vorbedingung im Click-Listener fehlte.

11. **Block 4 (AudioFileFactory) hatte keine explizite Sub-Schritt-Liste** in §11.2.2 — Implementer hätte aus §4.11 KG-Markern rekonstruieren müssen.

**Befund:** **11 Findings (3 Critical, 6 Important, 2 Minor) — ~16 Plan-Edits in 4 Dateien (Spec 1: 11, Spec 2: 3, Spec 3: 3, Hauptplan: 1).**

**Hauptlücke:** Die Resolver-Signatur-Inkonsistenz (Findings F-1 + F-2) hätte den Block-4-Build sofort blockiert; ohne S-7-Audit wäre der Implementer in eine konfuse Compile-Error-Schleife geraten ("`(state) -> Action?` vs. `(state, services) -> Action?` — wo lebt `services`?").

---

## Findings + Applied Fixes

### F-1 `ButtonSlot.actionResolver`-Signatur-Drift gegen `resolveRecordAction`

- **Severity:** Critical
- **Prüf-Achse:** 1 (Pre-Dispatch-Allocation), 7 (Bugs durch Migration)
- **Was:** Spec 2 §3.2 (Z. 91 vor Fix) definiert `val actionResolver: (DictateUiState) -> Action?` — 1-arg-Funktionstyp. Aber Spec 2 §8.5 `resolveRecordAction` ist mit der PENDING-1-Resolution post-2026-05-11 auf 2-arg umgestellt: `fun resolveRecordAction(state: DictateUiState, services: ModuleServices): Action?`. Die `LayoutCatalog`-Slot-Definitionen in §8.1/§8.2 (Z. 1077, 1128) referenzieren `actionResolver = ::resolveRecordAction` — eine Methodenreferenz, die nach Kotlin-Typ-Inferenz `(DictateUiState, ModuleServices) -> Action?` ergibt. Der Typ-Mismatch gegen `(DictateUiState) -> Action?` schlägt beim ersten `./gradlew assembleDebug` rot fehl. Plus: `wireStaticHandlers` (Spec 2 §6 Z. 603) ruft `slot.actionResolver(s)` mit nur einem Argument — würde nie kompilieren, wenn der Typ tatsächlich 2-arg wäre.
- **Konsequenz:** Block-4-Build-Blocker. Compile-Error mit verwirrender Fehlermeldung ("Type mismatch: inferred type is `KFunction2<DictateUiState, ModuleServices, Action?>` but `(DictateUiState) -> Action?` was expected"). Implementer-Reflex: entweder `services` aus `resolveRecordAction` entfernen (zerstört die R.2-Pre-Dispatch-Allocation) ODER alle anderen Slots auf 2-arg umstellen ohne klare Heimat für `services`. 30-60 Min Debug-Zeit, dann unklare Plan-Direktive.
- **Fix angewandt:**
  - **Spec 2 §3.2:** `ButtonSlot.actionResolver`-Typ auf `(DictateUiState, ModuleServices) -> Action?` erweitert mit Phase-B-S-7-Hinweis: alle anderen Resolver ignorieren das 2. Argument (`{ Action.X }` → `{ _, _ -> Action.X }`, `{ state -> ... }` → `{ state, _ -> ... }`); Pure-Function-Garantie bleibt (Resolver dürfen NUR `services.audioFileFactory` lesen).
  - **Spec 2 §6:** `ImeViewBackend`-Konstruktor um `services: ModuleServices`-Field erweitert; `wireStaticHandlers` ruft `slot.actionResolver(s, services)`.
  - **Spec 2 §11.6:** Click-Listener-Empfehlung Z. 1937 (jetzt 1939) auf 2-arg-Variante umgestellt.
  - **Spec 3 §4.2:** `OverlayBackend`-Konstruktor analog um `services`-Field erweitert; `wireStaticOverlayHandlers` ruft `slot.actionResolver(s, services)`.
  - **Spec 3 §11.5 Beweis-Snippet** auf `slot.actionResolver(state, services)` umgestellt.
  - **Spec 1 §11.2.2 Block 4 (NEU):** explizite Sub-Schritte 4-5 dokumentieren die Migration der 1-arg-Lambdas im LayoutCatalog (`{ Action.X }` → `{ _, _ -> Action.X }`).

### F-2 Spec 3 `OVERLAY_RECORD`-actionResolver fehlt `audioFile`-Argument

- **Severity:** Critical
- **Prüf-Achse:** 1 (Pre-Dispatch-Allocation, R.2), 7 (Bugs durch Migration)
- **Was:** Spec 3 §3.1 Z. 69 (vor Fix): `actionResolver = { Action.RecordingAction.StartRecording(target = InsertionTarget.MainInputConnection) }`. Aber `Action.RecordingAction.StartRecording` ist nach R.2 (Issue 1.1.7) eine `data class` mit ZWEI Feldern: `(target: InsertionTarget, audioFile: java.io.File)` (Spec 2 §3.3 Z. 168). Compile-Error: "No value passed for parameter 'audioFile'". Plus: keine `services.audioFileFactory.allocate()`-Aufruf im Widget-Pfad — Hardware-Allocation würde dem Reducer überlassen werden (R.2-Verstoß).
- **Konsequenz:** Block-6-Build-Blocker (wenn Spec 3 LayoutCatalog gebaut wird). Identische Bug-Klasse wie F-1, aber konzeptionell unterschiedlich: Spec 2 hat den Resolver-Helper `resolveRecordAction` (zentral), Spec 3 hatte ihn als Inline-Lambda — wo die Pre-Dispatch-Allocation übersehen wurde. Außerdem: Implementer könnte aus dem Spec-2-Pattern reflex-handeln und einen flachen 1-arg-Resolver schreiben, ohne `services.audioFileFactory.allocate()` zu rufen — Reducer mit `IOException` aus dem File-System-Layer wäre die Folge.
- **Fix angewandt:**
  - **Spec 3 §3.1:** OVERLAY_RECORD-Slot ruft jetzt `::resolveOverlayRecordAction` (Methodenreferenz statt Inline-Lambda).
  - **Spec 3 §3.1 (NEU nach LayoutMode-Definition):** Code-Block `resolveOverlayRecordAction(state, services): Action?` mit voller IOException-Handling-Logik (analog zu Spec 2 §8.5). HOVER ist defensive `null`, weil Visibility-Predicate ohnehin `ViewMode.WIDGET` verlangt; Race-Schutz für ViewMode-Toggle-Click.
  - **Spec 3 §3.1 (NEU):** Migrations-Hinweis "andere 1-arg-Resolver bekommen `_`-Placeholder im 2. Argument" für Block-6-Implementer.

### F-3 `LegacyAudioFileMigration` ist NICHT idempotent gegen Re-Marking

- **Severity:** Critical
- **Prüf-Achse:** 3 (Legacy-Migration), 7 (Daten-Verlust durch Migration)
- **Was:** Spec 1 §4.11.6.2 KG-AFF-2 RESOLVED-Block enthält den DAO-Query `markLegacyAudioSessionsFailed` (Z. 1885-1890 vor Fix):
  ```sql
  UPDATE sessions
  SET status = :failedStatus, last_error_type = 'UNKNOWN', last_error_message = :reason
  WHERE audio_file_path = :legacyPath
  ```
  Ohne `WHERE status NOT IN (...)`-Filter wird **jeder** Session-Row mit dem Legacy-Path geupdatet — auch bereits FAILED/CANCELLED-Rows. Die `last_error_message`-Spalte wird mit dem fixen `"audio_file_path_legacy_purged"` überschrieben — bestehende Fehler-Information (z.B. `"transcription_timeout"`, `"openai_rate_limit"`, `"network_error"`) geht verloren. Plus: falls eine COMPLETED-Session zufällig den Legacy-Path hat (extrem unwahrscheinlich, aber denkbar bei adb-Manipulation), würde sie auf FAILED gedowngraded — Daten-Verlust.
- **Konsequenz:** Bei zweitem Migrations-Lauf (Pref-Wipe via App-Daten-Wipe + Re-Upgrade) gehen Fehler-Diagnostik-Daten verloren. User in der History sieht plötzlich alle alten Failures mit dem generischen `"audio_file_path_legacy_purged"`-Marker statt der originalen Ursache — Bug-Reports werden schwer zu kategorisieren ("warum hat ALLES denselben Fehler?"). Außerdem: silent COMPLETED→FAILED-Downgrade ist ein Daten-Bug, der niemand bemerkt, bis der User in der History eine Session als FAILED sieht, die er gestern erfolgreich eingefügt hatte.
- **Fix angewandt:**
  - **Spec 1 §4.11.6.2:** DAO-Query um `AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')`-Klausel erweitert. Nur Sessions in `RECORDING`/`RECORDED`/`TRANSCRIBING` (die "incomplete pre-refactor"-Stati) werden gemarkiert.
  - **Spec 1 §4.11.6.2 (NEU vor DAO-Snippet):** "Idempotenz-Klausel auf DAO-Ebene (Phase-B S-7)"-Erklär-Block mit Begründung (Daten-Erhalt + COMPLETED-Schutz).
  - **Spec 1 §4.11.6.2 (NEU nach Aufruf-Site-Snippet):** "Idempotenz beim zweiten Lauf"-Block dokumentiert das Verhalten bei Pref-Wipe-Re-Upgrade-Zyklen (zweiter Lauf ist no-op gegen schon migrierte Rows).
  - **Spec 1 §11.2.2 Block 4 Sub-Schritt 10:** Phase-B-S-7-Reminder zur DAO-Query-Filter-Klausel.

### F-4 String-Resource `dictate_storage_full` fehlt + nicht in Block-4-Sub-Schritten

- **Severity:** Important
- **Prüf-Achse:** 4 (Resolver-Integration), 1 (Migrations-Vollständigkeit)
- **Was:** Spec 2 §8.5 `resolveRecordAction` ruft `services.toastSink.show(ctx.getString(R.string.dictate_storage_full))`. Plan-§4.11.10 F1 erwähnt "**Neue String-Resource nötig** in `res/values/strings.xml` (`dictate_storage_full`) + DE-Übersetzung" als Hinweis. **Aber:** kein konkreter EN/DE-Wert; kein expliziter Sub-Schritt in §11.2.2 Block-4. Verifiziert per `grep -rn "dictate_storage_full" app/src/main/res/` — Resource existiert heute NICHT (heutige `strings.xml` hat nur `dictate_cache_cleared/dictate_cache_clear_title/dictate_cache_clear_message`).
- **Konsequenz:** Build-Error beim ersten `./gradlew assembleDebug` (`Cannot resolve symbol 'dictate_storage_full'`). Implementer-Reflex: ad-hoc-String-Resource hinzufügen ohne DE-Übersetzung — die DE-Version (`values-de/strings.xml`) wird vergessen, User-Erfahrung leidet auf deutschen Geräten. Außerdem: ohne Plan-Anker im Sub-Schritt-Block sieht der Implementer die F1-Failure-Mode-Tabelle erst nach dem Compile-Fehler.
- **Fix angewandt:**
  - **Spec 1 §11.2.2 Block 4 Sub-Schritt 7 (NEU):** "String-Resource `dictate_storage_full` in EN + DE ergänzen" mit konkretem EN-Wert (`"Cache full — recording cannot start."`) und DE-Übersetzung (`"Cache voll — Aufnahme kann nicht starten."`). Als Pflicht-Aufgabe markiert.
  - Verweis aus §4.11.10 F1 auf die Sub-Schritt-Heimat ist implizit über §11.2.2 Block-4-Verweis.

### F-5 Recovery-Coupling-Tabelle (§4.11.6) deckt nur RECORDED, nicht v4-Stati RECORDING/TRANSCRIBING

- **Severity:** Important
- **Prüf-Achse:** 5 (Recovery-Coupling), 6 (Cross-Spec-Konsistenz mit S-2)
- **Was:** Spec 1 §4.11.6 Recovery-Coupling-Tabelle listet 3 Szenarien — alle für RECORDED-Status. Aber: S-2-DB-Schema-Migration (Block 3) bringt zwei neue Stati (RECORDING, TRANSCRIBING), die ebenfalls `audio_file_path != null` haben können. §11.6.2 + Block-3-Acceptance R.16a/b/c (S-2-eingearbeitet) dokumentieren die korrekten Recovery-Pfade (RECORDING→FAILED+cleanup, TRANSCRIBING→RECORDED-Downgrade-oder-FAILED). §4.11.6 referenziert nur den RECORDED-Pfad, nicht die anderen.
- **Konsequenz:** Implementer-Reflex: bei einem Sonderfall (z.B. "warum landet diese TRANSCRIBING-Session als Ghost?") sucht der Implementer den Recovery-Pfad in §4.11.6 — findet ihn nicht — vermutet einen Bug oder eine fehlende Acceptance-Klausel. Außerdem: bei einem Refactor der Recovery-Logik in Block 4 könnte ein Implementer die TRANSCRIBING-Behandlung aus dem Blick verlieren, weil sie nur in §11.6.2/§6.3 dokumentiert ist und nicht in der §4.11.6-Tabelle, wo der Block-4-AudioFileFactory-Implementer typischerweise sucht.
- **Fix angewandt:**
  - **Spec 1 §4.11.6:** Recovery-Coupling-Tabelle um die Spalte "Status (v4)" und 3 neue Zeilen erweitert (RECORDING-Crash, TRANSCRIBING-File-ok, TRANSCRIBING-File-weg), mit Querverweis auf R.16a/b/c. Pre-S-7-Tabelle hatte 3 Zeilen — post-S-7 hat 6 Zeilen.

### F-6 `getByStatus("RECORDED")` vs. `getSessionsByStatuses(List<String>)`-DAO-Drift (§11.6.2)

- **Severity:** Important
- **Prüf-Achse:** 4 (DAO-Konsistenz), 6 (Cross-Spec-Konsistenz mit S-2)
- **Was:** Spec 1 §11.6.2 Recovery-Snippet Z. 4781 + 4792 (vor Fix): `db.sessionDao().getByStatus("RECORDED")` — Singular-Variante mit `String`-Parameter. Aber: §6.3 listet die DAO-Definition Z. 3298 als `fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity>` (Plural, `List<String>`-Parameter). `getByStatus` existiert nicht. S-2-Report F-2 hat andere Recovery-Lücken adressiert (RECORDING-File-Path), aber diese Signatur-Drift nicht.
- **Konsequenz:** Compile-Fehler beim ersten Block-3-DAO-Test (`Unresolved reference: getByStatus`). Implementer-Reflex: entweder `getByStatus` ad-hoc anlegen (Drift gegen die Plan-Konvention `getSessionsByStatuses`) oder den Aufruf manuell umstellen — Plan-Drift gegen die SoT-DAO-Definition. Plus: bei Reading-Pfad-Diagnose ("welche DAOs sind neu?") sucht der Implementer entweder den falschen oder den richtigen, je nachdem wo er anfängt.
- **Fix angewandt:**
  - **Spec 1 §11.6.2:** Z. 4781 + 4792 auf `dao.getSessionsByStatuses(listOf("RECORDED"))` umgestellt mit Phase-B-S-7-FIX-Kommentar.

### F-7 `RecordingModule.reduceFailure` für `AllocateMediaRecorder`-Failure fehlt

- **Severity:** Important
- **Prüf-Achse:** 1 (Pre-Dispatch-Allocation), 7 (Bugs durch Migration — stuck state)
- **Was:** S-3-Report F-1 hat `reduceFailure(state, failure, ctx)` als optionalen Hook ins `DictateModule`-Interface eingeführt (Default `null` = `Rejected("reducer-null")`). S-3-Offene-Fragen für S-4 forderte explizit: "RecordingModule.reduceFailure für Effect.AllocateMediaRecorder-Failure ein `Preparing → Idle` + Hardware-Release-Effect". S-4 hat das nicht eingearbeitet — RecordingModule §15.2 hat keinen `reduceFailure`-Override. **Plan-Konsequenz:** wenn `MediaRecorder.prepare()` im Effect-Handler wirft (extern weggeräumter Cache zwischen `allocate()` und `runEffect`, MIC-Permission entzogen mid-prepare), wird `EffectFailure(originModuleId = Recording, ...)` dispatched → an `RecordingModule.reduceFailure` geroutet → Default `null` → `Rejected("reducer-null")` → State bleibt im `Preparing`. UI-Lock: `recordButton` ist disabled (Spec 2 §3.1: `enabledResolver = { state.recording !is RecordingState.Preparing }`). User kann nichts mehr machen, bis er die App neu startet.
- **Konsequenz:** Bug-Klasse "stuck-Preparing-State". Kein Recovery-Pfad ohne Service-Neustart. S-7 Edge-Case-Tabelle #1 sagt zwar "bei `IOException`-Allocate-Failure fängt der Resolver in Toast" — das deckt den **Pre-Dispatch**-Failure. Aber Edge-Case #3 sagt "OS löscht cacheDir zwischen `allocate()` und `MediaRecorder.start()` → MediaRecorder.prepare() failt → Effect-Handler dispatcht `Action.RecordingAction.CancelRecording`" — der Plan adressiert das inhaltlich, aber **strukturell** ist `CancelRecording` keine "Failure-Recovery"-Action sondern eine User-Action; sie müsste in einem `reduceFailure`-Arm gemappt werden (oder ein `Action.RecordingAction.CancelRecording`-Dispatch im EffectHandler-Catch — was die Pure-Function-Garantie für `runEffect` bricht, weil Effects neue Actions emittieren würden).
- **Fix angewandt:**
  - **Spec 1 §15.2:** `RecordingModule.reduceFailure`-Override eingefügt — mit Arm für `failure.effect == "AllocateMediaRecorder" && state is Preparing` (Rollback `Preparing → Idle` + `ReleaseMediaRecorder` + `DeleteAudioFile`). Zusätzlich Arm für `StopMediaRecorder`-Failure (Rollback `Active/Paused → Idle` + cleanup). Andere Effects: Default `null` (Rejected) für Phase 1, künftige Ergänzung dokumentiert.
  - KDoc-Block erklärt die Begründung + Querverweis auf S-3-F-1.

### F-8 `cleanupOrphanedTerminalAudio` Concurrency-Vertrag unklar (§6.3.1)

- **Severity:** Important
- **Prüf-Achse:** 2 (Race-Window), 5 (Concurrency)
- **Was:** §6.3.1 (KG-SST-2 RESOLVED) führt `cleanupOrphanedTerminalAudio` ein — räumt Audio-Files für FAILED/CANCELLED-Sessions im Service-Idle-Stop-Pfad. Der Plan erwähnt "Direkt vor `stopSelf()` im Service-Idle-Stop-Pfad" (Z. 3400 vor Fix), aber nicht: (a) wann genau triggert der Idle-Stop-Slot — über welchen Code-Pfad? (b) auf welchem Dispatcher läuft das (`Dispatchers.IO` via `withContext`)? (c) was bei concurrent allocate während Cleanup? (d) was bei concurrent `RecordingRepository.deleteBySessionId` während Cleanup (Double-Delete)?
- **Konsequenz:** Implementer-Reflex: Code-Pfad fragmentiert über `serviceScope.launch(Dispatchers.IO) { ... }` und `withContext(Dispatchers.IO)` — möglicherweise dupliziert. Plus: Concurrent-Allocate-Race wäre theoretisch möglich, wenn der Cleanup `filesDir/recordings/*` scannt und ein File mid-deletion auch von `Effect.DeleteAudioFile` (RecordingModule-Cancel) gelöscht wird. `File.delete()` ist idempotent — keine Crash-Klasse, aber dokumentationspflichtig.
- **Fix angewandt:**
  - **Spec 1 §6.3.1 (NEU nach Cutoff-Zeile):** "Concurrency-Vertrag (Phase-B S-7)"-Block mit:
    - Trigger-Slot-Präzision: `stopSelfWhenTerminal(state)`-Callback (§7.3 Z. 3625), Cleanup läuft via `serviceScope.launch(Dispatchers.IO)` parallel zu `stopSelf()` (best-effort).
    - Concurrent-Allocate-Verhalten: `isAllTerminal()` ist Voraussetzung; bei mid-Cleanup-Recording bleibt `stopSelf()` aus (kein Crash, nur Verzögerung).
    - Double-Delete-Race mit `RecordingRepository.deleteBySessionId`: idempotent via `File.delete()`-Return + `clearAudioFilePathBulk`-Idempotenz.
    - Dispatcher-Disziplin: explizit `findOrphanedTerminalAudio` (Room-Executor), `File.delete()` (Dispatchers.IO), `clearAudioFilePathBulk` (Room-Executor).

### F-9 `LegacyAudioFileMigration` läuft synchron auf Main-Thread (FGS-5s-Risiko)

- **Severity:** Important
- **Prüf-Achse:** 5 (Threading), 7 (FGS-5s-Frist)
- **Was:** Spec 1 §4.11.6.2 KG-AFF-2 RESOLVED-Block sagt: "Aufruf-Site: `DictatePipelineService.onCreate`, vor Schritt 7/8 (Recovery + Orphan-Cleanup), sync — die Migration ist O(1) (Existence-Check + 1 DB-Update + 1 Pref-Write)". Drei Operationen: (1) `PreferenceManager.getDefaultSharedPreferences(context)` — disk-blocking, <5 ms typisch; (2) `File(cacheDir, "audio.m4a").exists() + .delete()` — disk-blocking, <10 ms; (3) `dao.markLegacyAudioSessionsFailed(...)` — SQL-UPDATE auf indexed Spalte, <20 ms typisch, aber bei >10k Sessions ggf. 100+ ms. Summe: ~50-200 ms worst-case. Service.onCreate läuft auf Main-Thread, vor `startForeground()` — die FGS-5s-Frist (§11.1.4) wird angeknabbert, aber nicht voll konsumiert.
- **Konsequenz:** Bei extrem großen Sessions-Tabellen oder langsamem Storage (alte Geräte) könnte die Migration die FGS-Frist über andere Sub-Schritte hinweg drücken. Implementer-Reflex: Migration in `serviceScope.launch(Dispatchers.IO)` umstellen — Idempotenz (Pref-Flag + DAO-WHERE-Filter, post-F-3) macht das safe. Aber: ohne Plan-Klausel, ob async erlaubt ist, könnte der Implementer aus Unsicherheit den sync-Pfad wählen.
- **Fix angewandt:**
  - **Spec 1 §4.11.6.2 (NEU nach Aufruf-Site-Snippet):** "Threading + FGS-5s-Frist (Phase-B S-7)"-Block mit konkreten Timing-Bereichen (<5 ms, <10 ms, <20-100 ms), Worst-Case-Schätzung (~200 ms), Phase-1-Entscheidung (sync, weil <200 ms), Phase-2-Trigger (Telemetrie zeigt >500 ms → async). Cross-Link auf F-3-Idempotenz-Klausel (macht async safe).

### F-10 `PreferencesFragment.clearCacheRecursively` Race mit aktiver Recording

- **Severity:** Minor
- **Prüf-Achse:** 7 (Bugs durch Migration — unlink während offen)
- **Was:** Spec 1 §4.11.6.3 KG-AFF-3 RESOLVED-Block ersetzt die nicht-rekursive `delete()`-Schleife durch `clearCacheRecursively(cacheDir)`. Aber: der Click-Handler hat keinen Schutz gegen "Recording läuft gerade". Auf Linux/Android-FS verursacht `File.delete()` auf einem offenen File-Descriptor ein `unlink()` — der FD bleibt geöffnet (MediaRecorder schreibt weiter), aber der dirent-Eintrag ist weg. **Beim Recording-Stop:** `RecordingRepository.persistFromCache` sucht das File im cacheDir, findet es nicht → IOException → Pipeline-Stage failed → Ghost-Session, FAILED. User: "warum ist meine Aufnahme weg?". Plan-§4.11.6.3 dokumentiert das nicht.
- **Konsequenz:** Edge-Case-Bug, der nur unter spezifischer Sequenz (Recording läuft → User wechselt zu Settings → "Cache leeren" klickt → zurück zu IME → Recording stoppt) auftaucht. Wahrscheinlichkeit: niedrig, aber wenn es passiert, ist die Fehler-Ursache schwer zu rekonstruieren ("ich habe doch nichts gemacht").
- **Fix angewandt:**
  - **Spec 1 §4.11.6.3 (NEU nach Code-Patch-Snippet):** "Race-Schutz: aktive Recording während 'Cache leeren'"-Block mit:
    - Mechanismus erklärt (unlink + offene FD).
    - Defensive Java-Code im `OnPreferenceClickListener`: `if (recordingActive) { Toast 'Aufnahme läuft' + return }`.
    - Neue String-Resource `dictate_cache_clear_blocked_recording` (EN/DE).
    - Akzeptierter Edge-Case dokumentiert (1-2-Sekunden-Fenster zwischen Recording-Stop und State-Update).
    - Heritage: `lastObservedState` kommt vom `DictateUiStateObserver` (S-3-F-6 Java-Brücke).
  - **Spec 1 §11.2.2 Block 4 Sub-Schritt 12:** Phase-B-S-7-Reminder für den Race-Schutz.

### F-11 Block 4 (AudioFileFactory) hatte keine explizite Sub-Schritt-Liste in §11.2.2

- **Severity:** Minor
- **Prüf-Achse:** 1 (Migrations-Vollständigkeit)
- **Was:** §11.2.2 hat Sub-Schritt-Listen für Block 1a, Block 2, Block 1b, Block 3 — aber keine für Block 4. Die Block-4-Implementation ist über §4.11-KG-AFF-Marker (5 Stück) und §6.3.1 KG-SST-2 verstreut. Ein Implementer müsste sich die Reihenfolge selbst rekonstruieren — z.B. "muss `AudioFileFactory`-Interface vor `ModuleServices.audioFileFactory`-Field?" (ja), "muss `LegacyAudioFileMigration` vor `cleanupOrphans` laufen?" (ja). Pro Phase-Reviewer (z.B. S-7) ist diese Information ableitbar, aber zeit-ineffizient.
- **Konsequenz:** Block-4-Implementer fragmentiert die Implementation über mehrere Sub-Aufgaben ohne klare Reihenfolge — fehlende Steps werden potentiell vergessen (z.B. die String-Resource aus F-4).
- **Fix angewandt:**
  - **Spec 1 §11.2.2 (NEU vor §11.2.3 Test-Strategie):** "Block 4 — AudioFileFactory + Pre-Dispatch-Allocation + Legacy-Migration (siehe §4.11)" als eigene Sub-Sektion mit 14 expliziten Sub-Schritten (von Interface-Anlage bis Tests). Querverweise auf §4.11-Code-Snippets + KG-AFF-Markers. Phase-B-S-7-Reminder eingebaut.
  - **Spec 1 §10:** Block-4-Acceptance-Sektion neu eingefügt (12 Acceptance-Klauseln) — vorher gab es keinen Block-4-Acceptance-Block.

---

## Verifikationen (Code-Reads)

| Plan-Aussage | Verifiziert per | Ergebnis |
|---|---|---|
| `DictateInputMethodService.java:1612` ist `new File(getCacheDir(), "audio.m4a")` | Grep + Plan-Inventur S-7 | ✅ heute |
| `RecordingManager.kt:61-62` MediaRecorder-Container = MPEG_4 + AAC, `.m4a` | Read `RecordingManager.kt:55-66` | ✅ verifiziert |
| `RecordingRepository.kt:45-47` `persistFromCache` macht `copyTo(overwrite = true)` | Read `RecordingRepository.kt:45-49` | ✅ verifiziert |
| `PipelineOrchestrator.kt:837-855` Persist-Stage ohne explizites Cache-Delete | Read `PipelineOrchestrator.kt:830-885` | ✅ heute kein Sofort-Delete (KG-AFF-1 wird Block 4 hinzufügen) |
| `PreferencesFragment.java:272-289` ist nicht-rekursiv, `cacheSize` summiert nicht Sub-Dirs | Read `PreferencesFragment.java:266-296` | ✅ Z. 272 `getCacheDir().listFiles()` Top-Level, Z. 285 `file.delete()` direkt (no-op auf Dirs), Z. 275 `cacheFiles.length × File::length` ohne Sub-Dir-Recursion |
| `dictate_storage_full`-String-Resource existiert NICHT heute | Grep auf `app/src/main/res/values/strings.xml` | ✅ Nur `dictate_cache_cleared/clear_title/clear_message`; `dictate_storage_full` fehlt |
| `ButtonSlot.actionResolver`-Typ ist `(DictateUiState) -> Action?` (1-arg) vor Fix | Read Spec 2 §3.2 Z. 91 | ✅ Bug bestätigt — `resolveRecordAction(state, services)` (2-arg) referenz schlägt fehl |
| Spec 3 OVERLAY_RECORD `actionResolver` fehlt `audioFile`-Argument vor Fix | Read Spec 3 §3.1 Z. 69 | ✅ Bug bestätigt — `StartRecording(target = ...)` ohne `audioFile` |
| `data class StartRecording(target, audioFile)` verlangt beide Felder | Read Spec 2 §3.3 Z. 168 | ✅ verifiziert post-R.2 |
| `markLegacyAudioSessionsFailed`-DAO-Query hatte keinen `status NOT IN (...)`-Filter | Read Spec 1 §4.11.6.2 Z. 1878-1890 vor Fix | ✅ Bug bestätigt — unkonditionales UPDATE |
| `getByStatus("RECORDED")` vs `getSessionsByStatuses(List<String>)`-DAO-Drift | Read Spec 1 §11.6.2 Z. 4781 + §6.3 Z. 3298 | ✅ `getByStatus` ist im DAO nicht definiert |
| §4.11.6 Recovery-Coupling-Tabelle deckt nur RECORDED | Read Spec 1 §4.11.6 Z. 1654-1659 vor Fix | ✅ 3 Zeilen, alle RECORDED |
| `RecordingModule.reduceFailure` ist nicht definiert in §15.2 vor Fix | Read Spec 1 §15.2 Z. 5626-5815 vor Fix | ✅ kein `override fun reduceFailure(...)` |
| Spec 2 §6 `ImeViewBackend`-Konstruktor hat kein `services`-Field vor Fix | Read Spec 2 §6 Z. 517-525 vor Fix | ✅ 7 Felder, kein `services` |
| Spec 3 §4.2 `OverlayBackend`-Konstruktor hat kein `services`-Field vor Fix | Read Spec 3 §4.2 Z. 358-365 vor Fix | ✅ 6 Felder, kein `services` |

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|-------|---------|-----|------------------|
| Spec 2 | §3.2 ButtonSlot | Refactor | `actionResolver: (DictateUiState) -> Action?` → `(DictateUiState, ModuleServices) -> Action?` mit Phase-B-S-7-Erklär-Block (F-1) |
| Spec 2 | §6 ImeViewBackend | Refactor | Konstruktor um `services: ModuleServices` erweitert + Phase-B-S-7-Hinweis-Block (F-1) |
| Spec 2 | §6 wireStaticHandlers | Fix | `slot.actionResolver(s)` → `slot.actionResolver(s, services)` (F-1) |
| Spec 2 | §11.6 Empfehlungs-Snippet | Fix | `slot.actionResolver(s)` → `slot.actionResolver(s, services)` (F-1) |
| Spec 3 | §3.1 OVERLAY_RECORD | Refactor | Inline-Lambda → `::resolveOverlayRecordAction` mit Phase-B-S-7-FIX-Kommentar (F-2) |
| Spec 3 | §3.1 (NEU nach LayoutMode-Definition) | Add | `resolveOverlayRecordAction(state, services)`-Helper mit Pre-Dispatch-Allocate + IOException-Toast-Fallback (F-2) |
| Spec 3 | §3.1 Migrations-Hinweis | Add | "andere 1-arg-Resolver bekommen `_`-Placeholder im 2. Argument" (F-1) |
| Spec 3 | §4.2 OverlayBackend | Refactor | Konstruktor um `services: ModuleServices` erweitert + Phase-B-S-7-Hinweis-Block (F-1) |
| Spec 3 | §4.2 wireStaticOverlayHandlers | Fix | `slot.actionResolver(s)` → `slot.actionResolver(s, services)` (F-1) |
| Spec 3 | §11.5 Beweis-Snippet | Fix | Click-Listener-Snippet auf 2-arg umgestellt (F-1) |
| Spec 1 | §4.11.6 Recovery-Coupling-Tabelle | Refactor | 3 Zeilen → 6 Zeilen + Status-Spalte (RECORDING/TRANSCRIBING ergänzt, F-5) |
| Spec 1 | §4.11.6.2 DAO-Query | Fix | `WHERE audio_file_path = :legacyPath AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')` (F-3) |
| Spec 1 | §4.11.6.2 (NEU vor DAO-Snippet) | Add | "Idempotenz-Klausel auf DAO-Ebene"-Erklär-Block (F-3) |
| Spec 1 | §4.11.6.2 (NEU nach Aufruf-Site) | Add | "Idempotenz beim zweiten Lauf"-Block + "Threading + FGS-5s-Frist"-Block (F-3, F-9) |
| Spec 1 | §4.11.6.3 (NEU nach Code-Patch) | Add | "Race-Schutz: aktive Recording während 'Cache leeren'"-Block mit Defensive-Code (F-10) |
| Spec 1 | §6.3.1 (NEU nach Cutoff-Zeile) | Add | "Concurrency-Vertrag (Phase-B S-7)"-Block: Trigger-Slot, Dispatcher-Disziplin, Race-Verhalten (F-8) |
| Spec 1 | §11.6.2 Recovery-Snippet | Fix | `dao.getByStatus("RECORDED")` → `dao.getSessionsByStatuses(listOf("RECORDED"))` (2 Stellen, F-6) |
| Spec 1 | §15.2 RecordingModule | Add | `reduceFailure`-Override mit AllocateMediaRecorder + StopMediaRecorder-Arms (F-7) |
| Spec 1 | §11.2.2 Block 4 (NEU) | Add | 14 explizite Sub-Schritte für AudioFileFactory + Pre-Dispatch + Legacy-Migration (F-11, F-4) |
| Spec 1 | §10 Block 4 Acceptance (NEU) | Add | 12 neue Acceptance-Klauseln (alle Findings) |
| Hauptplan | §9 Iter-Log | Add | Phase-B Quality-Gate S-7 Eintrag (2026-05-13) — 11-Findings-Summary; chronologisch nach S-4 platziert |

**Gesamt:** ~21 Edit-Operationen in 4 Dateien (Spec 1: 11, Spec 2: 4, Spec 3: 5, Hauptplan: 1).

---

## Offene Fragen für nachfolgende Agents

### Für S-5 (Service-Schicht / Foreground-Service)

- **`KeyboardLayoutManager.attach(backend)` reicht `services` durch** — heute (vor Block-4) hat das `KeyboardLayoutManager` keinen `services`-Konstruktor-Parameter. S-5 sollte beim Block-5-Implementation prüfen, ob `services` an den Manager fließt (typischerweise via `DictateInputMethodService`-Constructor-Param) und an die Backends (Spec 2 §6 + Spec 3 §4.2 post-S-7 verlangen `services` im Constructor). Ohne diese Kette schlägt der Block-5-Build sofort fehl.
- **`PreferencesFragment` muss `DictateUiStateObserver` konsumieren** — Phase-B-S-7-F-10 Race-Schutz greift auf `lastObservedState.recording.isActiveOrPausedOrPreparing()` zu. Heute hat `PreferencesFragment` keinen StateFlow-Observer; S-5 sollte beim Block-5-Implementation den Java-Brücken-Observer-Pattern (S-3-F-6) auf `PreferencesFragment` ausdehnen.

### Für S-6 (LayoutCatalog) + S-8 (OverlayBackend)

- **Mechanische Migration aller 1-arg-Lambdas** in §8.1-§8.4 (Spec 2) + §3.1 (Spec 3) auf 2-arg `(state, services) -> Action?`. Phase-B S-7 erläutert die Konvention (`{ Action.X }` → `{ _, _ -> Action.X }`, `{ state -> ... }` → `{ state, _ -> ... }`), aber jeder ButtonSlot in den 5 LayoutModes + 1 OverlayMode muss touched werden. Compile-Fehler bei der ersten `assembleDebug` zeigt jeden Punkt — S-6 + S-8 sollten dafür sorgen, dass die Migration konsistent läuft.
- **`resolveOverlayRecordAction` lebt in Spec 3 §3.1** — analog zu `resolveRecordAction` in Spec 2 §8.5. S-8 sollte sicherstellen, dass keine Drift entsteht (z.B. unterschiedliche String-Resource-IDs für den IOException-Toast). Beide Resolver rufen jetzt `R.string.dictate_storage_full`.

### Für S-9 (ResetSuppressBit-Lifecycle)

- **Keine direkten S-7-Touchpoints** — der `RecordingModule.reduceFailure` Phase-B-S-7-F-7-Fix erweitert RecordingModule, aber `onCrossModuleStateChange` (für ResetSuppressBit-Cascade) ist orthogonal. S-9 sollte beim Block-4-Acceptance-Pass prüfen, dass der `R.RSB-FIX-A`-Test auch dann grün läuft, wenn `reduceFailure` für `AllocateMediaRecorder` greift (theoretisches Szenario: Allocate failed beim ersten Klick → Preparing → Idle Rollback; ResetSuppressBit-Cascade lief beim `Idle → Preparing`-Vorwärts-Übergang, aber nicht beim Rollback — das ist korrekt, weil die Suppress-Wahl des Users bis zur nächsten ECHTEN Session gelten soll).

### Cross-Cutting

- **Concurrency-Vertrag-Convention**: Phase-B-S-7-F-8 dokumentiert für `cleanupOrphanedTerminalAudio` einen expliziten Vertrag. Analog sollten alle anderen Cleanup-Routinen (Block-4-`cleanupOrphans`, `RecordingRepository.deleteBySessionId`, `DurationHealingJob.heal`) gegen das gleiche Schema geprüft werden: Trigger-Slot, Dispatcher-Disziplin, Concurrency-Race-Verhalten. Empfehlung an Phase-Reviewer S-8/S-9: bei Cleanup-Routinen-Refactor diesen Vertrag mitziehen.
- **Idempotenz-Beweis für DAO-UPDATE-Queries** (Phase-B-S-7-F-3): die `markLegacyAudioSessionsFailed`-Query wurde mit `WHERE status NOT IN (...)` filtergesperrt. Andere `UPDATE`-Queries im Plan (`updateStatus`, `clearAudioFilePath`, `markInserted`) sollten gegen das gleiche Schema geprüft werden — können sie idempotent zweimal laufen (z.B. nach Crash)?

---
