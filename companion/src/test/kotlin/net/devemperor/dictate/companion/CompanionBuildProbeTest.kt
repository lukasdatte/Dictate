package net.devemperor.dictate.companion

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test proving `:companion` compiles against `:shared` and its own dependency set,
 * and that its test source set runs.
 */
class CompanionBuildProbeTest {

    @Test
    fun describe_touchesEveryPinnedDependency() {
        val description = CompanionBuildProbe.describe()

        assertTrue(description, description.contains("""shared={"name":"companion"}"""))
        assertTrue(description, description.contains("ktor=Application"))
        assertTrue(description, description.contains("sqldelight=jdbc:sqlite:"))
        assertTrue(description, description.contains("zxing=QR_CODE"))
    }
}
