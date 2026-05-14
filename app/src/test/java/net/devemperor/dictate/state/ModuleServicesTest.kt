package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.devemperor.dictate.testutil.FakeSharedPreferences
import net.devemperor.dictate.testutil.NoopAudioFileFactory
import net.devemperor.dictate.testutil.NoopRecordingHardware
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ModuleServices] — the DI container handed to every
 * [DictateModule.runEffect] call.
 *
 * **Scope of C4 verification.** The concrete subsystem implementations
 * land in Block 3 (Subsystem-Adapter-Migration). For C4, what we test
 * is:
 *
 * - The constructor surface matches Spec 1 §4.7 (every documented field
 *   is present + correctly typed).
 * - The `scope` + `emitAction` wiring (the two fields populated already
 *   in C4) round-trips correctly.
 * - The [fakeModuleServices] test fixture builds an instance with the
 *   no-op fakes — i.e. tests can construct a ModuleServices without
 *   pulling in Android.
 *
 * Behavioural tests of the subsystem interfaces (e.g.
 * "RecordingHardwareSubsystem.allocate produces an
 * `Action.RecordingAction.MediaRecorderReady`") live with the
 * production implementations in B3.
 */
class ModuleServicesTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `fakeModuleServices builds without arguments`() {
        val services: ModuleServices = fakeModuleServices()
        assertNotNull(services)
    }

    @Test
    fun `scope field reflects the supplied CoroutineScope`() {
        val services = fakeModuleServices(scope = scope)
        assertSame(scope, services.scope)
    }

    @Test
    fun `emitAction lambda is invoked with the supplied action`() {
        val captured = mutableListOf<Action>()
        val services = fakeModuleServices(
            emitAction = { action -> captured += action },
        )
        services.emitAction(Action.LanguageAction.RefreshFromPref)
        services.emitAction(Action.PipelineAction.ClearManualPasteFlag)

        assertEquals(
            listOf<Action>(
                Action.LanguageAction.RefreshFromPref,
                Action.PipelineAction.ClearManualPasteFlag,
            ),
            captured,
        )
    }

    @Test
    fun `default no-op subsystem fakes satisfy the runEffect signature`() {
        // We can't call runEffect directly here (modules don't exist yet),
        // but we can verify that every subsystem field is wired with a
        // non-null no-op fake — i.e. the contract surface is complete.
        val services = fakeModuleServices()

        assertSame(NoopRecordingHardware, services.recordingHardware)
        assertSame(NoopAudioFileFactory, services.audioFileFactory)
        assertNotNull(services.bluetoothSco)
        assertNotNull(services.audioFocus)
        assertNotNull(services.recordingTimer)
        assertNotNull(services.amplitudeStream)
        assertNotNull(services.borderGlow)
        assertNotNull(services.pipelineRunner)
        assertNotNull(services.sessionRepo)
        assertNotNull(services.notificationCoordinator)
        assertNotNull(services.toastSink)
        assertNotNull(services.sharedPrefs)

        // clipboard is nullable per Android docs — fake defaults to null.
        assertNull(services.clipboard)

        // inputConnectionProvider is a function — invoke returns null in the fake.
        assertNull(services.inputConnectionProvider.invoke())
    }

    @Test
    fun `fake SharedPreferences round-trip works for typical Pref reads`() {
        // Sanity check that the fake SharedPreferences is usable in tests
        // — a Phase-2 module test would read `services.sharedPrefs` to
        // verify pref-mirror behaviour.
        val prefs = FakeSharedPreferences().apply {
            edit().putBoolean("dictate.test.key", true).apply()
            edit().putString("dictate.test.name", "abc").apply()
        }
        val services = fakeModuleServices(sharedPrefs = prefs)

        assertTrue(services.sharedPrefs.getBoolean("dictate.test.key", false))
        assertEquals("abc", services.sharedPrefs.getString("dictate.test.name", null))
        assertFalse(services.sharedPrefs.getBoolean("missing.key", false))
    }
}
