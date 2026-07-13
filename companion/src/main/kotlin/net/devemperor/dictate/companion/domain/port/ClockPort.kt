package net.devemperor.dictate.companion.domain.port

/**
 * Wall-clock time, injected.
 *
 * Not a convenience: the pairing TTL, the `last_seen` touch and the sync watermark all read the
 * clock, and a test that has to *sleep* 120 seconds to see a token expire is a test nobody runs.
 */
interface ClockPort {
    fun nowMillis(): Long
}
