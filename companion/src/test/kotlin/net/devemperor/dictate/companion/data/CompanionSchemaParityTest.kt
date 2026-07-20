package net.devemperor.dictate.companion.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.fakes.assertCheckFailure
import net.devemperor.dictate.companion.fakes.exec
import net.devemperor.dictate.companion.fakes.names
import net.devemperor.dictate.companion.domain.session.HostOrigin
import net.devemperor.dictate.companion.domain.session.MessageRole
import net.devemperor.dictate.companion.domain.session.ResponseFormatKind
import net.devemperor.dictate.companion.domain.session.SessionOrigin
import net.devemperor.dictate.companion.domain.session.SessionStatus
import net.devemperor.dictate.companion.domain.session.SessionType
import net.devemperor.dictate.companion.domain.session.StepStatus
import net.devemperor.dictate.companion.domain.session.StepType
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * The Double-Enum rule, pinned across the whole Room-parity session model (desktop-host.md §3.6).
 *
 * Successor to `OriginCheckConstraintParityTest`, which pinned the ablated `received_texts`. Two
 * families, per §3.6:
 *
 * **(a) CHECK acceptance / rejection.** For every finite-set column: each enum value the code can
 * produce **must** be insertable, and a value the enum cannot produce (`'TELEPATHY'`) **must** be
 * rejected. The second half is what makes the first worth anything — without it, a CHECK dropped by
 * accident would still let the first pass.
 *
 * **(b) Room parity.** Each companion enum's `.name` set **must** equal the hand-transcribed
 * [RoomParityReference] set (whose SSoT pointers name the Room origin). A drift on either side turns
 * this red with a diff — the cross-module guard that stands in until the real `:shared` SSoT exists.
 */
class CompanionSchemaParityTest {

    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private var chainIndex = 0
    private var seq = 0

    @Before
    fun setUp() {
        driver.exec("PRAGMA foreign_keys = ON")
        SchemaMigrator.migrate(driver)
        driver.exec(
            "INSERT INTO devices(device_id, name, secret_hash, paired_at) " +
                "VALUES ('test-device-0001', 'Pixel 8', 'hash', 1)",
        )
        // A parent session the FK-bearing children (steps, messages) can hang off.
        insertSession("base-session")
    }

    // ── Family (b): companion enum ↔ Room reference ─────────────────────────────────────────

    @Test
    fun companionEnums_matchTheRoomReference() {
        assertEquals(RoomParityReference.SESSION_TYPE, SessionType.entries.names())
        assertEquals(RoomParityReference.SESSION_STATUS, SessionStatus.entries.names())
        assertEquals(RoomParityReference.SESSION_ORIGIN, SessionOrigin.entries.names())
        assertEquals(RoomParityReference.AI_ERROR_TYPE, AIProviderException.ErrorType.entries.names())
        assertEquals(RoomParityReference.STEP_TYPE, StepType.entries.names())
        assertEquals(RoomParityReference.STEP_STATUS, StepStatus.entries.names())
        assertEquals(RoomParityReference.RESPONSE_FORMAT, ResponseFormatKind.entries.names())
        assertEquals(RoomParityReference.MESSAGE_ROLE, MessageRole.entries.names())
    }

    @Test
    fun insertionOutcomeWire_isASubsetOfTheDomainOutcome() {
        // FAILED exists in the domain enum and not on the wire (a failed insertion is a 503, not an
        // outcome). If the wire enum ever grew a value the domain does not know, dispatch_state could
        // not store it — the compile-time-adjacent check for that.
        InsertionOutcomeWire.entries.forEach { wire ->
            assertNotNull("no domain outcome for wire value ${wire.name}", InsertionOutcome.valueOf(wire.name))
        }
    }

    // ── Family (a): CHECK acceptance ────────────────────────────────────────────────────────

    @Test
    fun everySessionEnumValue_isAccepted() {
        SessionType.entries.forEach { insertSession("type-${it.name}", type = it.name) }
        SessionStatus.entries.forEach { insertSession("status-${it.name}", status = it.name) }
        SessionOrigin.entries.forEach { insertSession("origin-${it.name}", origin = it.name) }
        AIProviderException.ErrorType.entries.forEach { insertSession("err-${it.name}", lastErrorType = it.name) }
        insertSession("err-null", lastErrorType = null)
        HostOrigin.entries.forEach { insertSession("host-${it.name}", hostOrigin = it.name) }
    }

