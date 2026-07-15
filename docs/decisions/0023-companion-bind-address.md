# ADR-0023: Bind-Address Selection — Materialised Tailscale Default and Multi-Connector Binding

**Status:** Accepted
**Subsystem:** companion, security
**Date:** 2026-07-15
**Supersedes:** —
**Author:** Lukas + Claude Code

> **Cooperates with ADR-0017.** That ADR owns the client/server role split, the Tailscale transport
> and the two-layer trust model, and its §3 (F-7) is the first statement of a bind-address policy.
> This ADR *refines* §3: it keeps the intent (bind the tailnet, warn when listening wider) and makes
> the policy precise where §3 was written before the code existed — how the choice is stored, what
> happens when several interfaces are wanted at once, and what happens when a chosen address is gone
> at start-up. ADR-0017 remains authoritative for everything else about the transport.

## Plain-language summary

The companion is a small server on the user's PC that the phone sends dictated text to. It has to
decide **which network address to listen on**. Listening on "everything" (`0.0.0.0`) means every
machine on the office or café LAN can reach the port; listening only on the **Tailscale** address
means only the user's own private mesh network can. Tailscale addresses are recognisable: they all
live in the range `100.64.0.0/10`.

This ADR decides: show the user every address their PC has, let them tick the ones to listen on,
default to *Tailscale only* when a Tailscale address exists, and never silently widen that back to
"everything" later.

## Research

The decision is grounded in the code as it stood at `f9f02cd` and in ADR-0017 §3:

- **ADR-0017 §3 was never implemented.** It prescribes "the server binds to the Tailscale interface
  address … falls back to `0.0.0.0` … **and shows a visible warning in the UI**"
  (`docs/decisions/0017-client-server-roles-transport-pairing.md:46-52`). The code defaulted to
  `DEFAULT_BIND_ADDRESS = "0.0.0.0"` (`companion/.../domain/CompanionSettings.kt:42` at `f9f02cd`)
  with no warning anywhere in `SettingsScreen.kt`. Every installation therefore listened on the LAN.

- **Advertised address and bind address were independent decisions.** The pairing QR was built from
  `AdvertisedAddress.detect { container.serverName }` (`Main.kt:67` at `f9f02cd`) while the socket
  was bound from `container.settings.bindAddress` (`Main.kt:30`). Nothing connected the two: a user
  who bound `192.168.1.5` while having Tailscale was advertised `100.x` — an address nobody listened
  on. The failure is silent until pairing fails.

- **The bind address was entirely unvalidated.** `SettingsViewModel.setBindAddress`
  (`SettingsViewModel.kt:57-60` at `f9f02cd`) wrote any string through to SQLite; `Main.kt:35`
  called `server.start()` inside `remember { }` with no `try`. A typo (`192.168.1.999`) made the
  application throw on every subsequent start, with no in-app way back — only a hand-edit of the
  SQLite settings table.

- **Ktor 3.1.3 supports multiple connectors on one engine.** Verified against the resolved artifact
  `ktor-server-core-jvm-3.1.3.jar`: `ApplicationEngine.Configuration` exposes
  `getConnectors()/setConnectors(List<EngineConnectorConfig>)`, `EngineConnectorConfigKt.connector`
  exists as an extension, and `embeddedServer(factory, ApplicationEnvironment, configure, module)`
  is available. Multi-address binding needs no engine change and no new dependency.

## Context

The companion must be reachable by the user's phone and by nothing else that can be avoided. Three
forces meet:

- **Security.** A port bound to `0.0.0.0` is reachable by every device on the LAN. The bearer
  credential (ADR-0017 §2) protects the *action*, but the listening socket itself is attack surface
  that Tailscale-only binding removes entirely.
- **Reachability.** Tailscale is not universal. Some machines have no tailnet; some users pair over
  the LAN; some addresses (a VPN that comes up after login) do not exist at configuration time.
- **Honesty.** Whatever the companion binds, the pairing QR must advertise an address that is
  actually served — a QR that pairs a target the phone can never reach is the exact bug `f9f02cd`
  fixed for the hostname case, and it must not return through the bind-address door.

