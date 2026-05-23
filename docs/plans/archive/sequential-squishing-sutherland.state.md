# State: sequential-squishing-sutherland

**Plan:** [→ sequential-squishing-sutherland.md](sequential-squishing-sutherland.md)
**Ziel:** Drei zusammenhängende Verbesserungen am Android-IME `Dictate`: (1) Single-Row-Layout via Long-Press-Toggle auf `edit_numbers_btn`, (2) persistenter + live-wirksamer Audio-Focus-Toggle als Direktzugriffs-Button, (3) Aufräumen toter/duplizierter State-Felder (`isPreparing`, Service-`audioFocusEnabled`).
**Gestartet:** 2026-05-05 18:50

## Chunks

| # | Chunk | Plan-Zeilen | Status | Agent-ID (Impl) | Agent-ID (Val) | Abgeschlossen |
|---|-------|-------------|--------|-----------------|----------------|---------------|
| 1 | Foundation: Strukturelle Vorbereitung + State-Cleanup (Block 0 + 3a + 3b) | 21–123, 309–341 | ✅ done | a0106b61636a216b9 | a13d2b5e28ecb6a32 | 2026-05-05 |
| 2 | Audio-Focus-Toggle + Live-Hook (Block 2 + 3c) | 206–306, 343–387, 419–429 | ✅ done | a2e8cca357a1c60a1 | ae244c099b524e48b | 2026-05-06 |
| 3 | Single-Row-Modus (Block 1) | 126–203, 457–467 | ⏳ pending | - | - | - |

## Issues

*(Wird während Implementation befüllt)*

## Pending Follow-ups (Reminder für spätere Chunks)

Diese kosmetischen Aufräum-Aufgaben wurden in Chunk 1 bewusst aufgeschoben, damit der Build zwischen Chunks stabil bleibt. Sie sind **keine Blocker**, müssen aber in Chunk 2/3 erledigt werden, damit am Ende kein toter / unvollständiger Code zurückbleibt.

### Für Chunk 2 (Audio-Focus-Toggle) — ✅ erledigt 2026-05-06
- ✅ **`MainButtonViews.editAudioFocusButton` + `MainButtonViews.audioFocusButton` von nullable auf non-null umgestellt.** XML-IDs `edit_audio_focus_btn` und `audio_focus_btn` existieren in `activity_dictate_keyboard_view.xml`; `?.let`-Wrappers in `MainButtonsController.kt` entfernt; `@JvmOverloads` aus `MainButtonViews` entfernt.
- ✅ **`onAudioFocusToggled` in `DictateInputMethodService.java` echt implementiert.** Reihenfolge: SP-Write → `recordingStateController.setAudioFocusRuntime(enabled)` → `mainButtonsController.refreshAudioFocusIcon(enabled)`. Kein `// TODO Chunk 2` mehr im Service.

### Für Chunk 3 (Single-Row-Modus)
- **`KeyboardViews`-Felder (`mainButtonsClTyped`, `actionRow`, `inputRow`, `recordPulseLayout`, `spaceButton`, `backspaceButton`, `enterButton`, `resendButton`) von nullable auf non-null umstellen**, sobald der Service alle 8 `findViewById`-Aufrufe macht. `@JvmOverloads` kann dann ggf. wieder weg, wenn alle Java-Caller die voll geladene Variante nutzen.
- **`KeyboardLayoutModeController` instanziieren** im Service (Block 0d sagt "neue Klasse" — Konstruktion fehlt aktuell). Die Klasse hat ein leeres `init {}` und no-op-Methoden — beim Wiring müssen `setSingleRowMode()` und `refresh()` mit echtem Verhalten gefüllt werden.
- **`@Suppress("unused")` auf `KeyboardLayoutModeController.rootView()`** entfernen, sobald die Methode echte Caller hat. Sonst bleibt der Suppress permanent und maskiert wirklichen toten Code in der Zukunft.
- **`applyVisibility()` → `layoutModeController.refresh()`-Bridge nachholen** (Plan-Block 0a fordert das, in Chunk 1 noch nicht umgesetzt, weil Controller nicht instanziiert).
- **Stub-Body von `onSingleRowModeToggled`** echt implementieren (TODO-Marker mit `// TODO Chunk 3` ist gesetzt).

