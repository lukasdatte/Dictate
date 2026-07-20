# Repair W1-3 — convention-D-2

**Date:** 2026-07-20T00:40:00+02:00
**Agent-ID:** repair-W1-3

## Findings

### convention-D-2 (green, Nice-to-have) — fixed

`SerialJobQueue.known` used a fully-qualified inline reference
`java.util.concurrent.ConcurrentHashMap.newKeySet<String>()` while the sibling
`java.util.concurrent` utilities (`ConcurrentLinkedQueue`, `Executors`,
`AtomicBoolean`) were all imported — an inconsistent treatment of same-package
utilities against the codebase convention of importing them.

Fix applied in
`companion/src/main/kotlin/net/devemperor/dictate/companion/pipeline/JobQueue.kt`:
- Added `import java.util.concurrent.ConcurrentHashMap` (line 3, alphabetically
  ordered with the existing `java.util.concurrent.*` imports).
- Changed the use site (line 33) to `ConcurrentHashMap.newKeySet<String>()`.

## Tests

`./gradlew :companion:test` — BUILD SUCCESSFUL (green).

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/companion/src/main/kotlin/net/devemperor/dictate/companion/pipeline/JobQueue.kt`

## Drift

none
