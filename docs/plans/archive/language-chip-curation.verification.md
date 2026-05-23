# End-to-End Verification Checklist — Language-Chip Curation + Versioned Storage + Resend-Bugfix

**Plan:** [`language-chip-curation.md`](./language-chip-curation.md)
**State:** [`language-chip-curation.state.md`](./language-chip-curation.state.md)
**Erstellt:** 2026-04-27 (Phase 4 wrap-up)
**Reviewer:** _Name eintragen_
**Build:** _APK-Hash / Tag eintragen_
**Test-Geräte:** _Modell + Android-Version eintragen_

Diese Datei begleitet den manuellen End-to-End-Test der drei zusammen ausgelieferten
Features (Language-Chip + Kuration, Versioned Storage, Phase-5 Resend-Bugfix).
Die Items spiegeln Plan §4.2 + §5.7 + die Validation-Findings aus den
Implementation-Chunks wider.

Jede Zeile ist eine Akzeptanz-Bedingung; abhaken (`[x]`) wenn auf einem realen
Gerät reproduziert. Items, die situativ nicht prüfbar sind (z.B. API-Version
nicht verfügbar) → mit `n/a — <Grund>` kommentieren statt überspringen.

---

## 1. Fresh Install

- [ ] `defaultValue` (`["detect", "en"]`) ist im Picker sichtbar
- [ ] Chip zeigt "Auto-Detect" (oder die entsprechende Lokalisierung)
- [ ] Record-Button-Label passt zur effektiven Sprache
- [ ] Kein Crash beim ersten Öffnen des IME
- [ ] Logcat: kein `WTF` / `FATAL` während `DictateApplication.onCreate()`

---

## 2. Upgrade von alter App-Version (Legacy-StringSet → Envelope)

> Voraussetzung: vorherige Build-Version mit StringSet-Persistierung war installiert
> und hat eine kuratierte Liste angelegt (z.B. `["de", "en", "fr"]`).

- [ ] Beim ersten Start nach Update: Logcat zeigt `Migrated N languages from StringSet to versioned list`
- [ ] Nach Migration: Picker-Inhalt entspricht dem alten StringSet (Reihenfolge label-sortiert)
- [ ] `Pref.InputLanguagePos` zeigt nach Migration auf dieselbe Sprache wie vor dem Update (Pos-Erhaltung)
- [ ] Re-Start der App: Migration läuft NICHT erneut (Idempotenz, Logcat zeigt keine zweite "Migrated"-Zeile)
- [ ] SharedPreferences-XML inspizieren (`adb shell run-as net.devemperor.dictate cat …shared_prefs/net.devemperor.dictate_preferences.xml`): Key `input_languages` ist jetzt ein `<string>` (Envelope-JSON), nicht mehr `<set>`

---

## 3. Always-visible Chip

- [ ] Idle-State: Chip ist sichtbar
- [ ] Recording-State: Chip ist sichtbar, klickbar
- [ ] Pipeline Running: Chip ist sichtbar, **disabled** (greyed out / nicht klickbar)
- [ ] Pipeline Preparing: Chip ist sichtbar, disabled
- [ ] ReprocessStaging: Chip ist sichtbar, zeigt Override-Label
- [ ] Nach ReprocessStaging-Exit: Chip zeigt wieder das Permanent-Label
- [ ] Während Pipeline-Wechsel (Idle → Running → Idle) bleibt Chip-Position stabil (kein Layout-Jitter)

---

## 4. PopupMenu — Gruppierte Liste

- [ ] Oberer Block: kuratierte Sprachen, alphabetisch nach **Display-Label** sortiert (nicht nach ISO-Code)
- [ ] Unterer Block: alle anderen Sprachen, alphabetisch nach Display-Label sortiert
- [ ] Letzter Eintrag: "⚙ Sprachen verwalten…"
- [ ] **API 28+ (Android 9+):** Visueller Trenner (horizontale Linie) zwischen oberem und unterem Block
- [ ] **API 26/27 (Android 8.x):** Disabled Label-Item "────── andere Sprachen ──────" als Trenner-Fallback
- [ ] Auswahl aus oberem Block in **Idle**: permanenter Wechsel
- [ ] Auswahl aus oberem Block in **ReprocessStaging**: nur transient, Liste unverändert
- [ ] Auswahl aus unterem Block in **Idle**: permanent — Sprache wandert beim nächsten Öffnen nach oben (Auto-Curation)
- [ ] **(Quality-Gate N-5)** Auswahl aus unterem Block in **ReprocessStaging**: nur transient, kuratierte Liste **bleibt unverändert** — beim nächsten Idle-Picker-Öffnen erneut im unteren Block
- [ ] Trenner-Item selbst ist nicht klickbar (kein Toast, kein State-Wechsel)
- [ ] "Sprachen verwalten…" öffnet die Settings-Activity
- [ ] **(Quality-Gate K-7)** "Sprachen verwalten…" scrollt im Settings direkt zur Sprach-Auswahl, sodass sie sichtbar ist ohne manuelles Scrollen

