package net.devemperor.dictate.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Smoke test proving that `:shared` compiles, its test source set runs, and the three
 * pinned libraries work together at runtime — not just on the compile classpath.
 */
class SharedBuildProbeTest {

    @Test
    fun encodeAndValidate_validInput_returnsJson() {
        assertEquals("""{"name":"dictate"}""", SharedBuildProbe.encodeAndValidate("dictate"))
    }

    @Test
    fun encodeAndValidate_violatingInput_returnsNull() {
        assertNull(SharedBuildProbe.encodeAndValidate(""))
    }

    @Test
    fun probeRequestUrl_normalizesUrl() {
        assertEquals("http://localhost:8080/v1/health", SharedBuildProbe.probeRequestUrl("http://localhost:8080/v1/health"))
    }
}
