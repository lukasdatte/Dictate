package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **INT-3 — the AC-10 single-architecture invariant regression-lock.**
 *
 * The `dictate-cutover-completion` Epic collapsed the two-orchestrator
 * coexistence (the parent-plan INT-1 parallel-dormant / double-dispatch
 * failure class) into a single coherent architecture: the new
 * `DictateOrchestrator` is the sole recording state-router, the legacy
 * `JobExecutor`/`PipelineOrchestrator` survive **only** behind the
 * `PipelineRunnerSubsystem` interface plus the one documented RESUME
 * carve-out, and the four legacy render controllers are deleted.
 *
 * The Phase-4 integration audit re-verified this invariant *by
 * inspection* (grep) but flagged (INT-3, NTH) that it had **no
 * automated guard** — a future change could silently reintroduce a
 * second `JobExecutor.start` recording-trigger, resurrect the
 * `USE_LEGACY_RECORDING_DRIVE` dead switch, re-wire a deleted
 * controller, or rebind the deprecated test-only stub subsystems, and
 * nothing would go RED. This test is that guard (the D4 regression-lock
 * the integration agent asked for).
 *
 * # Why a pure-JVM source-scan (K-1 / K-4)
 *
 * The invariant is a *structural* property of the production source
 * text, not a runtime behaviour — it asserts which call-sites and
 * tokens exist in which files. So this is a deterministic, fast,
 * **pure JVM unit test** (no Robolectric, no Android `Context`, no
 * mocking framework): it reads the production `.kt`/`.java` files via
 * plain `File` IO under the module dir (Gradle runs unit tests with
 * the module directory as CWD — same pattern as
 * `MotionSceneSchemaTest`'s `File("src/main/res/...")`), strips
 * comments + string literals, then asserts on the remaining
 * functional code.
 *
 * Stripping is the load-bearing design choice: the cutover left ~167
 * KDoc / `@see` / gotcha **historical anchors** that name the deleted
 * controllers and the old switch on purpose (the documented
 * replacement trail the integration report explicitly blesses as
 * "not a compile dependency"). A naive grep would false-RED on every
 * one. By scanning only *functional* code (comments + strings
 * removed) the test pins the real invariant: zero **wiring/type/call**
 * references, while doc-anchors stay free.
 *
 * # Non-vacuity (RR-4 false-GREEN mitigation)
 *
 * A regression-lock that cannot go RED is worse than none. Each
 * structural assertion is paired with a `commentStripperIsSound*`
 * self-test that feeds the stripper a synthetic snippet containing the
 * *banned* construct once as code and once inside a comment/string,
 * and asserts the stripper keeps the code occurrence and drops the
 * doc one. Concretely: if a developer adds a second
 * `JobExecutor.INSTANCE.start(` recording-trigger, [
 * exactlyOneJobExecutorStartInIme_andItIsTheResumeCarveOut] counts 2
 * and fails; if they re-introduce `USE_LEGACY_RECORDING_DRIVE` in
 * production code, [noUseLegacyRecordingDriveSwitchInProduction]
 * fails; if they re-wire `PipelineServiceStubSubsystems.pipelineRunner`
 * / `.notificationCoordinator` into `onCreate`,
 * [stubSubsystemsNotWiredIntoPipelineServiceOnCreate] fails — none of
 * these regressions are masked by the comment-strip because the
 * stripper-soundness self-tests prove a real `.start(` survives
 * stripping while a commented one does not.
 *
 * @see net.devemperor.dictate.core.DictateCutoverE2ETest (recording-drive cutover, runtime)
 * @see net.devemperor.dictate.core.RenderPathCutoverGateTest (render-path cutover, runtime)
 */
class CutoverArchitectureInvariantTest {

    private val mainSrcRoot = File("src/main/java/net/devemperor/dictate")
    private val imeFile = File(mainSrcRoot, "core/DictateInputMethodService.java")
    private val pipelineServiceFile = File(mainSrcRoot, "core/DictatePipelineService.kt")
    private val imeViewBackendFile = File(mainSrcRoot, "state/render/ImeViewBackend.kt")
    private val overlayBackendFile = File(mainSrcRoot, "state/render/overlay/OverlayBackend.kt")

    private val deletedRenderControllers = listOf(
        "MainButtonsController",
        "RecordingUiController",
        "KeyboardUiController",
        "KeyboardStateManager",
    )

    // ---- comment/string stripping ------------------------------------

    /**
     * Removes line comments (`//…`), block comments (`/* … */`, incl.
     * KDoc/Javadoc `/** … */`) and double-quoted string literals from
     * Kotlin/Java source so the scan only sees *functional* code.
     *
     * This is intentionally a small hand-rolled state machine (not a
     * full parser): it is sound for the property under test — it never
     * keeps a token that lives only inside a comment or string, and it
     * never drops a token that lives in real code. The
     * `commentStripperIsSound*` self-tests below prove both directions
     * on the exact banned constructs.
     */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    i += 2
                    while (i < n && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < n && !(source[i] == '*' && i + 1 < n && source[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' -> {
                    // Skip a double-quoted literal (handles \" escapes and
                    // Kotlin/Java triple-quoted raw strings).
                    if (next == '"' && i + 2 < n && source[i + 2] == '"') {
                        i += 3
                        while (i + 2 < n && !(source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"')) i++
                        i += 3
                    } else {
                        i++
                        while (i < n && source[i] != '"') {
                            if (source[i] == '\\' && i + 1 < n) i++
                            i++
                        }
                        i++
                    }
                    out.append(' ') // keep token boundaries intact
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun functionalCode(file: File): String {
        assertTrue(
            "Production source not found at ${file.absolutePath} — " +
                "Gradle should run unit tests with the module dir as CWD",
            file.isFile,
        )
        return stripCommentsAndStrings(file.readText())
    }

    private fun allMainSources(): List<File> =
        mainSrcRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

    // ---- (a) every JobExecutor.start in the IME lives in a named carve-out

    @Test
    fun exactlyOneJobExecutorStartInIme_andItIsTheResumeCarveOut() {
        val code = functionalCode(imeFile)

        val startRegex = Regex("""JobExecutor\s*\.\s*INSTANCE\s*\.\s*start\s*\(""")
        val matches = startRegex.findAll(code).toList()
        // Two documented carve-outs: the RESUME helper (startResumeJob) and the
        // ADR-0013 review-continuation helper (startReviewContinuationJob). Any
        // OTHER start-site reintroduces the parent-plan INT-1 double-dispatch
        // failure class and must fail this test.
        assertEquals(
            "AC-10 invariant: the IME must contain exactly TWO functional " +
                "JobExecutor.INSTANCE.start( call-sites — the RESUME carve-out " +
                "(startResumeJob) and the ADR-0013 review-continuation carve-out " +
                "(startReviewContinuationJob). A third reintroduces the INT-1 " +
                "double-dispatch failure class.",
            2,
            matches.size,
        )

        val allowedCarveOuts = setOf("startResumeJob", "startReviewContinuationJob")
        val anyMethodSigRegex = Regex("""\b(private|public|protected)\b[^;{}]*\b(\w+)\s*\(""")
        matches.forEach { m ->
            val before = code.substring(0, m.range.first)
            val enclosing = anyMethodSigRegex.findAll(before).lastOrNull()?.groupValues?.get(2)
            assertTrue(
                "Every JobExecutor.start must live in a documented carve-out " +
                    "($allowedCarveOuts). Enclosing method was: $enclosing",
                enclosing in allowedCarveOuts,
            )
        }
    }

    @Test
    fun commentStripperIsSound_jobExecutorStart() {
        val sample = """
            // legacy JobExecutor.INSTANCE.start(this, oldReq) — DELETED, doc only
            /* historical: JobExecutor.INSTANCE.start was the C5 path */
            String s = "JobExecutor.INSTANCE.start(in-a-string)";
            boolean started = JobExecutor.INSTANCE.start(this, request); // real
        """.trimIndent()
        val stripped = stripCommentsAndStrings(sample)
        val n = Regex("""JobExecutor\s*\.\s*INSTANCE\s*\.\s*start\s*\(""").findAll(stripped).count()
        assertEquals(
            "Stripper must keep exactly the ONE real code occurrence and drop " +
                "the comment + string occurrences (proves the (a) assertion is " +
                "non-vacuous: a reintroduced 2nd code call-site WOULD be counted).",
            1,
            n,
        )
    }

    // ---- (b) zero USE_LEGACY_RECORDING_DRIVE anywhere in app/src/main

    @Test
    fun noUseLegacyRecordingDriveSwitchInProduction() {
        val offenders = allMainSources().filter { f ->
            functionalCode(f).contains("USE_LEGACY_RECORDING_DRIVE")
        }
        assertTrue(
            "AC-10 invariant: the USE_LEGACY_RECORDING_DRIVE dead switch was " +
                "removed in C7 and must never return as functional code " +
                "(a lingering switch is a dormant second recording driver). " +
                "Offending files: ${offenders.map { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun commentStripperIsSound_useLegacySwitch() {
        val sample = """
            // USE_LEGACY_RECORDING_DRIVE was the C7-deleted guard (doc only)
            /** @see USE_LEGACY_RECORDING_DRIVE history */
            val note = "USE_LEGACY_RECORDING_DRIVE in a string is harmless"
        """.trimIndent()
        assertTrue(
            "Stripper must drop all comment/string occurrences of the dead " +
                "switch (proves (b) only fires on a REAL reintroduction).",
            !stripCommentsAndStrings(sample).contains("USE_LEGACY_RECORDING_DRIVE"),
        )
        val codeSample = "val USE_LEGACY_RECORDING_DRIVE = false"
        assertTrue(
            "Stripper must keep a real code declaration of the switch " +
                "(proves the assertion is non-vacuous).",
            stripCommentsAndStrings(codeSample).contains("USE_LEGACY_RECORDING_DRIVE"),
        )
    }

    // ---- (c) zero functional refs to the 4 deleted render controllers

    @Test
    fun noFunctionalReferencesToDeletedRenderControllers() {
        // The four controller .kt files must not exist at all.
        deletedRenderControllers.forEach { name ->
            val f = File(mainSrcRoot, "core/$name.kt")
            assertTrue(
                "Deleted render controller resurfaced as a source file: ${f.path}",
                !f.exists(),
            )
        }

        // No functional code (import / type / constructor / call) may
        // reference them. Doc-anchors (KDoc/@see/gotcha) and XML
        // comments are explicitly allowed (the integration report
        // blesses the historical-anchor trail) — hence the comment strip.
        val violations = mutableListOf<String>()
        allMainSources().forEach { f ->
            val code = functionalCode(f)
            deletedRenderControllers.forEach { name ->
                Regex("""\b${Regex.escape(name)}\b""").findAll(code).forEach { m ->
                    val lineNo = code.substring(0, m.range.first).count { it == '\n' } + 1
                    violations += "${f.path}:$lineNo -> $name"
                }
            }
        }
        assertTrue(
            "AC-7 / AC-RR-6 / AC-RR-7 invariant: the four legacy render " +
                "controllers are DELETED; no functional code may reference " +
                "them (doc-anchors are OK and are stripped before scanning). " +
                "Functional violations: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun commentStripperIsSound_deletedControllers() {
        val sample = """
            // CR-DEL — KeyboardStateManager + MainButtonsController DELETED
            /** Mirrors `KeyboardUiController.state`. @see RecordingUiController */
            val tag = "KeyboardStateManager was here"
        """.trimIndent()
        val stripped = stripCommentsAndStrings(sample)
        deletedRenderControllers.forEach { name ->
            assertTrue(
                "Doc/string mention of $name must be stripped (proves (c) does " +
                    "NOT false-RED on the blessed historical anchors).",
                !stripped.contains(name),
            )
        }
        val codeSample = "private val ksm = KeyboardStateManager(view)"
        assertTrue(
            "A real code reference to a deleted controller must survive " +
                "stripping (proves (c) is non-vacuous: a re-wired controller " +
                "WOULD be flagged).",
            stripCommentsAndStrings(codeSample).contains("KeyboardStateManager"),
        )
    }

    // ---- (d) deprecated stub subsystems not wired into onCreate

    @Test
    fun stubSubsystemsNotWiredIntoPipelineServiceOnCreate() {
        val code = functionalCode(pipelineServiceFile)

        // The deprecated test-only stubs must not be assigned into the
        // ModuleServices the service actually constructs. Their only
        // legitimate remaining mention is the prose replacement-trail
        // comment (stripped) — so ANY surviving functional reference to
        // `PipelineServiceStubSubsystems.pipelineRunner` /
        // `.notificationCoordinator` is a regression.
        listOf("pipelineRunner", "notificationCoordinator").forEach { member ->
            val ref = Regex("""PipelineServiceStubSubsystems\s*\.\s*$member\b""")
            assertTrue(
                "AC-1 invariant: PipelineServiceStubSubsystems.$member must NOT " +
                    "be wired in DictatePipelineService (the real adapter / " +
                    "coordinator is). A functional reference here means the " +
                    "no-op stub is back on the production route.",
                !ref.containsMatchIn(code),
            )
        }

        // Positive side: the real adapter + coordinator ARE wired into
        // the constructed ModuleServices (the inverse of INT-1). Pin
        // them so a future "revert to stub" cannot pass silently.
        assertTrue(
            "The real PipelineRunnerSubsystemAdapter must be wired as " +
                "ModuleServices.pipelineRunner.",
            Regex("""pipelineRunner\s*=\s*pipelineRunnerSubsystemAdapterImpl""")
                .containsMatchIn(code),
        )
        assertTrue(
            "The real PipelineNotificationCoordinator must be wired as " +
                "ModuleServices.notificationCoordinator.",
            Regex("""notificationCoordinator\s*=\s*notificationCoordinatorImpl""")
                .containsMatchIn(code),
        )
    }

    @Test
    fun commentStripperIsSound_stubSubsystemWiring() {
        // Mirrors the real DictatePipelineService.kt:475 prose trail.
        val docOnly = """
            // C4-B2 — real notification coordinator. Replaces the
            // `PipelineServiceStubSubsystems.notificationCoordinator` no-op.
        """.trimIndent()
        assertTrue(
            "The DictatePipelineService:475-style replacement-trail comment " +
                "must be stripped (proves (d) does not false-RED on the " +
                "documentation of the replacement).",
            !Regex("""PipelineServiceStubSubsystems\s*\.\s*notificationCoordinator""")
                .containsMatchIn(stripCommentsAndStrings(docOnly)),
        )
        val codeSample =
            "notificationCoordinator = PipelineServiceStubSubsystems.notificationCoordinator,"
        assertTrue(
            "A real stub-wiring assignment must survive stripping (proves (d) " +
                "is non-vacuous: a reverted-to-stub wiring WOULD be flagged).",
            Regex("""PipelineServiceStubSubsystems\s*\.\s*notificationCoordinator""")
                .containsMatchIn(stripCommentsAndStrings(codeSample)),
        )
    }

    // ---- (e) catalog-click affordance-hook symmetry — IME-side helper wired
    //
    // Post-cutover hotfix (R2/R3/R4 device regression — see ADR-0005
    // Decision-History "catalog-click affordance hook symmetry").
    //
    // The RECORD click on Active|Paused (catalog returns
    // `Action.RecordingAction.StopRecordingAndSend`) needs the IME-runtime
    // R-1 `JobRequest` snapshot + the pipeline-step-row prime BEFORE the
    // catalog dispatches, because `PipelineRunnerSubsystemAdapter.submit`
    // → `resolveFresh` runs asynchronously off the dispatch and would
    // otherwise hit an empty snapshot → loud `UnsupportedOperationException`
    // (the R-1 silent-data-loss tripwire) → `state.pipeline` hangs in
    // `Preparing` forever (endless "Sending…" with no progress / no
    // step-rows — exactly the device regression that escaped auto-tier
    // green). The fix wires a symmetric affordance hook (mirroring RESEND):
    //
    //   1. `DictateInputMethodService` declares
    //      `prepareCatalogStopRecordingIfActive()` (the helper) and calls
    //      it from the `imeSideAffordance` lambda's RECORD branch.
    //   2. `ImeViewBackend.kt` fires `imeSideAffordance(id, false)` from
    //      the click branch for BOTH `RESEND` and `RECORD`.
    //
    // (e) locks (1); (f) locks (2). Each has a paired stripper-soundness
    // self-test (RR-4 false-GREEN mitigation).

    @Test
    fun catalogStopRecordingAffordanceHelperIsWired() {
        val code = functionalCode(imeFile)
        val defRegex = Regex(
            """\bprivate\s+(?:void|boolean)\s+prepareCatalogStopRecordingIfActive\s*\(\s*\)"""
        )
        val callRegex = Regex("""\bprepareCatalogStopRecordingIfActive\s*\(""")
        val defs = defRegex.findAll(code).count()
        val calls = callRegex.findAll(code).count()
        assertEquals(
            "Post-cutover R2 invariant: DictateInputMethodService MUST declare " +
                "exactly one `prepareCatalogStopRecordingIfActive()` helper " +
                "(the IME-side R-1 snapshot + UI prime for the catalog-driven " +
                "RECORD click). Two definitions would split the responsibility; " +
                "zero means the helper was removed and the catalog 'stop & send' " +
                "tap is back to hanging the pipeline FSM.",
            1,
            defs,
        )
        // `calls` includes the definition itself (`prepareCatalogStopRecordingIfActive(`
        // appears once in the signature). Real invocations = calls - defs.
        val callSites = calls - defs
        assertTrue(
            "Post-cutover R2 invariant: " +
                "`prepareCatalogStopRecordingIfActive()` MUST be invoked from " +
                "the `imeSideAffordance` lambda's RECORD branch. Helper is dead " +
                "code otherwise → the regression returns silently. Call-sites: " +
                "$callSites.",
            callSites >= 1,
        )
    }

    @Test
    fun commentStripperIsSound_catalogStopRecordingHelper() {
        // KDoc / @see / historical-anchor mentions of the helper name must
        // be stripped so the test does not false-GREEN on documentation
        // alone (R2 regression must STILL fail loud if the helper is gone
        // but the doc-anchor stays).
        val docOnly = """
            // Post-cutover R2 — see prepareCatalogStopRecordingIfActive helper
            /** @see prepareCatalogStopRecordingIfActive */
            String s = "prepareCatalogStopRecordingIfActive in a string";
        """.trimIndent()
        val stripped = stripCommentsAndStrings(docOnly)
        assertTrue(
            "Doc/string mentions of the helper name must be stripped (proves " +
                "(e) does NOT false-GREEN on documentation alone).",
            !stripped.contains("prepareCatalogStopRecordingIfActive"),
        )
        val codeSample = """
            private void prepareCatalogStopRecordingIfActive() { return; }
            prepareCatalogStopRecordingIfActive();
        """.trimIndent()
        val codeStripped = stripCommentsAndStrings(codeSample)
        assertTrue(
            "A real definition + call-site of the helper must survive stripping " +
                "(proves (e) is non-vacuous: a removed helper WOULD be flagged).",
            Regex("""\bprivate\s+void\s+prepareCatalogStopRecordingIfActive\s*\(\s*\)""")
                .containsMatchIn(codeStripped) &&
                Regex("""\bprepareCatalogStopRecordingIfActive\s*\(""")
                    .findAll(codeStripped).count() >= 2,
        )
    }

    // ---- (f) catalog-click affordance-hook symmetry — backend gate
    //         {RESEND, RECORD} both fire the affordance

    @Test
    fun imeViewBackendClickBranchFiresAffordanceForBothResendAndRecord() {
        val code = functionalCode(imeViewBackendFile)
        // The click branch fires `imeSideAffordance(id, false)` from a
        // gate that names BOTH `LogicalButtonId.RESEND` and
        // `LogicalButtonId.RECORD`. Pragmatic structural check: at least
        // one functional `imeSideAffordance(id, false)` call must have
        // both ids within a 200-char window (≈ the same `if (…) { … }`
        // block on either ordering / either spelling — `||`, `in setOf`,
        // etc.).
        val callRegex = Regex("""imeSideAffordance\s*\(\s*id\s*,\s*false\s*\)""")
        val matches = callRegex.findAll(code).toList()
        assertTrue(
            "Post-cutover R2 invariant (backend half): ImeViewBackend.kt MUST " +
                "call imeSideAffordance(id, false) at least once from the click " +
                "branch. The hook is the IME-side R-1 snapshot trigger.",
            matches.isNotEmpty(),
        )
        val windowChars = 200
        val symmetryCovered = matches.any { m ->
            val start = (m.range.first - windowChars).coerceAtLeast(0)
            val end = (m.range.last + windowChars).coerceAtMost(code.length - 1)
            val nearby = code.substring(start, end)
            nearby.contains("LogicalButtonId.RESEND") &&
                nearby.contains("LogicalButtonId.RECORD")
        }
        assertTrue(
            "Post-cutover R2 invariant (backend half): the imeSideAffordance(id, " +
                "false) click-hook gate in ImeViewBackend.kt MUST cover BOTH " +
                "LogicalButtonId.RESEND AND LogicalButtonId.RECORD (asymmetric " +
                "gate is the exact failure mode that shipped: RESEND-only gate, " +
                "RECORD click drops through to the catalog dispatch with no " +
                "snapshot → pipeline FSM hangs in Preparing → endless 'Sending…' " +
                "with no progress / no step-rows). Found imeSideAffordance(id, " +
                "false) call(s) but none in a 200-char window naming both ids.",
            symmetryCovered,
        )
    }

    @Test
    fun commentStripperIsSound_affordanceHookSymmetry() {
        val codeSample = """
            if (id == LogicalButtonId.RESEND || id == LogicalButtonId.RECORD) {
                imeSideAffordance(id, false)
            }
        """.trimIndent()
        val stripped = stripCommentsAndStrings(codeSample)
        assertTrue(
            "A real symmetric-gate + affordance call must survive stripping " +
                "(proves (f) non-vacuity: a real wiring WOULD be matched).",
            stripped.contains("LogicalButtonId.RESEND") &&
                stripped.contains("LogicalButtonId.RECORD") &&
                Regex("""imeSideAffordance\s*\(\s*id\s*,\s*false\s*\)""")
                    .containsMatchIn(stripped),
        )
        val docOnly = """
            // historical: id == LogicalButtonId.RESEND || id == LogicalButtonId.RECORD →
            //   imeSideAffordance(id, false) — the symmetric hook
            /* val ref = "id == LogicalButtonId.RECORD" */
        """.trimIndent()
        val docStripped = stripCommentsAndStrings(docOnly)
        assertTrue(
            "Doc-anchor mentioning the symmetric wiring must be stripped " +
                "(proves (f) does NOT false-GREEN on documentation alone — " +
                "the regression must still fail loud if the actual gate is " +
                "asymmetric).",
            !docStripped.contains("LogicalButtonId.RESEND") &&
                !docStripped.contains("LogicalButtonId.RECORD"),
        )
    }

    // ---- (g) OverlayBackend click branch fires the affordance hook
    //         for OVERLAY_RECORD — dictate-widget-integration §8.4
    //         Chunk 4.4 / AC-10.
    //
    // Failure mode this locks against: a future edit removes the
    // OVERLAY_RECORD-branch from `wireStaticOverlayHandlers`. The
    // overlay-RECORD click would then drop straight into the catalog
    // dispatch, the IME's `prepareCatalogStopRecordingIfActive()` helper
    // never runs, the orchestrator's async `resolveFresh` finds no
    // snapshot, the R-1 tripwire fires (silent EffectFailure), and the
    // pipeline FSM hangs in Preparing → endless "Sending…" with no
    // commit-text. That is the EXACT bug the user reported for the
    // overlay SEND path before this hook landed (plan §5 SEND-Path-
    // Tracing).

    @Test
    fun overlayBackendClickBranchFiresAffordanceForOverlayRecord() {
        val code = functionalCode(overlayBackendFile)
        // The click branch must invoke `imeSideAffordance(id, false)`
        // from inside a gate that names `LogicalButtonId.OVERLAY_RECORD`.
        val callRegex = Regex("""imeSideAffordance\s*\(\s*id\s*,\s*false\s*\)""")
        val matches = callRegex.findAll(code).toList()
        assertTrue(
            "dictate-widget-integration AC-10: OverlayBackend.kt MUST " +
                "call imeSideAffordance(id, false) at least once from the " +
                "click branch. Without this, the OVERLAY_RECORD click drops " +
                "into the catalog dispatch with no R-1 snapshot, the " +
                "pipeline FSM hangs in Preparing forever (endless " +
                "'Sending…').",
            matches.isNotEmpty(),
        )
        val windowChars = 200
        val gatedByOverlayRecord = matches.any { m ->
            val start = (m.range.first - windowChars).coerceAtLeast(0)
            val end = (m.range.last + windowChars).coerceAtMost(code.length - 1)
            val nearby = code.substring(start, end)
            nearby.contains("LogicalButtonId.OVERLAY_RECORD")
        }
        assertTrue(
            "dictate-widget-integration AC-10: the imeSideAffordance(id, " +
                "false) call in OverlayBackend.kt MUST sit in a gate that " +
                "names LogicalButtonId.OVERLAY_RECORD (the R-1 snapshot is " +
                "only meaningful for the overlay record/send button).",
            gatedByOverlayRecord,
        )
    }

    // ---- (h) no OnSharedPreferenceChangeListener outside PipelinePrefMirror
    //         (dictate-indirection-cleanup AC-7 + review-fix G5).
    //
    // Failure mode this locks against: a future edit re-introduces a
    // custom `setOnSharedPreferenceChangeListener(...)` in
    // `DictateInputMethodService.java` (or anywhere else in the
    // production source tree). The indirection-cleanup plan retired
    // `inputLanguagesListener` and `audioFocusListener` because the
    // PipelinePrefMirror is the sole SP→State seam — any new custom
    // listener creates a second authority for SP-driven state mutation
    // and re-opens the AC-7 invariant.
    //
    // The PipelinePrefMirror.kt source file is the ONE legitimate
    // registration site (its registration is wrapped + traced + tested
    // by PipelinePrefMirrorTest + PipelinePrefMirrorCascadeTest).

    @Test
    fun noOnSharedPreferenceChangeListenerOutsidePipelinePrefMirror() {
        val regex = Regex(
            """(?:set|register)OnSharedPreferenceChangeListener\s*\("""
        )
        val violations = mutableListOf<String>()
        allMainSources().forEach { f ->
            // Allow the one legitimate site in PipelinePrefMirror itself.
            if (f.path.endsWith("PipelinePrefMirror.kt")) return@forEach
            val code = functionalCode(f)
            regex.findAll(code).forEach { m ->
                val lineNo = code.substring(0, m.range.first).count { it == '\n' } + 1
                violations += "${f.path}:$lineNo"
            }
        }
        assertTrue(
            "AC-7 invariant (dictate-indirection-cleanup, review-fix G5): " +
                "custom OnSharedPreferenceChangeListener registrations are " +
                "ONLY allowed in PipelinePrefMirror.kt. Any other site " +
                "creates a second SP→State authority and re-opens the " +
                "feedback-loop / drift class. Functional violations: " +
                "$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun commentStripperIsSound_onSharedPreferenceChangeListener() {
        val docOnly = """
            // historical: setOnSharedPreferenceChangeListener was wired here
            /** @see registerOnSharedPreferenceChangeListener */
            val s = "registerOnSharedPreferenceChangeListener in a string"
        """.trimIndent()
        val stripped = stripCommentsAndStrings(docOnly)
        assertTrue(
            "Doc/string mentions of the listener API must be stripped " +
                "(proves (h) does NOT false-RED on a deprecation comment).",
            !Regex("""(?:set|register)OnSharedPreferenceChangeListener\s*\(""")
                .containsMatchIn(stripped),
        )
        val codeSample =
            "sp.registerOnSharedPreferenceChangeListener(myListener)"
        assertTrue(
            "A real listener registration must survive stripping (proves " +
                "(h) is non-vacuous: a new wiring WOULD be flagged).",
            Regex("""registerOnSharedPreferenceChangeListener\s*\(""")
                .containsMatchIn(stripCommentsAndStrings(codeSample)),
        )
    }

    // ---- (i) PRE-BIND-FALLBACK tag on direct SP-writes for mirrored prefs
    //         in DictateInputMethodService (review-fix G2 + G5).
    //
    // The dictate-indirection-cleanup plan AC-5 admits two patterns:
    // (A) defensive early-return when `pipelineBinder == null`, and
    // (B) an SP-write fallback at sites whose User-intent must not be
    // lost across the narrow `bindService` window. Pattern B sites MUST
    // carry the exact tag string `PRE-BIND-FALLBACK` on any of the 8
    // raw-source lines immediately preceding the SP-write call. Without the tag the writer is undocumented drift
    // — a regression of the legacy SP-roundtrip antipattern.
    //
    // This lock greps the *raw* IME source (NOT the stripped one) for
    // `DictatePrefsKt.put(sp.edit()...)` or `sp.edit().put(...)` /
    // `sp.edit().remove(...)` patterns and asserts each match is preceded
    // by the tag within the 5-line window. The tag itself lives in a
    // `//` comment, so this scan deliberately keeps comments to read it.

    @Test
    fun directSharedPrefsWritesInImeAreTaggedAsPreBindFallback() {
        // Mirrored prefs the plan considers state-axis-owned. List is
        // derived from PipelinePrefMirror's 19-key list plus the two
        // RecordingModule-owned non-mirrored writes (LastFileName /
        // TranscriptionAudioFile) whose persistence is now an Effect
        // (Chunk 4.4). All have a corresponding dispatched-Action path
        // — any direct write needs the PRE-BIND-FALLBACK justification.
        val mirroredOrModuleOwnedPrefs = listOf(
            // PipelinePrefMirror computed-mirror axes (Spec 1 §4.5)
            "Pref.SmallMode", "Pref.SingleRowMode", "Pref.Animations",
            "Pref.AudioFocus", "Pref.UseBluetoothMic", "Pref.Vibration",
            "Pref.ResendButton",
            "Pref.RewordingEnabled", "Pref.AutoFormattingEnabled",
            "Pref.InstantOutput", "Pref.AutoEnter",
            "Pref.Theme", "Pref.AccentColor",
            "Pref.OverlayCharacters", "Pref.OutputSpeed",
            "Pref.OverlayPositionPortraitX", "Pref.OverlayPositionPortraitY",
            "Pref.OverlayPositionLandscapeX", "Pref.OverlayPositionLandscapeY",
            "Pref.InputLanguages", "Pref.InputLanguagePos",
            // Module-owned via PrefPersistenceService (Chunk 4.4)
            "Pref.LastFileName", "Pref.TranscriptionAudioFile",
        )
        // Read raw source (comments KEPT so the tag is visible).
        val rawLines = imeFile.readText().lines()
        val violations = mutableListOf<String>()

        rawLines.forEachIndexed { index, line ->
            // Skip the line itself if it contains the tag (defensive —
            // the tag must precede the write, not be on the same line).
            mirroredOrModuleOwnedPrefs.forEach { pref ->
                val isWrite = (line.contains("DictatePrefsKt.put(") ||
                    line.contains("sp.edit().put(") ||
                    line.contains("sp.edit().remove(")) &&
                    line.contains(pref)
                if (!isWrite) return@forEach
                // Search 8 raw-source lines above for the tag. 8 is large
                // enough to accommodate a multi-line comment block that
                // wraps the tag (an `else { … }` block can have the tag
                // at the top and the SP-write below several KDoc lines)
                // and small enough to keep the locality property — a tag
                // that drifts more than 8 lines away from the write it
                // governs is no longer "the tag preceding the write".
                val windowStart = (index - 8).coerceAtLeast(0)
                val window = rawLines.subList(windowStart, index)
                val hasTag = window.any { it.contains("PRE-BIND-FALLBACK") }
                if (!hasTag) {
                    violations += "DictateInputMethodService.java:${index + 1} -> $pref (untagged direct write)"
                }
            }
        }

        assertTrue(
            "AC-5 invariant (dictate-indirection-cleanup, review-fix G2 + G5):" +
                " direct SharedPreferences writes for mirrored / module-owned" +
                " prefs in DictateInputMethodService.java are ONLY allowed at" +
                " sites carrying the exact tag string `PRE-BIND-FALLBACK` on" +
                " any of the 5 raw-source lines immediately above the write." +
                " Untagged writes are a regression of the legacy SP-roundtrip" +
                " antipattern. Violations: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun preBindFallbackTagLockIsNonVacuous() {
        // Write the lock against a synthetic example: an untagged write
        // MUST trip; a tagged write MUST pass. Without this proof, the
        // (i) test could be silently disabled by a list-typo or an
        // off-by-one in the 5-line window calculation.
        //
        // The implementation under test is the same scanning loop as
        // `directSharedPrefsWritesInImeAreTaggedAsPreBindFallback`, but
        // applied to in-memory string fixtures so a real regression to
        // the production file does not need to be staged.

        val taggedSnippet = listOf(
            "} else {",
            "    // PRE-BIND-FALLBACK: SP-write authorized because dispatcher",
            "    // unavailable.",
            "    DictatePrefsKt.put(sp.edit(), Pref.SmallMode.INSTANCE, true).apply();",
            "}",
        )
        val untaggedSnippet = listOf(
            "} else {",
            "    // some random comment that has nothing to say about the topic",
            "    DictatePrefsKt.put(sp.edit(), Pref.SmallMode.INSTANCE, true).apply();",
            "}",
        )

        fun scanForUntaggedWrites(lines: List<String>): List<Int> {
            val out = mutableListOf<Int>()
            lines.forEachIndexed { index, line ->
                val isWrite = (line.contains("DictatePrefsKt.put(") ||
                    line.contains("sp.edit().put(")) &&
                    line.contains("Pref.SmallMode")
                if (!isWrite) return@forEachIndexed
                val windowStart = (index - 8).coerceAtLeast(0)
                val window = lines.subList(windowStart, index)
                if (!window.any { it.contains("PRE-BIND-FALLBACK") }) out += index
            }
            return out
        }

        assertTrue(
            "A PRE-BIND-FALLBACK-tagged write MUST pass the lock " +
                "(proves the tag is correctly recognized).",
            scanForUntaggedWrites(taggedSnippet).isEmpty(),
        )
        assertEquals(
            "An untagged direct write MUST trip the lock — proving (i) is " +
                "non-vacuous (a real new regression would be caught).",
            1,
            scanForUntaggedWrites(untaggedSnippet).size,
        )
    }

    @Test
    fun commentStripperIsSound_overlayAffordanceHook() {
        val codeSample = """
            if (id == LogicalButtonId.OVERLAY_RECORD) {
                imeSideAffordance(id, false)
            }
        """.trimIndent()
        val stripped = stripCommentsAndStrings(codeSample)
        assertTrue(
            "A real OVERLAY_RECORD-gated affordance call must survive " +
                "stripping (proves (g) non-vacuity).",
            stripped.contains("LogicalButtonId.OVERLAY_RECORD") &&
                Regex("""imeSideAffordance\s*\(\s*id\s*,\s*false\s*\)""")
                    .containsMatchIn(stripped),
        )
        val docOnly = """
            // historical: if (id == LogicalButtonId.OVERLAY_RECORD) {
            //   imeSideAffordance(id, false) }
            /* "OVERLAY_RECORD" was the merged SEND slot */
        """.trimIndent()
        val docStripped = stripCommentsAndStrings(docOnly)
        assertTrue(
            "Doc-only mention of the overlay affordance hook must NOT " +
                "survive stripping (proves (g) does not false-GREEN).",
            !docStripped.contains("LogicalButtonId.OVERLAY_RECORD"),
        )
    }

    // ---- (j) pause_btn has NO android:foreground in the keyboard layout XML
    //         (dictate-pipeline-render-and-state-unification §5.6 / AC-B).
    //
    // Failure mode this locks against: a future edit re-introduces an
    // `android:foreground="@drawable/ic_baseline_pause_24"` (or any other
    // drawable) on the `pause_btn` MaterialButton in
    // `activity_dictate_keyboard_view.xml`. The catalog `iconResolver =
    // ::resolvePauseIcon` already writes MaterialButton.icon — a static
    // foreground is a second writer that renders ON TOP, producing the
    // B-B duplication bug (two icons visible during Recording / Paused).
    //
    // The scan is on the **raw** XML text (no comment-strip needed for
    // XML — comments are <!-- … --> and an `android:foreground` inside
    // an XML comment would already be inert).

    @Test
    fun pauseBtnHasNoAndroidForegroundInKeyboardLayoutXml() {
        val layoutFile = File("src/main/res/layout/activity_dictate_keyboard_view.xml")
        assertTrue(
            "Layout file missing at ${layoutFile.absolutePath}",
            layoutFile.isFile,
        )
        val xml = layoutFile.readText()

        // Match the pause_btn MaterialButton element block. Tag spans
        // until the next self-closing `/>` or open `>`; we want to
        // scan only the attributes of THAT element.
        val pauseBlockRegex = Regex(
            """<com\.google\.android\.material\.button\.MaterialButton\b[^>]*?android:id\s*=\s*"@\+id/pause_btn"[^>]*?/>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val pauseBlock = pauseBlockRegex.find(xml)
        assertTrue(
            "Could not locate `pause_btn` MaterialButton element in the " +
                "keyboard layout XML — layout shape changed; update this " +
                "regression-lock to match.",
            pauseBlock != null,
        )
        val attrs = pauseBlock!!.value
        assertTrue(
            "AC-B regression-lock (dictate-pipeline-render-and-state-unification " +
                "§5.6): `pause_btn` MUST NOT carry `android:foreground` — the " +
                "catalog `iconResolver = ::resolvePauseIcon` is the SOLE writer " +
                "of the pause-icon axis (via MaterialButton.icon). A static " +
                "foreground re-introduces the B-B duplication bug (two " +
                "icons visible during Recording / Paused).",
            !attrs.contains("android:foreground"),
        )
    }

    // ---- (k) Backspace long-press affordance branch wired in IME lambda
    //         (dictate-pipeline-render-and-state-unification §5.5 / AC-C).
    //
    // Failure mode this locks against: a future edit removes the
    // `if (id == LogicalButtonId.BACKSPACE && isLongPress)` branch from
    // the `imeSideAffordance` lambda in DictateInputMethodService. The
    // accelerated-delete cascade (`onBackspaceLongClicked`) would then
    // become dead code again (its CR-DEL legacy caller is already gone),
    // re-introducing the B-C regression: long-press on Backspace does
    // nothing.
    //
    // Two assertions are paired: (1) the IME lambda calls
    // `onBackspaceLongClicked()` inside a gate that names BACKSPACE; and
    // (2) the ImeViewBackend long-click branch includes BACKSPACE in its
    // affordance gate (so the IME lambda is actually fed the gesture).

    @Test
    fun backspaceLongPressAffordanceWiredInImeLambda() {
        val imeCode = functionalCode(imeFile)
        val callRegex = Regex("""onBackspaceLongClicked\s*\(\s*\)""")
        val calls = callRegex.findAll(imeCode).toList()
        assertTrue(
            "AC-C invariant: DictateInputMethodService MUST invoke " +
                "`onBackspaceLongClicked()` from a code path (the IME " +
                "affordance lambda's BACKSPACE branch). Without it the " +
                "accelerated-delete cascade is dead code.",
            // calls.size includes the method definition site itself
            // (`public void onBackspaceLongClicked() { ... }` matches the
            // regex). We require at least 2: the definition + at least
            // one invocation.
            calls.size >= 2,
        )
        // The comment-stripper replaces comments with whitespace but
        // keeps newlines — a one-line gate `} else if (id == BACKSPACE
        // && isLongPress) { onBackspaceLongClicked(); }` survives in
        // ~80 chars of functional code. We scan a generous 600-char
        // window to absorb any future indent/refactor noise.
        val windowChars = 600
        val gatedByBackspace = calls.any { m ->
            val start = (m.range.first - windowChars).coerceAtLeast(0)
            val end = (m.range.last + windowChars).coerceAtMost(imeCode.length - 1)
            val nearby = imeCode.substring(start, end)
            nearby.contains("LogicalButtonId.BACKSPACE") &&
                nearby.contains("isLongPress")
        }
        assertTrue(
            "AC-C invariant: `onBackspaceLongClicked()` MUST be called from " +
                "a gate that names BOTH `LogicalButtonId.BACKSPACE` and " +
                "`isLongPress` within a 600-char window — i.e. the IME-side " +
                "affordance lambda's BACKSPACE-long-press branch. Without " +
                "this branch the catalog-driven render path swallows the " +
                "long-press gesture (no longClickResolver on BACKSPACE) and " +
                "the cascade never starts.",
            gatedByBackspace,
        )
    }

    @Test
    fun imeViewBackendLongClickBranchIncludesBackspace() {
        val code = functionalCode(imeViewBackendFile)
        // The long-click branch fires `imeSideAffordance(id, true)`.
        val callRegex = Regex("""imeSideAffordance\s*\(\s*id\s*,\s*true\s*\)""")
        val matches = callRegex.findAll(code).toList()
        assertTrue(
            "AC-C invariant (backend half): ImeViewBackend.kt MUST call " +
                "`imeSideAffordance(id, true)` at least once from the " +
                "long-click branch — the hook that feeds BACKSPACE / " +
                "RECORD / RESEND long-presses into the IME-side lambda.",
            matches.isNotEmpty(),
        )
        // The OR-chain gate (`id == RECORD || id == RESEND || id ==
        // BACKSPACE`) is ~80 chars of functional code post-strip; we
        // scan 600-char windows on either side to absorb whitespace
        // padding from stripped comments and tolerate future reorders.
        val windowChars = 600
        val coversBackspaceRecordResend = matches.any { m ->
            val start = (m.range.first - windowChars).coerceAtLeast(0)
            val end = (m.range.last + windowChars).coerceAtMost(code.length - 1)
            val nearby = code.substring(start, end)
            nearby.contains("LogicalButtonId.BACKSPACE") &&
                nearby.contains("LogicalButtonId.RECORD") &&
                nearby.contains("LogicalButtonId.RESEND")
        }
        assertTrue(
            "AC-C invariant (backend half): the `imeSideAffordance(id, true)` " +
                "gate in ImeViewBackend.kt MUST cover RECORD, RESEND, and " +
                "BACKSPACE within a 600-char window. Dropping BACKSPACE " +
                "re-introduces the dead-cascade regression (B-C).",
            coversBackspaceRecordResend,
        )
    }

    // ---- (l) IME affordance lambda handles BOTH RECORD and OVERLAY_RECORD
    //         (dictate-pipeline-render-and-state-unification §5.4 / AC-P-4).
    //
    // Failure mode this locks against: the IME's `imeSideAffordance`
    // lambda matched only `LogicalButtonId.RECORD` — an OVERLAY_RECORD
    // click in the floating widget fired the affordance hook with
    // `id = OVERLAY_RECORD`, but the lambda body dropped through
    // without calling `prepareCatalogStopRecordingIfActive()`. The
    // catalog `StopRecordingAndSend` then dispatched with no R-1
    // snapshot → pipeline FSM hangs in Preparing forever → endless
    // "sendet" (B-A Critical bug).
    //
    // The lock requires that the lambda's body — specifically the call
    // site of `prepareCatalogStopRecordingIfActive()` — sits inside a
    // gate that names BOTH `RECORD` and `OVERLAY_RECORD`.

    @Test
    fun affordanceHookHandlesBothRecordIds() {
        val code = functionalCode(imeFile)
        val callRegex = Regex("""prepareCatalogStopRecordingIfActive\s*\(\s*\)""")
        val calls = callRegex.findAll(code).toList()
        assertTrue(
            "AC-P-4 invariant: the IME's `imeSideAffordance` lambda MUST " +
                "invoke `prepareCatalogStopRecordingIfActive()` from at " +
                "least one branch. Without it, OVERLAY_RECORD (and RECORD) " +
                "clicks dispatch StopRecordingAndSend with no R-1 snapshot " +
                "→ pipeline FSM hangs in Preparing forever (B-A regression).",
            calls.isNotEmpty(),
        )
        // Each invocation of `prepareCatalogStopRecordingIfActive()` MUST
        // sit in a gate that names BOTH `LogicalButtonId.RECORD` and
        // `LogicalButtonId.OVERLAY_RECORD`. The window is generous
        // (1500 chars) because the post-strip lambda body still spans a
        // tall `else if` ladder with multi-paragraph KDocs stripped to
        // whitespace between branches — narrower windows missed the
        // RECORD gate on the OVERLAY_RECORD branch and vice-versa.
        val windowChars = 1500
        val symmetricGateCount = calls.count { m ->
            val start = (m.range.first - windowChars).coerceAtLeast(0)
            val end = (m.range.last + windowChars).coerceAtMost(code.length - 1)
            val nearby = code.substring(start, end)
            nearby.contains("LogicalButtonId.RECORD") &&
                nearby.contains("LogicalButtonId.OVERLAY_RECORD")
        }
        assertTrue(
            "AC-P-4 invariant: at least one " +
                "`prepareCatalogStopRecordingIfActive()` call-site MUST sit " +
                "in a gate that names BOTH `LogicalButtonId.RECORD` AND " +
                "`LogicalButtonId.OVERLAY_RECORD` within a 1500-char window. " +
                "Without ID-symmetry, the widget SEND click drops through " +
                "without a R-1 snapshot — the B-A Critical regression " +
                "returns (endless 'sendet' in the widget).",
            symmetricGateCount >= 1,
        )
    }

    // ---- (m) updatePromptButtonsEnabledState reads orchestrator state
    //         (dictate-pipeline-render-and-state-unification §5.7 / AC-E + AC-P-1).
    //
    // Failure mode this locks against: the legacy implementation read
    // `recordingStateController.getState()` (a legacy controller that
    // post-cutover stays permanently Idle) which made the
    // `disableNonSelectionPrompts` flag permanently `false` (B-E
    // regression). The fix routes the predicate through the
    // orchestrator state — `pipelineBinder.getState().value.recording`
    // / `.pipeline`.
    //
    // The lock asserts the IME function body does NOT contain
    // `recordingStateController.getState()` — i.e. the legacy read is
    // gone from THIS function (other in-file readers may persist; OQ-3
    // leaves them in scope for a follow-up plan
    // `dictate-recording-state-controller-removal`).

    @Test
    fun updatePromptButtonsEnabledStateReadsOrchestratorNotLegacyController() {
        val code = functionalCode(imeFile)
        // Locate the method body: from the signature
        // `updatePromptButtonsEnabledState() {` to its matching `}` at
        // brace depth 0 from the opening brace. Hand-rolled bracket
        // matcher (the comment-stripper has already removed comments
        // + strings so braces are real).
        val sigRegex = Regex(
            """\bprivate\s+void\s+updatePromptButtonsEnabledState\s*\(\s*\)\s*\{"""
        )
        val sigMatch = sigRegex.find(code)
        assertTrue(
            "AC-E regression-lock: DictateInputMethodService MUST declare " +
                "`private void updatePromptButtonsEnabledState()` — the " +
                "single entry-point for re-evaluating the prompt-chips " +
                "disable bit. Signature missing → method renamed / " +
                "removed; update this lock to match.",
            sigMatch != null,
        )
        val bodyStart = sigMatch!!.range.last + 1
        var depth = 1
        var i = bodyStart
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        val body = code.substring(bodyStart, (i - 1).coerceAtLeast(bodyStart))
        assertTrue(
            "AC-E + AC-P-1 invariant: " +
                "`updatePromptButtonsEnabledState()` MUST NOT read " +
                "`recordingStateController.getState()` — the legacy " +
                "controller is post-cutover dead code (its state stays " +
                "permanently Idle on the new path → the chips were " +
                "always tappable, the B-E regression). Read the " +
                "orchestrator-authoritative `pipelineBinder.getState()" +
                ".value.recording / .pipeline` instead. Body snippet: " +
                "${body.lines().filter { it.isNotBlank() }.take(8)}",
            !Regex("""recordingStateController\s*\.\s*getState\s*\(""")
                .containsMatchIn(body),
        )
        // Positive symmetry — the new implementation MUST read the
        // orchestrator state. Pin the read so a future "revert to
        // legacy" cannot silently pass.
        assertTrue(
            "AC-E positive lock: " +
                "`updatePromptButtonsEnabledState()` MUST consult " +
                "`pipelineBinder.getState()` (the orchestrator's " +
                "authoritative StateFlow). Without it, the flag would " +
                "be a constant — the legacy bug returns.",
            Regex("""pipelineBinder\s*\.\s*getState\s*\(""").containsMatchIn(body),
        )
    }

    @Test
    fun commentStripperIsSound_promptButtonsLegacyControllerRead() {
        val codeSample = """
            private void updatePromptButtonsEnabledState() {
                RecordingState s = recordingStateController.getState();
                disableNonSelectionPrompts = s.isRecordingOrPaused();
            }
        """.trimIndent()
        val stripped = stripCommentsAndStrings(codeSample)
        assertTrue(
            "A real legacy-controller read in the method body MUST survive " +
                "stripping (proves (m) is non-vacuous — a regression WOULD " +
                "fail loud).",
            Regex("""recordingStateController\s*\.\s*getState\s*\(""")
                .containsMatchIn(stripped),
        )
        val docOnly = """
            // historical: recordingStateController.getState() was the
            //   legacy read — removed in B-E (AC-E + AC-P-1).
            /* val s = "recordingStateController.getState()" */
        """.trimIndent()
        val docStripped = stripCommentsAndStrings(docOnly)
        assertTrue(
            "Doc-only mentions of the legacy read in KDoc/anchors MUST be " +
                "stripped (proves (m) does NOT false-RED on the " +
                "documented replacement-trail).",
            !Regex("""recordingStateController\s*\.\s*getState\s*\(""")
                .containsMatchIn(docStripped),
        )
    }

    @Test
    fun commentStripperIsSound_affordanceHookOverlayRecordSymmetry() {
        val codeSample = """
            } else if (id == LogicalButtonId.RECORD
                    || id == LogicalButtonId.OVERLAY_RECORD) {
                prepareCatalogStopRecordingIfActive();
            }
        """.trimIndent()
        val stripped = stripCommentsAndStrings(codeSample)
        assertTrue(
            "A real symmetric RECORD/OVERLAY_RECORD gate around the helper " +
                "call MUST survive stripping (proves (l) non-vacuity).",
            stripped.contains("LogicalButtonId.RECORD") &&
                stripped.contains("LogicalButtonId.OVERLAY_RECORD") &&
                Regex("""prepareCatalogStopRecordingIfActive\s*\(\s*\)""")
                    .containsMatchIn(stripped),
        )
        val docOnly = """
            // historical: id == LogicalButtonId.RECORD || id == LogicalButtonId.OVERLAY_RECORD →
            //   prepareCatalogStopRecordingIfActive() — the symmetric snapshot
            /* val ref = "LogicalButtonId.OVERLAY_RECORD" */
        """.trimIndent()
        val docStripped = stripCommentsAndStrings(docOnly)
        assertTrue(
            "Doc-only mention of the symmetric gate must NOT survive " +
                "stripping (proves (l) does not false-GREEN).",
            !docStripped.contains("LogicalButtonId.RECORD") &&
                !docStripped.contains("LogicalButtonId.OVERLAY_RECORD"),
        )
    }

    @Test
    fun commentStripperIsSound_backspaceLongPressAffordance() {
        val codeSample = """
            } else if (id == LogicalButtonId.BACKSPACE && isLongPress) {
                onBackspaceLongClicked();
            }
        """.trimIndent()
        val stripped = stripCommentsAndStrings(codeSample)
        assertTrue(
            "A real BACKSPACE-long-press affordance branch must survive " +
                "stripping (proves the BACKSPACE-affordance lock is " +
                "non-vacuous).",
            stripped.contains("LogicalButtonId.BACKSPACE") &&
                stripped.contains("isLongPress") &&
                Regex("""onBackspaceLongClicked\s*\(\s*\)""").containsMatchIn(stripped),
        )
        val docOnly = """
            // historical: id == LogicalButtonId.BACKSPACE && isLongPress →
            //   onBackspaceLongClicked() — the accel-delete cascade entry
            /* val ref = "id == LogicalButtonId.BACKSPACE && isLongPress" */
        """.trimIndent()
        val docStripped = stripCommentsAndStrings(docOnly)
        assertTrue(
            "Doc-only mentions of the BACKSPACE-affordance branch must NOT " +
                "survive stripping (proves the lock does not false-GREEN).",
            !docStripped.contains("LogicalButtonId.BACKSPACE") &&
                !docStripped.contains("isLongPress"),
        )
    }
}
