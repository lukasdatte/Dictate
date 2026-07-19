# Konzept-Skizze — Dictate Desktop + Peer-Verteilung

Status: **Entschieden** — alle 34 Fragen des Fragenkatalogs sind beantwortet
(Stand 2026-07-19); diese Skizze spiegelt den verbindlichen Entscheidungs-Stand.
Wichtigste Abweichungen von den ursprünglichen Empfehlungen: **F1 Compose-Desktop
statt Browser-UI** (damit entfallen F3 TS-Codegen und F13 ersatzlos), **F12
Envelope-Encryption statt Zero-Knowledge**, **F15 volle Room-Schema-Parität**,
**F18 voller Prüfmodus ab v1**, **F22 Android-Migration sofort mit v1**.
Basis: `bestandsaufnahme.md` und `fragenkatalog.md` im selben Ordner.
Implementierungsplan: `~/.claude/plans/desktop-companion-v1.md`.

## 1. Leitidee: Drei Schichten statt zwei Apps

Die bestehende Architektur trennt bereits sauber in „reine Logik" (Reducer,
AI-Contracts, Codecs) und „Plattform-Render/IO" (Android-Views, Win32). Das Konzept
verallgemeinert diese Naht zu drei Schichten:

```
┌─────────────────────────────────────────────────────────────────┐
│  Peer-Netz im Tailnet (NEU)                                     │
│  Jeder Companion = Katalog-Anbieter UND -Bezieher; optionaler   │
│  headless „Hub" ist NUR ein weiterer Peer (gleiches Protokoll). │
│  Verteilung: Prompts, Provider+Keys, Modelle, Profile —         │
│  hash-basierter Abo-Sync, pull-only, verschlüsselte Secrets     │
└──────────────▲──────────────────────────▲───────────────────────┘
               │ HTTPS, pull              │ HTTPS, pull + serve
┌──────────────┴───────────┐  ┌───────────┴──────────────────────┐
│  :app (Android-IME)      │  │  :companion (Desktop-Host, JVM)  │
│  Aufnahme+Pipeline Phone │  │  NEU: Aufnahme+Pipeline Desktop  │
│  bestehend               │  │  Global-Hotkey, Text-Insertion,  │
│                          │  │  SQLDelight-History, warmer Tray │
└──────────▲───────────────┘  └───────────▲──────────────────────┘
           │ nutzt                        │ nutzt (JVM) + serviert
┌──────────┴──────────────────────────────┴──────────────────────┐
│  Geteilter Kern (Ausbau von :shared)                           │
│  :shared-ai  — AIProvider, Runner, Orchestrator-Kern,          │
│                PromptBuilder/Service, Conversation/Codec,      │
│                ReviewDecision, ParameterRegistry, ModelFetcher │
│  :shared     — Wire-DTOs + Konform + ProtocolCodec (bestehend) │
│                + neue Hub-/UI-Protokoll-Familien               │
└────────────────────────────────────────────────────────────────┘
           Desktop-UI: Compose (Mini-Panel + Verwaltungs-Screens,
           direkt im Companion-Prozess — F1/F5 entschieden)
```

Kernprinzip (aus ADR-0004/0027 verallgemeinert): **State, Pipeline und UI leben im
JVM-Companion-Prozess** — eine Sprache (Kotlin), keine zweite AI-/State-
Implementierung. Die AI-Infrastruktur existiert genau EINMAL (Kotlin/JVM, geteilt
zwischen `:app` und `:companion`); das Compose-Mini-Panel ist ein natives Fenster
desselben warmen Prozesses (F5), wodurch UI-Protokoll, WebSocket-State-Sync und
TS-Typ-Codegen komplett entfallen.

## 2. Modul-Aufteilung (Refactoring-Pfad)

1. **`:shared-ai` (neu, pure JVM wie `:shared`)** — Extraktion aus `app/.../ai/`:
   - Sofort: `AIProvider`, `AIProviderException`, `ModelInfo`, `ParameterDef/Registry`,
     Runner-Interfaces + DTOs, `ai/prompt/*`, `ai/conversation/*` (inkl.
     `StructuredResponseCodec`, `ReviewDecision`).
   - Mit Ports: `AIOrchestrator` (Ports `AiConfig` statt SharedPreferences,
     `UsageSink` statt UsageDao), `RunnerFactory`, die drei Runner (`ProxyConfig`-Port,
     `AudioDurationReader`-Port), `ModelFetcher`; `org.json` → kotlinx-serialization.
   - `:app` implementiert die Ports mit SharedPreferences/Room (Verhalten unverändert),
     `:companion` mit SQLDelight/Settings.
   - Warum eigenes Modul statt in `:shared`: `:shared` hat den strengen
     `SharedPurityTest` (kein okhttp-api-Leak, jvmTarget 1.8) und ist das
     Wire-Protokoll; AI-SDKs (openai-java, anthropic-java) würden die Reinheit
     verwässern. Separates Modul = separate Dependency-Policy, gleiche Prinzipien.
