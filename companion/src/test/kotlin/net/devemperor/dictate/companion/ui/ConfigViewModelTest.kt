package net.devemperor.dictate.companion.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.ui.config.ActiveProfileStore
import net.devemperor.dictate.companion.ui.config.ConfigViewModel
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The config-management view model (desktop-host.md §9.2), without Compose.
 *
 * `Dispatchers.Unconfined` runs each `launch` inline, so state is readable on the next line.
 */
class ConfigViewModelTest {

    private val database = CompanionDatabase.inMemory()
    private var clock = 1L
    private val repo = CompanionConfigRepository(database, now = { clock })
    private var active: String? = null
    private val store = object : ActiveProfileStore {
        override fun get(): String? = active
        override fun set(id: String?) { active = id }
    }
    private val ids = AtomicInteger(0)
    private val viewModel = ConfigViewModel(repo, store, CoroutineScope(Dispatchers.Unconfined), newId = { "id-${ids.incrementAndGet()}" })

    @Test
    fun createProfile_showsUpInState() {
        viewModel.createProfile("German")
        assertEquals(listOf("German"), viewModel.state.value.profiles.map { it.name })
    }

    @Test
    fun setActiveProfile_updatesThePointer_andState() {
        viewModel.createProfile("A")
        val id = viewModel.state.value.profiles.single().id
        viewModel.setActiveProfile(id)
        assertEquals(id, active)
        assertEquals(id, viewModel.state.value.activeProfileId)
    }

    @Test
    fun deletingTheActiveProfile_clearsThePointer() {
        viewModel.createProfile("A")
        val id = viewModel.state.value.profiles.single().id
        viewModel.setActiveProfile(id)
        viewModel.deleteProfile(id)
        assertNull("the dangling active pointer is cleared", active)
        assertTrue(viewModel.state.value.profiles.isEmpty())
    }

    @Test
    fun duplicateProfile_makesACopyWithoutProvenance() {
        viewModel.createProfile("Orig")
        val id = viewModel.state.value.profiles.single().id
        viewModel.duplicateProfile(id)
        val names = viewModel.state.value.profiles.map { it.name }.toSet()
        assertEquals(setOf("Orig", "Orig (copy)"), names)
        assertTrue(viewModel.state.value.profiles.all { it.sourceRef == null })
    }

    @Test
    fun createProviderThenModel_linksThemAndAppearsInState() {
        viewModel.createProvider(ProviderType.OPENAI, "OpenAI")
        val providerId = viewModel.state.value.providers.single().id
        viewModel.createModel(providerId, "gpt-4o-mini", ModelFunction.COMPLETION)
        val model = viewModel.state.value.models.single()
        assertEquals("gpt-4o-mini", model.modelId)
        assertEquals(providerId, model.providerRef)
    }

    @Test
    fun createPrompt_requiresText() {
        viewModel.createPrompt("Empty", "   ")
        assertTrue("a blank prompt text is rejected", viewModel.state.value.prompts.isEmpty())
        viewModel.createPrompt("Formal", "Rewrite formally")
        assertEquals(listOf("Formal"), viewModel.state.value.prompts.map { it.name })
    }
}
