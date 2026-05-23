# Phase B — S-2 DB-Schema-Migration: SessionStatus v3 (4 Stati) → v4 (6 Stati + inserted_at + CHECK-Recreate) Migrations-Pfad-Review

**Erstellt:** 2026-05-13
**Reviewer:** Phase-B-Agent S-2 (Subsystem #2 von 9)
**Plan-Version vor Edits:** Stand nach S-1-Apply-Pass (Commit `9f84730`, S-1-Report `phase-b-s1-state-hierarchy.md`)

---

## Summary

Der Migrationspfad für S-2 ist **mechanisch solide** — die table-recreate-Strategie folgt der bewährten Vorlage `MIGRATION_2_3` (verifiziert in `MigrationTo3.kt:43-117`), das FK-Cascade-Datenverlust-Risiko ist sauber begründet (SQLite Cascade greift nicht bei `DROP TABLE`), der Multi-Step v1→v4-Test ist konkret formuliert. Die CHECK-Constraint-Werte sind vollständig, der `inserted_at`-Backfill semantisch korrekt, die Konsumenten-Tabelle (4 Sites) komplett mit Java/Kotlin-Patches.

**Drei strukturelle Lücken** wurden behoben:

1. **Doppel-Sicherung HistoryAdapter** — der bestehende `try { SessionStatus.valueOf(...) } catch (IllegalArgumentException) { RECORDED }`-Wrapper (Z. 131-135, gefunden per Read) ist **eine zweite Schicht** über dem neuen `default:`-Branch. Plan-Patch hatte das nicht erkannt — sah aus wie "lass den Wrapper weg, der default macht das jetzt". Ohne Wrapper bricht aber das Downgrade-Szenario (User mit v4-DB, App auf v3 zurück). Beide Schichten müssen koexistieren — disjunkte Failure-Modes (DB-String unbekannt vs. Enum-Wert ohne case).

2. **androidTest-Setup als unsichtbare Aufwandsfalle** — Inventur Surprise-Finding #4 sagte das schon, aber im Block-3-Plan war es als Implementation-Detail von "MigrationTo4.kt anlegen" versteckt. Substanziell: neues Source-Set, Version-Catalog-Erweiterung um 3 Dependencies (`room-testing`, `androidx.test.runner`, `androidx.test.rules`), build.gradle-Update, Smoke-Test-Verifikation. ~3 h Aufwand, separater Sub-Schritt 0 vor allem anderen.

3. **RECORDING-File-Recovery-Lücke** — Plan-Recovery-Code löscht `audioFilePath` bei RECORDING→FAILED, aber RECORDING-DB-Rows haben heute typischerweise `audio_file_path = NULL` (Path wird erst beim Recording-Stop in `transitionRecorded` geschrieben). Das partial-written File lebt in `cacheDir/audio.m4a` und wird vom Recovery-Pfad **nicht** geräumt. Tatsächlich räumt es `AudioFileFactory.cleanupOrphans` (Block 4) bzw. der `cacheDir`-OS-Cleanup. Das war im Plan implizit — jetzt explizit.

**Drei Detail-Klärungen:**

4. `inserted_at`-Index — keiner, mit Begründung (kleine Tabelle, kein Hot-Path).
5. FAILED-DB-Row-Lifecycle — DB-Rows bleiben persistent, nur Audio-Files werden gecleant.
6. JobExecutor.register vs. Effect.PersistStatus-Verzahnung — die "DB-first"-Regel betrifft nur den Modul-Effect-Pfad, nicht JobExecutor (Lock-Producer bleibt unangetastet).

**Eine Code-Korrektur:** HistoryDetailActivity:287-299 — Plan-Anweisung "explizit RECORDING/TRANSCRIBING ausschließen" ist redundant gegenüber der bestehenden Whitelist-Logik (`canReprocess = status IN {RECORDED, FAILED, CANCELLED, COMPLETED}` lässt neue Werte automatisch fallen). Plan-Edit nimmt diese Anweisung zurück.

**Eine ergänzte Sektion:** Downgrade-Strategie (v4 → v3) explizit dokumentiert — "kein Pfad, App crasht, User-Daten intakt, Re-Install ist Recovery".

**Befund:** 2 Critical, 4 Important, 2 Minor — **8 Findings, 9 Plan-Edits** in Spec 1. Spec 2 + Spec 3 nicht berührt (S-2 ist reiner Spec-1-Scope).

---

## Findings + Applied Fixes

### F-1 HistoryAdapter Doppel-Sicherung try/catch + default: nicht dokumentiert

- **Severity:** Critical
- **Prüf-Achse:** 4 (Konsumenten-Audit), 7 (Bugs durch Migration)
- **Was:** Plan-§6.1.3-Patch-Snippet zeigt einen `switch (status) { ...; default: Log.wtf + GONE; }`-Branch. Aber der echte Code in `HistoryAdapter.java:131-135` umschließt den Switch heute schon mit `try { status = SessionStatus.valueOf(session.getStatus()); } catch (IllegalArgumentException e) { status = SessionStatus.RECORDED; }`. Plan-Patch erwähnt diesen Wrapper nicht — ein Implementer könnte denken, der `default:`-Branch macht ihn redundant, und ihn entfernen.
- **Konsequenz:** Bug-Klasse "Downgrade-Crash". Wenn ein User die App auf v4 installiert (DB hat RECORDING/TRANSCRIBING-Rows), dann auf v3 zurück (z.B. Hotfix-Rollback, Backup-Restore mit älterer APK), kennt die v3-SessionStatus-Enum diese Werte nicht. `SessionStatus.valueOf("RECORDING")` wirft `IllegalArgumentException`. **Mit** try/catch-Wrapper: Fallback zu RECORDED, Pending-Badge, kein UI-Crash. **Ohne** Wrapper: Exception propagiert in den RecyclerView-Bind-Loop, History-View crasht beim ersten Scroll. Außerdem: der `default:`-Branch wäre ohne `try/catch` für den heutigen Code unerreichbar (alle Strings, die nicht in der v3-Enum sind, werden vom `valueOf` abgefangen, bevor sie den switch erreichen) — ein Implementer könnte deshalb fälschlich folgern, dass der `default:` "redundant" ist und ihn auch entfernen, was die zweite Schutzschicht (Enum-Erweiterung ohne case) eliminieren würde.
- **Fix angewandt:** §6.1.3 Konsumenten-Tabelle Zeile `HistoryAdapter` umformuliert mit explizitem Hinweis auf die try/catch-Wrapper-Schicht. NEUER Block "Doppel-Sicherung try/catch + `default:` — keine Redundanz, sondern zwei Failure-Modes" unter dem `HistoryAdapter`-Patch eingefügt: Tabelle Failure-Mode × Catcher (DB-String unbekannt → try/catch; Enum erweitert, switch nicht → default:). Block-3-Acceptance ergänzt um Punkt "Doppel-Sicherung HistoryAdapter" mit konkretem Unit-Test-Vorschlag (Robolectric `session.status = "UNBEKANNT"` → RECORDED-Fallback).

### F-2 RECORDING-Recovery-Pfad räumt cacheDir/audio.m4a nicht

- **Severity:** Critical
- **Prüf-Achse:** 7 (Bugs durch Migration), 3 (Atomicity)
- **Was:** Plan-§6.3-Recovery-Code für RECORDING-Sessions enthält `row.audioFilePath?.let { File(it) }?.takeIf { exists() }?.runCatching { delete() }`. Das räumt nur Files, deren Pfad in der DB-Row steht. Aber: heutige Recording-Architektur (Vorbild-Snippet `SessionManager.transitionRecorded(sessionId, audioFilePath)` in §6.1, plus verifiziert in `PipelineOrchestrator.persistNewSession`) schreibt den Pfad **erst beim Recording-Stop** in die DB-Row. Während RECORDING ist die Row noch `audio_file_path = NULL`. Das partial-written File lebt aber physisch in `cacheDir/audio.m4a` (heutiger Stand, `DictateInputMethodService.java:1706` zeigt `audioFile.getAbsolutePath()` als Path-Quelle, der wird per `persistFromCache` nach `filesDir/recordings/...` migriert beim Stop).
- **Konsequenz:** Audio-File-Leak nach jedem OOM-Death während aktiver Aufnahme. Pro Sequenz ~50-500 KB im cacheDir (Dictate-Audio ist m4a-komprimiert, kurze Recordings). Bei Power-Usern, die mit häufigen Recordings + OS-Druck unter Speicher-Mangel arbeiten, summiert sich das. Außerdem: bei Block-4-Migration nach `filesDir/recordings/{sessionId}.m4a` wird der Pfad zwar bekannt (sessionId steckt im Path), aber das File ist im `filesDir` (kein OS-Cleanup) und der `cleanupOrphans`-Pfad räumt nur via `AudioFileFactory.cleanupOrphans(referencedPaths)` — der läuft im Service-onCreate, also fängt das, wenn der nächste App-Start passiert. Plan §7.3 Z. 3215-3225 hat den Cleanup-Pfad bereits, aber er ist nicht in §6.3 RECORDING-Recovery referenziert.
- **Fix angewandt:** Hinweis-Block "RECORDING-Recovery: das partial-written Audio-File auf Disk" in §6.3 zwischen "Reihenfolge File-Op vs. DB-Op"-Block und Atomicity-Block eingefügt. Klarstellt: (a) RECORDING-Rows haben typischerweise `audio_file_path = NULL`, (b) das `clearAudioFilePath`-Snippet ist nur für den Sonderfall (Block-4-AudioFileFactory schreibt Path beim Allocate), (c) der Phase-1-Cleanup läuft via zwei orthogonale Pfade: `AudioFileFactory.cleanupOrphans` (im Service-onCreate, Block 4) bzw. `cacheDir`-OS-Cleanup für Block-3-Layout. Akzeptiert, weil cacheDir-OS-Cleanup für die kurze Phase 1 ausreicht.

### F-3 androidTest-Infrastruktur-Setup als versteckter Aufwand

- **Severity:** Important
- **Prüf-Achse:** 6 (Test-Setup), 1 (Migrations-Vollständigkeit)
- **Was:** Phase-A-Inventur Surprise-Finding #4 hat es notiert: `androidTest/`-Verzeichnis existiert nicht (verifiziert per `ls app/src/`: nur `main/` und `test/`). Block-3-Plan in §11.2.2 hat das in Sub-Schritt 2 "MigrationTo4.kt anlegen" untergebracht — also als Implementation-Detail. Aber: das Setup ist substanziell (neues Source-Set, 3 neue Version-Catalog-Einträge, build.gradle-Erweiterung, Smoke-Test-Verifikation). KG-SST-3 RESOLVED-Block hat die Dependency-Liste (`room-testing`, `androidx.test:runner`, `androidx.test:rules`), aber **wo genau in `app/build.gradle`** und **wie via Version-Catalog** ist unklar. Heutiges Projekt nutzt Version-Catalog konsequent (alle `libs.X`-Refs in `gradle/libs.versions.toml`), Plan-Patch ohne Version-Catalog-Erweiterung würde zwei Patterns im gleichen Build-File mischen.
- **Konsequenz:** Implementer fängt mit MigrationTo4Test.kt an, läuft `connectedAndroidTest`, kriegt `ClassNotFoundException` (room-testing fehlt), debuggt 30 Min, fügt Dependency direkt in build.gradle (nicht via Catalog), Style-Inkonsistenz wird im Code-Review nachgepflegt — zusätzlicher Hops. Plus: Block-3-Aufwandsschätzung wäre zu niedrig, weil das Setup nicht separat sichtbar war.
- **Fix angewandt:** §11.2.2 Block 3 von 9 auf 14 Sub-Schritte erweitert. Schritt **0 (NEU)** ist explizit "androidTest-Infrastruktur anlegen" mit 5 Konkretisierungen (Verzeichnis, Version-Catalog-Erweiterung, build.gradle-Snippet, Smoke-Test, Verifikation per `./gradlew connectedDebugAndroidTest`). Neue §11.7.0a Sub-Sektion mit den exakten Catalog-Einträgen (`room-testing`, `androidx-test-runner`, `androidx-test-rules`), build.gradle-Snippet, `AndroidTestSetupSmokeTest.kt`-Body, CI-Note (Instrumented-Tests laufen heute nicht in CI), Aufwandsschätzung (~3 h für den androidTest-Anteil von Block 3). Acceptance-Punkt "Phase-B S-2 androidTest-Smoke" hinzugefügt (Smoke-Test muss grün laufen, BEVOR MigrationTo4Test implementiert wird).

### F-4 HistoryDetailActivity-Whitelist-Logik bereits defensiv, Plan-Anweisung redundant

- **Severity:** Important
- **Prüf-Achse:** 4 (Konsumenten-Audit)
- **Was:** Heutige `HistoryDetailActivity.java:287-299` verwendet eine **Whitelist** für `canReprocess`: `canReprocess = audioAvailable && !jobActive && (status == RECORDED || status == FAILED || status == CANCELLED || status == COMPLETED)`. Plan-§6.1.3-Tabelle-Zeile sagte: "RECORDING/TRANSCRIBING explizit ausschließen — Zeile `canReprocess = status != RECORDING && status != TRANSCRIBING && (existing)`". Das ist eine **Blacklist**-Logik, die zur bestehenden Whitelist hinzugefügt würde. Aber: neue Enum-Werte (RECORDING/TRANSCRIBING) sind in der bestehenden Whitelist nicht enthalten und matchen daher automatisch `canReprocess = false`. Die `!= X && != Y`-Klausel ist redundant — sie ändert das Verhalten nicht.
- **Konsequenz:** Code-Lärm im Patch — Implementer fügt eine Klausel hinzu, die nichts tut. Wartungsschuld (zwei Stellen, die geändert werden müssen, wenn ein 7. Enum-Wert kommt, statt einer). Außerdem: wenn man die heutige Whitelist-Logik nicht versteht, fügt man die Blacklist hinzu in der Annahme, sie sei nötig — Beobachtungs-Bias.
- **Fix angewandt:** §6.1.3 Konsumenten-Tabelle Zeile `HistoryDetailActivity:287-299` umformuliert: "**Keine Code-Änderung nötig** — die Whitelist-Logik ist bereits defensiv gegen neue Status-Werte." Block-3-Sub-Schritte ergänzt um Schritt 12 "**HistoryDetailActivity.java:287-299** — KEINE Code-Änderung (existierende Whitelist `RECORDED || FAILED || CANCELLED || COMPLETED` schließt RECORDING/TRANSCRIBING automatisch aus, siehe §6.1.3 Konsumenten-Tabelle)" — als positive Antitese, damit Implementer es nicht aus Reflex doch ändert.

### F-5 `inserted_at`-Index-Begründung fehlt

- **Severity:** Important
- **Prüf-Achse:** 1 (Migrations-Vollständigkeit), 3 (Atomicity)
- **Was:** Plan-MigrationTo4.kt-Snippet recreated 5 Indices auf `sessions` (parent_session_id, type, created_at, origin, status). Die neue Spalte `inserted_at` bekommt **keinen** Index. Aber: `findPendingInsertion()` queriet `WHERE inserted_at IS NULL`, `deleteInsertedOlderThan(cutoff)` queriet `WHERE inserted_at < :cutoff`. Bei wachsender sessions-Tabelle wäre ein Index strategisch — aber bei Dictate-üblicher Größe (typisch <1k, Power-User <10k Sessions) ist Full-Table-Scan auf 4-Byte-Long-Spalte nicht messbar langsamer. Plan-Snippet hat keinen expliziten Hinweis warum kein Index — Implementer könnte aus "Best-Practice"-Reflex einen `CREATE INDEX index_sessions_inserted_at` hinzufügen (Auto-Reflex: jede Spalte, auf der gefiltert wird, kriegt einen Index).
- **Konsequenz:** Wenn der Implementer den Index hinzufügt: höhere Insert/Update-Cost pro Pipeline-Run (Index muss mitgepflegt werden bei jedem `markInserted`-Call). Kein katastrophaler Bug, aber Plan-Drift gegenüber dem unausgesprochenen Design. Außerdem: Plan-Test `migrate3To4_preservesIndices` listet die 5 Indices explizit — wenn der Implementer den Index als zusätzliche Zeile addiert, schlägt der Test rot fehl ohne klares Signal warum.
- **Fix angewandt:** Hinweis-Block nach dem MigrationTo4.kt-Snippet eingefügt: "Warum kein `index_sessions_inserted_at`?" — Begründung (kleine Tabelle, kein Hot-Path), explizite Negierung des Auto-Reflexes ("SessionEntity.kt-Annotation bleibt entsprechend bei 5 Indices … kein `Index("inserted_at")` ergänzen"), Reservation für post-hoc-Migration falls Telemetrie es später nötig macht. Sub-Schritt 4 in §11.2.2 Block 3 ergänzt: "KEIN zusätzlicher `Index("inserted_at")` (siehe Begründung unter §6.1)."

### F-6 FAILED-DB-Row-Lifecycle nicht dokumentiert

- **Severity:** Important
- **Prüf-Achse:** 3 (Backfill-Semantik), 7 (Cleanup-Race)
- **Was:** Plan-§6.3.1 (KG-SST-2 RESOLVED) führt `cleanupOrphanedTerminalAudio` ein — räumt Audio-Files für FAILED/CANCELLED-Sessions. Aber: die Routine setzt nur `audio_file_path = NULL`, **löscht keine DB-Rows**. FAILED-Sessions sammeln sich also unbegrenzt in der `sessions`-Tabelle an, bis der User sie manuell via `HistoryDetailActivity → Delete` entfernt. `deleteInsertedOlderThan(cutoff)` greift nur für COMPLETED+inserted (Plan-Recovery-Tabelle bestätigt das). Plan dokumentiert weder die Konsequenz (DB-Bloat bei häufigen Quota-Fehlern) noch die Begründung (User-Wert des Fehler-Logs).
- **Konsequenz:** Implementer könnte aus dem Plan ableiten, dass FAILED-Sessions irgendwann automatisch verschwinden — und ein Bug-Report "FAILED-Sessions stapeln sich" als Implementation-Bug interpretieren statt als bewusste Design-Entscheidung. Plus: die Frage "warum nicht auch FAILED-Rows löschen nach 30d?" bleibt offen — beim PR-Review würde sie auftauchen.
- **Fix angewandt:** Hinweis-Block "DB-Row-Lifecycle für FAILED/CANCELLED — bewusst kein Auto-Cleanup" am Ende von §6.3.1 eingefügt. Begründung: User-Wert (Fehlerstatus bleibt sichtbar), DB-Größe (irrelevant bei <50 KB/Jahr), Reservation für späteren `deleteFailedOlderThan(cutoff)` als Phase-2-Pfad.

### F-7 Verzahnung JobExecutor.register vs. Effect.PersistStatus unklar

- **Severity:** Important
- **Prüf-Achse:** 5 (DB-Reihenfolge KG-SST-5), 7 (SRP-Bruch-Risiko)
- **Was:** Plan §6.2 R.17 sagt "DB first, then Cache" für RECORDING/TRANSCRIBING. Plan §6.1.1 sagt "Producer-Sites `JobExecutor.kt:96/:164` bleiben unverändert" und die `JobExecutor.start()` ruft heute `ActiveJobRegistry.register(sessionId, initial)` **VOR** dem Pipeline-Run (Z. 96). Aber nicht klar formuliert: WO genau schreibt der Reducer `SessionDao.updateStatus(TRANSCRIBING)`? Vor oder nach `JobExecutor.start`? Wenn `Effect.PersistStatus(TRANSCRIBING)` und `Effect.StartPipeline(jobRequest)` als zwei separate SideEffects aus dem Reducer kommen, ist die Reihenfolge entscheidend: 1) DB updateStatus → 2) Registry.update → 3) JobExecutor.start (Lock-Claim + Pipeline). Wenn nicht klar, könnte ein Implementer "Lock zuerst, dann State schreiben"-Reflex haben — und plötzlich macht JobExecutor.start `sessionDao.updateStatus(TRANSCRIBING)` selbst, was den SRP der JobExecutor-Klasse bricht (Lock-Producer wird Status-Producer).
- **Konsequenz:** SRP-Bruch in JobExecutor — die Klasse hat heute klar: Lock-Claim, Cancel-Token, Executor-Lifecycle. DB-Status-Writes sind nicht ihre Verantwortung. Plus: Race-Condition bei `JobExecutor.start`-Failure (z.B. parallel-Job aktiv → `register` returnt false): State ist schon `Running(...)`, aber Pipeline läuft nicht — der Modul-Reducer muss das mit einem Rollback-Action (`RejectedJobAlreadyActive`) auffangen. Plan adressed das nicht explizit.
- **Fix angewandt:** Hinweis-Block "Wichtige Verzahnung: `JobExecutor.register` vs. `Effect.PersistStatus(TRANSCRIBING)`" am Ende von §6.2 R.17 eingefügt. Konkrete 4-Schritt-Sequenz (Reducer-State-Mutation → DB-Write → Registry-Update → JobExecutor.start Lock-Claim). Failure-Pfade explizit (2(a) fehlschlägt → PersistenceError, 3(c) fehlschlägt → RejectedJobAlreadyActive in Block 4). Implementer-Anker: "NICHT `JobExecutor.start` so umschreiben, dass es zuerst `SessionDao.updateStatus(TRANSCRIBING)` schreibt — das würde SRP brechen".

