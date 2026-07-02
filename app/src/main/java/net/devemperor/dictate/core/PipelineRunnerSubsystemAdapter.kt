package net.devemperor.dictate.core

import android.content.Context
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.state.PipelineRunnerSubsystem
import java.io.File

/**
 * Production [PipelineRunnerSubsystem] — a **thin delegation** to the
 * process-global [JobExecutor] singleton (Spec 1 §9.6: "`JobExecutor`
 * nie gelöscht — implementiert das `PipelineRunner`-Interface";
 * §13.3.11 DIP — this adapter *is* the abstraction the orchestrator
 * hangs on).
 *
 * **OQ-1 disposition (Epic §7 OQ-1, decided in C3-B1):** the adapter is
 * the thin-delegation option, NOT a reimplementation of the 1386-LOC
 * [PipelineOrchestrator] runner body. [JobExecutor] already exposes a
 * clean `start` / `cancel` surface and [ActiveJobRegistry] a clean
 * `isActive` / size surface, so no architecture-conflict escalation is
 * needed. `JobExecutor` / `PipelineOrchestrator` are **not rewritten**;
 * this adapter wraps `JobExecutor.INSTANCE` exactly as
 * `DictateInputMethodService` does today (the proven code path, Epic §7
 * OQ-1 fallback).
 *
 * **R-1 — the load-bearing recording-drive risk (Epic §6.1).** The IME's
 * `JobRequest.TranscriptionPipeline` construction
 * (`DictateInputMethodService.java:2214-2230`) threads ~15 fields:
 * language, queued-prompt-ids, style-prompt, livePrompt,
 * autoSwitchKeyboard, targetAppPackage, totalSteps, recordingsDir,
 * origin, kind, showResendButton, etc. The new orchestrator path's
 * submit Effect ([net.devemperor.dictate.state.modules.PipelineModule.Effect.SubmitPipeline])
 * carries **only** `sessionId` + `audioFile` — the remaining ~13 fields
 * are IME-runtime context (LanguageResolver, PromptQueueManager,
 * AutoFormattingService, EditorInfo, the IME's `livePrompt` /
 * `autoSwitchKeyboard` instance flags) that is **not yet threaded onto
 * the orchestrator path**. Threading it is C5's job (the IME-trigger
 * flip).
 *
 * To keep R-1 a *surfaced* risk rather than a *silent* one, the adapter
 * does **not** invent defaults for the IME-runtime-only fields. It
 * delegates [JobRequest.TranscriptionPipeline] construction to an
 * injected [PipelineConfigResolver] seam (mirroring the established
 * `emitAction: (Action) -> Unit` provider-lambda pattern used by the
 * other `core` adapter classes). C5 wires a resolver backed by the
 * IME's exact construction sources; until then the
 * [DefaultPipelineConfigResolver] resolves the fields that ARE
 * available service-side and **throws** for the IME-runtime-only ones,
 * so a premature new-path submit fails loud (R-1: silent data loss is
 * the failure mode we are defending against — a thrown exception is
 * caught by `PipelineModule`'s `EffectFailure` arm and surfaces; a
 * silently-wrong `JobRequest` does not).
 *
 * **Submit-direction only (Epic §4 Block B1).** This adapter is the
 * orchestrator → runner direction. The runner → orchestrator
 * action-back-channel (notification action-buttons → Action) is C4's
 * `PipelineActionRouter`, not here. This chunk also does **not** flip
 * the IME trigger (C5) nor delete the legacy `JobExecutor.start`
 * call-site (C7, gated on C6); the legacy path stays fully intact —
 * C3 only makes the *new* path *able* to reach a real runner.
 *
 * **Threading.** `submit` / `submitReprocess` / `cancel` are called from
 * `PipelineModule.runEffect`, which runs on the orchestrator's
 * `Dispatchers.Main.immediate` dispatch loop.
 * [JobExecutor.start] is itself non-blocking (it submits onto its own
 * single-thread executor and returns immediately), so the main thread
 * is not stalled.
 *
 * @param context captured for [JobExecutor.start]'s failure path (it
 *   updates the session row to FAILED via Room when the pipeline
 *   throws). Use the application context — the adapter outlives any
 *   single IME-view.
 * @param configResolver R-1 seam: produces the full
 *   [JobRequest.TranscriptionPipeline] for a `(sessionId, audioFile)`
 *   submit, and the reprocess variant. C5 injects the IME-faithful
 *   resolver; [DefaultPipelineConfigResolver] is the C3 baseline.
 *
 * @see net.devemperor.dictate.state.PipelineRunnerSubsystem
 * @see net.devemperor.dictate.core.JobExecutor
 * @see net.devemperor.dictate.core.ActiveJobRegistry
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §9.6 §13.3.11
 */
