package net.devemperor.dictate.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LanguageLabelResolver] using the [LanguageLabelResolver.initializeForTest]
 * seam — no Android Context required (Quality-Gate K-1, K-4).
 *
 * The init-throws contract (Defense-in-Depth W-1) is exercised in a separate
 * test class that runs before any other test touches the resolver, because
 * Kotlin `lateinit` cannot be un-set within a JVM. Within this class every
 * test re-initialises the resolver in [Before] to avoid state pollution from
 * a prior test ordering.
 */
class LanguageLabelResolverTest {

    @Before
    fun setUp() {
        LanguageLabelResolver.initializeForTest(
            codes = arrayOf("detect", "en", "de", "fr", "es"),
            labels = arrayOf("Auto-Detect", "English", "Deutsch", "Français", "Español")
        )
    }

    @Test
    fun `resolveLabel returns mapped label for known code`() {
        assertEquals("Deutsch", LanguageLabelResolver.resolveLabel("de"))
        assertEquals("English", LanguageLabelResolver.resolveLabel("en"))
        assertEquals("Auto-Detect", LanguageLabelResolver.resolveLabel("detect"))
    }

    @Test
    fun `resolveLabel falls back to code itself for unknown code`() {
        assertEquals("xyz", LanguageLabelResolver.resolveLabel("xyz"))
    }

    @Test
    fun `recordLabelFor falls back to label when no record-array supplied`() {
        // initializeForTest defaults recordLabels = labels.copyOf() unless overridden.
        assertEquals("English", LanguageLabelResolver.recordLabelFor("en"))
        assertEquals("Deutsch", LanguageLabelResolver.recordLabelFor("de"))
    }

    @Test
    fun `recordLabelFor returns its own array when explicitly supplied`() {
        LanguageLabelResolver.initializeForTest(
            codes = arrayOf("en", "de"),
            labels = arrayOf("English", "Deutsch"),
            recordLabels = arrayOf("EN", "DE")
        )
        assertEquals("EN", LanguageLabelResolver.recordLabelFor("en"))
        assertEquals("DE", LanguageLabelResolver.recordLabelFor("de"))
    }

    @Test
    fun `sortByLabel orders by display label case-insensitively`() {
        // Labels: detect=Auto-Detect, en=English, de=Deutsch, fr=Français, es=Español.
        // Lowercased alphabetic order: auto-detect, deutsch, english, español, français.
        // Note: with locale-default lowercase, "Español" -> "español", "English" -> "english",
        // "Français" -> "français". The exact order depends on String.compareTo against
        // those lowercased forms.
        val sorted = LanguageLabelResolver.sortByLabel(listOf("en", "de", "fr", "detect", "es"))
        assertEquals(listOf("detect", "de", "en", "es", "fr"), sorted)
    }

    @Test
    fun `sortByLabel returns empty list for empty input`() {
        assertEquals(emptyList<String>(), LanguageLabelResolver.sortByLabel(emptyList()))
    }

    @Test
    fun `othersThan returns the complement set, label-sorted`() {
        // Curated: en + de. Others: detect, fr, es. Label-sorted: detect (Auto-Detect),
        // es (Español), fr (Français).
        val others = LanguageLabelResolver.othersThan(listOf("en", "de"))
        assertEquals(listOf("detect", "es", "fr"), others)
    }

    @Test
    fun `othersThan with all curated returns empty`() {
        val all = listOf("detect", "en", "de", "fr", "es")
        assertEquals(emptyList<String>(), LanguageLabelResolver.othersThan(all))
    }

    @Test
    fun `othersThan with empty curated returns all codes label-sorted`() {
        val all = LanguageLabelResolver.othersThan(emptyList())
        assertEquals(5, all.size)
        // Verify it's label-sorted and contains every code exactly once.
        assertEquals(setOf("detect", "en", "de", "fr", "es"), all.toSet())
        // First entry must have alphabetically-smallest label.
        assertEquals("detect", all.first())
    }

    @Test
    fun `indexOfCode returns resource-array position for known code`() {
        // codes = ["detect", "en", "de", "fr", "es"].
        assertEquals(0, LanguageLabelResolver.indexOfCode("detect"))
        assertEquals(1, LanguageLabelResolver.indexOfCode("en"))
        assertEquals(4, LanguageLabelResolver.indexOfCode("es"))
    }

    @Test
    fun `indexOfCode returns -1 for unknown code`() {
        assertEquals(-1, LanguageLabelResolver.indexOfCode("xyz"))
    }

    @Test
    fun `allCodes returns the resource-order list`() {
        assertEquals(listOf("detect", "en", "de", "fr", "es"), LanguageLabelResolver.allCodes())
    }

    @Test
    fun `allowed returns the code set`() {
        assertEquals(setOf("detect", "en", "de", "fr", "es"), LanguageLabelResolver.allowed())
    }

    @Test
    fun `initializeForTest is idempotent on repeated identical calls`() {
        // Re-init with same data should leave reads stable.
        LanguageLabelResolver.initializeForTest(
            codes = arrayOf("detect", "en", "de", "fr", "es"),
            labels = arrayOf("Auto-Detect", "English", "Deutsch", "Français", "Español")
        )
        assertTrue(LanguageLabelResolver.isInitializedForTest())
        assertEquals("Deutsch", LanguageLabelResolver.resolveLabel("de"))
    }

    /**
     * Defense-in-Depth (Quality-Gate W-1): every accessor `check`s the init
     * state and throws [IllegalStateException] when the resolver was never
     * initialised. The plan §1.6 explicitly requires a test for this.
     *
     * Implementation: Kotlin `lateinit var` is backed by a plain Java field
     * that holds `null` until first assignment; reflection lets us reset it
     * to simulate the un-initialised state. The `try/finally` block restores
     * the field afterwards so subsequent tests in the class (and in any other
     * test class that runs in the same JVM) keep observing the
     * `@Before`-installed state.
     */
    @Test
    fun `accessors throw when initialize was not called (Defense-in-Depth W-1)`() {
        val resolverClass = LanguageLabelResolver::class.java
        // The lateinit field name on the JVM matches the Kotlin var name.
        val codesField = resolverClass.getDeclaredField("codes")
        codesField.isAccessible = true
        val saved = codesField.get(LanguageLabelResolver)
        codesField.set(LanguageLabelResolver, null)

        try {
            assertAccessorThrows("allCodes") { LanguageLabelResolver.allCodes() }
            assertAccessorThrows("allowed") { LanguageLabelResolver.allowed() }
            assertAccessorThrows("resolveLabel") { LanguageLabelResolver.resolveLabel("en") }
            assertAccessorThrows("recordLabelFor") { LanguageLabelResolver.recordLabelFor("en") }
            assertAccessorThrows("indexOfCode") { LanguageLabelResolver.indexOfCode("en") }
            assertAccessorThrows("sortByLabel") {
                LanguageLabelResolver.sortByLabel(listOf("en", "de"))
            }
            assertAccessorThrows("othersThan") {
                LanguageLabelResolver.othersThan(listOf("en"))
            }
        } finally {
            // Restore the saved state so subsequent tests are not poisoned.
            codesField.set(LanguageLabelResolver, saved)
        }
    }

    private inline fun assertAccessorThrows(name: String, block: () -> Unit) {
        try {
            block()
            fail("$name must throw IllegalStateException when un-initialised")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
