---
title: Companion — Windows Release
status: Accepted
type: Runbook
audience: Whoever cuts a release of the desktop companion
last_reviewed: 2026-07-13
---

# Companion — Windows Release

How to build, install and verify the Windows installer (`.msi`) of the Dictate desktop companion.

> [!IMPORTANT]
> **jpackage cannot cross-compile.** It builds packages for the **host** operating system only —
> this is a property of JEP 392, not a configuration option. No `targetFormats` setting will make an
> `.msi` appear on a Linux machine; `:companion:packageMsi` simply fails there. **A Windows release
> requires a Windows machine.**

## What builds where

| Task | Linux (the dev VM) | Windows |
|---|---|---|
| `:companion:test` | ✅ the full suite, including the E2E against a real server | ✅ |
| `:companion:run` | ✅ starts, with `NoopTextInserter` + the "insertion not available" banner | ✅ with real insertion |
| `:companion:createDistributable` | ✅ unpacked app image under `build/compose/binaries/main/app/` | ✅ |
| `:companion:packageDeb` | ✅ `.deb` | ✗ |
| `:companion:packageMsi` | ✗ **fails** | ✅ (needs WiX in `PATH`) |
| `:companion:packageDistributionForCurrentOS` | → `.deb` | → `.msi` |

`Deb` is configured **so that the packaging path is exercisable on the dev VM at all**. If only `Msi`
were configured, `packageDistributionForCurrentOS` would fail on Linux and the packaging
configuration would never be built until someone tried to ship — which is the worst moment to find
out it is broken.

## Prerequisites on the Windows machine

1. **JDK 21** (or 17+; the Gradle toolchain resolves the rest).
2. **WiX Toolset 3.14** — jpackage shells out to `candle.exe` and `light.exe`, so both must be in
   `PATH`. WiX **4.x does not work**: jpackage calls the 3.x binaries by name.
3. The repository, cloned.

## Building

```powershell
gradlew.bat :companion:test          # never ship a red suite
gradlew.bat :companion:packageMsi
```

The artefact lands in:

```
companion\build\compose\binaries\main\msi\DictateCompanion-1.0.0.msi
```

## Before every release: check the upgrade UUID

`companion/build.gradle` → `nativeDistributions { windows { upgradeUuid } }`.

```
upgradeUuid = "B9083816-3022-4502-B5F3-7CDA7BA722D8"
```

**This GUID must never change.** Windows Installer identifies a *product* by it. Change it, and the
next MSI installs **alongside** the old companion rather than upgrading it — two copies, both
autostarting, both trying to bind port 8756, and a user with no idea why their phone reaches the
wrong one. It was generated once (2026-07-13) and is frozen with a comment saying so.

## Known state of a V1 build (not bugs)

- **The MSI is unsigned.** SmartScreen will warn ("Windows protected your PC" → *More info* → *Run
  anyway*). A code-signing certificate is a deliberate follow-up, not an oversight; do not spend a
  release trying to make the warning go away by other means.
- **No custom installer icon.** jpackage uses its default. The in-app window and tray icon are drawn
  in code and *are* correct; only the `.msi`/`.exe` shell icon is the default one. Cosmetic.
- **The bundle is large** (~110 MB): it carries its own Java runtime and Skia. That is the price of
  an app the user does not have to install a JDK for.

## Post-install verification — the Windows checklist

Everything below is what a Linux CI **cannot** prove. The rest of the system is covered by
`:companion:test`, which must be green before you get here.

| # | Check | Expected |
|---|---|---|
| 1 | Dictate into **Notepad**, **Word**, **Windows Terminal**, a **browser** field | The text is typed at the caret (`TYPED_CTRL_V`) |
| 2 | Copy something yourself, then dictate | The dictated text is pasted, and your own clipboard content is back ~800 ms later |
| 2b | Copy an **image**, then dictate | The text is pasted; the image is **not** restored (a `String`-shaped clipboard port cannot put it back — the dictated text stays on the clipboard, and that is the documented behaviour) |
| 3 | Minimise everything (the desktop has focus), then dictate | `CLIPBOARD_ONLY`: the text is on the clipboard, the phone acknowledges it **and shows a hint**; the history row shows the "clipboard only" badge |
| 4 | Focus an **elevated** window (admin PowerShell / Task Manager), then dictate | UIPI blocks the injection. Expected: `CLIPBOARD_ONLY` — **never** a silent success. If the phone reports "typed" here, that is the one bug this whole design exists to prevent |
| 5 | Tray icon | Renders; the menu offers Open / Pause receiving / Quit; closing the window keeps the app running in the tray |
| 6 | Settings → "Start with the computer", then **reboot** | The Run key is written (`HKCU\…\Run\DictateCompanion`, path **quoted**, `--minimized`); after the reboot the companion is running in the tray with no window. Turning it off removes the key |
| 7 | Install → **upgrade** with a newer MSI → uninstall | The upgrade *replaces* the installation (same `upgradeUuid`), it does not install a second copy |
| 8 | Windows Firewall vs. the Tailscale interface | The phone reaches the companion over the tailnet. The Tailscale adapter is normally classified as a *private* network — verify, and allow the app on private networks if prompted |
| 9 | SmartScreen on the unsigned MSI | A warning appears. Expected (see "Known state" above) |
| 10 | End to end from the phone | Dictate → the text appears in the active Windows window. Then: quit the companion → the phone shows the text as a **pending part** → start the companion → "send" from the history row delivers it |

## If there is no Windows machine

A GitHub Actions job on `windows-latest` running `packageMsi` and uploading the artefact would do
it. Note that this repository has **no CI at all** today (`.github/` does not exist) — introducing it
is its own decision and its own work package, not something to bolt onto a release.

## References

- ADR-0017 — the companion is the only server; the 200 is the delivery confirmation
- ADR-0018 — Windows insertion behind a port; `CLIPBOARD_ONLY` is a success with a hint
- ADR-0019 — auto-send + the pending-part fallback and the history-row re-send (checklist #3, #4, #10)
- ADR-0020 — lazy cursor sync (the PC's derived history copy; triggered after dispatch and at app start)
- `docs/architecture/windows-dispatch/README.md` — the cross-cutting subsystem overview
- `companion/src/main/kotlin/.../platform/windows/Win32TextInserter.kt` — the insertion policy and
  its three deliberate imperfections
- `companion/src/main/kotlin/.../platform/windows/WinRegistryAutostart.kt` — the Run-key contract
