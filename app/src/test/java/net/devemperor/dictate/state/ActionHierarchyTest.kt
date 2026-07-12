package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Type-level tests for the [Action] sealed hierarchy.
 *
 * Verifies:
 * - Every module has an inner sealed Action class registered as a direct
 *   `Action::class.sealedSubclasses` child (so the orchestrator's
 *   `KClass.sealedSubclasses` walk finds them).
 * - `data object` singletons compare by identity.
 * - `data class` actions compare by content.
 * - [Action.EffectFailure] is a top-level sibling of the module actions
 *   (not nested inside one).
 */
class ActionHierarchyTest {

    // ────────────────────────────────────────────────────────────────
    // Sealed hierarchy completeness — Action has the expected children
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `Action sealedSubclasses include all 18 module sealed actions plus EffectFailure`() {
        val direct = Action::class.sealedSubclasses.map { it.simpleName }.toSet()

        // 18 module action classes + 1 top-level EffectFailure = 19 direct subclasses.
        // B3.1 / ADR-0008 added `WidgetAction` alongside the legacy
        // `ViewModeAction`; both stay registered until B3.5 retires the
        // ViewMode axis. 2026-07-02 (ADR-0006 completion) added
        // `InfoHintAction`. ADR-0013 added `ReviewPanelAction`. ADR-0014 added
        // `HistoryPanelAction`. The remaining names match the §15.1 module
        // inventory (+ 1 Phase-2 stub).
        val expected = setOf(
            "EffectFailure",
            "RecordingAction",
            "PipelineAction",
            "ViewModeAction",
            "WidgetAction",
            "LayoutAction",
            "AudioAction",
            "ResendAction",
            "LivePromptAction",
            "LanguageAction",
            "OverlayAction",
            "FeatureToggleAction",
            "ThemingAction",
            "PendingSessionsAction",
            "KeyboardInputAction",
            "InfoHintAction",
            "InterruptionAction",
            "ReviewPanelAction",
            "HistoryPanelAction",
        )
        assertEquals(expected, direct)
    }

    @Test
    fun `every module action sealed class has at least one concrete leaf`() {
        Action::class.sealedSubclasses
            .filter { it != Action.EffectFailure::class }    // EffectFailure is a leaf, not a sealed
            .forEach { module ->
                val leafCount = module.sealedSubclasses.size
                assertTrue(
                    "Module action ${module.simpleName} has no leaves",
                    leafCount > 0,
                )
            }
    }

    // ────────────────────────────────────────────────────────────────
    // data object singleton identity
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `data object action singletons compare by identity`() {
        assertSame(Action.RecordingAction.PauseRecording, Action.RecordingAction.PauseRecording)
        assertSame(Action.RecordingAction.CancelRecording, Action.RecordingAction.CancelRecording)
        assertSame(Action.OverlayAction.ResetSuppressBit, Action.OverlayAction.ResetSuppressBit)
        assertSame(Action.KeyboardInputAction.Backspace, Action.KeyboardInputAction.Backspace)
        assertSame(Action.ViewModeAction.OnImeViewHidden, Action.ViewModeAction.OnImeViewHidden)
    }

    @Test
    fun `data object actions are equal by reference and by content`() {
        // data object has both === and == returning true (the latter via auto-generated equals)
        assertEquals(Action.RecordingAction.StopRecording, Action.RecordingAction.StopRecording)
        // F-1 — `ClearManualPasteFlag` moved from `PipelineAction` to
        // `ResendAction` because the flag lives on `ResendState`.
        assertEquals(Action.ResendAction.ClearManualPasteFlag, Action.ResendAction.ClearManualPasteFlag)
    }

