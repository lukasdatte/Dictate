# State: QWERTZ UI Integration Fix

**Plan:** [→ qwertz-ui-integration-fix.md](qwertz-ui-integration-fix.md)
**Ziel:** 4 UI-Integrationsprobleme des QWERTZ-Keyboards fixen: Buttons verdecken, Close-Button, Recording-Indikator, Pipeline-Progress.
**Gestartet:** 2026-03-12 15:00

## Chunks

| # | Chunk | Zeilen | Status | Agent-ID (Impl) | Agent-ID (Val) | Abgeschlossen |
|---|-------|--------|--------|-----------------|----------------|---------------|
| 1 | Komplette UI Integration | 21-107 | ✅ | a3e0f30399eaa1d45 | a586ae579cea0f995 | 2026-03-12 |

## Issues

### Chunk 1: Komplette UI Integration
- **Behoben:** `infoCl` Visibility nicht wiederhergestellt in `hideQwertzKeyboard()` (Wichtig)
- **Behoben:** Hardcoded "Aufnahme..." String → String-Resource (Nice-to-Have)
- **Ignoriert:** Recording-View bleibt im Layout (konsistentes Android-Pattern)
- **Ignoriert:** `overlayCharactersLl` nicht wiederhergestellt (False Positive — temporäres Overlay)
- **Ignoriert:** Accessibility Strings hardcoded (konsistent mit bestehendem Pattern)
