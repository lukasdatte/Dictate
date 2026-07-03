package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-001 / F-003 structural regression-lock (reprocess send flow +
 * auto-apply priming).
 *
 * **F-001** — ReprocessStaging Send used to lose the user's staged queue
 * edits on BOTH trigger surfaces: the catalog record button dispatched a
 * queue-less `SendStaging` (whose reducer arm emitted
 * `Effect.SubmitReprocess(queue = emptyList())` → live-queue fallback),
 * while the QWERTZ path's correctly-parameterised *direct*
 * `pipelineRunner.submitReprocess(...)` was silently dropped as a
 * duplicate (`ActiveJobRegistry.register` → `false`). The fix unifies
 * both surfaces onto ONE flow: `handleReprocessSend()` is the single
 * submitter — it snapshots the reprocess config and dispatches
 * `SendStaging` carrying the staged queue as explicit content slots
 * (`PromptQueueSlot.fromIds(editableQueue)`); the IME's direct
 * `submitReprocess` call is deleted and the catalog resolver
 * (`resolveSendStagingAction`) returns `null`.
 *
 * **F-003** — the auto-apply prompt queue was only primed on the QWERTZ
 * record path (`startRecording()` → `prepareAutoApplyQueue()`); the
 * catalog record button + overlay widget start recordings via the
 * catalog `StartRecording` dispatch and never primed. The fix adds
 * `prepareCatalogAutoApplyQueueIfIdle()` to the shared
 * RECORD/OVERLAY_RECORD affordance branch.
 *
 * Both halves live in `DictateInputMethodService.java` (legacy Java IME —
 * not constructible in a pure-JVM test), so this lock follows the
 * established source-scan pattern of
 * [net.devemperor.dictate.core.CutoverArchitectureInvariantTest] /
 * [net.devemperor.dictate.history.HistoryDetailJobRoutingInvariantTest]:
 * strip comments + string literals, assert on the remaining functional
 * code. Each ban/requirement is paired with a stripper self-test so the
 * lock cannot silently go false-GREEN (RR-4).
 *
 * Red-proofs (each test fails on the pre-fix source):
 *  - [imeNeverCallsSubmitReprocessDirectly] — pre-fix
 *    `handleReprocessSend` contained the direct call (1 ≠ 0).
 *  - [handleReprocessSendDispatchesThePayloadBearingSendStaging] —
 *    pre-fix `SendStaging(targetSessionId)` was single-arg.
 *  - [handleReprocessSendIsReachableFromBothSendSurfaces] — pre-fix the
 *    only call-site was `onRecordClicked` (1 < 2).
 *  - [autoApplyPrimingHelperExistsAndDelegates] /
 *    [autoApplyQueueIsPrimedOnBothRecordStartSurfaces] — pre-fix the
 *    helper did not exist and `prepareAutoApplyQueue` had exactly one
 *    IME call-site.
 *
 * The pure-state halves of the fix are locked elsewhere:
 * `PipelineModuleTest` (SendStaging payload → `Effect.SubmitReprocess`),
 * `ImePipelineConfigResolverTest` + `PipelineRunnerSubsystemAdapterTest`
 * (explicit-empty vs unset through the resolvers), and
 * `ActionResolversTest` (catalog `resolveSendStagingAction` stays null).
 */
class ReprocessSendFlowInvariantTest {

    private val imeFile = File(
        "src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java"
    )

    private fun functionalCode(): String {
        assertTrue("source file moved? ${imeFile.absolutePath}", imeFile.isFile)
        return stripCommentsAndStrings(imeFile.readText())
    }

    // ── F-001: single submit flow ─────────────────────────────────────────

    @Test
    fun imeNeverCallsSubmitReprocessDirectly() {
        val code = functionalCode()
        assertEquals(
            "F-001: the IME must not call pipelineRunner.submitReprocess " +
                "directly — the staged queue travels on the SendStaging " +
                "action and the PipelineModule reducer arm is the single " +
                "submitter. A direct call re-opens the duplicate-submit " +
                "race in which the queue-carrying job is dropped by " +
                "ActiveJobRegistry.",
            0,
            Regex("""\.\s*submitReprocess\s*\(""").findAll(code).count(),
        )
    }

    @Test
    fun handleReprocessSendDispatchesThePayloadBearingSendStaging() {
        val code = functionalCode()
        // The staged queue is converted to explicit content slots…
        assertEquals(
            "F-001: handleReprocessSend must convert the staged editable " +
                "queue to explicit slots via PromptQueueSlot.fromIds " +
                "(empty = run zero prompts, NOT unset).",
            1,
            Regex("""PromptQueueSlot\s*\.\s*fromIds\s*\(\s*editableQueue\s*\)""")
                .findAll(code).count(),
        )
        // …and the SendStaging dispatch carries (sessionId, slots, language).
        assertEquals(
            "F-001: the SendStaging dispatch must carry the staged slots + " +
                "language — a queue-less SendStaging(sessionId) is exactly " +
                "the pre-fix data loss.",
            1,
            Regex(
                """SendStaging\s*\(\s*targetSessionId\s*,\s*stagedSlots\s*,\s*selectedLanguage\s*\)"""
            ).findAll(code).count(),
        )
    }