2. **`:companion` ausbauen** — neue Subsysteme:
   - `capture/`: Audio-Aufnahme via `javax.sound.sampled` (JVM, plattformübergreifend
     — F4 entschieden). Rolling-Segment-Idee aus ADR-0007 übernehmen
     (Crash-Resilienz), Format WAV/Opus statt AAC.
   - `pipeline/`: schlanker Desktop-Pipeline-Orchestrator auf `:shared-ai`
     (Serialisierung wie ADR-0009: eine Queue, ein Job). Kein Port des kompletten
     Android-`state/`-Orchestrators — der ist auf IME-Achsen zugeschnitten; der
     Desktop braucht ~4 Achsen (recording, pipeline, review, ui-panel).
   - `hotkey/`: globaler Hook (Win32 `RegisterHotKey`/Low-Level-Hook via JNA — Muster
     `Win32InputPerformer` existiert; Linux/macOS-Ports später, Port-Pattern wie
     `TextInserter` ADR-0018).
   - `ui/panel/`: **Compose-Mini-Panel** (F1/F5): rahmenloses, always-on-top,
     **fokus-freies** Fenster (F21, WS_EX_NOACTIVATE-artig hinter einem
     Fenster-Port), vom warmen Prozess in <50 ms getoggelt. Rendert
     `DesktopUiState` direkt (Compose-State, kein Wire-Protokoll). Recording-Kern
     als 1:1-Nachbau der Widget-Design-Sprache in Compose-Canvas (F19:
     Wellenform-Bars mit Alters-Fade, HSV-Glow, Ripple-Pulse — Parameter/Kurven
     aus `AmplitudeProcessor`/`VisualizerUtils` übernehmen); Verwaltungs-Screens
     im bestehenden Compose-Material3-Stil mit übernommener Farb-/Formsprache.
   - `data/`: Session-Schema-Ausbau (SQLDelight) in **voller Room-Schema-Parität**
     (F15 entschieden): `sessions`/`transcriptions`/`processing_steps`/
     `conversation_messages` mit gleichen Double-Enum-Werten + CHECK-Constraints;
     Parity-Tests wie `OriginCheckConstraintParityTest` existieren als Vorbild.

## 3. Technologie-Entscheidung Desktop-UI (Trade-offs, entschieden per F1)

| Option | Beschreibung | Pro | Contra |
|---|---|---|---|
| **A. Browser-UI + Companion-Backend (Empfehlung)** | TS-SPA, vom lokalen Companion serviert; State/Pipeline/Secrets auf JVM; WebSocket-Sync | Eine AI-Implementierung (Kotlin, geteilt mit App); Secrets nie im Browser; Hotkey + Text-Insertion + Warmhalten kann nur der native Prozess; Browser-UI frei gestaltbar; CORS/Key-Probleme entfallen | Zwei Sprachen (Kotlin+TS); UI-Protokoll muss doppelt getypt werden (→ Schema-Codegen, s. u.) |
| B. Compose-Desktop-UI ausbauen (kein Browser) | Recording-UI direkt in der bestehenden Compose-App | Am wenigsten Neubau; eine Sprache; Hotkey/Warmhalten trivial | Erfüllt „Browser" nicht; Compose-Desktop-UI-Feinheit (Animationen) schwächer; kein Weg zu späterem Web-Zugriff von fremden Rechnern |
| C. Kotlin/Multiplatform + Wasm/JS | `:shared`(+`-ai`) auf KMP umstellen, Browser-Client in Compose-Web/Kotlin-JS | Echtes Code-Sharing bis in den Browser | Superseded ADR-0015 (bewusst gegen KMP entschieden); Kotlin-Ceiling 2.1.20 kollidiert mit Wasm-Reife; AI-SDKs sind JVM-only → Runner trotzdem nicht browserfähig; hohes Toolchain-Risiko |
| D. TS-Vollneubau (Browser ruft Provider direkt) | Browser-App mit eigener AI-Schicht | Kein lokaler Prozess nötig | Doppelimplementierung der gesamten AI-/Prompt-Logik (verletzt DRY-Kernziel); API-Keys im Browser-Storage; CORS-Blockaden (Anthropic/OpenAI nur teils browserfähig); kein globaler Hotkey, keine Text-Insertion in Fremd-Apps |

