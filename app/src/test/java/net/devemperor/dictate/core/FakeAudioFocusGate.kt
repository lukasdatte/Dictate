package net.devemperor.dictate.core

/**
 * Counter-based fake of [AudioFocusGate] for unit tests.
 *
 * Each call to [request] increments [requestCount] and returns the value
 * stored in [requestResult] (default `true` ≙ `AUDIOFOCUS_REQUEST_GRANTED`).
 * Each call to [abandon] increments [abandonCount].
 *
 * Quality-Gate K-1 (handwritten fakes only — no Mockito) and K-4 (no Android
 * Context required) — both satisfied: this fake is a pure Kotlin class that
 * can run in a vanilla JVM unit-test (no Robolectric, no instrumentation).
 *
 * Usage:
 * ```
 * val gate = FakeAudioFocusGate()
 * controller.setAudioFocusRuntime(true)
 * assertEquals(1, gate.requestCount)
 * ```
 */
class FakeAudioFocusGate(
    /** Return value for [request]. Toggle to simulate `AUDIOFOCUS_REQUEST_FAILED`. */
    var requestResult: Boolean = true
) : AudioFocusGate {

    var requestCount: Int = 0
        private set

    var abandonCount: Int = 0
        private set

    override fun request(): Boolean {
        requestCount += 1
        return requestResult
    }

    override fun abandon() {
        abandonCount += 1
    }

    /** Resets both counters — handy when re-using the same fake across phases. */
    fun reset() {
        requestCount = 0
        abandonCount = 0
    }
}
