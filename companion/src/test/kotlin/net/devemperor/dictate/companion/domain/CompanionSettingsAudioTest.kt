package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.data.memory.InMemorySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The desktop audio settings — device selection + rolling-segment length (desktop-host.md §4.2/§4.3). */
class CompanionSettingsAudioTest {

    private val settings = CompanionSettings(InMemorySettings())

    @Test
    fun audioInputDevice_defaultsToNull_meaningSystemDefault() {
        assertNull(settings.audioInputDevice)
    }

    @Test
    fun audioInputDevice_roundTrips_andBlankClearsBackToDefault() {
        settings.audioInputDevice = "Microphone (USB Audio)"
        assertEquals("Microphone (USB Audio)", settings.audioInputDevice)

        settings.audioInputDevice = null
        assertNull("a cleared device means the system default again", settings.audioInputDevice)
    }

    @Test
    fun rollingSegment_defaultsTo30s() {
        assertEquals(CompanionSettings.DEFAULT_ROLLING_SEGMENT_SEC, settings.rollingSegmentSeconds)
    }

    @Test
    fun rollingSegment_roundTripsWithinBounds() {
        settings.rollingSegmentSeconds = 45
        assertEquals(45, settings.rollingSegmentSeconds)
    }

    @Test
    fun rollingSegment_outOfBoundsOrGarbageFallsBackToDefault() {
        settings.rollingSegmentSeconds = 1 // below MIN
        assertEquals(CompanionSettings.DEFAULT_ROLLING_SEGMENT_SEC, settings.rollingSegmentSeconds)

        settings.rollingSegmentSeconds = 100_000 // above MAX
        assertEquals(CompanionSettings.DEFAULT_ROLLING_SEGMENT_SEC, settings.rollingSegmentSeconds)
    }
}