**ENTSCHIEDEN (F1): Option B — Compose-Desktop-UI, kein Browser.** Der User hat
sich gegen die Browser-UI und für den Ausbau der bestehenden Compose-App
entschieden: eine Sprache (Kotlin), minimaler Neubau, Hotkey/Warmhalten/
Fenster-Steuerung trivial im eigenen Prozess (F5: natives rahmenloses
always-on-top Compose-Fenster, <50 ms Toggle). Damit entfallen ersatzlos: das
UI-Wire-Protokoll, der WebSocket-State-Sync, das TS-Typ-Codegen-Thema (ex-F3)
und die Browser-Secret-Frage (ex-F13). Die Options-Tabelle oben bleibt als
dokumentierter Entscheidungskontext stehen. Die schwächere Web-Erreichbarkeit
von fremden Geräten ist bewusst aufgegeben; ein späterer Web-Zugriff wäre eine
neue Entscheidung.

## 4. Verteilungs-Architektur: Peer-Katalog über Tailscale

> Nachtrag eingearbeitet: Verschiedene Systeme/User verbinden sich direkt
> miteinander (P2P über Tailscale); ein dedizierter Server ist langfristig möglich,
> aber im Modell **nur ein weiterer Peer**. EIN Protokoll, EIN Datenmodell.

### Peer-Modell & Transport

Ein **Peer** ist jeder Teilnehmer, der das Katalog-Protokoll spricht. Anbieten kann
jeder, der einen Server betreibt — das sind nach ADR-0017 heute genau die
Desktop-Companions (jeder bringt bereits einen im Tailnet adressierbaren
Ktor-Server mit, Bind-Katalog ADR-0023) sowie optional ein headless „Hub"-Dienst
(gleicher Code, ohne UI, z. B. auf einer VM). **Android bleibt reiner Bezieher**
(server-los per ADR-0017); von Android aus Teilen heißt: auf den eigenen
Companion-/Hub-Peer publizieren.

- **Adresse:** MagicDNS-Name + Port (`heim-pc.tailXXXX.ts.net:8756`) — das gewohnte
  Tailnet-Muster; kein Port-Forwarding, TLS via Tailscale/Tailscale-Serve.
- **Verbindung herstellen:** bewährtes Pairing-Muster aus `shared/auth/`
  (One-Time-Token → Peer-Secret, serverseitig nur Hash) — pro Peer-Beziehung ein
  Credential; Tailnet ist Netz-Trust, App-Credential Defense-in-Depth (ADR-0017-
  Doktrin unverändert).
- **Protokoll:** neue additive Payload-Familie auf dem bestehenden Wire-Stack
  (`:shared`-DTOs + Konform + ProtocolCodec, Erweiterung nach ADR-0025-Muster:
  neue Endpoints + `HealthResponse`-Capability-Flag `supportsCatalog`, kein
  Version-Bump). Grob: `GET /v1/catalog` (Root-Hash + Entity-Index),
  `GET /v1/catalog/entity/{id}` (Payload), jeweils nur `visibility: shared`.

### Abo-Sync: hash-basiert, pull-only

Zwei Bezugs-Modi pro bezogenem Datum:

