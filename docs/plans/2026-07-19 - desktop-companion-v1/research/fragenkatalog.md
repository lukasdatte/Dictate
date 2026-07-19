# Fragenkatalog — Dictate Desktop (Browser) + Server-Verteilung

Nummeriert zum Einzeln-Beantworten. Pro Frage: Kontext, Optionen mit Trade-offs,
Empfehlung. Verweise: `bestandsaufnahme.md`, `konzept-skizze.md`.

---

## Block A — Plattform & Technologie

### F1: Wie hart ist die Anforderung „Browser" für die Desktop-UI?

Kontext: Hotkey, Warmhalten und Text-Insertion in fremde Apps kann nur ein nativer
Prozess leisten — der Companion existiert bereits als warmer JVM-Tray-Prozess mit
Compose-UI. Die Frage ist, ob die *Bedienoberfläche* im Browser laufen muss oder ob
„Desktop-App" reicht.

- **Option 1 — Browser-UI, vom Companion serviert (Konzept-Empfehlung):** TS-SPA,
  Companion liefert Assets + WebSocket-State. Pro: moderne UI-Freiheit, später auch
  von anderen Geräten erreichbar (Tailscale), klare Render-Host-Trennung. Contra:
  zweite Sprache (TS), UI-Protokoll nötig.
- **Option 2 — Compose-Desktop-UI ausbauen:** Recording-Panel direkt in der
  bestehenden App. Pro: minimaler Neubau, eine Sprache. Contra: kein Browser, Web-
  Design-Sprache (Animationen) schwerer nachzubilden.
- **Option 3 — Hybrid:** Compose-Fenster mit eingebettetem WebView, das die
  Browser-UI rendert. Pro: Fenster-Management nativ, UI web. Contra: WebView-
  Abhängigkeit auf JVM (KCEF/JCEF) ist schwergewichtig (~100 MB Bundle).

**Empfehlung:** Option 1. Falls das Mini-Panel als eigenes Browser-Fenster
(App-Mode-Fenster von Chrome/Edge) akzeptabel ist, entfällt die WebView-Frage
komplett. → hängt an F5.

### F2: Wo läuft die AI-Pipeline des Desktop-Clients?

Kontext: Die Runner (openai-java/anthropic-java) sind reine JVM-Bibliotheken;
Browser-Direktcalls zu den Providern scheitern teils an CORS und legen Keys in den
Browser.

- **Option 1 — Auf dem Companion (JVM):** eine geteilte Kotlin-Implementierung
  (`:shared-ai`), Browser bleibt dünn und secret-frei. Contra: keine.
- **Option 2 — Im Browser (TS-Neubau):** Doppelimplementierung der gesamten
  Prompt-/Conversation-/Fallback-Logik, Keys im Browser-Storage, CORS-Risiko.

**Empfehlung:** Klar Option 1 — das ist der Kern des „aufteilen statt portieren".

### F3: Wie wird das UI-/Hub-Protokoll zwischen Kotlin und TypeScript geteilt?

Kontext: ADR-0015 hat KMP bewusst abgelehnt; `:shared` ist pure-JVM ohne JS-Target.
Ein Browser-Client braucht aber getypte DTOs, sonst entsteht genau die Drift, die
ADR-0016 verhindert.

- **Option 1 — Schema-Export + Codegen:** JSON-Schema/OpenAPI aus den
  kotlinx-serialization-DTOs generieren, TS-Typen daraus codegenerieren
  (Build-Step). Pro: `:shared` bleibt SSoT, ADR-0015 unangetastet. Contra:
  Generator-Pflege; Konform-Constraints reisen nicht mit (Server validiert weiter).
- **Option 2 — `:shared` auf KMP mit JS-Target umstellen:** echtes Sharing. Contra:
  Supersede von ADR-0015, Kotlin-Ceiling 2.1.20, Toolchain-Risiko, die AI-SDKs
  bleiben trotzdem JVM-only.
- **Option 3 — Manuell doppelt pflegen:** nur bei sehr kleinem UI-Protokoll
  vertretbar; Drift-Risiko.

**Empfehlung:** Option 1. Der Browser ist ein „trusted thin client" hinter dem
Companion — Validierungs-Authority bleibt serverseitig, TS braucht nur Typen.

### F4: Wer nimmt das Audio auf — Browser oder Companion?

Kontext: Android nutzt MediaRecorder (AAC/M4A, Rolling-Segments ADR-0007). Auf dem
Desktop gibt es zwei Mikrofonzugänge.