class PipelineRunnerSubsystemAdapter(
    private val context: Context,
    private val configResolver: PipelineConfigResolver,
) : PipelineRunnerSubsystem {

    override fun submit(sessionId: String, audioFile: File) {
        val request = configResolver.resolveFresh(sessionId, audioFile)
        // `JobExecutor.start`'s `Boolean` (false = another job active) is
        // intentionally not propagated: on the new path the single-submit
        // guard is the FSM `Idle → Preparing` edge (Epic F-12 — only an
        // `Idle` pipeline emits `SubmitPipeline`), so a busy-collision
        // cannot originate here. The IME's legacy busy-toast lives on the
        // legacy call-site, which C3 leaves intact.
        JobExecutor.start(context, request)
    }

    override fun submitReprocess(
        sessionId: String,
        audioFile: File?,
        queue: List<Int>,
        language: String?,
    ) {
        val request = configResolver.resolveReprocess(sessionId, audioFile, queue, language)
        JobExecutor.start(context, request)
    }

    override fun cancel(sessionId: String) {
        JobExecutor.cancel(sessionId)
    }

    /**
     * `true` iff the registry currently tracks a job for [sessionId].
     * Mirrors [ActiveJobRegistry.isActive] — the single source of truth
     * for "is this pipeline running" (the IME's busy-toast guard uses
     * the same registry via `JobExecutor.start`'s `false` return).
     */
    override fun isRunning(sessionId: String): Boolean =
        ActiveJobRegistry.isActive(sessionId)

    override fun activeJobCount(): Int =
        ActiveJobRegistry.state.value.size
}

/**
 * R-1 seam: builds the [JobRequest.TranscriptionPipeline] for a
 * pipeline submit. Split out so C5 can inject an implementation backed
 * by `DictateInputMethodService`'s exact construction sources
 * (LanguageResolver, PromptQueueManager, AutoFormattingService, the
 * `EditorInfo` target package, the `livePrompt` / `autoSwitchKeyboard`
 * instance flags) without `PipelineRunnerSubsystemAdapter` taking a
 * dependency on the IME service.
 *
 * **Why a seam and not "compute it in the adapter"?** The
 * IME-runtime-only fields (see [PipelineRunnerSubsystemAdapter] KDoc
 * R-1 paragraph) are not reachable from the service-side composition
 * root in C3. Inventing defaults for them is exactly the silent
 * data-loss R-1 forbids. The seam makes the missing-source explicit and
 * gives C5 a single, typed insertion point.
 */
interface PipelineConfigResolver {

    /** Build the fresh-recording [JobRequest.TranscriptionPipeline]. */
    fun resolveFresh(sessionId: String, audioFile: File): JobRequest.TranscriptionPipeline

    /** Build the reprocess-staging [JobRequest.TranscriptionPipeline]. */
    fun resolveReprocess(
        sessionId: String,
        audioFile: File?,
        queue: List<Int>,
        language: String?,
    ): JobRequest.TranscriptionPipeline
}