- **Abonnieren (`SUBSCRIBE`):** Lokal wird pro bezogener Entität der
  **Content-Hash** (SHA-256 über die kanonische Serialisierung) gespeichert.
  Sync-Lauf: erst `GET /v1/catalog` → Root-Hash vergleichen (ein Request für „hat
  sich irgendwas geändert?"), bei Abweichung Entity-Index diffen und nur geänderte
  Entitäten neu ziehen. Änderung ⇒ lokale Kopie aktualisieren + **Benachrichtigung**
  (Companion: Tray-Notification + Peer-Explorer-Badge; Android:
  System-Notification).
- **Einmalig ziehen (`ONE_SHOT`):** Kopie ohne gespeicherte Peer-Bindung für den
  Sync (Herkunft `sourceRef` bleibt als Anzeige-Metadatum erhalten).

Pull-only mit Polling (Intervall + Trigger bei App-/Panel-Öffnen) — konsistent mit
der „kein Back-Channel"-Doktrin (ADR-0017/0020); der Root-Hash macht das Polling
billig (ein GET, meist 304-artig). Lokal editierte abonnierte Entitäten: Kopien
sind standardmäßig **read-only**; „Bearbeiten" koppelt explizit als Fork ab
(kein Merge-Problem, Frage F29).

Verhältnis zum ADR-0020-Muster: Der Session-Sync (Phone→PC) bleibt unverändert
cursor-basiert; der Katalog-Sync ist bewusst hash-basiert, weil Entitäten klein,
selten geändert und einzeln identifizierbar sind — Hashes liefern zusätzlich die
Drift-Erkennung (lokal editiert? Peer geändert?) gratis.

### Peer Explorer (UI)

Eigener Screen (Companion-UI/Browser-UI; Android als schlanke Settings-Seite):
Liste der verbundenen Peers (Name, MagicDNS-Adresse, Status/letzter Kontakt), pro
Peer die bezogenen Entitäten mit Typ, Modus (Abo/One-Shot), letztem Abgleich und
Zustand (aktuell / Update verfügbar / lokal abgekoppelt), plus Aktionen
(jetzt abgleichen, Abo lösen, Fork). Umgekehrte Sicht „Was biete ich an?" =
Sichtbarkeits-Verwaltung der eigenen Entitäten. Datengrundlage: `peers`- und
`subscriptions`-Tabellen (Peer-Ref, Entity-Ref, Modus, lastHash, lastCheckedAt) —
Herkunft ist damit vollständig nachvollziehbar.

### Langfristiger Server-Pfad (nur vorbereiten, nicht bauen)

Ziel laut Nachtrag: später sollen sämtliche API-Zugriffe über einen Server laufen
können (Berechnungen + bestimmte Prompts vollständig serverseitig, damit API-Keys
das Servergerät nie verlassen). Vorbereitung ohne Zusatzkomplexität — drei
Nahtstellen genügen, alle existieren fast schon:

1. **`AIOrchestrator` ist bereits die einzige AI-Naht** (CLAUDE.md-Konvention:
   nie SDKs direkt). Die Extraktion nach `:shared-ai` mit `AiConfig`-Port zementiert
   das; ein späterer `GatewayCompletionRunner`/`GatewayTranscriptionRunner` ist nur
   eine weitere `RunnerFactory`-Variante hinter demselben Interface.
2. **`ProviderConfig.kind = LOCAL | GATEWAY`** — der Enum-Wert `GATEWAY` wird im
   Datenmodell reserviert und dokumentiert, aber nicht implementiert (Double-Enum-
   Pattern erlaubt spätere Migration sauber). Ein Gateway-Provider zeigt statt auf
   eine Vendor-API auf einen Peer; der Key liegt dann nur dort. Serverseitige
   Prompts wären dort `visibility: shared`-Entitäten, deren *Text* der Peer bei
   Bedarf gar nicht ausliefert, sondern nur per Referenz ausführt — das bleibt
   explizit Zukunfts-Design, das Entitätenmodell (Referenzen statt Inline-Werte)
   macht es möglich.
3. **Protokoll-Namensraum:** die Katalog-Familie wird so geschnitten, dass eine
   spätere `/v1/ai/*`-Familie (Proxy-Aufrufe) additiv daneben passt (ADR-0025-
   Muster). Keine Vorab-Implementierung, kein spekulativer Code — nur der
   dokumentierte Platzhalter in ADR + DTO-Namensraum.

Mehr Vorbereitung als diese drei Punkte wäre die „übermäßige Komplexität", die der
Nachtrag ausschließt.

### Entitätenmodell (teilbar, versioniert)

```
ProviderConfig  — Anbieter-Definition: AIProvider-Kind (OPENAI/ANTHROPIC/.../CUSTOM),
                  baseUrl, Capability-Flags; Key-Referenz optional (lokal ODER Hub)
ApiCredential   — API-Key als verschlüsselter Blob + Metadaten (Provider, Label);
                  Klartext existiert nur client-seitig nach Entschlüsselung
ModelRef        — Modell-Definition: providerRef, modelId, Funktion
                  (TRANSCRIPTION/COMPLETION), Parameter-Defaults (ParameterDef-Werte)
Prompt          — wie PromptEntity minus Pill-Felder: name, text, requiresSelection,
                  autoApply-Empfehlung; type entfällt (keine Pills auf Desktop)
Profile (Preset)— die konfigurierbare Einheit: { transcription: ModelRef,
                  completion: ModelRef, prompts: [PromptRef|inline], systemPrompt-
                  Auswahl, AmbiguityMode, Parameter-Overrides }
```

Jede Entität: `id (uuid)`, `contentHash` (SHA-256 der kanonischen Serialisierung —
zugleich Sync-Watermark und Drift-Detektor), `visibility (private|shared)`,
`sourceRef?` (Herkunfts-Peer + Original-Id bei bezogenen Kopien), `updatedAt`.
**Teilen = sichtbar machen** (`visibility: shared` im eigenen Katalog); **Beziehen =
Kopie** (Abo hält sie per Hash-Abgleich aktuell, One-Shot nicht — siehe „Abo-Sync"
oben). Das bestehende PromptImportExport-v2-JSON wird zur Wire-Repräsentation
weiterentwickelt (v3: + Profile/ModelRef/ProviderConfig), sodass Datei-Export und
Katalog-Protokoll dieselbe Serialisierung nutzen — die kanonische Form ist zugleich
die Hash-Basis (SSoT in `:shared`). Lokale Entitäten und bezogene Kopien
koexistieren; ein Profil referenziert Prompts/Modelle per stabiler ID.

### Verschlüsselungskonzept (nur Secrets, Rest Klartext)

Anforderung: Keys verschlüsselt übertragen UND lokal verschlüsselt gespeichert;
bei Profilen ausschließlich Zugangsdaten verschlüsselt.

- **Transport:** immer TLS (Peers via Tailscale/Tailscale-Serve; das Muster ist
  im Projekt etabliert).
- **At-Rest client-seitig (beide Plattformen):** Envelope-Verschlüsselung — ein
  lokaler Master-Key im Plattform-Keystore (Android Keystore; Desktop: DPAPI/
  Keychain/libsecret hinter einem `SecretStore`-Port im Stil von ADR-0018),
  Payload AES-256-GCM. Das beendet zugleich die in ADR-0017 deferred „Klartext-SP"-
  Situation → eigene projektweite ADR „Secret-Storage".
- **Key-Sharing über Peers — ENTSCHIEDEN (F12): Envelope-Encryption, KEIN
  Zero-Knowledge, KEIN Share-Passwort-Schritt.** Anbietende Peers DÜRFEN die von
  ihnen verwalteten Credentials entschlüsseln: der anbietende Peer hält das
  Credential in seinem lokalen SecretStore und liefert es beim Beziehen über den
  TLS-Kanal aus; der Empfänger legt es sofort in seinen eigenen SecretStore.
  Pflicht bleibt damit exakt das Anforderungs-Paar „verschlüsselt übertragen"
  (TLS/Tailscale) + „lokal verschlüsselt gespeichert" (SecretStore beidseitig,
  F11); auf keiner Platte liegt je ein Klartext-Key. Der Katalog-Index trägt für
  Credentials nur Metadaten (Provider, Label, contentHash über den verschlüsselten
  At-Rest-Blob bzw. einen Key-Fingerprint) — Secrets erscheinen nie im Index.
  Bewusst akzeptierter Trade-off: Wer einen Key anbietet, vertraut dem
  Peer-Betreiber ohnehin (Self-Hosted-Kontext); die Zero-Knowledge-Varianten
  (Share-Passwort, X25519-sealed-box) bleiben als dokumentierte spätere
  Härtungsoption im Fragenkatalog, sind aber NICHT Teil von v1.

