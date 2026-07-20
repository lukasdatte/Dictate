package net.devemperor.dictate.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.devemperor.dictate.R
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.adapter.SharedPrefsProxyConfig
import net.devemperor.dictate.ai.model.ModelFetcher
import net.devemperor.dictate.ai.model.ParameterRegistry
import net.devemperor.dictate.config.ConfigEntityMapper
import net.devemperor.dictate.config.ConfigEntityMigration
import net.devemperor.dictate.config.ConfigRepository
import net.devemperor.dictate.config.ConfigSecrets
import net.devemperor.dictate.config.ConfigWireMapping.toAIProvider
import net.devemperor.dictate.config.ConfigWireMapping.toWire
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore
import net.devemperor.dictate.shared.config.ApiCredentialEntity
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderKind
import java.util.UUID

/**
 * Provider editor (C3, spec §10.1 + §10.2): providerType/label/baseUrl/credential of ONE
 * `ProviderConfigEntity`, plus its `ModelRefEntity` list. `kind=GATEWAY` is not offered (F31).
 *
 * Model selection is the §10.2 union: hardcoded/fetched suggestions (via [ModelFetcher], live when
 * a key is present) merged with existing refs, with free text for the providers without a listing
 * endpoint (Anthropic/Custom — spec §14 Gap 2). A saved key goes to the SecretStore under the
 * credential ref (F12) — never into a pref or a table.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §10.1, §10.2
 */
class ProviderEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROVIDER_ID = "net.devemperor.dictate.provider_edit_id"
    }

    private lateinit var sp: SharedPreferences
    private lateinit var db: DictateDatabase
    private lateinit var repo: ConfigRepository

    private lateinit var typeSpinner: Spinner
    private lateinit var labelEt: EditText
    private lateinit var baseUrlGroup: LinearLayout
    private lateinit var baseUrlEt: EditText
    private lateinit var apiKeyEt: EditText
    private lateinit var keyStatusTv: TextView
    private lateinit var modelList: LinearLayout

    /** All provider types offered in the editor (LOCAL kinds only — GATEWAY reserved, F31). */
    private val providerChoices = AIProvider.values().toList()

    private var providerId: String? = null
    private var existing: ProviderConfigEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_provider_edit)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_provider_edit)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.dictate_config_providers)
        }

        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE)
        db = DictateDatabase.getInstance(this)
        repo = ConfigRepository(db)

        typeSpinner = findViewById(R.id.provider_edit_type_spn)
        labelEt = findViewById(R.id.provider_edit_label_et)
        baseUrlGroup = findViewById(R.id.provider_edit_base_url_group)
        baseUrlEt = findViewById(R.id.provider_edit_base_url_et)
        apiKeyEt = findViewById(R.id.provider_edit_api_key_et)
        keyStatusTv = findViewById(R.id.provider_edit_key_status_tv)
        modelList = findViewById(R.id.provider_edit_model_list)

        typeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providerChoices.map { it.displayName },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        existing = providerId?.let { db.providerConfigDao().byId(it) }?.let { ConfigEntityMapper.toDto(it) }

        existing?.let { dto ->
            val index = providerChoices.indexOf(dto.providerType.toAIProvider())
            if (index >= 0) typeSpinner.setSelection(index)
            labelEt.setText(dto.label)
            baseUrlEt.setText(dto.baseUrl.orEmpty())
            keyStatusTv.text = if (dto.credentialRef != null) {
                getString(R.string.dictate_config_key_unchanged)
            } else {
                getString(R.string.dictate_config_key_missing)
            }
        }

        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                baseUrlGroup.visibility =
                    if (providerChoices[position] == AIProvider.CUSTOM) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        baseUrlGroup.visibility = if (selectedProvider() == AIProvider.CUSTOM) View.VISIBLE else View.GONE

        findViewById<MaterialButton>(R.id.provider_edit_add_model_btn).apply {
            // Models reference the provider row — a NEW provider must be saved first.
            isEnabled = existing != null
            setOnClickListener { showModelDialog(null) }
        }
        findViewById<MaterialButton>(R.id.provider_edit_save_btn).setOnClickListener { save() }

        rebuildModelList()
    }

    private fun selectedProvider(): AIProvider = providerChoices[typeSpinner.selectedItemPosition]

    // ── Save (§10.1) ──────────────────────────────────────────────────────────────────────────

    private fun save() {
        val provider = selectedProvider()
        val label = labelEt.text.toString().trim().ifEmpty { provider.displayName }
        val id = existing?.id ?: providerId ?: UUID.randomUUID().toString()

        // A newly entered key becomes a credential + SecretStore entry; empty keeps the old one.
        var credentialRef = existing?.credentialRef
        val newKey = apiKeyEt.text.toString().trim()
        if (newKey.isNotEmpty()) {
            val secretStore = AndroidKeystoreSecretStore.create(this)
            if (!secretStore.available) {
                Toast.makeText(this, R.string.dictate_config_export_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val credentialId = credentialRef ?: UUID.randomUUID().toString()
            val keyBytes = newKey.toByteArray(Charsets.UTF_8)
            repo.upsertCredential(
                ApiCredentialEntity(
                    id = credentialId,
                    providerType = provider.toWire(),
                    label = "$label Key",
                    keyFingerprint = ConfigEntityMigration.fingerprint(keyBytes),
                ),
            )
            secretStore.put(ConfigSecrets.credentialRef(credentialId), keyBytes)
            credentialRef = credentialId
        }

        val base = existing ?: ProviderConfigEntity(
            id = id,
            providerType = provider.toWire(),
            label = label,
        )
        repo.upsertProviderConfig(
            base.copy(
                providerType = provider.toWire(),
                kind = ProviderKind.LOCAL,
                label = label,
                baseUrl = baseUrlEt.text.toString().trim().ifEmpty { null }
                    .takeIf { provider == AIProvider.CUSTOM },
                credentialRef = credentialRef,
            ),
        )
        finish()
    }

    // ── Models (§10.2) ────────────────────────────────────────────────────────────────────────

    private fun rebuildModelList() {
        modelList.removeAllViews()
        val id = existing?.id ?: return
        db.modelRefDao().byProvider(id).forEach { row ->
            val dto = ConfigEntityMapper.toDto(row)
            val functionLabel = when (dto.function) {
                ModelFunction.TRANSCRIPTION -> getString(R.string.dictate_config_function_transcription)
                ModelFunction.COMPLETION -> getString(R.string.dictate_config_function_completion)
            }
            val row2 = LayoutInflater.from(this).inflate(R.layout.item_config_row, modelList, false)
            row2.findViewById<TextView>(R.id.item_config_row_title).text = dto.modelId
            row2.findViewById<TextView>(R.id.item_config_row_subtitle).text = functionLabel
            row2.setOnClickListener { showModelDialog(dto) }
            val actions = row2.findViewById<LinearLayout>(R.id.item_config_row_actions)
            val delete = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                setIconResource(R.drawable.ic_baseline_delete_24)
                contentDescription = getString(R.string.dictate_config_delete)
                setOnClickListener {
                    db.modelRefDao().deleteById(dto.id)
                    rebuildModelList()
                }
            }
            actions.addView(delete)
            modelList.addView(row2)
        }
    }

    /**
     * Add/edit dialog for one model ref: function spinner, free-text model id with suggestion
     * dropdown (union of hardcoded + live-fetched ids), parameter defaults editor.
     */
    private fun showModelDialog(existingModel: ModelRefEntity?) {
        val providerConfig = existing
        if (providerConfig == null) {
            // The provider row must exist before models can reference it.
            Toast.makeText(this, R.string.dictate_config_save, Toast.LENGTH_SHORT).show()
            return
        }
        val provider = providerConfig.providerType.toAIProvider()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val functionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ProviderEditActivity,
                android.R.layout.simple_spinner_item,
                listOf(
                    getString(R.string.dictate_config_function_transcription),
                    getString(R.string.dictate_config_function_completion),
                ),
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(if (existingModel?.function == ModelFunction.TRANSCRIPTION) 0 else 1)
            isEnabled = existingModel == null
        }
        container.addView(TextView(this).apply { setText(R.string.dictate_config_model_function) })
        container.addView(functionSpinner)

        val modelIdEt = AutoCompleteTextView(this).apply {
            setHint(R.string.dictate_config_model_id)
            threshold = 0
            setText(existingModel?.modelId.orEmpty())
        }
        container.addView(TextView(this).apply { setText(R.string.dictate_config_model_id) })
        container.addView(modelIdEt)

        val paramContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(paramContainer)

        val paramValues = existingModel?.parameterDefaults.orEmpty().toMutableMap()
        val paramEditor = ParameterMapEditor(this, paramContainer, paramValues)

        fun renderParams() {
            val isCompletion = functionSpinner.selectedItemPosition == 1
            if (isCompletion) {
                paramEditor.render(ParameterRegistry.getCompletionParameters(provider, modelIdEt.text.toString()))
            } else {
                paramContainer.removeAllViews()
            }
        }
        renderParams()
        functionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = renderParams()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // §10.2 union: hardcoded suggestions now, fetched ids merged in asynchronously.
        fun applySuggestions(ids: List<String>) {
            modelIdEt.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ids.distinct().sorted()),
            )
        }
        applySuggestions(ModelFetcher.getHardcodedModels(provider).map { it.id })
        fetchSuggestions(provider, providerConfig.credentialRef, functionSpinner) { fetched ->
            runOnUiThread {
                if (!isFinishing) {
                    applySuggestions(ModelFetcher.getHardcodedModels(provider).map { it.id } + fetched)
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dictate_config_add_model)
            .setView(container)
            .setPositiveButton(R.string.dictate_config_save) { _, _ ->
                val modelId = modelIdEt.text.toString().trim()
                if (modelId.isEmpty()) return@setPositiveButton
                val function = if (functionSpinner.selectedItemPosition == 0) {
                    ModelFunction.TRANSCRIPTION
                } else {
                    ModelFunction.COMPLETION
                }
                repo.upsertModelRef(
                    (
                        existingModel ?: ModelRefEntity(
                            id = UUID.randomUUID().toString(),
                            providerRef = providerConfig.id,
                            modelId = modelId,
                            function = function,
                        )
                        ).copy(modelId = modelId, parameterDefaults = paramValues.toMap()),
                )
                rebuildModelList()
            }
            .setNegativeButton(R.string.dictate_cancel, null)
            .show()
    }

    /** Live model listing (§10.2 (a)) — only for providers with an endpoint and a stored key. */
    private fun fetchSuggestions(
        provider: AIProvider,
        credentialRef: String?,
        functionSpinner: Spinner,
        onResult: (List<String>) -> Unit,
    ) {
        if (credentialRef == null) return
        val forTranscription = functionSpinner.selectedItemPosition == 0
        Thread {
            try {
                val secretStore = AndroidKeystoreSecretStore.create(this)
                val keyBytes = secretStore.get(ConfigSecrets.credentialRef(credentialRef)) ?: return@Thread
                val models = ModelFetcher.fetchModels(
                    provider,
                    String(keyBytes, Charsets.UTF_8),
                    SharedPrefsProxyConfig(sp),
                    forTranscription,
                )
                onResult(models.map { it.id })
            } catch (e: Exception) {
                // Suggestions are best-effort; free text always works (§14 Gap 2).
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
