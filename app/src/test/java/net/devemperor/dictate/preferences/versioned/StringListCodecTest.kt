package net.devemperor.dictate.preferences.versioned

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [StringListCodec].
 *
 * Covers encode round-trip, JSONArray decode, null-as-empty, and the wrong-
 * type-throws contract that signals a hard shape error to the caller.
 */
class StringListCodecTest {

    // ────────────────────────────── encode ───────────────────────────────────────────

    @Test
    fun `encode empty list returns empty JSONArray`() {
        val encoded = StringListCodec.encode(emptyList())
        assertTrue(encoded is JSONArray)
        assertEquals(0, (encoded as JSONArray).length())
    }

    @Test
    fun `encode list of strings returns JSONArray with same entries`() {
        val encoded = StringListCodec.encode(listOf("en", "de", "fr"))
        assertTrue(encoded is JSONArray)
        val arr = encoded as JSONArray
        assertEquals(3, arr.length())
        assertEquals("en", arr.getString(0))
        assertEquals("de", arr.getString(1))
        assertEquals("fr", arr.getString(2))
    }

    // ────────────────────────────── decode ───────────────────────────────────────────

    @Test
    fun `decode JSONArray returns matching list`() {
        val arr = JSONArray(listOf("a", "b", "c"))
        val decoded = StringListCodec.decode(arr)
        assertEquals(listOf("a", "b", "c"), decoded)
    }

    @Test
    fun `decode null returns empty list`() {
        assertEquals(emptyList<String>(), StringListCodec.decode(null))
    }

    @Test
    fun `decode wrong type throws IllegalArgumentException`() {
        try {
            StringListCodec.decode("this is not a JSONArray")
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
            StringListCodec.decode(org.json.JSONObject().apply { put("foo", "bar") })
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
        val original = listOf("alpha", "beta", "gamma")
        val encoded = StringListCodec.encode(original)
        val decoded = StringListCodec.decode(encoded)
        assertEquals(original, decoded)
    }
}