### F-8 Downgrade-Strategie (v4 → v3) nicht dokumentiert

- **Severity:** Minor
- **Prüf-Achse:** 7 (Migration-Reversibility)
- **Was:** Plan-§11.7.0-Risiko-Tabelle deckt 6 Risiken ab, aber Downgrade fehlt. Heutige `DictateDatabase` (verifiziert `:67-103`) hat KEIN `fallbackToDestructiveMigrationOnDowngrade`. Wenn ein User absichtlich (Backup-Restore-aus-älterer-APK) oder unabsichtlich (Hotfix-Rollback mit DB-Backup-Datei intakt) auf eine v3-App downgraded, gibt es zwei mögliche Szenarien: (a) DB ist im Backup von v3 — kein Konflikt; (b) DB-File ist v4, App ist v3 — Room sucht eine Migration "4 to 3" und wirft `IllegalStateException` beim ersten DB-Zugriff. Plan-Inventur Phase-A in §S-2 Migrations-Schwerpunkt ("Backwards/Forwards") nicht adressiert.
- **Konsequenz:** Crash-Klasse, die niemand wahrnimmt — bis ein Beta-Tester nach einem Rollback einen Bug-Report schreibt. Plus: pre-existing RECORDING/TRANSCRIBING-Rows aus der v4-Zeit würden in v3 zum HistoryAdapter-try/catch-Fallback führen (siehe F-1) — aber dass der `valueOf`-Crash gefangen wird, hängt vom Wrapper ab (siehe F-1-Fix).
- **Fix angewandt:** §11.7.0-Risiko-Tabelle um eine Zeile "DB-Downgrade v4 → v3" erweitert. Bewusste Entscheidung "kein Pfad implementieren" mit Begründung (Wiederinstallation der v4-App ist der einfachere User-Pfad), Hinweis auf User-Daten-Sicherheit (DB-File bleibt intakt), Cross-Link auf F-1-Fix für die RECORDING/TRANSCRIBING-Row-Behandlung.

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|-------|---------|-----|------------------|
| Spec 1 | §6.1 (nach MigrationTo4.kt-Snippet) | Add | "Warum kein `index_sessions_inserted_at`?"-Hinweis-Block (F-5) |
| Spec 1 | §6.1.3 Konsumenten-Tabelle (HistoryAdapter-Zeile) | Refactor | Try/catch-Wrapper Z. 131-135 explizit dokumentiert, Doppel-Sicherung-Hinweis (F-1) |
| Spec 1 | §6.1.3 Konsumenten-Tabelle (HistoryDetailActivity-Zeile) | Refactor | "Keine Code-Änderung nötig" (Whitelist ist defensiv), redundante Plan-Anweisung gestrichen (F-4) |
| Spec 1 | §6.1.3 (nach HistoryAdapter-Patch + Lint-Backup) | Add | "Doppel-Sicherung try/catch + `default:` — keine Redundanz, sondern zwei Failure-Modes"-Block mit Failure-Mode-Tabelle (F-1) |
| Spec 1 | §6.3 (nach Reihenfolge-Block) | Add | "RECORDING-Recovery: das partial-written Audio-File auf Disk"-Hinweis-Block: cacheDir/audio.m4a-Cleanup via `AudioFileFactory.cleanupOrphans` (Block 4) bzw. cacheDir-OS-Cleanup (F-2) |
| Spec 1 | §6.3.1 (am Ende) | Add | "DB-Row-Lifecycle für FAILED/CANCELLED — bewusst kein Auto-Cleanup"-Hinweis-Block (F-6) |
| Spec 1 | §6.2 R.17 (am Ende) | Add | "Wichtige Verzahnung: `JobExecutor.register` vs. `Effect.PersistStatus(TRANSCRIBING)`"-Block mit 4-Schritt-Sequenz + Implementer-Anker gegen SRP-Bruch (F-7) |
| Spec 1 | §10 Block-3-Acceptance | Refactor | 4 neue S-2-Acceptance-Klauseln (androidTest-Smoke, Doppel-Sicherung, Cleanup-Reihenfolge, SessionStatus-KDoc-Update) |
| Spec 1 | §11.2.2 Block 3 | Refactor | Sub-Schritte von 9 auf 14 erweitert: Schritt 0 (NEU: androidTest-Setup), Schritte 10-14 (Konsumenten/Lint/Strings); inline-Hinweise auf S-2-Klärungen (F-3, F-4) |
| Spec 1 | §11.7.0 Risiko-Tabelle | Add | Zeile "DB-Downgrade v4 → v3" (F-8) |
| Spec 1 | §11.7.0a (NEU) | Add | "androidTest-Setup (NEU für Block 3 — siehe Inventur Surprise-Finding #4)"-Sektion: heutiger Zustand, 5-Schritt-Setup, CI-Note, Aufwandsschätzung (F-3) |
| Hauptplan | §9 Iter-Log | Add | Phase-B Quality-Gate S-2 Eintrag (2026-05-13) mit 8-Findings-Summary |

