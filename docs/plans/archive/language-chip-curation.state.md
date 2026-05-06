# State: language-chip-curation

**Plan:** [→ language-chip-curation.md](language-chip-curation.md)
**Chunks-Datei:** [→ language-chip-curation.chunks.json](language-chip-curation.chunks.json)
**Branch:** `feature/language-chip-curation`
**Vorab-Commit auf main:** `0d8eb73` (PopupMenu-Pattern für Sprach-Picker, BadTokenException-Fix)
**Ziel:** Always-visible Language-Chip in der Prompts-Leiste mit Dual-Mode-Schreibverhalten (permanent im Normalmodus, temporär in ReprocessStaging), Kuration der Sprach-Liste aus dem Keyboard heraus über gruppierte PopupMenu, plus Portierung des Versioned-Envelope-Storage-Systems aus dem excel_ekl-Projekt für zukunftssichere Pref-Migrationen. Zusätzlich: Bugfix für den Resend-Button (kurzer Klick → Insert schlägt bei COMPLETED-Status fehl) via robuster InputConnection-Capture mit 3-stufigem Fallback.
**Gestartet:** 2026-04-27

## Chunks

Reihenfolge folgt Plan-Empfehlung: Phase 5 zuerst (scope-isoliert, schneller User-Wert), dann Phase 0-4 als zusammenhängende Sprach-Iteration.

| # | Chunk | Main-Sektionen | ~Tokens | Status | Agent-ID (Impl) | Agent-ID (Val) | Abgeschlossen |
|---|-------|---------------|---------|--------|-----------------|----------------|---------------|
| 1 | Phase 5 — Resend-Button Bugfix + Strategie-Briefing | Execution Plan, Context, Design-Prinzipien, Phase 5, Dateien, Edge Cases, Offene Punkte | ~12.5k | ✅ | ac3a9c6c11ce5484b | a52f2a522f5a21721 | 2026-04-27 |
| 2 | Phase 0 — Versioned-Envelope-Foundation | Phase 0 | ~11.7k | ✅ | a29257a29d59905f3 | aad8cec27e600d762 | 2026-04-27 |
| 3 | Phase 1 — LanguageController + Plugin + Cross-Phase Refactor | Phase 1 | ~14.5k | ✅ | ab0ffadb3c8293856 | a39ecb5c0cd313bdd | 2026-04-27 |
| 4 | Phase 2 — Always-visible Chip + Gruppierte PopupMenu | Phase 2 | ~12.2k | ✅ | a772e68696295894f | a8653e852c543debb | 2026-04-27 |
| 5 | Phase 3 — Settings-UI-Anpassung | Phase 3 | ~9.6k | ✅ | a62d597fa0eb23480 | ac3456aad3c5a34ec | 2026-04-27 |
| 6 | Phase 4 — Tests & Validation (Wrap-up) | Phase 4, Aufwand | ~10.2k | ✅ | a16b658cd5dde7f94 | (kein Validation — Audit-Chunk) | 2026-04-27 |

## Issues

### Chunk 1: Phase 5 — Resend-Button Bugfix
- **Behoben (Wichtig):** W-1 KDoc für `commitSlowOutput` IC-Capture-Semantik dokumentiert
- **Behoben (Wichtig — UX-Bug):** W-2 `commitTextToInputConnection` um `enableAutoEnter`-Boolean erweitert (6-arg). Backward-compat-Wrapper default `true`; Resend-Committer (Stage 1+2) gated auf `false`. Verhinderte falsche Enter-Auslieferung an gewechselten Editor.
- **Behoben (Wichtig — Naming):** W-3 `OnResendClickedTest.kt` → `ResendStatusDispatcherTest.kt` umbenannt; inner class entsprechend.
- **Ignoriert:** N-1 bis N-5 (5 Nice-to-Haves) — alle nicht load-bearing.
- **Skalations-Entscheidung:** `ResendInsertStrategy` + `ResendStatusDispatcher` als separate Stateless Kotlin Objects mit Sealed-Class-Outcomes (über Plan hinaus). Vom Validator gerechtfertigt: ermöglicht JVM-Tests ohne Mockito/Robolectric.
- **Verbleibende Risiken:** Verhaltensänderung — Resend-Klick triggert nicht mehr Auto-Enter (war vorher buggy). Im Release-Commit-Message dokumentieren.

