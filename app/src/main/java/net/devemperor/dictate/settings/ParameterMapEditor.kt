package net.devemperor.dictate.settings

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import net.devemperor.dictate.R
import net.devemperor.dictate.SimpleTextWatcher
import net.devemperor.dictate.ai.model.ParameterDef
import net.devemperor.dictate.ai.model.ParameterType
import net.devemperor.dictate.config.ConfigEntityMapper
import java.util.Locale

/**
 * Renders a [ParameterDef] list into a container and edits a **canonical-string parameter map**
 * (`ModelRefEntity.parameterDefaults` / `ProfileEntity.parameterOverrides`, spec §10.2) — the
 * entity-model successor of the old pref-backed parameter UI in `APISettingsActivity`. An unset /
 * default control removes its key from the map; set values are stored in the canonical string form
 * (`0.7`, `4096`, `low`) that `ProfileResolver` and the content hash consume.
 *
 * Keeps the old widget mapping (FLOAT_RANGE→SeekBar, INT_RANGE→EditText, ENUM→Spinner) and the
 * `mutuallyExclusiveWith` disable logic.
 */
class ParameterMapEditor(
    private val context: Context,
    private val container: LinearLayout,
    private val values: MutableMap<String, String>,
) {

    private val paramViews = mutableMapOf<String, Array<View>>()

    fun render(defs: List<ParameterDef>) {
        container.removeAllViews()
        paramViews.clear()
        defs.forEach { def ->
            when (def.type) {
                ParameterType.FLOAT_RANGE -> addFloatField(def)
                ParameterType.INT_RANGE -> addIntField(def)
                ParameterType.ENUM -> addEnumField(def)
            }
        }
        defs.forEach { def ->
            if (def.mutuallyExclusiveWith != null) {
                applyExclusion(def, values.containsKey(def.name))
            }
        }
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(context.getColor(R.color.dictate_blue))
        setPadding(0, 32, 0, 8)
    }

    private fun addFloatField(def: ParameterDef) {
        val displayName = formatParamName(def.name)
        val min = def.min?.toFloat() ?: 0f
        val max = def.max?.toFloat() ?: 2f
        val steps = Math.round((max - min) * 10)
        val saved = values[def.name]?.toFloatOrNull()

        val labelView = label(floatLabel(displayName, saved))
        container.addView(labelView)

        val seekBar = SeekBar(context).apply {
            this.max = steps + 1 // position 0 = server default
            progress = if (saved == null) 0 else Math.round((saved - min) * 10) + 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (progress == 0) {
                        values.remove(def.name)
                        labelView.text = floatLabel(displayName, null)
                    } else {
                        val value = min + (progress - 1) / 10f
                        values[def.name] = ConfigEntityMapper.canonicalDecimal(value)
                        labelView.text = floatLabel(displayName, value)
                    }
                    applyExclusion(def, progress != 0)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        container.addView(seekBar)
        paramViews[def.name] = arrayOf(seekBar, labelView)
    }

    private fun addIntField(def: ParameterDef) {
        val displayName = formatParamName(def.name)
        val rangeHint = if (def.min != null && def.max != null) {
            String.format(Locale.US, " (%d-%d)", def.min!!.toInt(), def.max!!.toInt())
        } else {
            ""
        }
        val labelView = label(displayName + rangeHint)
        container.addView(labelView)

        val editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Default"
            values[def.name]?.toIntOrNull()?.let { setText(it.toString()) }
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(editable: Editable) {
                    val parsed = editable.toString().trim().toIntOrNull()
                    if (parsed == null || parsed <= 0) {
                        values.remove(def.name)
                    } else {
                        values[def.name] = parsed.toString()
                    }
                    applyExclusion(def, values.containsKey(def.name))
                }
            })
        }
        container.addView(editText)
        paramViews[def.name] = arrayOf(editText, labelView)
    }

    private fun addEnumField(def: ParameterDef) {
        val enumValues = def.enumValues ?: return
        if (enumValues.isEmpty()) return
        val labelView = label(formatParamName(def.name))
        container.addView(labelView)

        val entries = listOf("Default") + enumValues
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, entries).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val saved = values[def.name]
            setSelection(if (saved != null && saved in enumValues) enumValues.indexOf(saved) + 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) values.remove(def.name) else values[def.name] = enumValues[position - 1]
                    applyExclusion(def, position != 0)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        container.addView(spinner)
        paramViews[def.name] = arrayOf(spinner, labelView)
    }

    /** Disables the mutually exclusive parameter's controls while [def] carries a value. */
    private fun applyExclusion(def: ParameterDef, isActive: Boolean) {
        val other = def.mutuallyExclusiveWith ?: return
        paramViews[other]?.forEach { view ->
            view.isEnabled = !isActive
            view.alpha = if (isActive) 0.4f else 1f
        }
    }

    private fun floatLabel(displayName: String, value: Float?): String =
        if (value == null) "$displayName: Default" else String.format(Locale.US, "%s: %.1f", displayName, value)

    private fun formatParamName(name: String): String =
        name.split('_').joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
}