---

## 5. Settings-UI

- [ ] MultiSelectListPreference zeigt aktuell kuratierte Auswahl korrekt (Häkchen an den richtigen Sprachen)
- [ ] Änderung in Settings → zurück zum Keyboard: PopupMenu reflektiert neue Auswahl beim Öffnen
- [ ] **(Chunk-5 Stale-Chip-Verification)** Sprache in Settings ändern, dann zum Keyboard zurück: Chip-Label aktualisiert sich **ohne** dass eine Pipeline gestartet werden muss (Listener-Bridge greift)
- [ ] Setzen einer leeren Auswahl → Toast-Warning (`dictate_input_languages_empty`), keine Persistierung
- [ ] **(Quality-Gate K-5)** SharedPreferences-XML nach Settings-Save: Key `input_languages` ist weiterhin Envelope-`<string>`, kein `<set>` (kein StringSet-Override)
- [ ] **(Edge-Case Pos-Drift)** Settings: aktuelle Sprache ent-haken (z.B. Pos zeigte auf "Französisch", User entfernt FR) → Pos springt auf 0, neue erste Sprache wird aktiv

---

## 6. Dual-Mode-Schreibverhalten

- [ ] Chip-Click in **Idle** → permanenter Wechsel (überlebt App-Neustart)
- [ ] Chip-Click in **ReprocessStaging** → nur transient
- [ ] Nach ReprocessStaging-Exit (Send oder Cancel): permanente Sprache **unverändert**
- [ ] Long-Press-Cycle ändert permanent (siehe §9)

---

## 7. Sanitization / Self-Heal

- [ ] Manuell in `adb shell` einen ungültigen ISO-Code ins Prefs-XML schreiben (z.B. `["xx", "en"]`) → nächster App-Start filtert `xx` raus, kein Crash
- [ ] Prefs-XML mit leerer Liste → Default-Werte (`["detect", "en"]`) werden geladen, kein Crash
- [ ] Prefs-XML mit malformed JSON (z.B. `{not valid json`) → `OnMissingMigration.RESET_TO_DEFAULT` greift, Default-Werte geladen
- [ ] Self-Heal-Write-Amplification: Chip-Click ohne Änderung der Liste → keine zusätzlichen XML-Writes (per `adb shell ls -la …shared_prefs/` Mtime kontrollieren)

---

## 8. Cycle-Button (Long-Press Record)

- [ ] Long-Press auf Record cycled durch die **kuratierte Liste** (nicht mehr durch Legacy-StringSet)
- [ ] Cycle-Action feuert `languageController.setLanguage(nextInCurated)` → Chip-Label updated synchron
- [ ] Reihenfolge im Cycle entspricht alphabetischer Label-Sortierung (konsistent mit PopupMenu oberer Block)
- [ ] Cycle in **ReprocessStaging**: wirkt nur transient (analog zum Chip-Click)

---

## 9. Phase 5 — Resend-Button-Bugfix

### 9.1 Bug-Reproduzieren-vor-Fix (nur falls auf alter Build verfügbar)

- [ ] Mit alter Build-Version: kurze Aufnahme → Pipeline läuft → kurzer Klick auf Resend → KEIN Insert (bestätigt Bug — wird durch Fix behoben)

### 9.2 Status-Matrix

