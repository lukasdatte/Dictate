# Repair Wave — Block E finding E-T2

**Date:** 2026-07-20T13:30:00+02:00
**Agent role:** repair-fix (single finding cluster)
**Report file:** this file

## Finding E-T2 — full `:app:testDebugUnitTest` run flaked with `FileSystemAlreadyExistsException`

**Classification:** green / Important (CI-reliability).

### What I did

Set `forkEvery = 1` in `app/build.gradle` (`testOptions.unitTests.all { … }`),
replacing the previous `forkEvery = 80`. This puts every test **class** in its
own JVM fork.

### Why this fix (and not something lighter)

Root-cause investigation:

- `grep -rn "newFileSystem|FileSystems\."` across `app/`, `shared/`, `shared-ai/`,
  `companion/` returns **nothing** — no code in this repository opens a jar
  `FileSystem`. The leaked, never-closed `jar:` FileSystem that produces
  `java.nio.file.FileSystemAlreadyExistsException` at `ZipFileSystemProvider.java:104`
  comes from **inside Robolectric** (its `android-all` jar loading). We cannot
  close it from our own code, so a "close the FileSystem after use" fix is not
  available.
- The JDK's `ZipFileSystemProvider` keeps a **per-JVM, URI-keyed map** of open
  filesystems. When one Robolectric test class leaves a `jar:` FileSystem open,
  a *later* class in the **same fork** that opens the same URI throws
  `FileSystemAlreadyExistsException`. This is exactly the observed profile:
  order-dependent, non-deterministic, and surfacing on an unrelated victim
  (`AndroidKeystoreSecretStoreTest.put_whenKekUnavailable_throwsUnavailable`,
  a JVM-infra error rather than an assertion failure).
- The app module has ~80 `@RunWith(RobolectricTestRunner)` classes. With
  `forkEvery = 80` the whole Robolectric suite effectively ran in **one fork** —
  the maximum-collision configuration and the reason Block E's new
  `PeerExplorerActivityTest` (another Robolectric class in the same module)
  raised the odds.

Because the FileSystem map is per-JVM, **the only deterministic cure is a fresh
JVM per test class** (`forkEvery = 1`). Any `forkEvery > 1` leaves a collision
window and would merely lower the flake frequency, not remove it — not a real
fix for a non-deterministic failure. `maxParallelForks` alone does not help
either: parallel forks are separate JVMs, so they don't share the map, but
within any one fork the cross-class window remains unless `forkEvery = 1`.

### Trade-off (called out explicitly per engineering-principles)

`forkEvery = 1` pays a fresh Robolectric bootstrap per class → more wall-clock on
the app unit-test suite. Chosen deliberately: CI reliability on a keyboard app is
worth more than test minutes, and a JVM-per-class also strictly strengthens the
pre-existing shadow-state/heap-accumulation rationale that motivated the earlier
periodic recycle. `maxHeapSize = "2g"` is kept (the inflation suite still needs a
real heap within its single fork). I deliberately did **not** add
`maxParallelForks`: each parallel fork holds its own 2g heap, and a fixed cap
could OOM a small CI runner — sequential forks are portable-safe on any runner.
The inline comment documents `maxParallelForks` as the future speed lever if the
suite grows large enough to need it.

### Verification

- `grep` confirmed no in-repo `newFileSystem` usage (leak is dependency-internal).
- `./gradlew :app:testDebugUnitTest` (cached inputs) reported **BUILD SUCCESSFUL /
  UP-TO-DATE** — a `testOptions.forkEvery` change is not a test-task input, so
  Gradle did not re-execute; this confirms the build script still configures
  cleanly but does NOT exercise the new fork behaviour.
- `./gradlew :app:testDebugUnitTest --rerun-tasks` was launched to actually run the
  full app unit-test suite under `forkEvery = 1`. **At the time this report was
  written the forced re-run had not yet completed** (a JVM-per-class run over ~120
  test classes is slow by design). The commit-/verify step MUST confirm this run
  finished `BUILD SUCCESSFUL` before the finding is closed — background task id
  `b0a4v8zfe`, output at
  `/tmp/claude-1000/-home-lukas-WebStorm-Dictate/60ea45f0-d601-44e6-ac75-ce4ee25887d7/tasks/b0a4v8zfe.output`.
  The change is config-only (no source/test edits), so a green run is expected, but
  it is not yet independently confirmed.

### Skipped / out-of-scope notes

- None. No new issues discovered while fixing.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/build.gradle`

## Drift (files outside the finding's assigned scope)

- None. The finding listed `app/build.gradle` and the test file; the fix is
  purely in `app/build.gradle`. The test file
  (`app/src/test/java/net/devemperor/dictate/secrets/AndroidKeystoreSecretStoreTest.kt`)
  is the flake *victim*, not the cause, and needed no change — note it lives under
  `.../secrets/`, not `.../keystore/` as the finding text guessed.