### Modell-Selektor überarbeitet

Zweistufig, datengetrieben statt UI-verdrahtet: **1) Anbieter wählen/anlegen**
(`ProviderConfig`, lokal oder von einem Peer bezogen) → **2) Modell wählen**
(Vereinigung aus live-`ModelFetcher`-Ergebnis + bezogenen `ModelRef`s + Freitext
bei Anthropic/Custom). Dieselbe Komponente speist Android-Settings (Refactor von
`APISettingsActivity` auf das neue Datenmodell) und Desktop-UI. Modelle sind damit
automatisch teilbar (ModelRef ist eine Entität, kein Pref-String mehr).

### Prompt-Editor

Lokal erstellen (wie heute, minus Pill-Semantik) + „Geteilte Prompts"-Ansicht pro
Peer (Browsen im Peer-Katalog, Kopieren-in-lokal bzw. Abonnieren). Gleiches
UI-Muster auf Android (bestehendes, frisch überarbeitetes PromptsOverview
erweitert um Herkunfts-Badge lokal/Peer) und Desktop (Browser-UI); der Peer
Explorer verlinkt hierher.

## 5. Namensvorschlag für die Preset-Einheit: **„Profil" (engl. `Profile`)**

Begründung: Ein Profil ist im allgemeinen Sprachgebrauch genau das — eine benannte,
umschaltbare Kombination aus Werkzeug + Einstellungen (Browser-Profile, VS-Code-
Profiles, OBS-Profile). „Preset" klingt nach reinen Parameterwerten, „Set" ist zu
generisch, „Workflow" suggeriert Mehrschrittigkeit, „Modus" kollidiert mit
`AmbiguityMode`/`ViewMode`. „Profil" trägt die Semantik „Modell + Prompts +
Verarbeitungseinstellungen, dupliziere mich, teile mich" natürlich und ist
DE/EN-identisch. **ENTSCHIEDEN (F24): Die Einheit heißt „Profil"** (`Profile` im
Code). Inhalt per F17: Transcription-ModelRef (+ Sprache/Style-Prompt),
Completion-ModelRef + Parameter-Overrides, aktivierte Nachbearbeitungs-Prompts
(geordnet, mit autoApply), System-Prompt-Auswahl, AmbiguityMode; Credentials
werden ausschließlich referenziert, nie eingebettet.

