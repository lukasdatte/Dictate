package net.devemperor.dictate.core

import net.devemperor.dictate.state.BluetoothScoSubsystem

/**
 * Production [BluetoothScoSubsystem] backed by the existing
 * [BluetoothScoControl] surface (implemented by [BluetoothScoManager]
 * in production, by handwritten fakes in unit tests).
 *
 * **C8 — subsystem-adapter migration (Spec 1 §9.6):** the legacy
 * [BluetoothScoManager] is preserved (the table marks it "nie gelöscht —
 * wird hinter `BluetoothScoSubsystem`-Interface gewrapped"); this thin
 * adapter satisfies the orchestrator-side interface contract. Today the
 * IME-side recording flow still drives the SCO lifecycle directly; in
 * future blocks (B4-B6) when the orchestrator becomes the primary record-
 * button owner, the [AudioModule] effect handlers route through here.
 *
 * **Why depend on [BluetoothScoControl] (not [BluetoothScoManager])?**
 * Quality-Gate K-1 (handwritten fakes only) — the unit test injects a
 * pure-Kotlin fake [BluetoothScoControl] without instantiating a Context
 * or AudioManager.
 *
 * **Thread-safety:** the wrapped manager confines its registration /
 * startSco / release calls to the main looper internally via its own
 * [android.os.Handler]; callers from `runEffect` are already on
 * `Dispatchers.Main.immediate`.
 *
 * @see net.devemperor.dictate.state.BluetoothScoSubsystem
 * @see net.devemperor.dictate.core.BluetoothScoControl
 * @see net.devemperor.dictate.core.BluetoothScoManager
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §9.6
 */
class BluetoothScoSubsystemAdapter(
    private val manager: BluetoothScoControl,
) : BluetoothScoSubsystem {

    override fun start() {
        manager.startSco()
    }

    override fun stop() {
        manager.release()
    }
}