**Gesamt:** 9 Edit-Operationen in 2 Dateien (Spec 1: 11 Sektions-Berührungen / 9 Operations, Hauptplan: 1 Operation). Spec 2 + Spec 3 unverändert — S-2 ist reiner Spec-1-Scope (siehe oben "S-2-Relevante Referenzen in Spec 2/3: KEINE").

---

## Verifikationen (Code-Reads)

| Plan-Aussage | Verifiziert per | Ergebnis |
|---|---|---|
| MigrationTo3 hat 5 sessions-Indices | `MigrationTo3.kt:113-117` | ✅ 5 Indices (parent_session_id, type, created_at, origin, status) |
| `processing_steps`/`transcriptions` FK auf sessions.id mit CASCADE | `MigrationTo3.kt:138, :182` | ✅ Beide FKs sind `FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE` |
| `androidTest/`-Verzeichnis existiert nicht | `ls app/src/` | ✅ nur `main/` und `test/` |
| Heutige DictateDatabase ohne `fallbackToDestructiveMigration*` | `DictateDatabase.kt:67-103` | ✅ Nur `.allowMainThreadQueries()`, `.addMigrations(...)`, `.addCallback(...)` |
| Schema v3 hat keine CHECK in createSql | `app/schemas/.../3.json` | ✅ `createSql` listet Spalten ohne CHECK — Room-Validator vergleicht nur Spalten + FKs + Indices, **nicht** CHECK-Klauseln |
| `HistoryAdapter` hat try/catch-Wrapper Z. 131-135 | `HistoryAdapter.java:131-135` | ✅ `try { SessionStatus.valueOf(...) } catch { RECORDED }` |
| `HistoryDetailActivity:287-299` ist Whitelist-Logik | `HistoryDetailActivity.java:293-298` | ✅ `canReprocess = audioAvailable && !jobActive && (RECORDED \|\| FAILED \|\| CANCELLED \|\| COMPLETED)` |
| ResendStatusDispatcher hat exhaustive `when` | `ResendStatusDispatcher.kt:57-71` | ✅ 4 Branches (COMPLETED, CANCELLED, RECORDED, FAILED), kein Else |
| ActiveJobRegistry ist Kotlin object, process-local | `ActiveJobRegistry.kt:20-65` | ✅ `object ActiveJobRegistry` mit @Synchronized-Methoden |
| `JobExecutor.start` ruft register vor Pipeline-Run | `JobExecutor.kt:96-98` | ✅ `if (!ActiveJobRegistry.register(...)) { return false }` vor `executor.submit { ... }` |
| `JobExecutor.start` ruft unregister im finally | `JobExecutor.kt:161-165` | ✅ `finally { ...; ActiveJobRegistry.unregister(...) }` |
| `SessionStatus.kt:6` KDoc sagt "Runtime state lives in Registry" | `SessionStatus.kt:1-10` | ✅ "Runtime state (TRANSCRIBING, PROCESSING) is NOT stored here — it lives in ActiveJobRegistry" → muss nach M4 angepasst werden |
| `findAllAudioFilePaths`, `findPendingInsertion`, `markInserted`, `deleteInsertedOlderThan` existieren nicht heute | `SessionDao.kt` (97 Zeilen, alle Methoden gelesen) | ✅ Alle 4 sind neu in Block 3 |
| `recordingsDir` heute = `getFilesDir()/recordings` | `DictateInputMethodService.java:1711, :2517` | ✅ `new File(getFilesDir(), "recordings")` |
| Heutiges Recording-Cache = `cacheDir` | `DictateInputMethodService.java:1706` (`audioFile.getAbsolutePath()`) | ✅ Recording-File in cacheDir/audio.m4a, persistFromCache wandert nach recordingsDir |
| `DurationHealingJob.heal(...)` heilt DB nicht File-System | `DurationHealingJob.kt:33-72` (heutiger Stand) | ✅ Pattern bestätigt |
| build.gradle hat KEINEN lint-Block | `app/build.gradle:1-93` | ✅ Nur compileOptions, kotlinOptions, buildFeatures, packagingOptions, testOptions |
| `room = "2.6.1"` im Catalog | `gradle/libs.versions.toml` | ✅ + alle drei test-libs (`ext-junit`, `espresso-core`) als Refs |