### Chunk 2: Phase 0 — Versioned-Envelope-Foundation
- **Behoben (Wichtig):** #1 Test-Doku-Korrektur "JSONTokener throws on empty string" (war "handles gracefully")
- **Behoben (Wichtig):** #2 KDoc-Hinweis für BigDecimal-Edge-Case in `isVersionedEnvelope`
- **Behoben (Wichtig):** #3 Sinnloser try/catch um `VersionedMigrator.migrate` entfernt (no-op semantics)
- **Behoben (Nice-to-Have):** #4 Toter `to`-Parameter in `resetToDefault` entfernt; 3 Call-Sites aktualisiert
- **Behoben (Nice-to-Have):** #5 Inline-Kommentar `opt` vs `get` in VersionedSerializer
- **Behoben (Nice-to-Have, Bonus):** #6 IntListCodecTest hinzugefügt (Test-Symmetrie zu StringListCodecTest, 7 neue Tests)
- **Behoben (Nice-to-Have):** #7 `VersionedPluginRegistry`: `mutableMapOf` → `ConcurrentHashMap` + Threading-KDoc
- **Ignoriert:** #8 FakeSharedPreferences hochziehen (Phase-1-Setup, kommt in Chunk 3); #9 save() Boolean async-truth (KDoc dokumentiert das ausreichend)
- **Tests:** 100 Tests total (93 vor Bonus + 7 IntListCodecTest), alle grün
- **Plan-treuer als TS-Vorlage:** W-8 future-version respektiert Plugin-Strategie statt hart-throw (App-Downgrade-Crash-Schutz; gegenüber excel_ekl-Original eine bewusste Verbesserung)

### Chunk 3: Phase 1 — LanguageController + Plugin + Cross-Phase Refactor
- **Behoben (Wichtig):** W1 Plugin auto-registration via `init {}`-Block entfernt; explizite `register()`-Aufrufe in DictateApplication + Tests
- **Behoben (Wichtig):** W2 Init-Throws-Test via Reflection (lateinit Field auf null + try/finally-Restore) — kein class-Refactor nötig
- **Behoben (Wichtig):** W3 `persistCuratedAndPos`-Code-Duplikation aufgelöst — `internal fun persistInputLanguagesAndPos` als Top-level in `InputLanguagesPlugin.kt`, beide Aufrufer (LanguageController + LegacyMigration) nutzen ihn
- **Behoben (Wichtig — Zeitbombe für Phase 2):** W4 Init-Reihenfolge in `onCreateInputView` swapped — languageController vor servicePipelineCallback (Quality-Gate W-12)
- **Behoben (Nice-to-Have):** N1 Stale KDoc auf `setCallback` korrigiert; N2 `IDLE_ONLY_READER` → `NO_OP_PIPELINE_READER` mit klarer Doku; N3 Log-Warning für unbekannte Codes; N5 Dead assertion entfernt
- **Skipped (Nice-to-Have):** N4 `@MainThread`-Annotation — Projekt nutzt das Pattern nirgends
- **Tests:** 148 Tests total (101 vor Chunk 3 + 47 neu), alle grün
- **Plan-Abweichungen:** 3 dokumentiert vom Implementation-Agent, alle vom Validator als gerechtfertigt eingestuft (`resetForTest` skippen, `IDLE_ONLY_READER`, Plugin-Touch — letzteres durch W1-Fix obsolet geworden)

### Chunk 4: Phase 2 — Always-visible Chip + Gruppierte PopupMenu
- **Behoben (Wichtig):** Issue #1 Group-Divider Edge-Case — `setGroupDividerEnabled(true)` jetzt nur bei `!othersOrdered.isEmpty()`
- **Behoben (Wichtig):** Issue #2 `onLanguageCycled` ReprocessStaging-Drift — early-return mit Plan-Konformitäts-Kommentar
- **Behoben (Nice-to-Have):** #4 Hard-coded "detect" durch `InputLanguagesPlugin.INSTANCE.getDefaultValue().get(0)` ersetzt; #5 MENU_ID_OFFSET Erklärungs-Kommentar
- **Skipped (Nice-to-Have):** #3 Init-Order Race (Validator: "nicht blockierend"); #6 postDelayed Closure (vorhandener Null-Guard genügt)
- **Plan-Abweichungen vom Implementation-Agent (alle Validator-bestätigt):** `mainButtonsController.updateRecordButtonText` neu, defensive null-guard in getDictateButtonText, `Menu.NONE` statt undefiniertem `MENU_ID_INVALID`, alle `setLanguageChipVisible(false, null)` entfernt, Service-Sprach-Felder komplett weg
- **Tests:** 148 Tests stable (keine neuen — Phase 2 ist UI-Wiring, Tests in Phase 4)
- **Build:** PASS

