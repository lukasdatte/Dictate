package net.devemperor.dictate.preferences.versioned

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [IntListCodec]. Mirrors [StringListCodecTest] so the codec
 * pair has symmetric coverage and future shape regressions surface in both.
 */
class IntListCodecTest {

    // ────────────────────────────── encode ───────────────────────────────────────────

    @Test
    fun `encode empty list returns empty JSONArray`() {
        val encoded = IntListCodec.encode(emptyList())
        assertTrue(encoded is JSONArray)
        assertEquals(0, (encoded as JSONArray).length())
    }

    @Test
    fun `encode list of ints returns JSONArray with same entries`() {
        val encoded = IntListCodec.encode(listOf(1, 2, 3))
        assertTrue(encoded is JSONArray)
        val arr = encoded as JSONArray
        assertEquals(3, arr.length())
        assertEquals(1, arr.getInt(0))
        assertEquals(2, arr.getInt(1))
        assertEquals(3, arr.getInt(2))
    }

    // ────────────────────────────── decode ───────────────────────────────────────────

    @Test
    fun `decode JSONArray returns matching list`() {
        val arr = JSONArray(listOf(10, 20, 30))
        val decoded = IntListCodec.decode(arr)
        assertEquals(listOf(10, 20, 30), decoded)
    }

    @Test
    fun `decode null returns empty list`() {
        assertEquals(emptyList<Int>(), IntListCodec.decode(null))
    }

    @Test
    fun `decode wrong type throws IllegalArgumentException`() {
        try {
            IntListCodec.decode("this is not a JSONArray")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention JSONArray, was: ${e.message}",
                e.message!!.contains("JSONArray")
            )
        }
    }

    @Test
    fun `decode JSONObject throws IllegalArgumentException`() {
        try {
            IntListCodec.decode(org.json.JSONObject().apply { put("foo", 1) })
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention JSONArray, was: ${e.message}",
                e.message!!.contains("JSONArray")
            )
        }
    }

    // ────────────────────────────── round-trip ───────────────────────────────────────

    @Test
    fun `encode then decode preserves values`() {
        val original = listOf(7, 42, 99)
        val encoded = IntListCodec.encode(original)
        val decoded = IntListCodec.decode(encoded)
        assertEquals(original, decoded)
    }
}