/**
 * C3 baseline [PipelineConfigResolver].
 *
 * Resolves the [JobRequest.TranscriptionPipeline] fields that ARE
 * available service-side (1:1 with the IME construction — see the
 * field-by-field fidelity table in the B2 block-report `### Chunk
 * C3-B1`):
 *
 *  - `sessionId` — the pre-allocated UUID from the submit Effect
 *    (`DictateInputMethodService.java:2213/2215` `preAllocatedId`).
 *  - `audioFilePath` — `audioFile.absolutePath`
 *    (IME `:2218 audioFile.getAbsolutePath()`).
 *  - `kind` — `RECORDING` for fresh, `REPROCESS_STAGING` for reprocess
 *    (IME `:2217` / `:3041`).
 *  - `recordingsDir` — `File(filesDir, "recordings")`
 *    (IME `:2223` / `:3047` `new File(getFilesDir(), "recordings")`).
 *  - `origin` — `SessionOrigin.KEYBOARD`
 *    (IME `:2226` / `:3050`).
 *  - `reuseSessionId` — `null` fresh / `sessionId` reprocess
 *    (IME `:2224` / `:3048`).
 *  - reprocess `language` / `queuedPromptIds` / `modelOverride` — from
 *    the [PipelineRunnerSubsystem.submitReprocess] args (IME `:3043` /
 *    `:3045` / `:3044`), which the staging FSM already carries.
 *
 * **Throws [UnsupportedOperationException] for the fresh-recording
 * IME-runtime-only fields** — `totalSteps`, `language`, `stylePrompt`,
 * `queuedPromptIds`, `targetAppPackage`, `livePrompt`,
 * `autoSwitchKeyboard`, `showResendButton`. These come from the IME's
 * `LanguageResolver` / `PromptService` / `PromptQueueManager` /
 * `AutoFormattingService` / `EditorInfo` / instance flags
 * (`DictateInputMethodService.java:2187-2206, :2227-2229`) which are
 * **not on the orchestrator path until C5**. A new-path fresh submit
 * before C5 therefore fails *loud* (caught by `PipelineModule`'s
 * `EffectFailure` arm) rather than transcribing with wrong language /
 * no prompts (R-1 silent data loss). The legacy IME path is unaffected
 * — it constructs the full request itself and is still authoritative
 * (Epic §6.2; C5/C7 own the flip/delete).
 *
 * @param filesDirProvider supplies the service `filesDir` (the
 *   `recordings` parent) — provider-lambda so the resolver stays
 *   Context-free and unit-testable with a temp dir.
 */
class DefaultPipelineConfigResolver(
    private val filesDirProvider: () -> File,
) : PipelineConfigResolver {

    override fun resolveFresh(
        sessionId: String,
        audioFile: File,
    ): JobRequest.TranscriptionPipeline {
        // R-1: do NOT silently default the IME-runtime-only fields.
        // Surfacing beats guessing — C5 supplies the IME-faithful
        // resolver. See block-report ### Chunk C3-B1 fidelity table.
        throw UnsupportedOperationException(
            "Fresh-recording JobRequest construction requires the IME-runtime " +
                "config sources (language/prompt-queue/style-prompt/livePrompt/" +
                "autoSwitch/targetApp/totalSteps) that are not on the " +
                "orchestrator path until C5 (Epic §4 Block B3). The legacy IME " +
                "path remains authoritative for fresh recordings (Epic §6.2). " +
                "Inject a C5 PipelineConfigResolver to enable new-path submit.",
        )
    }

    override fun resolveReprocess(
        sessionId: String,
        audioFile: File?,
        queue: List<Int>,
        language: String?,
    ): JobRequest.TranscriptionPipeline = buildReprocess(sessionId, audioFile, queue, language)

    private fun buildReprocess(
        sessionId: String,
        audioFile: File?,
        queue: List<Int>,
        language: String?,
    ): JobRequest.TranscriptionPipeline {
        // Reprocess-staging carries its config in the action payload
        // (the staging FSM already threaded selectedLanguage /
        // editableQueue / selectedModel — IME :3009-3051). totalSteps
        // mirrors the IME: 1 (transcription) + queue size. The
        // AutoFormatting +1 (IME :3035) is an IME-runtime source not on
        // this path; documented as an R-1 reprocess delegation in the
        // block-report (the legacy reprocess path stays authoritative
        // until C5, same as the fresh path).
        val totalSteps = 1 + queue.size
        return JobRequest.TranscriptionPipeline(
            /* sessionId */ sessionId,
            /* totalSteps */ totalSteps,
            /* kind */ JobRequest.TranscriptionKind.REPROCESS_STAGING,
            /* audioFilePath */ audioFile?.absolutePath,
            /* language */ language,
            /* modelOverride */ null,
            // ID-only slots — the staging FSM carries entity IDs (see
            // PromptQueueSlot shape 1; content-carrying slots are the
            // history queue-editor's transport). fromIdsOrUnset: an empty
            // staging queue means UNSET (F-001 — Effect.SubmitReprocess
            // still carries emptyList), keeping the run-time live-queue
            // fallback that path relies on today.
            /* queuedPromptSlots */ PromptQueueSlot.fromIdsOrUnset(queue),
            /* targetAppPackage */ null,
            /* recordingsDir */ File(filesDirProvider(), "recordings"),
            /* reuseSessionId */ sessionId,
            /* stylePrompt */ null,
            /* origin */ SessionOrigin.KEYBOARD,
        )
    }
}