## Notizen

- **Branch-Naming-Mismatch:** Branch heißt noch `feature/language-chip-curation` (vom vorigen Feature). Optional umbenennen, wenn der lokale Push-Status es erlaubt.
- **Chunking-Abweichung von Plan-Reihenfolge:** Plan-Reihenfolge ist 0 → 3a → 3b → 3c → 2 → 1 (sechs Schritte). Chunks fassen das auf 3 Stück zusammen, ohne die interne Reihenfolge zu verletzen — siehe Execution-Plan im Plan-File.
- **Validation-Outcome Chunk 1 (Agent a13d2b5e28ecb6a32, 2026-05-05):** 0 Blocker, 0 Important, 3 Nice-to-Haves (alle als Pending Follow-ups oben dokumentiert). Plan-Treue hoch, alle 5 Plan-Abweichungen durch Build-Stabilität gerechtfertigt. Empfehlung "Chunk 2 starten" angenommen.
- **Tooling-Limitation:** SendMessage-Tool für Agent-Resume ist im aktuellen Setup nicht verfügbar. Self-Check + Validation wurden zu einem einzigen Validation-Schritt mit kritischem Review-Fokus zusammengelegt — externe Perspektive bleibt erhalten, Same-Agent-Resume entfällt.
- **Validation-Outcome Chunk 2 (Agent ae244c099b524e48b, 2026-05-06):** 0 Blocker, 0 Important, 4 Nice-to-Haves. Plan-Treue hoch. Listener-Reentrance via SP-Write→Listener→onAudioFocusToggled-Steps-2-3 ist benign (Idempotenz beider Methoden). Empfehlung "Chunk 3 starten" angenommen.
- **Optionale Cosmetic-Cleanups (nicht blockend, ggf. in Chunk 3 oder Post-Implementation aufzuräumen):**
  1. `BluetoothScoManager`: `open`-Modifier auf den 4 Methoden `registerReceiver`, `reconnect`, `unregisterReceiver`, `hasBluetoothInputDevice` entfernen — keine Test-Subklasse überschreibt sie (`FakeBluetoothScoControl` implementiert das Interface direkt). Class-level `open` und Interface-`override`-Methoden bleiben.
  2. `PauseTimeoutScheduler` ggf. in eigene Datei extrahieren (~30 LOC, grep-ability).
- **Chunk 2 — Plan-Abweichungen:**
  1. **Neuer `PauseTimeoutScheduler`-Interface + `HandlerPauseTimeoutScheduler` in `RecordingStateController.kt`.** Plan kennt nur den `Handler`-Konstruktor. Begründung: `Handler(Looper.getMainLooper())` schlägt im JVM-Unit-Test fehl (kein Looper). Test-Seam ist die kleinste Änderung, die K-1 (handgeschriebene Fakes, kein Mockito) und K-4 (kein Android Context im Unit-Test) erfüllt. Sekundärer Konstruktor `(gate, amplitude, mainHandler)` bleibt für die produktive Java-Verdrahtung.
  2. **`RecordingManager` und `BluetoothScoManager` als `open class` markiert + ausgewählte Methoden `open`.** Plan kennt sie als `class`. Begründung: für den Test-Subklassen-Override (siehe `FakeRecordingManager`). Produktiv kein Verhalten geändert.
  3. **Neuer `BluetoothScoControl`-Interface in `BluetoothScoManager.kt`; Controller hält jetzt `BluetoothScoControl` statt `BluetoothScoManager`.** Plan kennt nur den konkreten Manager-Typ. Begründung: der konkrete `BluetoothScoManager`-Konstruktor braucht Context + AudioManager + main-Thread-Looper — nicht im JVM-Unit-Test verfügbar. Interface-Extraktion folgt dem Pattern, das `AudioFocusGate` in Chunk 1 etabliert hat. Das `setManagers(...)`-Signature nimmt jetzt `BluetoothScoControl`; Java-Service übergibt weiter den konkreten Manager (Subtype-passend, kein Service-Diff). Receiver-Lifecycle (`registerReceiver`/`unregisterReceiver`) bleibt am konkreten Typ — gehört zur Service-Verdrahtung, nicht zur Aufnahme-State-Machine.
