package net.devemperor.dictate.core

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [EditorIdentity.isSame].
 *
 * Identity is defined by the pair (fieldId, packageName); both must match.
 * Either side being `null` returns `false` so the call site can fall back
 * to the captured-IC stage of the resend strategy.
 */
class EditorIdentityTest {

    @Test
    fun `same fieldId and packageName returns true`() {
        val a = editor(fieldId = 42, pkg = "com.example.app")
        val b = editor(fieldId = 42, pkg = "com.example.app")

        assertTrue(EditorIdentity.isSame(a, b))
    }

    @Test
    fun `different fieldId returns false`() {
        val a = editor(fieldId = 42, pkg = "com.example.app")
        val b = editor(fieldId = 43, pkg = "com.example.app")

        assertFalse(EditorIdentity.isSame(a, b))
    }

    @Test
    fun `different packageName returns false`() {
        val a = editor(fieldId = 42, pkg = "com.example.app")
        val b = editor(fieldId = 42, pkg = "com.other.app")

        assertFalse(EditorIdentity.isSame(a, b))
    }

    @Test
    fun `null left side returns false`() {
        val b = editor(fieldId = 42, pkg = "com.example.app")

        assertFalse(EditorIdentity.isSame(null, b))
    }

    @Test
    fun `null right side returns false`() {
        val a = editor(fieldId = 42, pkg = "com.example.app")

        assertFalse(EditorIdentity.isSame(a, null))
    }

    @Test
    fun `both null returns false`() {
        assertFalse(EditorIdentity.isSame(null, null))
    }

    @Test
    fun `fieldId zero with same packageName still compares by id`() {
        // fieldId == 0 is documented as a possible "unset" value on some
        // devices. The function does not special-case 0 — two zero-id
        // editors compare equal (which is a known limitation; the resend
        // strategy degrades gracefully to Stage 2 either way).
        val a = editor(fieldId = 0, pkg = "com.example.app")
        val b = editor(fieldId = 0, pkg = "com.example.app")

        assertTrue(EditorIdentity.isSame(a, b))
    }

    @Test
    fun `null vs non-null packageName returns false`() {
        val a = editor(fieldId = 42, pkg = null)
        val b = editor(fieldId = 42, pkg = "com.example.app")

        assertFalse(EditorIdentity.isSame(a, b))
    }

    // ── helpers ──

    private fun editor(fieldId: Int, pkg: String?): EditorInfo {
        val info = EditorInfo()
        info.fieldId = fieldId
        info.packageName = pkg
        return info
    }
}