/**
 * C5 — IME-faithful [PipelineConfigResolver] delegation.
 *
 * **The R-1 closure (C3-IMPL-1 / C3-IMPL-2, B2 block-report).** C3
 * shipped [DefaultPipelineConfigResolver], which **throws** for the 8
 * IME-runtime-only fresh-recording fields (`language`, `stylePrompt`,
 * `queuedPromptIds`, `targetAppPackage`, `livePrompt`,
 * `autoSwitchKeyboard`, `totalSteps`, `showResendButton`) because they
 * are not reachable from the service-side composition root — they live
 * in `DictateInputMethodService`'s `LanguageResolver` / `PromptService`
 * / `PromptQueueManager` / `AutoFormattingService` / `EditorInfo` /
 * instance flags. C5 flips the IME recording-trigger to `dispatch(...)`,
 * so the IME now needs a typed insertion point to thread those fields
 * onto the orchestrator path **field-for-field identical** to the
 * legacy `DictateInputMethodService.java:2214-2230` construction
 * (R-1: field-by-field fidelity — a dropped field silently transcribes
 * with the wrong language / no prompts).
 *
 * This resolver delegates to an IME-registered [PipelineConfigResolver]
 * when one is present (the IME installs it from `onServiceConnected`
 * via `LocalBinder.registerPipelineConfigResolver`, snapshotting its
 * live config at recording-trigger time), and falls back to [fallback]
 * (the C3 [DefaultPipelineConfigResolver]) when no IME is bound.
 *
 * **Why a delegate and not "rebuild the adapter".** The adapter is
 * constructed in `DictatePipelineService.onCreate` (before any IME
 * binds) and `PipelineRunnerSubsystemAdapter.configResolver` is
 * `private val` — immutable by design. A late-bound delegate (the same
 * `@Volatile` provider-lambda pattern the `LocalBinder.delegate*` fields
 * use) lets the IME supply the faithful resolver after bind without
 * reconstructing the adapter or widening the action payload with
 * IME-runtime config the reducer must not carry (ADR-0001 — actions are
 * pure data, no IME-view runtime state).
 *
 * **Fallback safety (Epic §6.2).** When no IME resolver is registered
 * (service running headless, IME not yet bound, or a future caller),
 * `resolveFresh` falls through to [DefaultPipelineConfigResolver] which
 * **throws** — surfacing beats a silently-wrong `JobRequest` (R-1).
 *
 * @param fallback the C3 baseline resolver used when no IME resolver is
 *   registered.
 * @param imeResolverProvider supplies the IME-registered resolver (or
 *   `null` when no IME is bound). A provider-lambda so the binding stays
 *   `@Volatile`-late-bound (mirrors `LocalBinder.delegateInputConnectionProvider`).
 */
class DelegatingPipelineConfigResolver(
    private val fallback: PipelineConfigResolver,
    private val imeResolverProvider: () -> PipelineConfigResolver?,
) : PipelineConfigResolver {

    override fun resolveFresh(sessionId: String, audioFile: File): JobRequest.TranscriptionPipeline =
        (imeResolverProvider() ?: fallback).resolveFresh(sessionId, audioFile)

    override fun resolveReprocess(
        sessionId: String,
        audioFile: File?,
        queue: List<Int>,
        language: String?,
    ): JobRequest.TranscriptionPipeline =
        (imeResolverProvider() ?: fallback).resolveReprocess(sessionId, audioFile, queue, language)
}