    // ────────────────────────────────────────────────────────────────
    // data class action equality
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `RecordingAction StartRecording equality is by content`() {
        val f = File("/cache/a.m4a")
        val a = Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, f, sessionId = "sid-test")
        val b = Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, f, sessionId = "sid-test")

        assertEquals(a, b)
    }

    @Test
    fun `RecordingAction StartRecording with different target is not equal`() {
        val f = File("/cache/a.m4a")
        val a = Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, f, sessionId = "sid-test")
        val b = Action.RecordingAction.StartRecording(InsertionTarget.REPROCESS_STAGING, f, sessionId = "sid-test")

        assertNotEquals(a, b)
    }

    @Test
    fun `PipelineAction Submit equality compares sessionId + audioFile`() {
        val f = File("/cache/x.m4a")
        val a = Action.PipelineAction.TriggerPipeline("s1", f)
        val b = Action.PipelineAction.TriggerPipeline("s1", f)
        val c = Action.PipelineAction.TriggerPipeline("s2", f)

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `OverlayAction UpdateOverlayPosition compares all three fields`() {
        val a = Action.OverlayAction.UpdateOverlayPosition(portrait = true, x = 0.5f, y = 0.5f)
        val b = Action.OverlayAction.UpdateOverlayPosition(portrait = true, x = 0.5f, y = 0.5f)
        val c = Action.OverlayAction.UpdateOverlayPosition(portrait = false, x = 0.5f, y = 0.5f)

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // ────────────────────────────────────────────────────────────────
    // EffectFailure — top-level, not nested
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `EffectFailure is a direct child of Action (not nested in a module action)`() {
        // The class object's direct supertype chain reaches Action via one step;
        // it is NOT inside any RecordingAction / PipelineAction / etc.
        val direct = Action::class.sealedSubclasses
        assertTrue(
            "EffectFailure must be a direct sealed subclass of Action",
            direct.contains(Action.EffectFailure::class),
        )
    }

    @Test
    fun `EffectFailure equality compares originModuleId effect and reason`() {
        val a = Action.EffectFailure(ModuleId.Recording, "AllocateMediaRecorder(...)", "io")
        val b = Action.EffectFailure(ModuleId.Recording, "AllocateMediaRecorder(...)", "io")
        val c = Action.EffectFailure(ModuleId.Pipeline, "AllocateMediaRecorder(...)", "io")

        assertEquals(a, b)
        assertNotEquals(a, c)    // different origin
    }

    // ────────────────────────────────────────────────────────────────
    // Exhaustiveness sanity — a reducer-shaped when over a module's
    // action sealed compiles without an `else` branch.
    // (Verified at compile time; the test only runs the branches.)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `exhaustive when over KeyboardInputAction covers all variants`() {
        // Compile-only sanity — if a new variant were added to
        // KeyboardInputAction this test would fail to compile.
        val actions = listOf<Action.KeyboardInputAction>(
            Action.KeyboardInputAction.Backspace,
            Action.KeyboardInputAction.EnterKey,
            Action.KeyboardInputAction.SpaceKey,
            Action.KeyboardInputAction.CopyToClipboard("hi"),
            Action.KeyboardInputAction.HostEditorAttached(HostEditorState()),
            Action.KeyboardInputAction.HostEditorDetached,
        )
        val labels = actions.map {
            when (it) {
                Action.KeyboardInputAction.Backspace -> "bs"
                Action.KeyboardInputAction.EnterKey -> "en"
                Action.KeyboardInputAction.SpaceKey -> "sp"
                is Action.KeyboardInputAction.CopyToClipboard -> "cp:" + it.text
                is Action.KeyboardInputAction.HostEditorAttached -> "ha"
                Action.KeyboardInputAction.HostEditorDetached -> "hd"
            }
        }
        assertEquals(listOf("bs", "en", "sp", "cp:hi", "ha", "hd"), labels)
    }

    @Test
    fun `exhaustive when over ViewMode enum covers all three modes`() {
        val labels = ViewMode.values().map {
            when (it) {
                ViewMode.KEYBOARD -> "k"
                ViewMode.WIDGET -> "w"
                ViewMode.HOVER -> "h"
            }
        }
        assertEquals(listOf("k", "w", "h"), labels)
    }
}
