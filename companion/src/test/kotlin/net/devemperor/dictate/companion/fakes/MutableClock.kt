package net.devemperor.dictate.companion.fakes

import net.devemperor.dictate.companion.domain.port.ClockPort

/** A clock a test can move. Without it, "the pairing token expires after 120 s" is a 120-second test. */
class MutableClock(var now: Long = START) : ClockPort {

    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }

    companion object {
        /** An arbitrary but fixed epoch-millis so failures read the same on every run. */
        const val START = 1_700_000_000_000L
    }
}
