# ADR-0017: Client/Server Role Split, Transport, and Pairing over Tailscale

**Status:** Accepted
**Subsystem:** protocol, security
**Scope:** Project-Wide
**Date:** 2026-07-14
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0016.** That ADR owns the wire format — the typed DTOs,
> the single `ProtocolCodec` entry point, the `ErrorEnvelope`, and protocol
> versioning. This ADR decides *who talks to whom over what* and *how the two
> sides prove they are allowed to* — the transport and trust layer that carries
> the ADR-0016 messages.

## Research

The Windows-Dispatch package extends Dictate — an Android speech-to-text keyboard — with a
desktop companion that types finished dictations into the active window on a PC. The decision
here is grounded in the code already built and green across Blocks 1–3:

- **The companion is the only HTTP surface.** `CompanionServer` binds one embedded Ktor CIO
  engine and mounts the whole protocol in `companionModule` — `pairRoutes` unauthenticated, and
  `dispatchRoutes`/`syncRoutes`/`healthRoutes` behind an `authenticated { … }` wrapper
  (`companion/src/main/kotlin/net/devemperor/dictate/companion/server/CompanionServer.kt:41-86`).
  There is no server code on the phone; the app is purely a client.

- **Delivery confirmation is the HTTP response, nothing else.** `DispatchClient.dispatch` returns
  `Success` *only* for a parsed 200 whose `delivered` flag is true, and downgrades a
  `delivered = false` 200 to `Failure` — every other outcome (timeout, aborted connection,
  unparsable body) is already a failure that falls into the pending-part mechanism
  (`shared/src/main/kotlin/net/devemperor/dictate/shared/client/DispatchClient.kt:62-88`).
  No second channel, no ACK round-trip, no back-channel exists in the codebase.

- **Two-layer trust is implemented, not aspirational.** Pairing exchanges a one-time token for a
  256-bit device secret; the desktop persists only the SHA-256 hash and compares constant-time
  (`companion/.../domain/PairingService.kt:77-103`, `Secrets.kt:31-53`), and every later request
  is checked by `AuthService.authenticate`, which hashes even an unknown device id against a dummy
  hash to keep failures indistinguishable
  (`companion/src/main/kotlin/net/devemperor/dictate/companion/domain/AuthService.kt:15-26`).

- **Both pairing paths are equivalent and CAMERA is optional.** `WindowsPairingActivity` wires a
  QR scanner *and* manual URL + 8-char code entry, reflecting a scan into the manual fields, and
  treats a declined camera as a non-error
  (`app/src/main/java/net/devemperor/dictate/settings/WindowsPairingActivity.java:49-51,83,131-161`);
  the manifest declares `CAMERA` with `android:required="false"`
  (`app/src/main/AndroidManifest.xml:15-16`).

This ADR records the reasoning behind that built shape. It corresponds to the ADR-0017 row and the
F-3/F-4/F-7 decisions in the windows-dispatch plan.

## Context

The companion must be reachable from the phone and must accept typed text into the active window on
the PC. Two questions have to be answered before any wire message can flow: **which side is the
server**, and **how does the phone reach it without opening a hole in the user's network**.

Constraints and prior state:

- The app already keeps every provider API key (OpenAI, Anthropic, Groq, …) in plain
  `SharedPreferences` under `net.devemperor.dictate`. There is no encrypted-secret tier in the
  project today.
- A PC on a home or office network is behind NAT; classic reachability would mean port-forwarding
  or a relay — both are operational burdens and attack surface the user must not have to think
  about.
- The user already runs **Tailscale**: every device gets a stable MagicDNS name and a
  WireGuard-encrypted point-to-point link with no port-forwarding. But a tailnet can hold *other*
  people's devices (a shared node, a work laptop) — being *on* the tailnet is not the same as
  being *authorized* to type into someone's active window.
- Typing into the active window is a high-trust action: whoever can call `POST /v1/dispatch`
  effectively controls the keyboard of the PC for the length of that text.

## Decision

**The desktop companion is the only server; the phone is a pure client. Reachability is provided by
Tailscale; an application-layer credential sits on top as defense-in-depth. Delivery confirmation
is the HTTP response itself — there is no second acknowledgement channel.**

### 1. Role split and transport

