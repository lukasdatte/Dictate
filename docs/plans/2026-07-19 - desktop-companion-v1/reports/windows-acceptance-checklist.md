# Windows Manual Acceptance Checklist — desktop-companion-v1

**Plan:** [→ ../desktop-companion-v1.md](../desktop-companion-v1.md) · **E2E-Runbook:** [→ ./e2e-runbook.md](./e2e-runbook.md)
**Scope:** §2 acceptance criteria **3** (desktop dictation E2E), **4** (review parity), **7** (peer sync) — the parts only a real Windows device can confirm.
**Owner:** Lukas (runs it) · **Status:** ⬜ pending sign-off

> [!NOTE]
> All automated coverage (companion/shared JVM: TC-P1..P4, TC-C1..C4; Android
> emulator: TC-A1..A3) is green before this checklist runs. This document is the
> **manual Windows release gate** — the last mile the Linux CI host cannot reach
> (`RegisterHotKey`, `WS_EX_NOACTIVATE`, DPAPI, `SendInput`). It expands the
> runbook's TC-W1..W5 into concrete, checkable steps. Tick every box; note any
> deviation in the "Observations" line under each block; sign off at the bottom.

## Preconditions

- [ ] Windows host reachable, paired to the phone over Tailscale (ADR-0017).
- [ ] Companion built and launched: `./gradlew :companion:run` (or the packaged MSI).
- [ ] A second companion instance available for the peer-sync block (one may run
      `--headless` as the hub peer).
- [ ] At least one **Profile** configured (transcription + completion ModelRef) with a
      real provider key entered on this device (stored via DPAPI SecretStore).
- [ ] A plain text editor (Notepad / editor field) open as the insertion target.

## Criterion 3 — Desktop Dictation E2E (runbook TC-W1, TC-W5)

Hotkey → warm panel → record → transcribe + post-process via the active profile →
auto-insert; session persisted with steps + conversation.

- [ ] **3.1** Press the configured global hotkey (default e.g. `Ctrl+Alt+Space`). The
      panel appears **within ~100 ms**, frameless and always-on-top.
- [ ] **3.2** The focused editor keeps keyboard focus (focus-free path), **or** the
      documented focus-restore fallback restores it before insert (ADR-0032, D4.3 — a
      fallback is a pass, not a failure). Note which path was taken.
- [ ] **3.3** Start recording; the amplitude curve animates (shared `AmplitudeProcessor`).
- [ ] **3.4** Exercise **Pause → Resume → Stop**: one continuous session results.
- [ ] **3.5** In a fresh dictation, **Discard** mid-recording: no session is persisted.
- [ ] **3.6** Complete a dictation with a real provider (TC-W5): transcription +
      post-processing run via the **active profile**; the result **auto-inserts at the
      caret** in the focused editor (Windows path). No auth/format/size error.
- [ ] **3.7** Open the companion history: the session is persisted with its
      **transcription + ≥1 processing step + conversation message(s)**, `origin` marked
      as desktop-recorded.

_Observations (3):_ ______________________________________________

## Criterion 4 — Review-Mode Parity incl. Re-Dictate (runbook TC-W1 review round)

`AmbiguityMode` semantics identical to ADR-0013; re-dictate produces a refinement
session + conversation continuation; verdict via the shared `ReviewDecision`.

- [ ] **4.1** With a Profile whose `AmbiguityMode` forces/allows review, dictate an
      intentionally ambiguous phrase so the model returns `needsClarification`.
- [ ] **4.2** The review panel **holds** (does not auto-insert), showing the model's
      question and its draft (Accept / Re-dictate / Discard).
- [ ] **4.3** **Re-dictate** a spoken clarification: it runs as a **conversation
      continuation** turn (not a fresh transcription-from-scratch), the panel updates,
      and a `REVIEW_REFINEMENT` session is recorded.
- [ ] **4.4** **Accept**: the final text inserts at the caret; `final_output_text` is
      persisted (crash-resilience, ADR-0013 §3).
- [ ] **4.5** Repeat once with a **non-forcing** AmbiguityMode and an unambiguous phrase:
      it inserts directly with **no** review panel (verdict = INSERT). Behaviour matches
      the Android review semantics (one shared `ReviewDecision` code path).

_Observations (4):_ ______________________________________________

## Criterion 7 — Peer-Sync across two real instances (runbook TC-W3, TC-W4)

Two companions share Prompt/Profile/ModelRef/Credential; SUBSCRIBE detects change via
root-hash; ONE_SHOT stays; a forked copy is never overwritten; credential lands only in
the receiver's SecretStore.

- [ ] **7.1** Pair companion **B** to companion **A** (pairing model, F10). If A is
      `--headless`, confirm it serves its catalog with no window.
- [ ] **7.2** From A, offer a **Profile** (with its ModelRef + Prompt + referenced
      **Credential**). From B, browse A's catalog in the Peer Explorer and **SUBSCRIBE**.
- [ ] **7.3** B pulls the entities; the **credential lands only in B's SecretStore**
      (never in a column, never shown in a UI field); an audit row is written on A per
      credential delivery.
- [ ] **7.4** Change the Prompt on A. On B's next poll, the **root-hash diff** is
      detected, the subscribed copy updates, and a **system/tray notification** fires.
- [ ] **7.5** A **ONE_SHOT** copy on B: confirm it stays unchanged (keeps its `sourceRef`).
- [ ] **7.6** **Fork** a subscribed copy on B (edit → decouple) and change the upstream on
      A: B's forked copy is **never overwritten** (`subscription_mode = NULL`); an
      "upstream changed" hint may show.
- [ ] **7.7** Take A **offline**: B shows a **staleness** indicator, no error spam.
- [ ] **7.8** (Optional, TC-W4) On a tailnet host with `tailscale` present, discovery
      enumerates catalog-capable peers; without the CLI it degrades to the manual path.

_Observations (7):_ ______________________________________________

## Sign-off

- [ ] Criterion 3 — Desktop dictation E2E: **PASS**
- [ ] Criterion 4 — Review parity incl. re-dictate: **PASS**
- [ ] Criterion 7 — Peer sync: **PASS**

> [!IMPORTANT]
> §2 criterion 9 (ADR completeness) is **already satisfied** — independently of this
> checklist — by the 8 ADR drafts having been promoted to `docs/decisions/` (ADR-0028
> through ADR-0035) plus their index rows. A fully ticked-and-signed checklist is what
> closes the **Windows-only** criteria **3 / 4 / 7**, which the Linux CI host cannot
> reach. Any FAIL routes to issue-triage per the runbook's "Failure Routing".

**Tester:** ______________  **Date:** ____________  **Build/commit:** ____________
