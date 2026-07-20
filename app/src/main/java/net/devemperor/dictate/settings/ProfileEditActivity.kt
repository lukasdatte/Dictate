package net.devemperor.dictate.settings

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.config.ConfigEntityMapper
import net.devemperor.dictate.config.ConfigRepository
import net.devemperor.dictate.config.ConfigWireMapping.toAIProvider
import net.devemperor.dictate.ai.model.ParameterRegistry
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptType
import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.PromptSelectionMode
import java.util.UUID

/**
 * Profile editor (C3, spec §10.3): name, transcription/completion model refs, ordered
 * post-processing prompts (include + autoApply + order), style/system prompt selection, ambiguity
 * mode, and completion parameter overrides. One save writes the whole `ProfileEntity` through
 * `ConfigRepository` (hash + timestamp recompute, §5.3).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §10.3
 */
class ProfileEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "net.devemperor.dictate.profile_edit_id"
    }

    private lateinit var db: DictateDatabase
    private lateinit var repo: ConfigRepository

    private lateinit var nameEt: EditText
    private lateinit var transcriptionSpinner: Spinner
    private lateinit var completionSpinner: Spinner
    private lateinit var stylePromptSpinner: Spinner
    private lateinit var stylePromptCustomEt: EditText
    private lateinit var systemPromptSpinner: Spinner
    private lateinit var systemPromptCustomEt: EditText
    private lateinit var ambiguitySpinner: Spinner
    private lateinit var promptListView: LinearLayout
    private lateinit var parameterContainer: LinearLayout

    private var profile: ProfileEntity = ProfileEntity(id = UUID.randomUUID().toString(), name = "")

    private lateinit var transcriptionModels: List<ModelRefEntity>
    private lateinit var completionModels: List<ModelRefEntity>

    /** Working copy of the ordered prompt list (uuid + autoApply), mutated by the checkbox rows. */
    private val workingPrompts = mutableListOf<ProfilePromptRef>()
    private val parameterOverrides = mutableMapOf<String, String>()

    private val promptModes =
        listOf(PromptSelectionMode.NONE, PromptSelectionMode.PREDEFINED, PromptSelectionMode.CUSTOM)
    private val ambiguityModes = AmbiguityModeValue.values().toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile_edit)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_profile_edit)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.dictate_config_profiles)
        }

        db = DictateDatabase.getInstance(this)
        repo = ConfigRepository(db)

        nameEt = findViewById(R.id.profile_edit_name_et)
        transcriptionSpinner = findViewById(R.id.profile_edit_transcription_model_spn)
        completionSpinner = findViewById(R.id.profile_edit_completion_model_spn)
        stylePromptSpinner = findViewById(R.id.profile_edit_style_prompt_spn)
        stylePromptCustomEt = findViewById(R.id.profile_edit_style_prompt_custom_et)
        systemPromptSpinner = findViewById(R.id.profile_edit_system_prompt_spn)
        systemPromptCustomEt = findViewById(R.id.profile_edit_system_prompt_custom_et)
        ambiguitySpinner = findViewById(R.id.profile_edit_ambiguity_spn)
        promptListView = findViewById(R.id.profile_edit_prompt_list)
        parameterContainer = findViewById(R.id.profile_edit_parameter_container)

        intent.getStringExtra(EXTRA_PROFILE_ID)?.let { id ->
            db.profileDao().byId(id)?.let { row ->
                profile = ConfigEntityMapper.toDto(row, db.profileDao().promptsOf(id))
            }
        }
        workingPrompts += profile.orderedPrompts
        parameterOverrides += profile.parameterOverrides

        val allModels = db.modelRefDao().getAll().map { ConfigEntityMapper.toDto(it) }
        transcriptionModels = allModels.filter { it.function == ModelFunction.TRANSCRIPTION }
        completionModels = allModels.filter { it.function == ModelFunction.COMPLETION }

        nameEt.setText(profile.name)
        setupModelSpinner(transcriptionSpinner, transcriptionModels, profile.transcriptionModelRef)
        setupModelSpinner(completionSpinner, completionModels, profile.completionModelRef)
        setupPromptModeSpinner(stylePromptSpinner, stylePromptCustomEt, profile.stylePromptMode)
        stylePromptCustomEt.setText(profile.stylePromptCustomText)
        setupPromptModeSpinner(systemPromptSpinner, systemPromptCustomEt, profile.systemPromptMode)
        systemPromptCustomEt.setText(profile.systemPromptCustomText)
        setupAmbiguitySpinner()
        rebuildPromptList()
        renderParameterOverrides()

        completionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) =
                renderParameterOverrides()

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<MaterialButton>(R.id.profile_edit_save_btn).setOnClickListener { save() }
    }

    // ── Spinners ──────────────────────────────────────────────────────────────────────────────

    private fun modelLabel(model: ModelRefEntity): String {
        val provider = db.providerConfigDao().byId(model.providerRef)?.label
            ?: model.providerRef.take(8)
        return "$provider · ${model.modelId}"
    }

    private fun setupModelSpinner(spinner: Spinner, models: List<ModelRefEntity>, selectedId: String?) {
        val entries = listOf(getString(R.string.dictate_config_model_none)) + models.map { modelLabel(it) }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, entries).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val index = models.indexOfFirst { it.id == selectedId }
        spinner.setSelection(if (index >= 0) index + 1 else 0)
    }

    private fun selectedModelId(spinner: Spinner, models: List<ModelRefEntity>): String? {
        val position = spinner.selectedItemPosition
        return if (position <= 0) null else models[position - 1].id
    }

    private fun setupPromptModeSpinner(spinner: Spinner, customEt: EditText, selected: PromptSelectionMode) {
        val labels = listOf(
            getString(R.string.dictate_config_prompt_mode_none),
            getString(R.string.dictate_config_prompt_mode_predefined),
            getString(R.string.dictate_config_prompt_mode_custom),
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(promptModes.indexOf(selected).coerceAtLeast(0))
        customEt.isEnabled = selected == PromptSelectionMode.CUSTOM
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                customEt.isEnabled = promptModes[pos] == PromptSelectionMode.CUSTOM
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupAmbiguitySpinner() {
        ambiguitySpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, ambiguityModes.map { it.name }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        ambiguitySpinner.setSelection(ambiguityModes.indexOf(profile.ambiguityMode).coerceAtLeast(0))
    }

    // ── Prompt ordering (§10.3) ───────────────────────────────────────────────────────────────

    /**
     * Rows: included prompts first (in profile order, movable), then the remaining PROMPT-type
     * pills (selectable). TEXT pills are Android-local literals and never part of a profile.
     */
    private fun rebuildPromptList() {
        promptListView.removeAllViews()
        val prompts = db.promptDao().getAll()
            .filter { it.typeEnum == PromptType.PROMPT && it.uuid.isNotEmpty() }
        val byUuid = prompts.associateBy { it.uuid }

        // Drop refs whose prompt row disappeared.
        workingPrompts.retainAll { it.promptRef in byUuid }

        workingPrompts.forEachIndexed { index, ref ->
            val prompt = byUuid.getValue(ref.promptRef)
            addPromptRow(
                title = prompt.name.orEmpty(),
                included = true,
                autoApply = ref.autoApply,
                onIncludeChanged = { checked ->
                    if (!checked) {
                        workingPrompts.removeAt(index)
                        rebuildPromptList()
                    }
                },
                onAutoApplyChanged = { checked ->
                    workingPrompts[index] = ref.copy(autoApply = checked)
                },
                onMoveUp = {
                    if (index > 0) {
                        workingPrompts.add(index - 1, workingPrompts.removeAt(index))
                        rebuildPromptList()
                    }
                },
                onMoveDown = {
                    if (index < workingPrompts.size - 1) {
                        workingPrompts.add(index + 1, workingPrompts.removeAt(index))
                        rebuildPromptList()
                    }
                },
            )
        }

        val includedUuids = workingPrompts.map { it.promptRef }.toSet()
        prompts.filter { it.uuid !in includedUuids }.forEach { prompt ->
            addPromptRow(
                title = prompt.name.orEmpty(),
                included = false,
                autoApply = false,
                onIncludeChanged = { checked ->
                    if (checked) {
                        workingPrompts += ProfilePromptRef(prompt.uuid, prompt.autoApply)
                        rebuildPromptList()
                    }
                },
                onAutoApplyChanged = {},
                onMoveUp = {},
                onMoveDown = {},
            )
        }
    }

    private fun addPromptRow(
        title: String,
        included: Boolean,
        autoApply: Boolean,
        onIncludeChanged: (Boolean) -> Unit,
        onAutoApplyChanged: (Boolean) -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(
            CheckBox(this).apply {
                isChecked = included
                setOnCheckedChangeListener { _, checked -> onIncludeChanged(checked) }
            },
        )
        row.addView(
            TextView(this).apply {
                text = title
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (included) {
            row.addView(
                CheckBox(this).apply {
                    text = getString(R.string.dictate_config_auto_apply)
                    isChecked = autoApply
                    setOnCheckedChangeListener { _, checked -> onAutoApplyChanged(checked) }
                },
            )
            row.addView(iconButton(R.drawable.ic_baseline_keyboard_arrow_up_24, R.string.dictate_config_move_up, onMoveUp))
            row.addView(iconButton(R.drawable.ic_baseline_keyboard_arrow_down_24, R.string.dictate_config_move_down, onMoveDown))
        }
        promptListView.addView(row)
    }

    private fun iconButton(iconRes: Int, descriptionRes: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(iconRes)
            contentDescription = getString(descriptionRes)
            setOnClickListener { onClick() }
        }

    // ── Parameter overrides ───────────────────────────────────────────────────────────────────

    private fun renderParameterOverrides() {
        val modelId = selectedModelId(completionSpinner, completionModels)
        val model = completionModels.firstOrNull { it.id == modelId }
        if (model == null) {
            parameterContainer.removeAllViews()
            return
        }
        val provider = db.providerConfigDao().byId(model.providerRef)
            ?.let { ConfigEntityMapper.toDto(it).providerType.toAIProvider() }
            ?: return
        ParameterMapEditor(this, parameterContainer, parameterOverrides)
            .render(ParameterRegistry.getCompletionParameters(provider, model.modelId))
    }

    // ── Save ──────────────────────────────────────────────────────────────────────────────────

    private fun save() {
        val name = nameEt.text.toString().trim().ifEmpty { getString(R.string.dictate_config_profile_name) }
        repo.upsertProfile(
            profile.copy(
                name = name,
                transcriptionModelRef = selectedModelId(transcriptionSpinner, transcriptionModels),
                completionModelRef = selectedModelId(completionSpinner, completionModels),
                orderedPrompts = workingPrompts.toList(),
                stylePromptMode = promptModes[stylePromptSpinner.selectedItemPosition],
                stylePromptCustomText = stylePromptCustomEt.text.toString(),
                systemPromptMode = promptModes[systemPromptSpinner.selectedItemPosition],
                systemPromptCustomText = systemPromptCustomEt.text.toString(),
                ambiguityMode = ambiguityModes[ambiguitySpinner.selectedItemPosition],
                parameterOverrides = parameterOverrides.toMap(),
            ),
        )
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
