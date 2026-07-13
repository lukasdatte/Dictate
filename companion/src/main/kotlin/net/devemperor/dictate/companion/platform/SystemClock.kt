package net.devemperor.dictate.companion.platform

import net.devemperor.dictate.companion.domain.port.ClockPort

/** The real wall clock. Every test uses a `MutableClock` instead. */
object SystemClock : ClockPort {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
