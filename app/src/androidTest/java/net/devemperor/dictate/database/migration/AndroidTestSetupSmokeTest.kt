package net.devemperor.dictate.database.migration

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bare-minimum instrumented test that verifies the androidTest source
 * set + runner wiring works (Spec 1 §11.7.0a step 4). Run via
 * `./gradlew connectedDebugAndroidTest` on a connected device or
 * emulator.
 *
 * **Local-only:** Block 3 does NOT add `connectedDebugAndroidTest` to
 * CI (Spec 1 §11.7.0a "CI-Integration"). Developers run instrumented
 * tests locally before merge; CI invocation arrives with a future
 * emulator-capable runner plan.
 *
 * If this smoke test ever fails, the rest of the instrumented suite
 * (notably [MigrationTo4Test]) cannot be trusted — fix the setup
 * before adding more cases.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTestSetupSmokeTest {
    @Test
    fun smoke() {
        assertTrue(true)
    }
}