- **Option 1 — Browser (`getUserMedia` + MediaRecorder-API):** Pro: Geräteauswahl-UI
  und Permission-Modell geschenkt, Wellenform-Daten (AnalyserNode) direkt für die
  Visualisierung. Contra: Aufnahme stirbt mit dem UI-Fenster (widerspricht
  „Panel darf zuklappen"), Format webm/opus, Audio muss zum Companion gestreamt
  werden.
- **Option 2 — Companion (`javax.sound.sampled`):** Pro: Aufnahme unabhängig vom
  UI-Fenster (robust, passt zum warmen Prozess), WAV→Provider direkt oder
  Opus-Encoding; Segment-/Recovery-Logik nach ADR-0007-Vorbild serverseitig.
  Contra: Geräteauswahl selbst bauen; Amplituden-Feed muss über WebSocket zur UI.
- **Option 3 — Hybrid:** Browser nimmt auf, streamt Chunks live an den Companion,
  der persistiert. Höchste Komplexität.

**Empfehlung:** Option 2. Die Aufnahme gehört zum warm gehaltenen Prozess, nicht
zum flüchtigen UI-Fenster; Amplituden-Streaming über den ohnehin nötigen
State-WebSocket ist billig.

### F5: Wie wird das Mini-Panel technisch warm gehalten und per Hotkey getoggelt?

Kontext: Anforderung 1 — definierte Tastenkombination öffnet eine dauerhaft warme,
kleine UI.

- **Option 1 — Companion-eigenes Compose-Fenster (rahmenlos, always-on-top), Inhalt
  nativ:** Pro: <50 ms Toggle, volle Fensterkontrolle (Position, Fokus-Rückgabe).
  Contra: UI in Compose statt Web (koppelt an F1-Option 2).
- **Option 2 — dediziertes Browser-Fenster im App-Mode**, vom Companion beim Start
  geöffnet und danach nur gezeigt/versteckt: Pro: Web-UI (F1-Option 1), warm durch
  persistenten Tab + offenen WebSocket. Contra: Fenster-Show/Hide über
  OS-APIs (Win32 FindWindow/ShowWindow) ist fummelig; Nutzer kann das Fenster
  schließen (Companion muss es neu spawnen).
- **Option 3 — Compose-Fenster mit WebView (KCEF):** vereint beides, aber schwerer
  Bundle-Footprint und JCEF-Pflege.

**Empfehlung:** Option 2 als Ziel (konsequent Browser), mit Option 1 als
Rückfalllinie, falls Fenster-Steuerung im Praxistest zu fragil ist. Der Hotkey wird
in jedem Fall vom Companion registriert (JNA/Win32; Port-Muster wie ADR-0018 für
spätere Linux/macOS-Unterstützung — siehe F6).

### F6: Welche Desktop-OS sind in Scope?

Kontext: Der Companion ist Windows-first (Win32-Insertion via JNA), läuft aber auf
Linux/macOS mit No-op-Insertion („canInsert=false"). Hotkey + Insertion brauchen
pro OS eigene Ports.

- **Option 1 — Windows-only (v1):** deckt den bestehenden Nutzungskontext; alle
  Ports (Hotkey, Insertion, Autostart) existieren nur für Win32.
- **Option 2 — Windows + Linux:** Lukas' Dev-Umgebung ist Linux — für Dogfooding
  wertvoll; Hotkey (X11/Wayland-Problematik!) und Insertion (xdotool/wtype) sind
  auf Wayland deutlich eingeschränkt.
- **Option 3 — alle drei.**

**Empfehlung:** Option 1 für Insertion/Hotkey, aber alle Neubauten hinter Ports
(wie `TextInserter`), sodass Linux-Dogfooding mit Clipboard-only + UI-Button statt
Hotkey sofort funktioniert.

---

## Block B — Server & Betrieb (Hub → Peer-Netz)

> **Reframing durch den Nachtrag (siehe Block G):** Das Verbindungsmodell ist ein
> Peer-Netz über Tailscale — jeder Companion kann Anbieter UND Bezieher sein, ein
> dedizierter „Hub" ist nur ein optionaler headless Peer mit demselben Protokoll.
> F7–F10 gelten sinngemäß für jeden anbietenden Peer.

### F7: Was ist der Hub — Deployment- und Mandanten-Modell?

Kontext: „Serverseitige Verteilung von Anfang an." Es gibt heute keinerlei
Server-Komponente außer dem lokalen Companion.

- **Option 1 — Self-hosted Single-Tenant (ein Hub pro Nutzer/Familie/Team):**
  kleiner Ktor-Dienst (Docker/VM), Reuse von `:shared`-Mustern (ProtocolCodec,
  Konform), Betrieb z. B. auf der eigenen VM hinter Tailscale/HTTPS. Pro: kein
  Account-System nötig (Pairing-artige Tokens reichen), Datenschutz trivial,
  passt zur bestehenden Tailscale-Infrastruktur. Contra: Teilen nur innerhalb
  des eigenen Hubs.
- **Option 2 — zentraler Multi-Tenant-Dienst (SaaS-artig):** öffentliches Teilen
  zwischen fremden Nutzern. Pro: „Community-Prompts". Contra: Accounts, Moderation,
  Betriebskosten, Missbrauch (geteilte Keys!), rechtliche Fragen.
- **Option 3 — beides schichtweise:** v1 self-hosted, Protokoll so entworfen, dass
  ein öffentlicher Katalog später andocken kann.

**Empfehlung:** Option 3 mit v1 = self-hosted Single-Tenant. Die Anforderung
„Nutzer beziehen freigegebene Prompts anderer Nutzer" funktioniert dort im
Team-/Familienkreis; das Entitätenmodell (uuid + revision + visibility) trägt beide
Welten. **Wichtigste offene Info: Wer ist die Zielgruppe des Teilens — eigener
Geräteverbund, kleines Team, oder Öffentlichkeit?**

### F8: Wo lebt der Hub-Code — Modul im Monorepo oder eigenes Repo?

Kontext: ADR-0015 definiert die Monorepo-Topologie mit `:shared` als Protokoll-SSoT;
der Hub will dieselben DTO-/Codec-Muster nutzen.

- **Option 1 — viertes Gradle-Modul `:hub` im Monorepo:** Pro: Protokoll-Sharing
  per `project(':shared')`, atomare Protokolländerungen, ein Test-Lauf. Contra:
  Repo wächst; Deployment-Artefakt (Server-JAR/Container) im App-Repo.
- **Option 2 — eigenes Repo mit publiziertem `shared`-Artefakt:** Pro: getrennte
  Release-Zyklen. Contra: Versionierungs-/Publikations-Overhead, Drift-Gefahr —
  genau das, was ADR-0015 vermeiden wollte.

**Empfehlung:** Option 1 (konsistent mit ADR-0015-Rationale). Durch das
Peer-Modell (Block G) verschärft: Der headless Hub-Peer ist idealerweise gar kein
eigenes Modul, sondern eine **Deployment-Variante des Companion-Servers**
(`--headless`-Start ohne Compose-UI) — dann entfällt die Frage fast vollständig;
ein eigenes `:hub`-Modul bräuchte es nur, falls der Hub funktional divergiert.

### F9: Pull-only oder Push für die Verteilung?

Kontext: ADR-0017/0020 verbieten Back-Channels im lokalen Protokoll; für den Hub
ist das neu zu entscheiden.

- **Option 1 — Pull-only (Katalog-Sync bei App-Start/Settings-Öffnen/manuell):**
  Pro: konsistent mit bestehender Doktrin, kein Verbindungs-Management, offline-
  freundlich. Contra: Änderungen kommen verzögert an.
- **Option 2 — Push (WebSocket/SSE vom Hub):** Pro: sofortige Propagation. Contra:
  stehende Verbindungen auf Mobilgerät (Akku), Komplexität ohne echten Bedarf —
  Prompts/Profile ändern sich selten.

**Empfehlung:** Option 1, mit `revision`-Cursor nach ADR-0020-Vorbild.

### F10: Wie authentifizieren sich Clients am Hub?

Kontext: Der lokale Pairing-Mechanismus (One-Time-Token → Device-Secret, Hash
serverseitig) ist bewährt und in `shared/auth/` implementiert.

- **Option 1 — Pairing-Modell wiederverwenden:** Hub generiert Einmal-Token
  (Admin-UI/CLI), Gerät tauscht es gegen ein Device-Secret. Pro: Code + UX
  existieren, kein Passwort-/Account-System. Contra: Geräte-zentriert, kein
  Nutzerbegriff (reicht für Single-Tenant).
- **Option 2 — Accounts (E-Mail+Passwort/OIDC):** nötig erst für Multi-Tenant.

**Empfehlung:** Option 1 für v1; Identitätsmodell erst mit F7-Option-2-Ausbau.

---

## Block C — Security & Verschlüsselung

### F11: Führen wir jetzt projektweit verschlüsselte Secret-Speicherung ein (auch Android)?

Kontext: API-Keys liegen heute im Klartext in SharedPreferences; ADR-0017 hat
verschlüsselte Ablage explizit deferred. Die Anforderung „lokal verschlüsselt
gespeichert" für bezogene Keys erzwingt mindestens auf dem Desktop einen
Secret-Store.

- **Option 1 — nur Desktop verschlüsselt, Android bleibt wie bisher:** Pro: kleiner
  Scope. Contra: inkonsistent — derselbe geteilte Key läge auf Android im Klartext,
  die Anforderung wäre nur formal erfüllt.
- **Option 2 — projektweiter `SecretStore`-Port (Android Keystore / Windows DPAPI /
  Keychain / libsecret), Migration der bestehenden Klartext-Keys:** Pro: löst den
  ADR-0017-Defer sauber auf, eine Abstraktion für beide Plattformen. Contra:
  Migrationsaufwand + neue ADR.

**Empfehlung:** Option 2 — wenn das Thema ohnehin angefasst wird, ist die halbe
Lösung technische Schuld mit Ansage.

### F12: Dürfen anbietende/vermittelnde Peers die geteilten API-Keys lesen (Zero-Knowledge ja/nein)?

Kontext: „Keys verschlüsselt übertragen und gespeichert" lässt offen, ob ein
Peer, der Keys anbietet oder weiterreicht (z. B. ein headless Hub-Peer), selbst
Klartext sehen darf. *(Durch das Peer-Modell aus Block G umformuliert: „Hub" =
beliebiger anbietender Peer; die Optionen gelten pro Peer-Beziehung.)*

- **Option 1 — Zero-Knowledge via Share-Passwort:** Teilender verschlüsselt den Key
  client-seitig (Argon2id → AES-256-GCM); der anbietende Peer speichert nur den
  Blob (gehasht wird über den Blob → Abo-Sync ohne Entschlüsselung); Empfänger
  entschlüsselt mit out-of-band mitgeteiltem Passwort. Pro: Peer-Kompromittierung
  legt keine Keys offen; simpel, kein Schlüsselverzeichnis. Contra: Passwort-
  Weitergabe ist manueller Schritt.
- **Option 2 — Peer-seitige Envelope-Encryption (anbietender Peer kann
  entschlüsseln):** Pro: reibungsloses Beziehen ohne Passwort. Contra: der Peer
  wird Hochwert-Ziel; beim eigenen Companion vertretbar, bei fremden
  Peers/Multi-Tenant nicht.
- **Option 3 — Empfänger-Public-Keys (X25519 sealed box):** komfortabel UND
  zero-knowledge, aber braucht Geräte-/Identitätsverzeichnis → Ausbaustufe.

**Empfehlung:** Option 1 für v1 (erfüllt beide Verschlüsselungsanforderungen
wörtlich), Option 3 als vorgesehene Evolution. In Profilen werden Key-Felder als
`ApiCredential`-Referenz modelliert, nie inline — damit ist „nur sensible
Zugangsdaten verschlüsselt" strukturell garantiert.

### F13: Dürfen Secrets jemals in den Browser (UI-Schicht)?

Kontext: Die Browser-UI zeigt Provider-Konfiguration an; zum Bearbeiten eines Keys
muss er eingegeben werden.

- **Option 1 — Write-only:** Browser sendet neue Keys zum Companion, bekommt nie
  Klartext zurück (nur maskierte Anzeige `sk-…abc`). Pro: kein Secret in
  Browser-Memory/DevTools-Persistenz. Contra: „Key anzeigen" unmöglich.
- **Option 2 — Read auf Anfrage mit Bestätigung.**

**Empfehlung:** Option 1 — deckungsgleich mit der ADR-0016-Redaction-Doktrin.

---

## Block D — Datenmodell & Sync

### F14: Teil-Semantik: Kopie (Fork) oder Live-Referenz mit Updates?

Kontext: „Server-Prompts kopierbar", „Presets duplizierbar/verschiebbar" deutet auf
Kopie-Semantik; Teams wollen aber evtl. zentrale Updates beziehen.

- **Option 1 — Fork beim Import:** bezogene Entität wird lokale Kopie mit
  `sourceRef` (Herkunfts-Merker). Pro: konfliktfrei, offline-robust, keine
  Merge-Logik. Contra: Updates des Originals kommen nicht an.
- **Option 2 — Link + Update-Kanal:** Entität bleibt hub-gebunden, Client zeigt
  „Update verfügbar". Contra: Konflikt-/Override-Logik, Offline-Fälle.
- **Option 3 — Fork + Update-Hinweis:** Kopie wie 1, aber `sourceRef`+`revision`
  erlauben einen unaufdringlichen „neuere Version verfügbar → erneut kopieren"-
  Hinweis ohne Merge.

**Empfehlung:** Option 3 — kostet fast nichts extra und hält beide Türen offen.

### F15: Bekommt der Desktop das volle Session-Schema (Parität zu Room) oder ein eigenes schlankeres?

Kontext: Android hat `sessions`/`transcriptions`/`processing_steps`/
`conversation_messages` (versioniert, Double-Enums); der Companion nur
`received_texts`. Desktop-Diktate brauchen History inkl. Aufzeichnung (Anforderung 4)
und Review/Regenerate braucht die Conversation-Persistenz (ADR-0012).

- **Option 1 — volle Schema-Parität in SQLDelight:** Pro: Features (Regenerate,
  Review-Refinement, Step-Kette) funktionieren identisch; Parity-Tests nach
  bestehendem Vorbild. Contra: größter Aufwand; zwei Schema-Definitionen (Room +
  SQLDelight) für dasselbe Modell.
- **Option 2 — reduziertes Desktop-Schema (Session + aktuelle Transcription +
  finaler Output + Conversation):** Pro: deckt die Desktop-Anforderungen; weniger
  Migration-Pflege. Contra: Feature-Divergenz wächst später.
- **Option 3 — gemeinsame Schema-SSoT** (ein Generator für Room + SQLDelight):
  theoretisch DRY, praktisch Tooling-Neuland — nicht empfohlen.

**Empfehlung:** Option 2, aber mit identischen Enum-Vokabularen und Spaltennamen
(Teilmenge der Room-Definitionen + Parity-Tests), damit spätere Konvergenz möglich
bleibt. Audio-Ablage: Segment-Dateien im Companion-Data-Dir mit denselben
Cleanup-Policies-Ideen (TTL inserted/cancelled).

### F16: Synchronisieren Desktop-Sessions irgendwohin (Phone, Hub)?

Kontext: Heute syncen Phone-Sessions → Companion-Archiv (ADR-0020, one-way).
Desktop-eigene Diktate erzeugen erstmals Sessions außerhalb des Phones.

- **Option 1 — lokal-only:** Desktop-History bleibt auf dem PC. Pro: keine neue
  Autoritätsfrage. Contra: keine geräteübergreifende History.
- **Option 2 — Desktop → Hub (History-Backup/Merge):** neue Privacy-Dimension
  (Klartext-Diktate auf dem Server), Konflikt mit Phone-Autorität aus ADR-0020.
- **Option 3 — gemeinsame History im Companion:** Phone synct heute schon dorthin;
  Desktop-Sessions landen in derselben DB → der Companion wird das natürliche
  „Familienarchiv" beider Quellen (getrennt per `origin`/Device-Spalte).

**Empfehlung:** Option 3 (fast geschenkt, da Phone-Sync existiert) — der Hub bleibt
strikt Konfigurations-Verteiler, NIE Diktat-Speicher (Privacy-Grenze klar).

### F17: Welche Einstellungen gehören in ein Profil, welche bleiben global?

Kontext: Das Profil kombiniert „Modell + Prompts + ggf. weitere Einstellungen".
Zu viel im Profil macht Umschalten überraschend, zu wenig macht es nutzlos.

- Vorschlag Profil-Inhalt: Transcription-ModelRef (+ Sprache/Style-Prompt),
  Completion-ModelRef + Parameter-Overrides, aktivierte Nachbearbeitungs-Prompts
  (geordnet, mit autoApply), System-Prompt-Auswahl, AmbiguityMode.
- Global bleiben: Provider/Credentials (referenziert, nicht enthalten), UI-/
  Hotkey-/Audio-Einstellungen, Cleanup-Policies, Proxy.

**Empfehlung:** wie vorgeschlagen; die Grenze „Profil referenziert Credentials,
enthält sie nie" ist zugleich die Verschlüsselungsgrenze (F12). Zu klären: Gilt das
Profil-Konzept ab v1 auch auf Android (Refactor der Settings), oder Desktop-first
mit späterer Android-Adoption? → Empfehlung: Datenmodell ab v1 geteilt, Android-UI-
Umbau als eigene Ausbaustufe.

---

## Block E — UX & Scope

### F18: Bekommt der Desktop den vollen Prüfmodus (Review-Panel) inkl. diktierter Verfeinerung?

Kontext: ADR-0013/0027-F8: Review ist bewusst IME-only; die Logik
(`ReviewDecision`, Conversation-Continuation) ist aber plattformneutral. Anforderung
5 verlangt „Prüfmodus mit entsprechender UI".

- **Option 1 — voller Prüfmodus:** Panel im Mini-Fenster (Nachricht, Output,
  Insert/Re-dictate/Discard), Re-dictate startet Desktop-Aufnahme S2. Pro:
  Feature-Parität. Contra: größte UI-Einheit des Desktop-Clients.
- **Option 2 — v1 nur Anzeigen+Insert/Discard/Edit-von-Hand, Re-dictate später.**

**Empfehlung:** Option 2 als Stufe, Option 1 als Ziel; in jedem Fall neue ADR, die
den Surface-Constraint aus 0013 für den Desktop-Host revidiert.

### F19: Wie ähnlich soll die Desktop-Widget-Optik dem Android-Overlay sein?

Kontext: Die Zeichnung (Canvas-Drawables) ist nicht wiederverwendbar, wohl aber
Design-Sprache und Parameter (Wellenform-Bars mit Alters-Fade, HSV-Glow,
Ripple-Pulse, Pill-Formen, Akzentfarbe).

- **Option 1 — 1:1-Nachbau in Web-Canvas/CSS** (AmplitudeProcessor-Kurven als Spec).
- **Option 2 — eigenes Desktop-Design, nur Farb-/Formsprache übernommen.**

**Empfehlung:** Option 1 für den Recording-Kern (Wiedererkennung, Anforderung 3),
Option 2 für Verwaltungs-Screens (dort dominiert ohnehin Material-artiges Layout).

### F20: Wie wählt man Nachbearbeitungs-Prompts am Desktop, wenn es keine Pills gibt?

Kontext: Anforderung 6 streicht die Pills; queued Prompts sind auf Android über
Pills + autoApply gesteuert.

- **Option 1 — nur über das Profil:** aktive Prompts sind Profileigenschaft;
  Umschalten = Profilwechsel (Dropdown im Mini-Panel). Pro: extrem schlankes
  Panel. Contra: Ad-hoc-Zuschalten einzelner Prompts braucht Profil-Duplikate.
- **Option 2 — Profil + Ad-hoc-Toggle-Liste im Panel** (Checkboxen für diese eine
  Aufnahme). Pro: flexibel. Contra: mehr UI-Zustand.

**Empfehlung:** Option 1 in v1 (Profil-Switcher ist ohnehin gefordert), Option 2 als
Erweiterung, falls sich Ad-hoc-Bedarf zeigt.

### F21: Verhalten nach Abschluss eines Desktop-Diktats — Auto-Insert oder Bestätigung?

Kontext: Am Phone entscheidet der IME-Kontext; am Desktop tippt der Companion per
Ctrl+V in die zuletzt fokussierte App. Das Mini-Panel stiehlt ggf. den Fokus.

- **Option 1 — Auto-Insert in die vorher fokussierte App** (Fokus merken beim
  Hotkey, zurückgeben vor Insert). Pro: „diktieren wie tippen". Contra:
  Fokus-Restauration ist fehleranfällig (UIPI, geschlossene Fenster).
- **Option 2 — Panel zeigt Ergebnis, Insert per Enter/Klick** (+ Option „sofort
  einfügen" als Einstellung).
- **Option 3 — Panel fokus-frei gestalten** (WS_EX_NOACTIVATE-artig), dann ist
  Auto-Insert sicher.

**Empfehlung:** Option 3 anstreben (Panel nimmt nie Fokus, Anzeige läuft parallel
zur Ziel-App), mit Option 2 als Fallback-Einstellung. Zusammenspiel mit dem
Prüfmodus (F18): AmbiguityMode entscheidet, ob eingefügt oder gehalten wird —
identische Semantik wie ADR-0013.

---

## Block F — Migration & Vorgehen

### F22: Wird `APISettingsActivity`/das Android-Settings-Modell im Zuge des Profil-Modells mit-refactored?

Kontext: Modell-/Provider-Wahl lebt auf Android als Pref-Strings + 783-Zeilen-
Activity; das neue Modell (ProviderConfig/ModelRef/Profile) ist entitätenbasiert.

- **Option 1 — ja, Android migriert auf das Entitätenmodell** (Prefs → DB-Migration,
  Activity-Umbau). Pro: ein Modell überall, Teilen funktioniert von Android aus.
  Contra: großer Umbau am stabilen Bestand.
- **Option 2 — Desktop-first; Android liest weiter Prefs**, ein Adapter mappt beim
  Hub-Sync. Contra: zwei Konfigurationswelten auf Zeit.

**Empfehlung:** Option 1, aber als eigene, späte Ausbaustufe (nach Desktop-v1) —
„von Anfang an vorgesehen" heißt: Entitätenmodell und Wire-Format ab Tag 1 so
entwerfen, dass Android verlustfrei migrieren kann (Superset der heutigen Prefs).

### F23: Bleibt der Datei-Export (SAF-JSON) als Teilen-Weg erhalten?

Kontext: PromptImportExport v2 ist das heutige Teilen-Feature; der Hub wird der
neue Weg.

- **Option 1 — beide:** Datei-Export bleibt (offline, hub-los), Format v3 = dieselbe
  Serialisierung wie das Hub-Wire-Format (SSoT in `:shared`).
- **Option 2 — Datei-Export deprecaten.**

**Empfehlung:** Option 1 — der Aufwand ist minimal, wenn Hub- und Dateiformat
dieselbe Codec-Implementierung teilen, und es liefert den Migrationspfad (Export
alt → Import neu).

### F24: Name der Preset-Einheit — „Profil"?

Kontext: konzept-skizze.md §5. Kandidaten: **Profil** (Empfehlung), Preset, Setup,
Workflow, Set, Modus.

- „Profil": vertraut (Browser-/VS-Code-/OBS-Profile), DE/EN-identisch, trägt
  „Kombination + umschaltbar + teilbar". Risiko: Kollision mit späterem
  „Benutzerprofil" bei Accounts.
- „Preset": kollisionsfrei, klingt aber nach reinen Parameterwerten.

**Empfehlung:** „Profil" (`Profile` im Code), Fallback „Preset" falls F7 Richtung
Accounts/Multi-Tenant geht.

---

## Block G — Peer-to-Peer-Sync & Verteilung (Nachtrag)

### F25: Wer darf Peer-Anbieter sein — nur Companions, oder auch Android/headless?

Kontext: ADR-0017 legt fest, dass das Phone server-los bleibt; jeder Companion
bringt bereits einen Tailnet-adressierbaren Ktor-Server mit. Ein „Hub" wäre
derselbe Server-Code ohne UI auf einer VM.

- **Option 1 — Companions + optionaler headless Hub-Peer (gleicher Code):** Pro:
  ein Protokoll, eine Server-Implementierung, ADR-0017 bleibt für das Phone
  unangetastet; Android teilt, indem es auf den eigenen Companion/Hub publiziert.
  Contra: Teilen direkt von Phone zu Phone geht nicht (braucht einen Desktop-Peer
  im Verbund).
- **Option 2 — auch Android als Anbieter:** Supersede von ADR-0017 (Phone bekommt
  einen Server), Akku-/Erreichbarkeits-Probleme.

**Empfehlung:** Option 1. „Server ist nur ein weiterer Peer" wird damit wörtlich:
Der headless Hub ist eine Deployment-Variante des Companion-Servers.

### F26: Wie finden sich Peers im Tailnet — manuelle Adresse oder Discovery?

Kontext: Jeder Teilnehmer braucht eine Adresse; Tailscale liefert stabile
MagicDNS-Namen (gewohntes Muster im Bestand: `*.ts.net`).

- **Option 1 — manuelle Eingabe von MagicDNS-Name+Port + Pairing-Token:** Pro:
  trivial, kein neuer Mechanismus, funktioniert tailnet-übergreifend (Tailscale-
  Sharing/Funnel). Contra: Tippen einer Adresse beim Einrichten.
- **Option 2 — Tailscale-API/CLI-Enumeration (`tailscale status --json`):** Peers
  im eigenen Tailnet automatisch listen und anpingen (`/v1/health` mit
  `supportsCatalog`-Flag). Pro: komfortabel. Contra: bindet an lokale
  Tailscale-Installation + nur eigenes Tailnet.
- **Option 3 — QR/Link wie beim bestehenden Phone-Pairing** (`dictate://pair`-
  Muster wiederverwenden, um Adresse+Token zu transportieren).

**Empfehlung:** Option 1 + 3 in v1 (Pairing-URI-Muster existiert samt QR-Code);
Option 2 als Komfort-Ausbau, hinter einem Port (keine harte Tailscale-Kopplung —
Tailscale ist „optimal", laut Anforderung aber nicht zwingend).

### F27: Hash-Granularität und Change-Detection — wie wird abgeglichen?

Kontext: Anforderung: pro bezogenem Datum wird ein Hash gespeichert und
abgeglichen; bei Abweichung neu ziehen. Naives Einzel-Polling pro Entität skaliert
schlecht und macht „hat sich irgendwas geändert?" teuer.

- **Option 1 — zweistufig: Katalog-Root-Hash + per-Entity-Content-Hash:** ein
  `GET /v1/catalog` liefert Root-Hash (Hash über sortierte Entity-Hashes) + Index;
  Root gleich → fertig (ein Request); sonst Index diffen, nur Geändertes ziehen.
  Pro: billiges Polling, erfüllt die Hash-pro-Datum-Anforderung exakt. Contra:
  kanonische Serialisierung muss stabil definiert sein (Feld-Reihenfolge!).
- **Option 2 — nur per-Entity-Hashes, Einzel-Requests:** einfachster Code, N
  Requests pro Lauf.
- **Option 3 — voller Merkle-Baum:** überdimensioniert für Dutzende Entitäten.

**Empfehlung:** Option 1. Die kanonische Serialisierung ist ohnehin nötig (sie ist
das v3-Export-Format, SSoT in `:shared`); der Hash über den *verschlüsselten*
Credential-Blob hält Secrets aus dem Abgleich heraus.

### F28: Wie funktioniert die Änderungs-Benachrichtigung — wer pollt, wie oft, wie wird angezeigt?

Kontext: „Bei Änderungen soll eine Benachrichtigung erfolgen" — bei pull-only gibt
es keinen Push vom Anbieter.

- **Option 1 — jedes Gerät pollt selbst** (Companion: Timer, z. B. stündlich +
  bei UI-Öffnen; Android: WorkManager-Periodik + beim App-Start) und zeigt lokal
  Tray-/System-Notification + Badge im Peer Explorer. Pro: kein neuer Kanal,
  offline-robust. Contra: doppeltes Polling pro Haushalt.
- **Option 2 — der eigene Companion pollt zentral** und das Phone erfährt es beim
  nächsten Kontakt: scheitert an der Rollenrichtung (Companion kann das Phone
  nicht erreichen, ADR-0017 kein Back-Channel).
- **Option 3 — Push (WebSocket/SSE zwischen Peers):** widerspricht der
  Pull-Doktrin, stehende Verbindungen für seltene Ereignisse.

**Empfehlung:** Option 1. Benachrichtigung ist Ergebnis des lokalen Sync-Laufs;
Intervall konfigurierbar, Default konservativ (Prompts/Profile ändern sich selten).

### F29: Konfliktverhalten — was passiert mit lokal editierten abonnierten Daten?

Kontext: Ein Abo zieht Änderungen des Anbieters nach; wenn der Bezieher die Kopie
lokal editiert hat, kollidieren beide Stände. Merge-Logik für Prompts/Profile ist
unverhältnismäßig.

- **Option 1 — abonnierte Kopien sind read-only; „Bearbeiten" = explizites
  Abkoppeln als Fork** (sourceRef bleibt als Herkunft): Pro: Konflikt strukturell
  unmöglich, klare UX („dieses Prompt gehört Peer X; eigene Version erstellen?").
  Contra: kein „lokaler Patch, der Updates weiter bekommt".
- **Option 2 — editierbar, bei Peer-Update Dialog überschreiben/behalten:** Drift-
  Erkennung via Hash vorhanden, aber wiederkehrende Entscheidungs-Dialoge nerven.
- **Option 3 — Drei-Wege-Merge:** überdimensioniert.

**Empfehlung:** Option 1 — deckt „Server-Prompts kopierbar" + „Presets
duplizierbar" direkt ab; der Peer Explorer zeigt abgekoppelte Einträge als
„lokal abgekoppelt (Update verfügbar)".

### F30: Trust- und Identitätsmodell der Peers — was identifiziert einen Peer?

Kontext: Verschiedene *User* sollen sich verbinden; heute gibt es nur
Geräte-Pairing (Token → Secret-Hash) innerhalb eines Haushalts.

- **Option 1 — Peer = Adresse + Pairing-Credential pro Beziehung** (bestehendes
  Modell verallgemeinert; Anzeigename vom Peer selbst gemeldet): Pro: kein
  Account-System, Code existiert. Contra: Identität ist nicht kryptografisch an
  eine Person gebunden (Umzug der Adresse = neue Beziehung); Impersonation nur
  durch Tailnet-Zugang + Token-Besitz begrenzt.
- **Option 2 — Peer-Schlüsselpaar (Ed25519), Adresse ist nur Transport:** stabile
  kryptografische Identität, Basis für F12-Stufe-3 (sealed-box) und signierte
  Kataloge. Contra: Schlüsselverwaltung/Rotation.
- **Option 3 — zentrale Accounts:** erst bei Multi-Tenant-Öffnung (F7).

**Empfehlung:** Option 1 für v1, aber das Datenmodell bekommt ein
`peerId`-Feld, das später ein Public-Key-Fingerprint werden kann (Option 2 als
vorgesehene Evolution) — kostet jetzt nichts, verhindert spätere Umbenennung.

### F31: Wie viel Vorbereitung für den Langfrist-Server-Pfad ist richtig?

Kontext: Langfristig sollen theoretisch sämtliche API-Zugriffe über einen Server
laufen können (Keys nur serverseitig, Prompts/Berechnungen serverseitig
ausführbar) — aber ausdrücklich nur architektonisch vorbereiten, keine
Implementierung, keine übermäßige Komplexität.

- **Option 1 — drei Nahtstellen (Konzept-Skizze §4):** (a) `AIOrchestrator`/
  Runner-Interfaces als einzige AI-Naht (entsteht durch die `:shared-ai`-Extraktion
  sowieso), (b) reservierter Enum-Wert `ProviderConfig.kind = GATEWAY`
  (dokumentiert, nicht implementiert), (c) Protokoll-Namensraum, der eine spätere
  additive `/v1/ai/*`-Familie zulässt. Pro: null Laufzeit-Komplexität, Umstieg
  später = neuer Runner + neue Endpoint-Familie. Contra: serverseitige
  Prompt-Ausführung („Prompt nur per Referenz, Text bleibt beim Anbieter") ist
  damit noch nicht designt — bewusst.
- **Option 2 — zusätzlich jetzt schon ein Gateway-Protokoll spezifizieren (ohne
  Implementierung):** Pro: Wire-Design früh validiert. Contra: Spekulation ohne
  Nutzer — genau die verbotene Komplexität.

**Empfehlung:** Option 1; Punkt (b)+(c) werden in der Peer-Katalog-ADR als
„Reserved for future use" festgehalten, mehr nicht.

### F32: Ein Protokoll für alles — Katalog-Familie auf dem bestehenden Wire-Stack?

Kontext: Es existieren bereits zwei Payload-Familien (Dispatch/Sync + Input) auf
einem Stack (`:shared`-DTOs, ProtocolCodec, Konform, additive Endpoints per
ADR-0025-Muster). Die Katalog-/Abo-Familie könnte denselben Stack nutzen oder ein
separates Protokoll werden.

- **Option 1 — gleiche Familie, gleicher Stack** (`/v1/catalog/*`,
  `supportsCatalog`-Health-Flag, Konform-Validierung, ErrorEnvelope): Pro: ein
  Codec, eine Versionierungs-Doktrin, E2E-Test-Muster existiert; „Server ist nur
  ein weiterer Peer" wird protokollarisch wahr. Contra: `:shared` wächst.
- **Option 2 — separates Protokoll (z. B. generisches Sync-Framework):** Pro:
  Unabhängigkeit. Contra: zweite Codec-/Versionierungs-Welt, verletzt die
  ADR-0016-SSoT-Idee ohne Not.

**Empfehlung:** Klar Option 1.

### F33: Wie verhält sich das System bei offline/unerreichbaren Peers?

Kontext: Peers sind Endgeräte (Desktop-PCs), keine Hochverfügbarkeits-Server —
ein Anbieter-PC ist nachts aus, ein Laptop verlässt das Tailnet. Der Sync-Lauf
muss damit als Normalfall umgehen, nicht als Fehler.

- **Option 1 — still tolerieren mit Staleness-Anzeige:** Abos behalten die letzte
  Kopie (voll funktionsfähig — Kopie-Semantik zahlt sich hier aus); der Peer
  Explorer zeigt „zuletzt erreicht vor N Tagen"; kein Fehler-Toast bei jedem
  Fehlversuch, nur ein dezenter Status. Erst nach konfigurierbarer Schwelle
  (z. B. 30 Tage) ein sichtbarer Hinweis „Peer dauerhaft offline — Abo lösen?".
- **Option 2 — Fehler pro fehlgeschlagenem Lauf melden:** ehrlich, aber nervt bei
  einem Peer, der planmäßig nur abends an ist.
- **Option 3 — Replikation über Zwischen-Peers** (Hub-Peer cached fremde
  Kataloge): erhöht Verfügbarkeit, macht aber aus dem P2P-Modell faktisch wieder
  eine Server-Topologie und wirft Weiterverteilungs-/Trust-Fragen auf.

**Empfehlung:** Option 1. Option 3 nur als bewusste spätere Entscheidung, falls
sich Offline-Anbieter als reales Problem zeigen (dann ohnehin ADR-würdig, weil
Weiterverteilung fremder Inhalte).

### F34: Was zeigt der Peer Explorer — nur Provenienz oder auch Angebots-/Netzsicht?

Kontext: Kernanforderung ist „ersichtlich, von wem welche Daten bezogen wurden"
(Provenienz). Naheliegende Erweiterungen konkurrieren um denselben Screen.

- **Option 1 — nur Bezugs-Sicht:** Peers → bezogene Entitäten (Typ, Modus
  Abo/One-Shot, letzter Abgleich, Zustand aktuell/Update/abgekoppelt, Aktionen).
  Minimal, erfüllt die Anforderung wörtlich.
- **Option 2 — Bezugs- + Angebots-Sicht (Empfehlung):** zusätzlich „Was biete ich
  an?" (eigene Entitäten mit `visibility: shared`, wer hat sie zuletzt abgeholt).
  Pro: ohne diese Sicht ist Teilen ein Blindflug; Abholer-Anzeige ist aus den
  Server-Logs/Device-Auth trivial ableitbar. Contra: etwas mehr UI.
- **Option 3 — zusätzlich Netz-/Katalog-Browser** (fremde Kataloge durchstöbern
  und direkt abonnieren): fachlich der „Prompt-Editor lädt vom Server"-Flow —
  gehört eher in die jeweiligen Editor-UIs (Prompt-/Profil-Listen mit
  Peer-Filter), der Peer Explorer verlinkt nur dorthin.

**Empfehlung:** Option 2, mit Option-3-Funktionalität in den Editor-UIs statt im
Explorer (eine Zuständigkeit pro Screen). Gilt für Desktop (Browser-UI) voll;
Android bekommt eine schlanke Read-only-Variante in den Settings.

---

## Top-10 (Priorisierung für die gemeinsame Beantwortung)

F1 (Browser wie hart?) → F5 (Warmhalte-/Fenstermechanik) → F4 (Audio wo?) →
F25+F7 (Peer-Topologie: wer bietet an, braucht es einen headless Hub-Peer,
Zielgruppe des Teilens) → F29 (Konfliktmodell abonnierter Daten) →
F12 (Zero-Knowledge Keys) → F11 (Secret-Store projektweit) →
F15 (Desktop-Schema-Umfang) → F16 (History-Ort) →
F17+F24 (Profil-Inhalt + Name).

Nachrangig, aber früh festzuzurren, weil datenmodell-prägend: F27
(Hash-/Katalog-Mechanik), F30 (peerId zukunftsfest), F31 (Gateway-Reservierung),
F22 (Android-Migration). Reine UX-Detailfragen für später: F33 (Offline-Peers),
F34 (Peer-Explorer-Scope).