The companion runs a single embedded **Ktor CIO** engine. CIO (not Netty) because a local
single-client server needs none of Netty's machinery and every megabyte would ride along in the
jpackage bundle. The phone holds no server; it drives the companion through `DispatchClient`, which
is the phone's *entire* view of the protocol.

**Delivery confirmation = the HTTP response.** A dispatch is confirmed delivered only by a 200 with
`delivered = true`; a `delivered = false` 200 is treated as failure (the server should have sent
503, so the flag is trusted over the status). There is deliberately no ACK protocol, no back-channel
and no second confirmation message: the request/response round-trip *is* the confirmation, and any
non-confirmation (timeout, dropped connection, unparsable body, `delivered = false`) routes the text
into the existing pending-part fallback (ADR-0019). This keeps the failure surface to exactly one
place.

### 2. Two-layer trust — Tailscale plus an application credential

Tailscale carries the transport: MagicDNS gives the companion a stable name, WireGuard encrypts the
link end-to-end, and no port needs to be forwarded. That is the *network* layer.

On top of it sits an **application** credential, because a tailnet device is not the same as an
authorized device — any other node in the tailnet could otherwise type text into the active window.
The handshake:

1. The desktop issues a **one-time pairing token**: an 8-character Crockford-Base32 code (no
   `I`/`L`/`O`/`U`, the characters humans confuse when reading a code off a screen), valid for
   **120 s**, held **in memory only** (`AtomicReference`, never a DB row) so restarting the
   companion invalidates any open QR code and a token can never outlive the process that showed it.
2. The desktop shows that token **simultaneously** as a `dictate://pair?v=1&url=<base64url>&t=<token>`
   QR code and as a typable code. The base URL is base64url-encoded so its own `:` `/` and query
   never collide with the outer URI grammar, and it carries its scheme so `tailscale serve` (https)
   and a plain LAN address (http) both work.
3. The phone scans or types it and calls `POST /v1/pair` (unauthenticated — the token in the body
   *is* the credential). The server redeems the token for a **long-lived 256-bit device secret**,
   persists a `Device` holding only the secret's **SHA-256 hash**, and returns the secret once.
4. The token is **burned on success**, so a reuse is answered `409` rather than `401`. It is also
   burned on a *presented-but-expired* redemption — a token that has already been on a network is
   never re-shown after a clock correction.
5. Every later request carries the secret as a bearer header. `AuthService` hashes the presented
   secret and compares it **constant-time** against the stored hash, and hashes even an *unknown*
   device id against a dummy hash so "unknown device" is not measurably faster than "wrong secret"
   (no device-enumeration timing oracle).

The desktop never stores the secret itself, only its hash; the phone never re-fetches it, only
re-sends it.

### 3. Bind address (F-7)

The server binds to the **Tailscale interface address**. If no tailnet interface is found it falls
back to `0.0.0.0` (listening on every interface, protected then only by the firewall and the bearer)
**and shows a visible warning in the UI**. The bind address is user-configurable in the settings
screen. The warning is load-bearing: without it, the `0.0.0.0` fallback would silently widen the
exposure from "one tailnet" to "the whole LAN".

### 4. Secret storage on the phone (F-3)

The device secret is stored in plain `SharedPreferences` — **the same tier the app already keeps
every provider API key at**. This is a deliberate consistency choice over a point improvement: the
Windows secret is no more valuable than the OpenAI key sitting next to it, and raising the bar to
`EncryptedSharedPreferences` is a **project-wide** concern spanning *all* secrets and its own
package — not a Windows theme. It is recorded as a follow-up, not done here.

### 5. Pairing paths (F-4)

QR scan and manual entry (URL + 8-char code) are **equivalent** — a scan is merely reflected into
the manual fields before pairing. `CAMERA` is an **optional, runtime-requested** permission
(`<uses-feature android:name="android.hardware.camera" android:required="false" />`): a user who
will not grant a keyboard app camera access types the code instead, and declining is not an error.
The companion shows QR and code in parallel.

### Scope of this Convention

Project-Wide. The rules bind every part of the client/server transport:

- **Applies to** the companion server (its single-server role, CIO engine, bind-address policy), the
  phone client (`DispatchClient` as the sole protocol surface), the pairing handshake (token TTL,
  one-time semantics, hashed-secret storage, constant-time comparison), and the phone-side secret
  storage tier.