A single free-text field cannot carry this. It cannot enumerate what is available, cannot express
"these two addresses", and cannot warn about what it implies.

## Decision

**The bind configuration is a user-visible selection over an enumerated catalogue of the machine's
own addresses, persisted as a materialised set of literal addresses; the Tailscale address is the
default only on first configuration; and a selection that cannot be bound falls back to loopback
with a visible error — never to `0.0.0.0`.**

### 1. The catalogue and the resolution are domain logic behind a port

`NetworkInterfaces` is a port (`fun interface`, `domain/port/`); `JvmNetworkInterfaces` in
`platform/` is the only code that touches `java.net.NetworkInterface`. `AddressCatalog` (domain)
turns the port's output into `List<BindCandidate(address, interfaceName, kind)>` where `kind` is
`TAILSCALE` (CGNAT `100.64.0.0/10`), `LAN`, `LOOPBACK` or `OTHER`, and `resolve(selection)` turns a
selection plus the live catalogue into a `ResolvedBinding(hosts, advertised, warnings)`.

The reason for the port is testability, not layering purity: the classification rules, the fallback
chain and the advertised-address derivation are exactly the logic that must be provable, and they
cannot be tested against the CI machine's real NICs.

### 2. The selection is materialised, not dynamic

`BindSelection` is `AllInterfaces` (→ `0.0.0.0`) or `Explicit(Set<String>)` — literal addresses.
There is deliberately **no** persistent "Tailscale mode" that re-resolves on every start. Tailscale
is a *default at first configuration*: the address found then is written into `Explicit`, and from
that moment the stored setting states exactly what will be bound.

A dynamic mode would re-pick "whatever is CGNAT right now" at every start. After a re-auth into a
different tailnet that silently binds a different network — the kind of behaviour that is invisible
until it matters. A materialised choice turns that same event into a visible one (see §4).

### 3. Migration never overwrites a deliberate choice

The legacy key `server.bind` is read but no longer written; new state lives in `server.bind.mode`
(`all` | `explicit`) and `server.bind.addresses`. Resolution order:

| Stored state | Result |
|---|---|
| `server.bind.mode` present | that, verbatim |
| legacy `server.bind` ∉ {`0.0.0.0`, blank} | `Explicit(setOf(legacy))` — a manual choice, kept |
| legacy `server.bind` = `0.0.0.0` | `AllInterfaces` — treated as deliberate |
| nothing stored | first configuration: Tailscale if present, else `AllInterfaces` |

The `0.0.0.0` row is the subtle one: it is indistinguishable from the old default. It is nonetheless
treated as a choice, because an update that narrows an existing installation to Tailscale-only would
silently strip reachability from a phone that pairs over the LAN. The user is *offered* the
narrowing (a one-click suggestion in the UI) instead of having it applied. **An update may not
change reachability without being asked — not even in the safer direction.**

The legacy key is left in place rather than deleted: it costs a few bytes and is the return path if
an older companion build is ever run against the same settings table.

### 4. Auto-heal only when unambiguous

If every address in an `Explicit` selection is gone but the catalogue holds **exactly one** candidate
of the same `kind`, the selection is re-pointed at it, persisted, and reported in the UI. This is the
Tailscale-re-auth case (the tailnet address changed) and re-pointing is what the user would do by
hand. With two or more candidates of that kind, any pick is a guess — the companion falls back (§5)
and asks instead.

### 5. A dead selection falls back to loopback, not to `0.0.0.0`

When nothing in the selection can be bound, the server binds `127.0.0.1` and the UI shows a
prominent error ("the server is reachable by nobody — the chosen address no longer exists"). The app
keeps running so the settings remain correctable in-app.

This is where this ADR is **more precise than ADR-0017 §3**. That clause's `0.0.0.0` fallback was
written for "no tailnet interface is found" — the *first-configuration* case, where widening is the
only way to be reachable at all and the warning makes it honest. It was not written for "the address
the user chose has disappeared". Reading it to cover the second case would mean a stopped Tailscale
daemon silently opens the port to the whole LAN — turning a transient network event into a
persistent exposure the user never approved. The first-configuration fallback to `AllInterfaces`
plus its visible warning (§3, last row) is retained exactly as §3 requires.

