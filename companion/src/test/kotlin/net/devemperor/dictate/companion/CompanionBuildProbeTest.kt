package net.devemperor.dictate.companion

import org.junit.Assert.assertTrue
import org.junit.Test

/** Smoke test for the dependencies whose real consumers have not landed yet (see [CompanionBuildProbe]). */
class CompanionBuildProbeTest {

    @Test
    fun describe_touchesEveryNotYetConsumedDependency() {
        val description = CompanionBuildProbe.describe()

        assertTrue(description, description.contains("sqldelight=jdbc:sqlite:"))
        assertTrue(description, description.contains("zxing=QR_CODE"))
    }
}