- **Exempt:** the *wire format* of the messages carried (owned by ADR-0016), the *text-insertion*
  mechanism the server invokes (ADR-0018), the *pipeline dispatch path and state* on the phone
  (ADR-0019), and the *sync* endpoints' data semantics (ADR-0020). This ADR owns transport and
  trust; those own what travels over it.

## Alternatives Considered

1. **Phone as server, or a bidirectional peer relationship.** The phone would host an endpoint the
   desktop calls, or both would expose surfaces. Rejected: a phone is intermittently reachable and
   frequently NATed even inside a tailnet's expectations; making it a server multiplies the attack
   surface and the reachability problem. One server, one client is the smallest trustworthy shape.

2. **A second acknowledgement channel / explicit ACK protocol.** After the 200, the desktop would
   confirm insertion out-of-band (a callback, a websocket, a poll). Rejected: it doubles the failure
   surface (now *two* things can fail) for information the synchronous response already carries. The
   `delivered` flag on the dispatch response is the single source of truth; collapsing confirmation
   into the response is what keeps the pending-part fallback (ADR-0019) the *only* recovery path.

3. **Tailscale alone, no application credential.** Trust every node that can reach the port.
   Rejected: a tailnet routinely contains devices that are not *this* user's authorized keyboard
   (shared nodes, a second person's laptop). Reachability ≠ authorization; without the bearer, any
   tailnet node could type into the active window.

4. **Application credential alone, over the raw LAN / port-forwarding.** Skip Tailscale, secure the
   channel with the bearer only. Rejected: it forces port-forwarding or a relay, exposes the port to
   the open internet, and ships an unencrypted transport that the bearer would have to compensate
   for. Tailscale gives WireGuard encryption and NAT traversal for free; the two layers are cheaper
   *and* stronger than either alone.

5. **`EncryptedSharedPreferences` for the device secret only.** Store the Windows secret one tier
   above the API keys. Rejected as inconsistent: it would leave the more-sensitive-in-aggregate API
   keys in plaintext while gold-plating one secret, and encourage the illusion that secrets are
   protected when most are not. Uniform storage plus a project-wide follow-up is the honest option.

6. **Encoding the base URL raw in the pairing URI.** Rejected: the base URL's own `:` `/` and query
   collide with the outer `dictate://pair?…` grammar in more JDKs than they survive; base64url makes
   the parse total and lets one URI carry both `http` LAN and `https` `tailscale serve` targets.

## Consequences

**Positive:**

- One server, one client, one confirmation path — the entire failure surface of dispatch is a single
  synchronous call, and every non-confirmation routes through the one pending-part fallback.
- Two independent layers must both hold for an attacker: they must be a tailnet member *and* hold a
  valid device secret. Compromising the tailnet alone does not let anyone type into the active
  window.
- The desktop stores only a salt-free SHA-256 of the secret; a leak of the companion's device store
  does not yield a usable credential, and constant-time comparison plus the dummy-hash path give no
  timing oracle for the secret or for device enumeration.
- In-memory one-time tokens with a 120 s TTL mean an abandoned or screenshotted QR code expires on
  its own and never survives a restart.
- The user is never asked to forward a port or grant camera access; both the network hole and the
  camera permission are avoidable.

**Negative:**

- The device secret lives in plain `SharedPreferences` (F-3). Accepted deliberately for consistency
  with the existing API keys; a project-wide encrypted-secrets migration is the recorded follow-up.
- Two trust layers mean one more moving part to reason about than a single bearer or a single VPN
  would — a reader must understand that Tailscale and the application credential are *both* required
  and serve *different* jobs.
- The system depends on Tailscale being installed and up on both ends; when it is not, the fallback
  (below) is strictly worse.

**Failure Modes:**

- **`0.0.0.0` fallback widens exposure to the whole LAN.** When no tailnet interface is found the
  server listens on every interface, protected only by the firewall and the bearer secret. The
  *visible UI warning* is the mitigation — but a user who dismisses it is now reachable by any host
  on the local network, not just the tailnet. The bearer still gates dispatch, so this is a widened
  attack surface, not an open door.
- **A lost or leaked device secret types into the active window until unpaired.** Anyone holding the
  256-bit secret can call `POST /v1/dispatch` and have text typed into whatever window is focused on
  the PC, for as long as the pairing stands. Recovery is to unpair the device on the desktop, which
  drops its `Device` row and invalidates the secret. There is no automatic expiry of a paired
  secret.
- **A `delivered = false` 200 is trusted over the status code.** If the server ever returns 200 with
  `delivered = false` (a server bug — it should send 503), the client treats it as failure and the
  text becomes a pending part. This is the safe direction (text is never silently dropped), but it
  means a misbehaving server surfaces as a spurious pending part rather than a hard error.

## References

- **Related Plan:** [windows-dispatch](../plans/) — the windows-dispatch plan (pending archival);
  ADR-0017 row + F-3/F-4/F-7 decisions in §7. Reciprocated by the plan's ADR table.
- **Related ADRs:**
  - ADR-0015 — module topology (`shared/` JVM + `companion/` Compose Desktop); the client and the
    auth primitives this ADR describes live in `shared/` and are shared verbatim between phone and
    companion.
  - ADR-0016 — the wire protocol, `ProtocolCodec`, and `ErrorEnvelope` this transport carries; the
    `UNAUTHORIZED` error code and `PROTOCOL_VERSION_UNSUPPORTED` handshake are defined there.
  - ADR-0018 — the server invokes the `TextInserter` port on a confirmed dispatch; that ADR owns the
    insertion mechanism and its outcomes.
  - ADR-0019 — the phone's dispatch path and state, and the `WINDOWS_UNAUTHORIZED → re-pair`
    recovery that consumes this ADR's auth failures.
  - ADR-0020 — the sync endpoints (`/v1/sync`, `/v1/sync/cursor`) mounted behind this ADR's
    `authenticated` wrapper.
- **Implementation:**
  - `shared/src/main/kotlin/net/devemperor/dictate/shared/auth/` — `PairingUri.kt`, `Secrets.kt`,
    `AuthHeaders.kt`.
  - `shared/src/main/kotlin/net/devemperor/dictate/shared/client/DispatchClient.kt` — the phone's
    sole protocol surface and the delivery-confirmation rule.
  - `companion/src/main/kotlin/net/devemperor/dictate/companion/domain/` — `PairingService.kt`,
    `AuthService.kt`; `companion/.../server/CompanionServer.kt` — the single Ktor CIO engine and
    bind address.
  - `app/src/main/java/net/devemperor/dictate/settings/WindowsPairingActivity.java` — QR + manual
    pairing UI; `app/src/main/AndroidManifest.xml` — optional CAMERA feature.
- **Tests:** `PairingServiceTest.kt`, `AuthServiceTest.kt`, `SecretsTest.kt`, `PairingUriTest.kt`.

## Decision History

### 2026-07-14 — Initial proposal

**Trigger:** The Windows-Dispatch feature (design rounds 0–2) had to fix, before any wire message
could be built, which side is the server and how the phone reaches it without opening a network
hole. Round-0 questions F-3 (secret storage), F-4 (QR/CAMERA), and F-7 (bind address) all resolved
into this decision.

**Before:** No desktop companion existed. The app kept every provider API key in plain
`SharedPreferences` and had no notion of a remote text target, no transport, and no pairing.

**After:** The companion is the only server (Ktor CIO, bound to the Tailscale interface with a
warned `0.0.0.0` fallback); the phone is a pure client whose delivery confirmation is the HTTP
response. A two-layer trust model — Tailscale for reachability plus a paired 256-bit device secret
(hashed, constant-time-compared, one-time-token handshake) for authorization — gates dispatch. The
secret is stored on the phone in plain `SharedPreferences` (consistent with the API keys; encrypted
storage deferred project-wide). QR scan and manual entry are equivalent, CAMERA optional.

**Reasoning:** One server / one client / one confirmation path minimizes the failure surface and
keeps the pending-part fallback (ADR-0019) the single recovery route. Two trust layers are needed
because a tailnet device is not an authorized device — reachability must not imply permission to
type into the active window. Consistency with the existing plaintext-secret tier was chosen over a
point encryption improvement, with a project-wide follow-up recorded, so the security posture is
uniform and honestly represented rather than selectively gold-plated.