---

## Offene Fragen für nachfolgende Agents

### Für S-3 (Action-Hierarchie)
- Plan-§6.2 Z. 2789 dokumentiert jetzt explizit die Verzahnung `JobExecutor.register` (Lock-Producer, bleibt) vs. `Effect.PersistStatus(TRANSCRIBING)` (Status-Producer). S-3 sollte verifizieren, dass die `Action`-sealed-Hierarchie tatsächlich `PipelineAction.Submit` → `PipelineAction.RejectedJobAlreadyActive(sessionId)` als Rollback-Action enthält (Block-4-Pfad, wenn JobExecutor.start `false` returnt). Plan-Spec 2 §3.3 nennt heute `Action.PipelineAction.PersistenceError` — der `RejectedJobAlreadyActive`-Pfad ist im S-2-Fix neu eingeführt, sollte in die Action-Hierarchie aufgenommen werden.

### Für S-4 (Pipeline-Orchestrierung)
- Plan-Phase-B S-2 §6.3 RECORDING-Recovery sagt: das partial-written `cacheDir/audio.m4a` wird vom RECORDING-Branch nicht gelöscht — Cleanup läuft via `AudioFileFactory.cleanupOrphans` (Block 4) bzw. cacheDir-OS-Cleanup. S-4 sollte verifizieren, dass `AudioFileFactory.cleanupOrphans(referenced)` im Service-onCreate-Pfad (§7.3 Z. 3215-3225) tatsächlich VOR `PipelineRecovery.recover()` läuft oder parallel — sodass eine RECORDING-Row aus M4-Recovery (downgraded zu FAILED, audio_file_path bleibt NULL) den Pfad im cacheDir nicht als "referenced" ansieht. Edge-Case: ein RECORDING-Row mit `audio_file_path = NULL`, das während des Recovery-Promotes der Cleanup-Pfad bereits den File gelöscht hat — race-conditions zwischen den beiden parallelen Coroutines im Service-onCreate.

