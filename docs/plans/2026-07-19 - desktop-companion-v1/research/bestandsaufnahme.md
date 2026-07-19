# Bestandsaufnahme — Desktop-/Browser-Konzept (Recherche-Phase)

Stand: 2026-07-18, Branch `main` (048fb37). Recherche über CLAUDE.md, alle 27 ADRs,
`ai/`, `state/`, `core/`, `windows/`, `database/`, `history/`, `rewording/`,
`preferences/`, `widget/`, `audio/` sowie `shared/` + `companion/`.

## 1. Die wichtigste Vorab-Erkenntnis: Das Monorepo existiert bereits

Das Repo ist kein reines Android-Projekt mehr, sondern ein **3-Modul-Monorepo**
(`settings.gradle`, ADR-0015):

| Modul | Technologie | Rolle |
|---|---|---|
| `:app` | Android (Java/Kotlin), minSdk 26 | IME, Aufnahme, AI-Pipeline, State-Machine, gesamte UI |
| `:shared` | **pure `kotlin("jvm")`**, jvmTarget 1.8, Android-/Ktor-/Coroutine-frei (per `SharedPurityTest` erzwungen) | Wire-Protocol: `@Serializable`-DTOs + Konform-Validierung, `ProtocolCodec` (einzige Codec-Tür), `DispatchClient`/`SyncClient`, Pairing-Secrets |
| `:companion` | Compose Multiplatform Desktop (JVM 17), Ktor-CIO-Server, SQLDelight, JNA | Desktop-Empfänger: Tray-App, Pairing, History-Archiv, Win32-Text-Insertion, semantische Input-Commands |

Zentrale Pfade:
- `/home/lukas/WebStorm/Dictate/shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/{Dtos,Endpoints,ProtocolCodec,Validations,ProtocolVersion}.kt`
- `/home/lukas/WebStorm/Dictate/companion/src/main/kotlin/net/devemperor/dictate/companion/{Main,CompanionBootstrap,CompanionContainer}.kt`
- `/home/lukas/WebStorm/Dictate/companion/.../server/CompanionServer.kt`, `.../domain/{DispatchService,InputCommandService,SyncService,AuthService,PairingService,HealthService}.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/windows/{WindowsDispatchCoordinator,PcInputCoordinator,PcInputCommandMapper,AndroidSyncSource}.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PcDictationActivity.kt`

**Merge-Stand:** `feature/companion-bind-address` und `feature/pc-dictation-view`
(Branch `pc-dictation-audit-fixes`) sind **vollständig in main** (beide Tips sind
Ancestors von main, `git cherry` leer). In diesen Worktrees liegt nichts Offenes.

## 2. Was der Companion heute kann — und was NICHT

**Kann:** Ktor-Server (Default-Port 8756, Bind-Katalog Tailscale/LAN/Loopback per
ADR-0023, nie `0.0.0.0`), Pairing (One-Time-Token 120 s, QR + manuell, Device-Secret
nur als SHA-256-Hash gespeichert), Text-Empfang `POST /v1/dispatch` → Clipboard +
`SendInput(Ctrl+V)` via JNA (`Win32TextInserter`, UIPI-Degradation → `CLIPBOARD_ONLY`),
semantische Input-Commands `POST /v1/input` (TYPE_TEXT, CURSOR_*, UNDO/REDO …, nie
VK-Codes; Chord-Auflösung Companion-seitig in `key_command_chords`), lazy History-Sync
vom Phone (`POST /v1/sync`, Cursor-basiert, idempotent, ADR-0020), Compose-UI mit drei
Screens (History mit „insert again", Devices/Pairing, Settings mit Bind-Adresse/
Chords/Autostart), Tray + SingleInstanceGuard + `--minimized`-Autostart.

**Kann NICHT (Lücken relativ zu den Desktop-Anforderungen):**
- **Keine Audio-Aufnahme** — der Companion ist reiner Text-Empfänger; Aufnahme + AI
  laufen heute ausschließlich auf dem Phone.
- **Keine AI-Pipeline** — kein Runner, kein Orchestrator, keine Keys auf dem Desktop.
- **Kein globaler Hotkey** — der Companion horcht nicht auf Tastatur (grep leer).
- **Kein Recording-/Overlay-UI** — nur Verwaltungsoberfläche.
- **Keine Prompts/Modelle/Presets** — Prompt-Verwaltung existiert nur in `:app`.
- Companion-History ist ein flaches `received_texts`-Archiv, nicht das reiche
  Session-Modell (`sessions`/`transcriptions`/`processing_steps`) der App.

