package net.devemperor.dictate.state.insertion

/**
 * THE one routing point (§4.1): chooses **exactly one** sink per action — the paired PC in PC-mode,
 * the local IME otherwise — so a keyboard action never reaches both fields at once (Entscheidung 1).
 *
 * Replaces ~15 scattered `if (pcMode) … else …` weiche with a single Open/Closed seam: a third
 * target later (a second PC, a tablet) is one more sink, not another branch in every call-site.
 *
 * [pcModeActive] is read **per submit** (a lambda over `state.features.windowsAutoSendActive`), so
 * toggling PC-mode takes effect on the very next keystroke without rebuilding anything.
 *
 * The dictation-text terminal keeps its own `WindowsAutoSend.shouldDivertToPc` weiche with the
 * pending-part fallback (Entscheidung 4) and does NOT flow through here — this router carries only
 * the live keyboard actions, which have no pending semantics.
 *
 * @see docs/decisions ADR "Keyboard-Action Routing — an Exclusive Sink Router" (plan-scoped,
 *   pending promotion) — the full decision, alternatives and failure modes.
 */
class KeyboardActionRouter(
    private val local: KeyboardActionSink,
    private val pc: KeyboardActionSink,
    private val pcModeActive: () -> Boolean,
) : KeyboardActionSink {

    override fun submit(action: KeyboardAction): SubmitResult =
        if (pcModeActive()) pc.submit(action) else local.submit(action)
}