### 6. The advertised address is derived from the binding

`ResolvedBinding.advertised` is computed *from* `hosts`, by priority `TAILSCALE > LAN > LOOPBACK`;
`null` (no candidate at all) keeps the existing hostname fallback in `Main.kt`. The invariant
`advertised == null || advertised in hosts || hosts == ["0.0.0.0"]` is enforced by test.

Deriving rather than co-deciding is the whole point: "QR advertises an address nobody listens on"
stops being a rule someone must remember and becomes a state the type cannot represent.

### 7. Free text survives as a validated advanced option

The catalogue guides; it must not become a cage. An address can legitimately be absent when the user
configures it (a VPN that comes up after login). A collapsed "Advanced" field accepts a literal IPv4
address, **validated** (which the free-text field never was, see Research) and marked "not currently
available" when it is not in the catalogue.

### Scope

Applies to the companion's listening socket, its settings persistence for that socket, and the
pairing QR's base address. **Exempt:** the wire format (ADR-0016), the trust model and pairing
handshake (ADR-0017 §§1-2, 4-5), and text insertion (ADR-0018). IPv6 is out of scope for this
revision: the enumeration is IPv4-only, matching `AdvertisedAddress`'s existing
`filterIsInstance<Inet4Address>()`. `AddressKind` is open for an `IPV6` constant when it lands.

## Alternatives Considered

1. **Keep the free-text field, only change its default to the Tailscale IP.** The smallest possible
   change. Rejected: it leaves the user typing an address they must first discover elsewhere
   (`ip addr`, the Tailscale tray), leaves the field unvalidated, cannot express multi-address
   binding at all, and leaves QR and bind address as two independent decisions — the actual defects.

2. **A persistent dynamic `TAILSCALE` mode that re-resolves each start.** Self-healing across
   address changes, no materialisation, no auto-heal rule needed. Rejected: the persisted setting
   would no longer state what happens; after a re-auth into another tailnet it binds a different
   network with no signal. §4 buys the same self-healing for the common case while keeping the
   ambiguous case visible.

3. **Auto-widen to `0.0.0.0` when the chosen address is missing (a literal reading of ADR-0017 §3).**
   Maximum reachability, never a dead server. Rejected: it converts "Tailscale is briefly down" into
   "the port is open to the LAN", indefinitely and unannounced. A security posture that degrades on
   a transient network event is not a posture. Loopback plus a visible error is reachable-by-nobody,
   which is *loud* — and loud beats silently wide.

4. **A radio list of single addresses (no multi-select).** Simpler UI, single connector, no
   `boundPort()` ambiguity. Rejected: the "Tailscale plus LAN during migration" case is real (a user
   moving their phone onto the tailnet wants both to work for a day), and Ktor supports multiple
   connectors natively — the simplification would buy little and cost a genuine use case.

5. **Enumerate addresses directly in the UI layer (no domain port).** Fewer types, one file.
   Rejected: the classification, priority, fallback and auto-heal rules are the substance of this
   ADR and would then be testable only on a machine with the right NICs — i.e. not in CI, not at all.

## Consequences

**Positive:**

- The default installation stops listening on the LAN — the security intent of ADR-0017 §3 is real
  for the first time, and the `0.0.0.0` case now carries the warning §3 always demanded.
- QR-versus-bind inconsistency is structurally impossible (§6), not a matter of care.
- The unvalidated-address footgun (an unstartable app after a typo) is closed from two directions:
  the normal path has no free text, and the advanced path validates.
- The catalogue makes the user's own network legible: address, interface name and kind, rather than
  a blank field and a guess.
- New address classes (further VPNs, IPv6) are a `kind` constant plus a predicate — the
  classification is one function, not conditionals spread across the codebase.

**Negative:**

- More types (`BindCandidate`, `BindSelection`, `ResolvedBinding`, `BindWarning`, the port) where
  there was one `String`. *The price for testable rules and a settable-in-one-place invariant.*