## 6. Was bewusst NICHT portiert wird

- **Prompt-Pills** (Vorgabe) — `PromptType.TEXT`/Pill-UI bleibt Android-only;
  das Desktop-Prompt-Modell kennt keine Pill-Felder.
- **Android-`state/`-Orchestrator als Ganzes** — nur das Muster (reine Reducer,
  eine Dispatch-Tür) wird übernommen, nicht die 19 IME-Achsen.
- **Review-Panel-Implementierung** — Logik (`ReviewDecision`,
  `ConversationContinuation`) wird geteilt, die UI entsteht neu in Compose;
  per F18 kommt der **volle Prüfmodus ab v1 inkl. diktierter Verfeinerung
  (Re-dictate)** — keine Stufung. Erfordert eine ADR, die die „Review ist
  IME-only"-Entscheidung (ADR-0013/0027-F8) für den Desktop-Host revidiert.
- **Phone↔PC-Dispatch-Pfad** — bleibt unverändert; der neue Desktop-Modus ist
  additiv (eigene Aufnahme), kein Ersatz für PC-Dictation via Phone.

## 7. Ausbaustufen (entschieden — Details im Implementierungsplan)

1. **Fundament:** `:shared-ai`-Extraktion + Ports; `:app` auf Ports umgestellt
   (verhaltensneutral, hohe Testbarkeit). Parallel: projektweiter SecretStore
   (F11) inkl. Migration der Android-Klartext-Keys.
2. **Entitätenmodell:** ProviderConfig/ModelRef/Prompt/Profil + kanonische
   v3-Serialisierung in `:shared`; **Android migriert sofort mit v1** (F22:
   Prefs→DB, `APISettingsActivity`-Umbau auf das Entitätenmodell).
3. **Desktop-Diktat:** Companion-Aufnahme + Pipeline + Hotkey + warmes
   Compose-Mini-Panel (fokus-frei, Auto-Insert per F21), History in voller
   Schema-Parität (F15), **voller Prüfmodus inkl. Re-dictate (F18)**,
   Profil-Switcher, Modell-Switcher, Prompt-Editor.
4. **Peer-Sync:** Katalog-Protokoll + Abo-/Hash-Sync + Benachrichtigungen +
   Peer Explorer (Bezugs- + Angebots-Sicht, F34) + Teilen (Prompts → Profile →
   Keys per F12-Envelope), Tailscale-Discovery-Port (F26: manuell + QR UND
   API-Enumeration), v3-Datei-Export; optionaler headless Hub-Peer als
   `--headless`-Deployment-Variante desselben Codes.

Neue ADRs, die dieses Konzept mindestens erfordert: (a) Desktop-Aufnahme+Pipeline-
Host (erweitert 0017-Rollenmodell), (b) Browser-UI als Render-Host + UI-Protokoll
(erweitert 0004/0016), (c) Peer-Katalog-Verteilungsarchitektur (Companion-Server
wird Mehrzweck-Peer, hash-basierter Abo-Sync, pull-only; erweitert 0017/0020 und
reserviert den Gateway-Pfad), (d) Secret-Storage projektweit (löst den 0017-Defer
auf), (e) Profil-Entitätenmodell, (f) Desktop-Review (revidiert
0013-Surface-Constraint).