**PC-Diktier-Modus heute (ADR-0027):** `PcDictationActivity` ist ein dritter
Render-Host der **Android-App** — das Phone nimmt auf, transkribiert, nachbearbeitet
und schickt fertigen Text an den PC. Der transiente `FeatureToggles.pcOnly`-Flag
divertiert jeden Pipeline-Terminal durch `WindowsDispatchCoordinator.dispatch`
(die eine Sende-Primitive, ADR-0019); Fehler → sichtbares Retry statt Pending-Part.
Editbar/Gesten/Text-Pills gehen als `/v1/input`-Commands an den PC. Der jetzt
gewünschte Desktop-Client ist das **umgekehrte** Szenario: Aufnahme und Pipeline auf
dem PC selbst, ohne Phone.

## 3. AI-Layer: Plattform-Neutralität (Detail)

Der AI-Layer ist überraschend nah an „extrahierbar" — die SDKs
(`com.openai:openai-java` 4.26.0, `com.anthropic:anthropic-java` 2.16.0, okhttp) sind
**reine JVM-Bibliotheken**; okhttp ist in `:shared` bereits Dependency.

**Sofort verschiebbar (0 Android-Deps):**
`AIProvider` (Enum mit Capabilities: `supportsTranscription/Completion`,
`isOpenAICompatible`, `allowsStructuredOutputTextFallback`), `AIProviderException`,
`AIFunction`, `ModelInfo`, `ParameterDef`/`ParameterRegistry`, alle
Runner-Interfaces + DTOs (`TranscriptionRunner`, `CompletionRunner`,
`ConversationRequest/Result`), `ai/prompt/` (PromptContext, PromptMode,
PromptBuilder mit XML-Escaping, PromptTemplates, PromptTypeClassifier), das gesamte
`ai/conversation/`-Paket (StructuredResponseCodec = einzige Wire-Authority für
`{message, output, needsClarification}`, ReviewDecision, ConversationReconstructor —
explizit „Android-free" dokumentiert).

**Mit kleiner Abstraktion verschiebbar:** `AIOrchestrator`, `RunnerFactory`, die drei
Runner (OpenAICompatible, Anthropic, ElevenLabs), `ModelFetcher`, `PromptService`/
`SystemPromptResolver`. Kopplungen sind nur: (a) `SharedPreferences` für
Key/Model/Proxy-Resolution → Config-Interface, (b) `UsageDao` (Room) im Orchestrator
→ `UsageSink`-Interface, (c) `org.json` bei ElevenLabs/KeytermsParser/ImportExport
(auf purem JVM nicht vorhanden) → kotlinx-serialization (in `:shared` schon da),
(d) `DictateUtils.getAudioDuration` (Android-Media) → Port.

**Bleibt Android:** `APISettingsActivity` (783 Zeilen, die einzige
Modell-/Provider-Wahl-UI: 2× Provider-Spinner + Modell-Spinner/Freitext,
Parameter-UI dynamisch aus `ParameterRegistry`), `PromptsOverviewActivity` (SAF-I/O),
Pipeline-Orchestrierung im IME-Service, SharedPreferences-Backend.

**API-Keys heute: Klartext** in Default-SharedPreferences
(`Pref.*ApiKey*`, `DictatePrefs.kt:119-140`); verschlüsselte Ablage wurde in
ADR-0017 explizit projektweit deferred. Modell-Listen: OpenAI/Groq/OpenRouter live
gefetcht (`ModelFetcher`, kein Cache), ElevenLabs hartkodiert, Anthropic/Custom
Freitext.

## 4. Prompts + Teilen-Feature (Bestand)

`PromptEntity` (`prompts`): `id, pos, name, prompt, requiresSelection, autoApply,
type` (Double-Enum `PromptType {PROMPT, TEXT}`, ADR-0024). Das bestehende
„Teilen-Feature" (`rewording/PromptImportExport.java`) ist ein **JSON-Datei-Export
via SAF** — kein Link, kein Server:
`{"version": 2, "prompts": [{name, prompt, requiresSelection, autoApply, type}]}`
mit v1-Abwärtskompatibilität. Die Serialisierungslogik ist bereits Activity-frei und
unit-getestet, hängt aber an `org.json` + Room-`PromptEntity`. → Gute Basis für ein
Server-Format, braucht aber ein plattformneutrales Prompt-DTO.

## 5. State-Machine + Render (Bestand)

`state/` folgt ADR-0001: ein Orchestrator, ~19 Module mit reinen Reducern +
Lens-Achsen, IO nur in `runEffect`. **Der Reducer-Kern ist konzeptionell
plattformneutral** (reine Datentransformation, keine Android-Typen in der Reduktion),
liegt aber in `:app`. **`state/render/` ist voll Android-gebunden**
(MotionLayout, View, MaterialButton), sauber getrennt über die
`RenderBackend`-Abstraktion — genau diese Multi-Backend-Naht (ADR-0004/0008/0027)
macht heute drei parallele Render-Hosts möglich (IME-View, Overlay-Widget,
PcDictationActivity) und ist das Vorbild für „Browser als weiterer Render-Host".

## 6. Widget / Overlay-Design (Bestand)

`widget/`: Design-Sprache = akzentfarben-getriebene, amplituden-reaktive Animation
(Wellenform-Bars mit Alters-Fade, Button-Glow via HSV-Brightness-Boost,
Ripple-Pulse), Pill-Formen. **Logik und Zeichnung sind sauber getrennt:**
- Portabel (kein Android): `core/AmplitudeProcessor` (Log-Norm + EMA),
  `EditBarWidthCalculator` (Peek-Arithmetik, pures `object`), Effekt-Parameter,
  `RecordingAnimation`-Strategy-Interface (fast Android-frei).
- Neu zu zeichnen: alle `Drawable`/`View`-Klassen (`AmplitudeVisualizerDrawable`,
  `BorderGlowDrawable`, `PulseLayout`) — reine Canvas-Renderer, in Web-Canvas/CSS
  gut reproduzierbar (die Parameter/Kurven sind der wiederverwendbare Teil).

## 7. History/Datenbank (Bestand)

Room v11, 8 Tabellen. Session-Modell: `sessions` (Status/Origin/Type als
Double-Enums, denormalisierte Caches `final_output_text`/`input_text`,
`inserted_at`, Multi-File-Audio-Pfade) → `transcriptions` (versioniert, Roh) →
`processing_steps` (versionierte Kette: AUTO_FORMAT/REWORDING/QUEUED_PROMPT/
CONVERSATION_TURN) → `conversation_messages` (ADR-0012) + `text_insertions`-Audit +
`usage`-Aggregat + `completion_log`. Audio: M4A/AAC 44.1 kHz mono 64 kbps,
Rolling-Segments „always-one-ahead" (ADR-0007), MediaMuxer-Concat — Aufnahme-Stack
ist stark Android-gebunden (MediaRecorder/MediaExtractor/MediaMuxer).
History-Panel-Filter (ADR-0014) ist definitionsgleich mit dem Sync-Filter (ADR-0020):
`COMPLETED AND final_output_text IS NOT NULL AND origin != REVIEW_REFINEMENT`.

## 8. Prüfmodus/Review (Bestand, ADR-0013)

Tri-State `AmbiguityMode` (ALWAYS_INSERT/AUTO/ALWAYS_REVIEW); Verdikt ist das
explizite Wire-Feld `needsClarification` + reine Regel `ReviewDecision.decide` →
In-Keyboard-Review-Panel (Insert / Re-dictate = transkriptions-only
Refinement-Session mit `ConversationContinuation` / Discard). **Review-Panel ist
bewusst IME-only** — ADR-0027 F8 hat entschieden, dass ein anderer Render-Host Review
NICHT nachbaut. Die Entscheidungslogik (`ReviewDecision`, `StructuredResponseCodec`)
ist plattformneutral; nur die Panel-UI ist Android.

## 9. Bindende ADR-Constraints für das Vorhaben

1. **ADR-0017:** „Desktop-Companion ist der einzige Server, Phone reiner Client;
   HTTP-Response = einzige Delivery-Confirmation, kein Back-Channel." Ein zentraler
   Verteil-Server ist ein neuer Peer → braucht neue ADR (Erweiterung/Supersede).
2. **ADR-0020:** „Phone authoritativ, PC abgeleitetes Archiv, kein bidirektionaler
   Sync." Server-autoritative Prompt-/Key-Verteilung invertiert das → neue ADR.
3. **ADR-0015/0016:** Wire-SSoT = `shared/` DTO + Konform + `ProtocolCodec` als
   einzige Tür; `shared/` ist pure-JVM **ohne JS-Target** (KMP bewusst abgelehnt) —
   ein Browser-Client kann das Protokoll nicht per Compile-Sharing nutzen.
   Kotlin-Ceiling ≤ 2.1.20 compilerweit.
4. **ADR-0016/0025:** Neue Fähigkeiten bevorzugt als additiver Endpoint +
   `HealthResponse`-Capability-Flag, kein Version-Bump bei additiven Feldern.
5. **ADR-0011/0019:** Ein Dispatch-Primitive, ein Terminal-Guard (exactly-once),
   ein Acknowledge-Kanal; ADR-0009: strikt serielle Pipeline-Queue.
6. **ADR-0013/0027:** Review IME-only; `StructuredResponseCodec` einzige Authority
   für Structured Output; Text-Fallback nur CUSTOM/OpenRouter/Groq.
7. **Es existiert KEINE ADR** zu serverseitiger Verteilung von
   Prompts/Keys/Modellen/Presets — vollständig neues Entscheidungsfeld.

## 10. Tailscale-/Netz-Bestand (relevant für das Peer-Sync-Vorhaben)

Tailscale ist bereits die tragende Netz-Annahme des Systems: ADR-0017 wählt
Tailscale (MagicDNS + WireGuard) als Reachability-Layer mit App-Credential als
Defense-in-Depth; ADR-0023 kategorisiert Bind-Adressen explizit nach
`kind: TAILSCALE|LAN|LOOPBACK|OTHER` mit Tailscale-only als Erstkonfigurations-
Default; die Pairing-URI transportiert die (base64url-kodierte) Base-URL inkl.
https für Tailscale-Serve. Jeder Companion bringt also schon heute einen
adressierbaren Ktor-Server im Tailnet mit — die technische Grundlage für ein
Peer-Modell („jeder Companion ist erreichbar") existiert; was fehlt, sind
Katalog-Endpoints, Abo-/Hash-Sync und ein Peer-Begriff im Datenmodell. Das Phone
bleibt per ADR-0017 bewusst server-los (reiner Client) — als Peer-**Anbieter**
kommen damit ohne neue ADR nur Desktop-Companions (und ein optionaler headless
Dienst) infrage, Android wäre reiner Bezieher.

## 11. Kern-Lückenliste (Anforderung → Bestand)

| Anforderung | Bestand | Lücke |
|---|---|---|
| Hotkey → warme kleine UI | Companion ist warmer Tray-Prozess, aber ohne Hotkey/Recording-UI | Global-Hotkey-Hook + Recording-Fenster/Browser-UI |
| Aufnahme darstellen/pausieren/löschen | Nur auf dem Phone (RecordingManager, State-Machine) | Desktop-Aufnahme-Stack + UI komplett neu (Logik teilbar) |
| Widget wiederverwenden | Logik (AmplitudeProcessor, Parameter) portabel; Zeichnung Android-Canvas | Neuzeichnung Web/Compose nach gleicher Design-Sprache |
| History-Infrastruktur | Reiches Modell in `:app` (Room); flaches Archiv im Companion (SQLDelight) | Session-Modell auf Desktop-Seite; Schema-Parität klären |
| Modell-Switcher | `APISettingsActivity` (Android-UI); Datenmodell portabel | Provider→Modell-Datenmodell normalisieren + neue UI |
| Nachbearbeitungsprompts | `PromptService`/Conversation portabel; Verwaltung Android | Prompt-Store + UI auf Desktop |
| Prüfmodus + UI | Logik portabel, Panel IME-only (ADR-0027 F8) | Desktop-Review-UI = neue ADR-Entscheidung |
| Server-Verteilung (Prompts/Keys/Modelle/Presets) | Nur lokaler JSON-Export (v2) | Kompletter Neubau: Server, Entitäten, Crypto, neue ADRs |
| Peer-Verbindungen über Tailscale (Nachtrag) | Tailscale-Reachability + Pairing + Ktor-Server pro Companion vorhanden (ADR-0017/0023) | Katalog-Protokoll, Peer-Registry, Abo-/Hash-Sync |
| Abo-Sync mit Hash-Abgleich + Benachrichtigung (Nachtrag) | Cursor-Sync-Muster existiert (ADR-0020, andere Richtung) | Content-Hashes, Subscription-Modell, Change-Notification |
| Peer Explorer UI (Nachtrag) | Devices-Screen im Companion (nur gepairte Phones) | Herkunfts-Tracking pro Entität + eigene UI |
| Langfrist-Server-Pfad für AI-Zugriffe (Nachtrag) | `AIOrchestrator` ist bereits die einzige AI-Naht | Gateway-Nahtstelle reservieren (nur Architektur, keine Implementierung) |