- Materialisation (§2) means a changed Tailscale address needs auto-heal (§4) or a click, where a
  dynamic mode would have needed neither. *Paid deliberately for a setting that says what it does.*
- `AddressCatalog` both enumerates and resolves — grazing SRP. They share the priority ordering and
  `resolve` consumes `enumerate`; splitting them now would duplicate the ordering or introduce a
  third object to pass it around. If `resolve` outgrows ~60 lines, `BindResolver(catalog)` is a clean
  later extraction.

**Failure Modes:**

- **`boundPort()` lies under multiple connectors with `port = 0`.** It returns
  `resolvedConnectors().first().port`, but each connector gets its *own* ephemeral port when the port
  is 0. Production always uses a fixed port and tests bind one connector, so the case is not hit
  today — but a future multi-connector ephemeral-port caller would read one connector's port and
  believe it is all of them. `resolvedEndpoints()` is the honest accessor; `boundPort()` documents
  the constraint and fails loudly when it is violated.
- **CIO binds connectors sequentially and one failure aborts the whole start.** This is why the
  selection is filtered against the live catalogue *before* binding. A future caller that constructs
  a `ResolvedBinding` by hand, bypassing `resolve()`, loses that preflight and gets a start-time
  throw instead of a warning.
- **An interface that goes down while running is not handled.** The socket dies with it; there is no
  watcher. This is consistent with the existing "takes effect on next start" semantics
  (`CompanionSettings.kt:16-17`) but means a laptop that suspends and rejoins on a different network
  may need a restart of the companion.
- **`0.0.0.0` selected while a stale `Explicit` entry lingers** is not a real state — `BindSelection`
  is a sealed hierarchy — but a hand-edited settings table can hold `mode = explicit` with an empty
  address list. That parses to the default rather than to an empty bind, matching the
  garbage-tolerance rule of `CompanionSettings` (`CompanionSettings.kt:11-14`).

## References

- **Related Plan:** `tmp/plan-bind-address.md` (worktree `feature/companion-bind-address`) — the plan
  that motivated this ADR; to be rewritten to the archived plan path at promotion.
- Related ADRs: **ADR-0017** — owns client/server roles, transport and pairing; this ADR refines its
  §3 (F-7) bind-address policy. **ADR-0016** — wire format, unaffected. **ADR-0018** — text
  insertion, unaffected. **ADR-0015** — companion monorepo topology and the layering precedent.
- Implementation: `companion/src/main/kotlin/net/devemperor/dictate/companion/domain/net/`,
  `platform/JvmNetworkInterfaces.kt`, `server/CompanionServer.kt`, `ui/settings/BindAddressSection.kt`
- Test suite: `companion/src/test/kotlin/net/devemperor/dictate/companion/domain/net/AddressCatalogTest.kt`,
  `domain/CompanionSettingsMigrationTest.kt`, `server/MultiConnectorE2ETest.kt`
- Engine capability verified against `ktor-server-core-jvm-3.1.3.jar` (`gradle/libs.versions.toml:55`)

## Decision History

### 2026-07-15 — Initial proposal

**Trigger:** A feature request to make the Tailscale address selectable and default in the settings
panel, plus multi-address listening. Planning surfaced that ADR-0017 §3 already mandated most of the
security intent and had never been implemented, and that the QR/bind split was an open defect.

**Before:** One free-text `bindAddress` string defaulting to `0.0.0.0`, unvalidated, with the pairing
QR deriving its address independently of it. No warning when listening on every interface, contrary
to ADR-0017 §3.

**After:** An enumerated catalogue behind a domain port; a materialised `BindSelection` with a
Tailscale-only default on first configuration; migration that preserves any deliberate prior choice;
loopback-plus-error rather than `0.0.0.0` when a chosen address disappears; the advertised address
derived from the binding under a tested invariant; free text retained as a validated advanced option.

**Reasoning:** The security win (nothing listening on the LAN) is only real if it is also *stable* —
a fallback that widens on a transient event would give it back at the worst moment. Materialising the
choice and deriving the advertisement turn two silent failure modes (wrong network bound, wrong
address advertised) into either impossible states or loud ones. The extra types are the price of
making those rules testable without the CI machine having the right network cards.
