package net.devemperor.dictate.core

import android.content.SharedPreferences
import net.devemperor.dictate.database.entity.SessionType

/**
 * Tracks the current session and step IDs during a dictation/rewording flow.
 * Extracted from DictateInputMethodService to keep session state out of the God-Class.
 *
 * Thread safety: volatile fields for cross-thread visibility (main thread reads,
 * background threads write after API calls).
 */
class SessionTracker(private val sessionManager: SessionManager) {

    @Volatile var currentSessionId: String? = null
        private set
    @Volatile var currentStepId: String? = null
        private set
    @Volatile var currentTranscriptionId: String? = null
        private set
    @Volatile var lastSessionId: String? = null
        private set
    @Volatile var lastOutput: String? = null
        private set

    /**
     * Starts a new session. No-op if a session is already active (guard).
     */
    fun startSession(
        type: SessionType,
        targetApp: String?,
        language: String?,
        audioPath: String?,
        parentId: String?
    ) {
        if (currentSessionId != null) return // Guard: already active
        val sessionId = sessionManager.createSession(type, targetApp, language, audioPath, parentId)
        currentSessionId = sessionId
    }

    /**
     * Sets the current transcription ID (clears step — transcription is the latest artifact).
     */
    fun setTranscription(id: String) {
        currentTranscriptionId = id
        currentStepId = null
    }

    /**
     * Sets the current step ID (clears transcription — step is the latest artifact).
     */
    fun setStep(id: String) {
        currentStepId = id
        currentTranscriptionId = null
    }

    /**
     * Resets the session state. Caches lastSessionId for resend functionality.
     * Sets currentSessionId to null immediately (so startSession() guard works),
     * then loads lastOutput from DB (must be called on background thread).
     */
    fun resetSession() {
        val previousSessionId = currentSessionId
        // Clear current state FIRST — so startSession() guard doesn't block new sessions
        currentSessionId = null
        currentStepId = null
        currentTranscriptionId = null

        if (previousSessionId != null) {
            lastSessionId = previousSessionId
            lastOutput = sessionManager.getFinalOutput(previousSessionId)
        }
    }

    /**
     * Restores last session ID from SharedPreferences (no DB access — safe for main thread).
     */
    fun restoreLastSessionIdFromPrefs(sp: SharedPreferences) {
        lastSessionId = sp.getString(PREF_LAST_SESSION_ID, null)
    }

    /**
     * Loads lastOutput from DB for the restored lastSessionId.
     * Must be called on a background thread (DB access).
     */
    fun restoreLastOutputFromDb() {
        if (lastSessionId != null && lastOutput == null) {
            lastOutput = sessionManager.getFinalOutput(lastSessionId!!)
        }
    }

    /**
     * Persists last session ID to SharedPreferences (for keyboard restart resilience).
     */
    fun persistToPrefs(sp: SharedPreferences) {
        sp.edit().putString(PREF_LAST_SESSION_ID, lastSessionId).apply()
    }

    /**
     * Reuses the last session as current (for resend/retry flows).
     */
    fun reuseLastSession() {
        currentSessionId = lastSessionId
    }

    companion object {
        private const val PREF_LAST_SESSION_ID = "net.devemperor.dictate.last_session_id"
    }
}