    @Test
    fun handleReprocessSendIsReachableFromBothSendSurfaces() {
        val code = functionalCode()
        val defs = Regex("""\bprivate\s+void\s+handleReprocessSend\s*\(\s*\)""")
            .findAll(code).count()
        val occurrences = Regex("""\bhandleReprocessSend\s*\(""").findAll(code).count()
        assertEquals(
            "exactly one handleReprocessSend definition expected",
            1,
            defs,
        )
        assertTrue(
            "F-001: handleReprocessSend must be invoked from BOTH send " +
                "surfaces — the QWERTZ record button (onRecordClicked) AND " +
                "the catalog record button (imeSideAffordance RECORD staging " +
                "branch). Call-sites found: ${occurrences - defs}.",
            occurrences - defs >= 2,
        )
    }

    // ── F-003: auto-apply priming on the catalog record path ─────────────

    @Test
    fun autoApplyPrimingHelperExistsAndDelegates() {
        val code = functionalCode()
        assertEquals(
            "F-003: DictateInputMethodService must declare exactly one " +
                "prepareCatalogAutoApplyQueueIfIdle() helper (the catalog " +
                "record-start auto-apply prime).",
            1,
            Regex("""\bprivate\s+void\s+prepareCatalogAutoApplyQueueIfIdle\s*\(\s*\)""")
                .findAll(code).count(),
        )
        // The helper's body must actually prime the queue manager — slice
        // the functional code from the DEFINITION (not the earlier call
        // site in the affordance lambda) and require the call within the
        // method body window.
        val defIdx = Regex("""\bprivate\s+void\s+prepareCatalogAutoApplyQueueIfIdle\s*\(\s*\)""")
            .find(code)!!.range.first
        val bodyWindow = code.substring(defIdx, minOf(defIdx + 600, code.length))
        assertTrue(
            "F-003: prepareCatalogAutoApplyQueueIfIdle must call " +
                "promptQueueManager.prepareAutoApplyQueue().",
            Regex("""promptQueueManager\s*\.\s*prepareAutoApplyQueue\s*\(\s*\)""")
                .containsMatchIn(bodyWindow),
        )
    }

    @Test
    fun autoApplyQueueIsPrimedOnBothRecordStartSurfaces() {
        val code = functionalCode()
        val helperCalls = Regex("""\bprepareCatalogAutoApplyQueueIfIdle\s*\(""")
            .findAll(code).count()
        assertTrue(
            "F-003: prepareCatalogAutoApplyQueueIfIdle() must be invoked " +
                "from the imeSideAffordance RECORD/OVERLAY_RECORD branch " +
                "(definition + >=1 call-site expected).",
            helperCalls >= 2,
        )
        assertTrue(
            "F-003: prepareAutoApplyQueue must have >=2 IME call-sites — " +
                "the QWERTZ startRecording() prime AND the catalog " +
                "affordance prime. One call-site means the catalog record " +
                "path records without the user's autoApply prompts again.",
            Regex("""\bprepareAutoApplyQueue\s*\(""").findAll(code).count() >= 2,
        )
    }

    // ── Non-vacuity: stripper keeps code hits, drops doc/string hits ─────

    @Test
    fun stripperKeepsFunctionalTokensAndDropsCommentOrStringOccurrences() {
        val snippet = """
            // pipelineRunner.submitReprocess(doc) — anchor, must NOT count
            /* prepareCatalogAutoApplyQueueIfIdle() in a block comment */
            String s = "handleReprocessSend(in a string)";
            runner.submitReprocess(real);
            prepareCatalogAutoApplyQueueIfIdle();
            handleReprocessSend();
        """.trimIndent()
        val stripped = stripCommentsAndStrings(snippet)
        assertEquals(1, Regex("""\.\s*submitReprocess\s*\(""").findAll(stripped).count())
        assertEquals(
            1,
            Regex("""\bprepareCatalogAutoApplyQueueIfIdle\s*\(""").findAll(stripped).count(),
        )
        assertEquals(1, Regex("""\bhandleReprocessSend\s*\(""").findAll(stripped).count())
    }

    /**
     * Comment + string-literal stripper (same algorithm as
     * `CutoverArchitectureInvariantTest`, kept local so the locks stay
     * independently movable — the established convention for these
     * source-scan tests).
     */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else ' '
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
                    if (next == '"' && i + 2 < n && source[i + 2] == '"') {
                        i += 3
                        while (i + 2 < n &&
                            !(source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"')
                        ) {
                            i++
                        }
                        i += 3
                    } else {
                        i++
                        while (i < n && source[i] != '"') {
                            if (source[i] == '\\' && i + 1 < n) i++
                            i++
                        }
                        i++
                    }
                    out.append(' ')
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