### Chunk 5: Phase 3 — Settings-UI-Anpassung
- **Behoben (Wichtig):** Issue #1 Stale-Chip im IME nach Settings-Save — `LanguageController.refreshFromPrefs()` Public-Method + `OnSharedPreferenceChangeListener` im IME registriert auf `input_languages`-Key + `input_language_pos`-Key. Cross-Controller-Sync per Listener-Bridge.
- **Behoben (Nice-to-Have):** Issue #3 SummaryProvider race condition — `setSummaryProvider` jetzt VOR `setValues` (1-Zeilen-Umstellung).
- **Skipped (Nice-to-Have):** #2 Pos-Mirror-Computation (plan-konform, validator-bestätigt); #4 Set-Reihenfolge (pre-existing); #5 FRAGMENT_ARG_SCROLL_TO Literal (pre-existing).
- **Plan-Abweichung vom Implementation-Agent:** `controller.getCuratedLanguages()` statt `VersionedPrefs.load()` direkt — vermeidet Java-Wildcard-Inferenz, behält K-6 Single-Source-of-Truth-Spirit, Validator: "besser als der Plan-Vorschlag".
- **Tests:** 150 Tests total (148 + 2 neue `refreshFromPrefs`-Tests), alle grün
- **Build:** PASS
- **Bekannte Limitation (Follow-up):** Der `inputLanguagesListener` wird nur in `cleanupOldControllers()` deregistriert (View-Recreate-Pfad). Bei direktem `onDestroy()` ohne Recreate leakt der Listener auf `sp` — kleines Risiko, da Process-Lifetime begrenzt. 1-Zeilen-Fix in `onDestroy()` als TODO dokumentiert.

### Chunk 6: Phase 4 — Tests & Validation (Wrap-up)
- **Test-Inventory:** Alle Plan-§4.1-Cases abgedeckt, keine Gaps. Total **155 Tests grün** über 17 Test-Files.
- **Neuer Test:** `MultiCallbackForwardingTest.kt` (5 Tests) — Quality-Gate K-2 Multi-Dispatch-Forwarding-Kette.
- **Listener-Leak-Fix:** `onDestroy()` deregistriert jetzt `inputLanguagesListener` und ruft `languageController.dispose()`. Schließt das Follow-up aus Chunk 5.
- **Verification-Checklist erstellt:** `language-chip-curation.verification.md` mit ~70 Items über 12 Sektionen für die manuelle Device-Verifikation.
- **Cross-Phase-Smoke-Verifikation:** Phase 5 6-arg `commitTextToInputConnection` ↔ Phase 2 Aufrufer konsistent. `LanguageController`-Wiring + Listener synchron mit View-Lifecycle. Resend-Strategy Stages 1/2 konsistent. **Keine Inkonsistenzen.**
- **Build:** PASS
- **27 Quality-Gate-Findings:** Alle abgehakt über die 6 Chunks verteilt.

---

## Final-Status

**Branch:** `feature/language-chip-curation`
**Vorab-Commit auf main:** `0d8eb73` (PopupMenu-BadTokenException-Fix)
**Implementation-Status:** ✅ Alle 6 Chunks abgeschlossen
**Tests:** 155 grün (von ursprünglich 32 Baseline → +123 neue Tests)
**Build:** PASS
**Manual-Verification:** Ausstehend (Verification-Checklist auf Android-Device)

**Bekannte Follow-ups (nicht-blockierend):**
1. Rollback nach App-Downgrade → `ClassCastException` (akzeptiert)
2. Pos-Drift bei Locale-Änderung → Schema-V2 (`CurrentLanguageCode: Pref<String>`)
3. `isSameEditor` als Heuristik dokumentiert (Stage-2-Fallback fängt das auf)
4. FAILED-Status User-Education-Toast (Out-of-Scope)
5. `LanguageRepository`-Interface (W-5 optional)
6. Backup-Strategie (W-8) für `VersionedPrefs.load`-Self-Heal — Insertion-Punkt dokumentiert
