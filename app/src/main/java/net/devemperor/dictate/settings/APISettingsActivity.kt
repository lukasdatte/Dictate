package net.devemperor.dictate.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.devemperor.dictate.R
import net.devemperor.dictate.config.CatalogExport
import net.devemperor.dictate.config.CatalogImport
import net.devemperor.dictate.config.ConfigEntityMapper
import net.devemperor.dictate.config.ProfileListMutations
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.rewording.PromptImportExport
import net.devemperor.dictate.shared.config.CatalogCodec
import net.devemperor.dictate.shared.config.CatalogFileV3
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProviderType

/**
 * Entity-based API settings hub (C3, spec §10): the provider list (§10.1) and the profile list
 * (§10.3) with activate / duplicate / move / delete, plus the v1/v2/v3 import dispatcher (§10.4)
 * and the v3 SAF export (§10.5). Replaces the old two-section pref-writing Activity; every write
 * goes through `ConfigRepository` (via the editors) — no migrated pref constant is referenced here
 * (AK8 grep test).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §10
 */
class APISettingsActivity : AppCompatActivity() {

    private lateinit var sp: SharedPreferences
    private lateinit var db: DictateDatabase
    private lateinit var providerList: LinearLayout
    private lateinit var profileList: LinearLayout

    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    /** Catalog chosen in the export dialog, written once the SAF target is picked. */
    private var pendingExport: CatalogFileV3? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_api_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_api_settings)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.dictate_api_settings)
        }

        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE)
        db = DictateDatabase.getInstance(this)
        providerList = findViewById(R.id.api_settings_provider_list)
        profileList = findViewById(R.id.api_settings_profile_list)

        findViewById<MaterialButton>(R.id.api_settings_add_provider_btn).setOnClickListener {
            startActivity(Intent(this, ProviderEditActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.api_settings_add_profile_btn).setOnClickListener {
            startActivity(Intent(this, ProfileEditActivity::class.java))
        }

        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFile(it) }
        }
        exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            uri?.let { writeExport(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildProviderList()
        rebuildProfileList()
    }

    // ── Providers (§10.1) ─────────────────────────────────────────────────────────────────────

    private fun rebuildProviderList() {
        providerList.removeAllViews()
        db.providerConfigDao().getAll().forEach { row ->
            val dto = ConfigEntityMapper.toDto(row)
            val keyBadge = if (dto.credentialRef != null) {
                getString(R.string.dictate_config_key_set)
            } else {
                getString(R.string.dictate_config_key_missing)
            }
            val peerBadge = if (dto.sourceRef != null) " · ${getString(R.string.dictate_config_origin_peer)}" else ""
            addRow(
                providerList,
                title = dto.label,
                subtitle = "${providerTypeName(dto.providerType)} · $keyBadge$peerBadge",
                onClick = {
                    startActivity(
                        Intent(this, ProviderEditActivity::class.java)
                            .putExtra(ProviderEditActivity.EXTRA_PROVIDER_ID, dto.id),
                    )
                },
            )
        }
    }

    private fun providerTypeName(type: ProviderType): String = type.name

    // ── Profiles (§10.3) ──────────────────────────────────────────────────────────────────────

    private fun orderedProfiles(): List<ProfileEntity> {
        val profiles = db.profileDao().getAll().map { ConfigEntityMapper.toDto(it, db.profileDao().promptsOf(it.id)) }
        return ProfileListMutations.ordered(profiles, sp.get(Pref.ProfileOrder))
    }

    private fun rebuildProfileList() {
        profileList.removeAllViews()
        val profiles = orderedProfiles()
        val activeId = sp.get(Pref.ActiveProfileId)
        val displayedIds = profiles.map { it.id }

        profiles.forEachIndexed { index, profile ->
            val activeSuffix = if (profile.id == activeId) " · ${getString(R.string.dictate_config_active)}" else ""
            val peerBadge = if (profile.sourceRef != null) " · ${getString(R.string.dictate_config_origin_peer)}" else ""
            val row = addRow(
                profileList,
                title = profile.name,
                subtitle = "${profile.orderedPrompts.size} prompts$activeSuffix$peerBadge",
                onClick = {
                    startActivity(
                        Intent(this, ProfileEditActivity::class.java)
                            .putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, profile.id),
                    )
                },
            )
            val actions = row.findViewById<LinearLayout>(R.id.item_config_row_actions)
            addAction(actions, R.drawable.ic_baseline_check_circle_outline_24, R.string.dictate_config_activate) {
                sp.edit().put(Pref.ActiveProfileId, profile.id).apply()
                rebuildProfileList()
            }
            addAction(actions, R.drawable.ic_baseline_keyboard_arrow_up_24, R.string.dictate_config_move_up) {
                sp.edit().put(Pref.ProfileOrder, ProfileListMutations.moved(displayedIds, index, index - 1)).apply()
                rebuildProfileList()
            }
            addAction(actions, R.drawable.ic_baseline_keyboard_arrow_down_24, R.string.dictate_config_move_down) {
                sp.edit().put(Pref.ProfileOrder, ProfileListMutations.moved(displayedIds, index, index + 1)).apply()
                rebuildProfileList()
            }
            addAction(actions, R.drawable.ic_baseline_content_copy_24, R.string.dictate_config_duplicate) {
                duplicateProfile(profile, index, displayedIds)
            }
            addAction(actions, R.drawable.ic_baseline_delete_24, R.string.dictate_config_delete) {
                confirmDeleteProfile(profile)
            }
        }
    }

    private fun duplicateProfile(source: ProfileEntity, index: Int, displayedIds: List<String>) {
        val copy = ProfileListMutations.copyOf(source, getString(R.string.dictate_prompts_copy_suffix))
        net.devemperor.dictate.config.ConfigRepository(db).upsertProfile(copy)
        // Insert the copy directly after its source in the display order.
        val newOrder = displayedIds.toMutableList().apply { add(index + 1, copy.id) }
        sp.edit().put(Pref.ProfileOrder, ProfileListMutations.serializeOrder(newOrder)).apply()
        rebuildProfileList()
    }

    private fun confirmDeleteProfile(profile: ProfileEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(profile.name)
            .setMessage(R.string.dictate_config_delete_profile_message)
            .setPositiveButton(R.string.dictate_yes) { _, _ ->
                db.profileDao().deleteById(profile.id)
                if (sp.get(Pref.ActiveProfileId) == profile.id) {
                    sp.edit().put(Pref.ActiveProfileId, "").apply()
                }
                rebuildProfileList()
                Toast.makeText(this, R.string.dictate_config_profile_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dictate_no, null)
            .show()
    }

    // ── Row helpers ───────────────────────────────────────────────────────────────────────────

    private fun addRow(container: LinearLayout, title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_config_row, container, false)
        row.findViewById<TextView>(R.id.item_config_row_title).text = title
        row.findViewById<TextView>(R.id.item_config_row_subtitle).text = subtitle
        row.setOnClickListener { onClick() }
        container.addView(row)
        return row
    }

    private fun addAction(actions: LinearLayout, iconRes: Int, contentDescriptionRes: Int, onClick: () -> Unit) {
        val button = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(iconRes)
            contentDescription = getString(contentDescriptionRes)
            layoutParams = LinearLayout.LayoutParams(
                resources.displayMetrics.density.times(40).toInt(),
                resources.displayMetrics.density.times(40).toInt(),
            )
            setOnClickListener { onClick() }
        }
        actions.addView(button)
    }

    // ── Import dispatcher (§10.4) ─────────────────────────────────────────────────────────────

    private fun importFile(uri: Uri) {
        val raw = try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (raw == null) {
            Toast.makeText(this, R.string.dictate_config_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        when (CatalogImport.detect(raw)) {
            CatalogImport.Format.V3 -> when (val result = CatalogImport.importV3(raw, db)) {
                is CatalogImport.Result.V3Imported -> {
                    rebuildProviderList()
                    rebuildProfileList()
                    Toast.makeText(this, R.string.dictate_config_import_success, Toast.LENGTH_SHORT).show()
                }
                is CatalogImport.Result.Malformed, is CatalogImport.Result.Invalid ->
                    Toast.makeText(this, R.string.dictate_config_import_failed, Toast.LENGTH_SHORT).show()
            }
            CatalogImport.Format.LEGACY_PROMPTS -> importLegacyPrompts(raw)
        }
    }

    /** v1/v2 prompt files go through the unchanged ADR-0024 parser, then the §8.5 backfill. */
    private fun importLegacyPrompts(raw: String) {
        val parsed = try {
            PromptImportExport.parse(raw)
        } catch (e: org.json.JSONException) {
            emptyList()
        }
        if (parsed.isEmpty()) {
            Toast.makeText(this, R.string.dictate_config_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        CatalogImport.appendLegacyPrompts(db, parsed)
        Toast.makeText(this, R.string.dictate_config_import_success, Toast.LENGTH_SHORT).show()
    }

    // ── v3 export (§10.5) ─────────────────────────────────────────────────────────────────────

    private fun showExportDialog() {
        val profiles = orderedProfiles()
        val labels = arrayOf(getString(R.string.dictate_config_export_everything)) +
            profiles.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dictate_config_export_title)
            .setItems(labels) { _, which ->
                pendingExport = if (which == 0) {
                    CatalogExport.fullCatalog(db)
                } else {
                    CatalogExport.profileCatalog(db, profiles[which - 1].id)
                }
                exportLauncher.launch(getString(R.string.dictate_config_export_filename))
            }
            .setNegativeButton(R.string.dictate_cancel, null)
            .show()
    }

    private fun writeExport(uri: Uri) {
        val catalog = pendingExport ?: return
        pendingExport = null
        try {
            val encoded = CatalogCodec.encode(catalog)
            contentResolver.openOutputStream(uri)?.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("no output stream")
            Toast.makeText(this, R.string.dictate_config_export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.dictate_config_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_api_settings, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        R.id.menu_api_settings_import -> {
            importLauncher.launch(arrayOf("application/json"))
            true
        }
        R.id.menu_api_settings_export -> {
            showExportDialog()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