### Für S-5 (Service-Schicht)
- Plan-Phase-B S-2 §11.7.0a definiert das androidTest-Setup als eigener Sub-Schritt von Block 3. S-5 (Foreground-Service) hat eigene Robolectric-Tests in §11.2.3 (`DictatePipelineServiceTest.kt`) — die laufen aber als JVM-Tests in `app/src/test/`. S-5 sollte verifizieren, dass kein Robolectric-Test versehentlich nach `app/src/androidTest/` rutscht (würde Instrumented-Test-Anforderung erzeugen). Plus: der `DictatePipelineServiceCleanupOrderTest.kt` aus der neuen Acceptance-Klausel (Phase-B S-2 Cleanup-Reihenfolge) gehört in welches Source-Set? Robolectric (JVM, app/src/test/) ist günstiger als Instrumented — Plan sollte explizit das eine oder andere wählen.

### Für S-7 (Audio-File-Management)
- Plan-§6.3.1 (KG-SST-2) führt `findOrphanedTerminalAudio` + `cleanupOrphanedTerminalAudio` ein. Das Plan-Snippet im §6.3.1-Hauptblock verwendet `List<OrphanedAudioRow>` (DTO mit id + path), der KG-SST-2-RESOLVED-Block in §11.7.0 verwendet `List<String>` (nur Pfade) + "pragmatischere Variante: id + path". S-7 sollte die Inkonsistenz auflösen: welche Signatur ist die finale? Default-Empfehlung: §6.3.1-DTO-Variante mit `OrphanedAudioRow(id, audioFilePath)` — sauberer Layer-Trennung (DAO liefert Tupel, Service löscht File + bulk-DAO-Update via IDs).

### Für S-9 (ResetSuppressBit-Lifecycle)
- Plan-Phase-B S-2 §10 Block-3-Acceptance ergänzt um den Punkt "SessionStatus-KDoc-Update" (KDoc auf `SessionStatus.kt:6` muss die neue Doppel-Truth-Realität reflektieren). S-9 sollte beim Review von §15.2 RecordingModule prüfen, ob es einen analogen KDoc-Update-Bedarf für `RecordingState`-Klassen gibt — die heutige `RecordingState.kt`-Doku sagt vermutlich nichts zur DB-Persistenz, aber nach M4 ist der Zustand auch persistent.

---
