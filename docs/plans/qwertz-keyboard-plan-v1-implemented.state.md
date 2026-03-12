# State: QWERTZ Keyboard

**Plan:** [→ qwertz-keyboard-plan.md](qwertz-keyboard-plan.md)
**Ziel:** SwiftKey-artiges QWERTZ-Keyboard mit 3 Layout-Modi, Shift/Caps-Lock State-Machine, Ctrl-Modifier, deutschen Umlauten, accelerierendem Backspace und Cursor-Swipe. Ersetzt das bestehende Numpad-Overlay.
**Gestartet:** 2026-03-12 15:30

## Chunks

| # | Chunk | Tasks | Status | Agent-ID (Impl) | Agent-ID (Val) | Abgeschlossen |
|---|-------|-------|--------|-----------------|----------------|---------------|
| 1 | Datenmodell + Utility-Handler | A1, A3, A4, A5 | ✅ | ad46074137b3279b8 | aed5d36922971a694 | 2026-03-12 15:45 |
| 2 | Layout-Definitionen + Ressourcen | A2, B2, B3 | ✅ | af1a7285c6023c1ba | ae5f21bef27d1dec3 | 2026-03-12 16:15 |
| 3 | View-Schicht | B1 | ✅ | a0aa3a659cac88391 | a52cade8670fa9a79 | 2026-03-12 16:45 |
| 4 | Controller + State-Machine | C1 | ✅ | a37e8187b4bebfbf4 | abbc0503b82a80353 | 2026-03-12 17:15 |
| 5 | Integration in Service + XML | D1, D2a-d | ✅ | a3e2c6cc4ee1c3d95 | a78b4ac65dbab0625 | 2026-03-12 17:50 |
| 6 | Feinschliff + Edge Cases | D3 | ✅ | ac62ad430b99ba583 | acf6b916590e8f962 | 2026-03-12 18:20 |

## Issues

### Chunk 1: Datenmodell + Utility-Handler
- **Behoben:** Handler nicht injizierbar in AcceleratingRepeatHandler (Wichtig)
- **Behoben:** onTouch return value in CursorSwipeTouchHandler – jetzt konfigurierbar (Wichtig)
- **Ignoriert:** var vs val bei KeyPressAnimator – pragmatischer (Nice-to-Have)
- **Ignoriert:** onSwipeStateChanged Callback-Timing – Original-Verhalten (Nice-to-Have)
- **Ignoriert:** Zusätzliche Constructor-Parameter – sinnvolle Erweiterungen (Nice-to-Have)

### Chunk 4: Controller + State-Machine
- **Behoben:** Cursor-Movement commitText("", 1) statt ("", 2) für Rechtsbewegung (Kritisch)
- **Behoben:** Layout-Switch Label-Parsing vereinfacht mit Konstanten (Wichtig)
- **Ignoriert:** Ctrl-Visual liest Farben indirekt – kein funktionaler Bug (Nice-to-Have)
- **Ignoriert:** SuppressLint – konsistent mit Projekt-Stil (Nice-to-Have)