- [ ] **COMPLETED + Klick OHNE Fokus-Wechsel** → Insert klappt (Stufe 1 — live IC)
- [ ] **COMPLETED + nach Klick auf anderes Feld klicken** → Insert klappt im **ursprünglichen** Feld (Stufe 2 — Capture-IC)
- [ ] **COMPLETED + Ziel-App schließen während Klick** → Toast `dictate_resend_focus_lost` erscheint, Resume läuft (Stufe 3)
- [ ] **CANCELLED + Output da** → Insert klappt (Stufe 1 oder 2 je nach Fokus)
- [ ] **CANCELLED + kein Output** → Resume läuft (unverändertes Verhalten)
- [ ] **RECORDED** → Resume läuft (unverändertes Verhalten)
- [ ] **FAILED** → KEINE Aktion, kein Resume, kein Toast (neue Verhaltensweise — Risiko F)
- [ ] **FAILED + Long-Press** → ReprocessStaging öffnet (unverändert, alter Pfad)

### 9.3 Side-Effects nach Stufe 2

- [ ] Auto-Enter-Wirkung nach Stufe-1-Insert: Enter wird ausgeführt
- [ ] Auto-Enter NACH Stufe-2-Insert: **NICHT** ausgeführt (Stufe 2 setzt `enableAutoEnter=false`)
- [ ] DB-Log enthält Insertion auch nach Stufe 2 (Audit bleibt vollständig, korrekt an `lastSession.getId()` gebunden via `sessionIdOverride`)
- [ ] **(Quality-Gate K-8)** `Pref.InstantOutput=false` (Slow-Output) aktiv, COMPLETED + Klick mit Editor-Wechsel (forciert Stufe 2) → Char-by-Char-Animation läuft auch hier (UX konsistent zur Stufe 1)

### 9.4 Race-Conditions

- [ ] **(Quality-Gate N-2)** Doppel-Klick auf Resend innerhalb 500ms → genau ein Insert (Re-Enable nach Cooldown verhindert Race; Button visuell disabled in der Zwischenzeit)
- [ ] **(Edge-Case captured EditorInfo null)** App im Hintergrund während Klick → kein Crash, fällt sauber in Stufe 3

---

## 10. Cross-Phase / Lifecycle

- [ ] **(Phase 4 follow-up)** App über Settings/Apps killen, dann erneut öffnen → kein Logcat-Warning über leaked `OnSharedPreferenceChangeListener` oder leaked Callback (`onDestroy`-Cleanup greift)
- [ ] View-Recreate (Configuration-Change, z.B. Theme-Wechsel oder Rotation): Chip-Label korrekt nach Wiederaufbau, kein Doppel-Callback
- [ ] PopupMenu öffnen + sofort Configuration-Change → kein `BadTokenException`-Crash (IME-safe Anchor-Token)
- [ ] **Threading:** Worker-Thread-Lese-Pfad (z.B. `PipelineOrchestrator` aus `dbExecutor`) liest `VersionedPrefs.load(prefs, InputLanguagesPlugin)` direkt — kein Crash (read-thread-safe)

---

## 11. Regression-Tests (automatisch)

- [ ] Reprocess-Pipeline funktioniert nach Language-Change während Staging (`JobRequest.language = override`)
- [ ] Build erfolgreich (`./gradlew assembleDebug`)
- [ ] Alle Unit-Tests grün (`./gradlew testDebugUnitTest`)
- [ ] Keine `BadTokenException` beim PopupMenu (IME-safe; bereits im Vor-Commit `0d8eb73` adressiert)

---

## 12. Bekannte Limitations / nicht zu testen

> Diese Punkte sind **bewusst nicht** Teil der Akzeptanz, dokumentiert für Reviewer:

- **Rollback nach App-Downgrade:** Wechsel zurück auf Pre-Migration-Build wirft `ClassCastException`. Akzeptiert (Edge Cases §1).
- **Pos-Drift bei Locale-Änderung:** Wenn in zukünftiger Version Display-Labels neu lokalisiert werden, kann sich Pos auf eine andere Sprache verschieben. Inhärentes Schema-Risiko (Risiko H). Follow-up: `CurrentLanguageCode: Pref<String>` als Schema-V2.
- **`isSameEditor` ist Heuristik:** Auf Geräten mit `fieldId == 0` greift Stufe 1 nie, fällt direkt in Stufe 2 (kein Bug, dokumentiert).
- **FAILED-User-Education:** Out-of-Scope-Toast "FAILED erfordert jetzt Long-Press → ReprocessStaging" als Follow-up.

---

**Sign-off:**

```
Tester:  __________________
Datum:   __________________
Build:   __________________
Status:  [ ] PASS   [ ] PASS-mit-Anmerkungen   [ ] FAIL
Anmerkungen:
```
