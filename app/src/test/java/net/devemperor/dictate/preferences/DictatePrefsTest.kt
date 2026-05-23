package net.devemperor.dictate.preferences

import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Pref] keys defined in [DictatePrefs].
 *
 * Currently covers Quality-Gate W11 for [Pref.SingleRowMode] (default value
 * + SP-Round-Trip via [FakeSharedPreferences]). Add further key-level
 * tests here when a new sealed-class entry needs default + round-trip
 * coverage.
 */
class DictatePrefsTest {

    @Test
    fun `Pref-SingleRowMode default is false`() {
        assertFalse(Pref.SingleRowMode.default)

        // Reading from an empty store returns the default.
        val sp = FakeSharedPreferences()
        assertFalse(sp.get(Pref.SingleRowMode))
    }

    @Test
    fun `Pref-SingleRowMode round-trips through FakeSharedPreferences`() {
        val sp = FakeSharedPreferences()

        // Write true → read true
        sp.edit().put(Pref.SingleRowMode, true).apply()
        assertTrue(sp.get(Pref.SingleRowMode))

        // Overwrite with false → read false
        sp.edit().put(Pref.SingleRowMode, false).apply()
        assertFalse(sp.get(Pref.SingleRowMode))

        // The underlying key is the documented one (guards against accidental rename).
        assertEquals("net.devemperor.dictate.single_row_mode", Pref.SingleRowMode.key)
    }
}
