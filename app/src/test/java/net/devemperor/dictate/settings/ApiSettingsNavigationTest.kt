package net.devemperor.dictate.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.config.ConfigRepository
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.shared.config.ApiCredentialEntity
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * AK8 Robolectric smoke for the new entity-based settings navigation: the hub renders the
 * provider + profile lists from the DB and the add buttons navigate to the editors
 * (Provider → Modelle → Profile, spec §10 / §11 C3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiSettingsNavigationTest {

    @Test
    fun hub_rendersEntityListsAndNavigates() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = DictateDatabase.getInstance(context)
        val repo = ConfigRepository(db)
        repo.upsertProviderConfig(
            ProviderConfigEntity(id = "prov-1", providerType = ProviderType.OPENAI, label = "OpenAI"),
        )
        repo.upsertProfile(ProfileEntity(id = "profile-1", name = "Work"))

        val activity = Robolectric.buildActivity(APISettingsActivity::class.java).setup().get()

        val providerList = activity.findViewById<LinearLayout>(R.id.api_settings_provider_list)
        val profileList = activity.findViewById<LinearLayout>(R.id.api_settings_profile_list)
        assertEquals(1, providerList.childCount)
        assertEquals(1, profileList.childCount)

        // Add-profile navigates to the profile editor.
        activity.findViewById<MaterialButton>(R.id.api_settings_add_profile_btn).performClick()
        var next: Intent? = shadowOf(activity).nextStartedActivity
        assertNotNull(next)
        assertEquals(ProfileEditActivity::class.java.name, next!!.component!!.className)

        // Add-provider navigates to the provider editor.
        activity.findViewById<MaterialButton>(R.id.api_settings_add_provider_btn).performClick()
        next = shadowOf(activity).nextStartedActivity
        assertEquals(ProviderEditActivity::class.java.name, next!!.component!!.className)

        // Tapping the provider row opens the editor with the row's id.
        providerList.getChildAt(0).performClick()
        next = shadowOf(activity).nextStartedActivity
        assertEquals(ProviderEditActivity::class.java.name, next!!.component!!.className)
        assertEquals("prov-1", next.getStringExtra(ProviderEditActivity.EXTRA_PROVIDER_ID))
    }

    @Test
    fun providerRow_deleteRemovesProviderModelsAndOrphanedCredential() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = DictateDatabase.getInstance(context)
        val repo = ConfigRepository(db)
        repo.upsertCredential(
            ApiCredentialEntity(id = "cred-del", providerType = ProviderType.OPENAI, label = "Key", keyFingerprint = "ff"),
        )
        repo.upsertProviderConfig(
            ProviderConfigEntity(
                id = "prov-del", providerType = ProviderType.OPENAI, label = "DeleteMe",
                credentialRef = "cred-del",
            ),
        )
        repo.upsertModelRef(
            ModelRefEntity(
                id = "model-del", providerRef = "prov-del",
                modelId = "gpt-4o-mini", function = ModelFunction.COMPLETION,
            ),
        )

        val activity = Robolectric.buildActivity(APISettingsActivity::class.java).setup().get()
        val providerList = activity.findViewById<LinearLayout>(R.id.api_settings_provider_list)
        // The DB singleton is shared across the class's tests — address our row by its unique label.
        val row = (0 until providerList.childCount).map { providerList.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.item_config_row_title).text == "DeleteMe" }
        val actions = row.findViewById<LinearLayout>(R.id.item_config_row_actions)
        val deleteLabel = activity.getString(R.string.dictate_config_delete)
        val deleteBtn = (0 until actions.childCount)
            .map { actions.getChildAt(it) }
            .first { it.contentDescription == deleteLabel }
        deleteBtn.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(db.providerConfigDao().byId("prov-del"))
        assertNull(db.modelRefDao().byId("model-del"))
        assertNull("orphaned credential must fall with its only provider", db.apiCredentialDao().byId("cred-del"))
        val remaining = (0 until providerList.childCount).map {
            providerList.getChildAt(it).findViewById<TextView>(R.id.item_config_row_title).text.toString()
        }
        assertEquals(emptyList<String>(), remaining.filter { it == "DeleteMe" })
    }

    @Test
    fun editors_inflateStandalone() {
        // The two editors must at least inflate against the real layouts (fresh, empty DB).
        Robolectric.buildActivity(ProfileEditActivity::class.java).setup()
        Robolectric.buildActivity(ProviderEditActivity::class.java).setup()
    }
}