    @Test
    fun everyProcessingStepEnumValue_isAccepted() {
        StepType.entries.forEach { insertStep("step-${it.name}", stepType = it.name) }
        StepStatus.entries.forEach { insertStep("stst-${it.name}", status = it.name) }
        ResponseFormatKind.entries.forEach { insertStep("rf-${it.name}", responseFormat = it.name) }
        insertStep("rf-null", responseFormat = null)
    }

    @Test
    fun everyConversationRole_isAccepted() {
        MessageRole.entries.forEach { insertMessage("role-${it.name}", role = it.name) }
    }

    @Test
    fun everyDispatchOutcome_isAccepted() {
        InsertionOutcome.entries.forEach { insertDispatchState("out-${it.name}", lastOutcome = it.name) }
        insertDispatchState("out-null", lastOutcome = null)
    }

    // ── Family (a): CHECK rejection ─────────────────────────────────────────────────────────

    @Test
    fun sessionType_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertSession("x", type = "TELEPATHY") }

    @Test
    fun sessionStatus_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertSession("x", status = "TELEPATHY") }

    @Test
    fun sessionOrigin_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertSession("x", origin = "TELEPATHY") }

    @Test
    fun lastErrorType_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertSession("x", lastErrorType = "TELEPATHY") }

    @Test
    fun hostOrigin_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertSession("x", hostOrigin = "TELEPATHY") }

    @Test
    fun stepType_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertStep("x", stepType = "TELEPATHY") }

    @Test
    fun stepStatus_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertStep("x", status = "TELEPATHY") }

    @Test
    fun responseFormat_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertStep("x", responseFormat = "TELEPATHY") }

    @Test
    fun conversationRole_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertMessage("x", role = "TELEPATHY") }

    @Test
    fun dispatchOutcome_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertDispatchState("x", lastOutcome = "TELEPATHY") }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────

    private fun insertSession(
        id: String,
        type: String = "RECORDING",
        status: String = "COMPLETED",
        origin: String = "KEYBOARD",
        lastErrorType: String? = null,
        hostOrigin: String = "PHONE_SYNC",
    ) {
        val err = lastErrorType?.let { "'$it'" } ?: "NULL"
        driver.exec(
            "INSERT INTO sessions(id, type, created_at, status, origin, last_error_type, host_origin, " +
                "audio_file_paths, audio_duration_seconds) " +
                "VALUES ('$id', '$type', 1, '$status', '$origin', $err, '$hostOrigin', '[]', 0)",
        )
    }

    private fun insertStep(
        id: String,
        stepType: String = "REWORDING",
        status: String = "SUCCESS",
        responseFormat: String? = null,
    ) {
        val rf = responseFormat?.let { "'$it'" } ?: "NULL"
        driver.exec(
            "INSERT INTO processing_steps(id, session_id, step_type, chain_index, version, is_current, " +
                "input_text, model_used, provider, prompt_tokens, completion_tokens, duration_ms, status, " +
                "created_at, response_format) " +
                "VALUES ('$id', 'base-session', '$stepType', ${chainIndex++}, 1, 1, 'in', 'm', 'p', 0, 0, 0, " +
                "'$status', 1, $rf)",
        )
    }

    private fun insertMessage(id: String, role: String) {
        driver.exec(
            "INSERT INTO conversation_messages(id, session_id, turn_index, seq, role, content, created_at) " +
                "VALUES ('$id', 'base-session', 0, ${seq++}, '$role', 'c', 1)",
        )
    }

    /** A fresh parent session per row — dispatch_state's PK is the session id. */
    private fun insertDispatchState(suffix: String, lastOutcome: String?) {
        insertSession("disp-$suffix")
        val outcome = lastOutcome?.let { "'$it'" } ?: "NULL"
        driver.exec(
            "INSERT INTO dispatch_state(session_id, device_id, received_at, dispatched, last_outcome) " +
                "VALUES ('disp-$suffix', 'test-device-0001', 1, 0, $outcome)",
        )
    }
}
