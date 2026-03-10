package net.devemperor.dictate.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.devemperor.dictate.R;
import net.devemperor.dictate.SimpleTextWatcher;
import net.devemperor.dictate.ai.AIProvider;
import net.devemperor.dictate.ai.model.ModelFetcher;
import net.devemperor.dictate.ai.model.ModelInfo;
import net.devemperor.dictate.ai.model.ParameterDef;
import net.devemperor.dictate.ai.model.ParameterRegistry;
import net.devemperor.dictate.ai.model.ParameterType;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.Pref;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class APISettingsActivity extends AppCompatActivity {

    private Spinner transcriptionProviderSpn;
    private Spinner transcriptionModelSpn;
    private EditText transcriptionAPIKeyEt;
    private EditText transcriptionCustomHostEt;
    private EditText transcriptionCustomModelEt;
    private LinearLayout transcriptionCustomFieldsWrapper;
    private LinearLayout transcriptionModelGroup;
    private ProgressBar transcriptionModelProgress;

    private Spinner rewordingProviderSpn;
    private Spinner rewordingModelSpn;
    private EditText rewordingAPIKeyEt;
    private EditText rewordingCustomHostEt;
    private EditText rewordingCustomModelEt;
    private LinearLayout rewordingCustomFieldsWrapper;
    private LinearLayout rewordingModelGroup;
    private ProgressBar rewordingModelProgress;
    private LinearLayout rewordingParameterContainer;

    private List<AIProvider> transcriptionProviders;
    private List<AIProvider> rewordingProviders;

    private boolean ignoreTextChange = false;
    private boolean ignoreSpinnerChange = false;
    private SharedPreferences sp;
    private int transcriptionModelFetchSeq = 0;
    private int rewordingModelFetchSeq = 0;
    private String currentRewordingModelId = "";

    /**
     * Maps (provider, parameterName) to Pref keys.
     * Mirrors AIOrchestrator.PARAMETER_PREFS but for the Settings UI.
     * Parameters without a Pref entry are not shown (extendable later).
     */
    private static final Map<AIProvider, Map<String, Pref<?>>> PARAM_PREFS = new HashMap<>();
    static {
        Map<String, Pref<?>> openai = new HashMap<>();
        openai.put("temperature", Pref.TemperatureOpenAI.INSTANCE);
        openai.put("max_completion_tokens", Pref.MaxTokensOpenAI.INSTANCE);
        openai.put("reasoning_effort", Pref.ReasoningEffortOpenAI.INSTANCE);
        PARAM_PREFS.put(AIProvider.OPENAI, openai);

        Map<String, Pref<?>> groq = new HashMap<>();
        groq.put("temperature", Pref.TemperatureGroq.INSTANCE);
        groq.put("max_completion_tokens", Pref.MaxTokensGroq.INSTANCE);
        PARAM_PREFS.put(AIProvider.GROQ, groq);

        Map<String, Pref<?>> anthropic = new HashMap<>();
        anthropic.put("temperature", Pref.TemperatureAnthropic.INSTANCE);
        anthropic.put("max_tokens", Pref.MaxTokensAnthropic.INSTANCE);
        PARAM_PREFS.put(AIProvider.ANTHROPIC, anthropic);

        Map<String, Pref<?>> openrouter = new HashMap<>();
        openrouter.put("temperature", Pref.TemperatureOpenRouter.INSTANCE);
        openrouter.put("max_completion_tokens", Pref.MaxTokensOpenRouter.INSTANCE);
        PARAM_PREFS.put(AIProvider.OPENROUTER, openrouter);

        // Custom uses OpenAI defaults (same as AIOrchestrator)
        PARAM_PREFS.put(AIProvider.CUSTOM, openai);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_api_settings);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_api_settings), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.dictate_api_settings);
        }

        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);

        // Bind views
        transcriptionProviderSpn = findViewById(R.id.api_settings_transcription_provider_spn);
        transcriptionModelSpn = findViewById(R.id.api_settings_transcription_model_spn);
        transcriptionAPIKeyEt = findViewById(R.id.api_settings_transcription_api_key_et);
        transcriptionCustomHostEt = findViewById(R.id.api_settings_transcription_custom_host_et);
        transcriptionCustomModelEt = findViewById(R.id.api_settings_transcription_custom_model_et);
        transcriptionCustomFieldsWrapper = findViewById(R.id.api_settings_transcription_custom_fields_wrapper);
        transcriptionModelGroup = findViewById(R.id.transcription_model_group);
        transcriptionModelProgress = findViewById(R.id.api_settings_transcription_model_progress);

        rewordingProviderSpn = findViewById(R.id.api_settings_rewording_provider_spn);
        rewordingModelSpn = findViewById(R.id.api_settings_rewording_model_spn);
        rewordingAPIKeyEt = findViewById(R.id.api_settings_rewording_api_key_et);
        rewordingCustomHostEt = findViewById(R.id.api_settings_rewording_custom_host_et);
        rewordingCustomModelEt = findViewById(R.id.api_settings_rewording_custom_model_et);
        rewordingCustomFieldsWrapper = findViewById(R.id.api_settings_rewording_custom_fields_wrapper);
        rewordingModelGroup = findViewById(R.id.rewording_model_group);
        rewordingModelProgress = findViewById(R.id.api_settings_rewording_model_progress);
        rewordingParameterContainer = findViewById(R.id.api_settings_rewording_parameter_container);

        // Build provider lists from AIProvider enum
        transcriptionProviders = AIProvider.withTranscription();
        rewordingProviders = AIProvider.withCompletion();

        setupTranscriptionSection();
        setupRewordingSection();
    }

    // ─── Transcription Section ───

    private void setupTranscriptionSection() {
        AIProvider currentProvider = AIProvider.fromPersistKey(
                DictatePrefsKt.get(sp, Pref.TranscriptionProvider.INSTANCE));

        // Provider spinner
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                transcriptionProviders.stream().map(AIProvider::getDisplayName).collect(Collectors.toList()));
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        transcriptionProviderSpn.setAdapter(providerAdapter);

        int providerIndex = transcriptionProviders.indexOf(currentProvider);
        if (providerIndex < 0) providerIndex = 0;
        transcriptionProviderSpn.setSelection(providerIndex);

        transcriptionProviderSpn.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (ignoreSpinnerChange) return;
                AIProvider selected = transcriptionProviders.get(position);
                sp.edit().putString(Pref.TranscriptionProvider.INSTANCE.getKey(), selected.name()).apply();
                updateTranscriptionUI(selected);
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });

        // API Key watcher
        transcriptionAPIKeyEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (ignoreTextChange) return;
                AIProvider provider = getSelectedTranscriptionProvider();
                Pref<String> keyPref = getTranscriptionApiKeyPref(provider);
                sp.edit().putString(keyPref.getKey(), editable.toString()).apply();
            }
        });

        // Custom host/model watchers
        transcriptionCustomHostEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (ignoreTextChange) return;
                sp.edit().putString(Pref.TranscriptionCustomHost.INSTANCE.getKey(), editable.toString()).apply();
            }
        });
        transcriptionCustomModelEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (ignoreTextChange) return;
                sp.edit().putString(Pref.TranscriptionCustomModel.INSTANCE.getKey(), editable.toString()).apply();
            }
        });

        updateTranscriptionUI(currentProvider);
    }

    private void updateTranscriptionUI(AIProvider provider) {
        ignoreTextChange = true;

        boolean isCustom = provider == AIProvider.CUSTOM;
        transcriptionCustomFieldsWrapper.setVisibility(isCustom ? View.VISIBLE : View.GONE);

        // API Key
        String apiKey = DictatePrefsKt.get(sp, getTranscriptionApiKeyPref(provider));
        transcriptionAPIKeyEt.setText(apiKey);

        // Custom fields
        if (isCustom) {
            transcriptionCustomHostEt.setText(DictatePrefsKt.get(sp, Pref.TranscriptionCustomHost.INSTANCE));
            transcriptionCustomModelEt.setText(DictatePrefsKt.get(sp, Pref.TranscriptionCustomModel.INSTANCE));
            transcriptionModelGroup.setVisibility(View.GONE);
        } else {
            transcriptionModelGroup.setVisibility(View.VISIBLE);
            fetchTranscriptionModels(provider, apiKey);
        }

        ignoreTextChange = false;
    }

    private void fetchTranscriptionModels(AIProvider provider, String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            transcriptionModelSpn.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{}));
            return;
        }

        int seq = ++transcriptionModelFetchSeq;
        if (transcriptionModelProgress != null) transcriptionModelProgress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                List<ModelInfo> models = ModelFetcher.fetchModels(provider, apiKey, sp, true);
                if (seq != transcriptionModelFetchSeq || isFinishing()) return;
                List<String> modelIds = models.stream().map(ModelInfo::getId).collect(Collectors.toList());

                runOnUiThread(() -> {
                    if (seq != transcriptionModelFetchSeq || isFinishing()) return;
                    if (transcriptionModelProgress != null) transcriptionModelProgress.setVisibility(View.GONE);

                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelIds);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    transcriptionModelSpn.setAdapter(adapter);

                    // Restore saved selection
                    String savedModel = getSavedTranscriptionModel(provider);
                    int pos = modelIds.indexOf(savedModel);
                    if (pos >= 0) transcriptionModelSpn.setSelection(pos);

                    transcriptionModelSpn.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                            String modelId = modelIds.get(position);
                            saveTranscriptionModel(getSelectedTranscriptionProvider(), modelId);
                        }
                        @Override
                        public void onNothingSelected(AdapterView<?> adapterView) {}
                    });
                });
            } catch (Exception e) {
                if (seq != transcriptionModelFetchSeq || isFinishing()) return;
                runOnUiThread(() -> {
                    if (transcriptionModelProgress != null) transcriptionModelProgress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.dictate_model_fetch_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ─── Rewording Section ───

    private void setupRewordingSection() {
        AIProvider currentProvider = AIProvider.fromPersistKey(
                DictatePrefsKt.get(sp, Pref.RewordingProvider.INSTANCE));

        // Provider spinner
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                rewordingProviders.stream().map(AIProvider::getDisplayName).collect(Collectors.toList()));
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rewordingProviderSpn.setAdapter(providerAdapter);

        int providerIndex = rewordingProviders.indexOf(currentProvider);
        if (providerIndex < 0) providerIndex = 0;
        rewordingProviderSpn.setSelection(providerIndex);

        rewordingProviderSpn.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (ignoreSpinnerChange) return;
                AIProvider selected = rewordingProviders.get(position);
                sp.edit().putString(Pref.RewordingProvider.INSTANCE.getKey(), selected.name()).apply();
                updateRewordingUI(selected);
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });

        // API Key watcher
        rewordingAPIKeyEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (ignoreTextChange) return;
                AIProvider provider = getSelectedRewordingProvider();
                Pref<String> keyPref = getRewordingApiKeyPref(provider);
                sp.edit().putString(keyPref.getKey(), editable.toString()).apply();
            }
        });

        // Custom host/model watchers
        rewordingCustomHostEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (ignoreTextChange) return;
                sp.edit().putString(Pref.RewordingCustomHost.INSTANCE.getKey(), editable.toString()).apply();
            }
        });
        rewordingCustomModelEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (ignoreTextChange) return;
                AIProvider current = getSelectedRewordingProvider();
                if (current == AIProvider.ANTHROPIC) {
                    sp.edit().putString(Pref.RewordingAnthropicModel.INSTANCE.getKey(), editable.toString()).apply();
                } else {
                    sp.edit().putString(Pref.RewordingCustomModel.INSTANCE.getKey(), editable.toString()).apply();
                }
            }
        });

        updateRewordingUI(currentProvider);
    }

    private void updateRewordingUI(AIProvider provider) {
        ignoreTextChange = true;

        boolean isCustom = provider == AIProvider.CUSTOM;

        rewordingCustomFieldsWrapper.setVisibility(isCustom ? View.VISIBLE : View.GONE);

        // API Key
        String apiKey = DictatePrefsKt.get(sp, getRewordingApiKeyPref(provider));
        rewordingAPIKeyEt.setText(apiKey);

        if (isCustom) {
            rewordingCustomHostEt.setText(DictatePrefsKt.get(sp, Pref.RewordingCustomHost.INSTANCE));
            rewordingCustomModelEt.setText(DictatePrefsKt.get(sp, Pref.RewordingCustomModel.INSTANCE));
            rewordingModelGroup.setVisibility(View.GONE);
        } else if (provider == AIProvider.ANTHROPIC) {
            // Anthropic: show free-text model field in custom wrapper, but no host
            rewordingCustomFieldsWrapper.setVisibility(View.VISIBLE);
            // Hide the host field, show only model
            findViewById(R.id.rewording_custom_host_group).setVisibility(View.GONE);
            rewordingCustomModelEt.setText(DictatePrefsKt.get(sp, Pref.RewordingAnthropicModel.INSTANCE));
            rewordingModelGroup.setVisibility(View.GONE);
        } else {
            rewordingModelGroup.setVisibility(View.VISIBLE);
            fetchRewordingModels(provider, apiKey);
        }

        // Update parameter UI (modelId may be empty if not yet selected)
        updateParameterUI(provider, currentRewordingModelId);

        ignoreTextChange = false;
    }

    private void fetchRewordingModels(AIProvider provider, String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            rewordingModelSpn.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{}));
            return;
        }

        int seq = ++rewordingModelFetchSeq;
        if (rewordingModelProgress != null) rewordingModelProgress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                List<ModelInfo> models = ModelFetcher.fetchModels(provider, apiKey, sp, false);
                if (seq != rewordingModelFetchSeq || isFinishing()) return;
                List<String> modelIds = models.stream().map(ModelInfo::getId).collect(Collectors.toList());

                runOnUiThread(() -> {
                    if (seq != rewordingModelFetchSeq || isFinishing()) return;
                    if (rewordingModelProgress != null) rewordingModelProgress.setVisibility(View.GONE);

                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelIds);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    rewordingModelSpn.setAdapter(adapter);

                    // Restore saved selection
                    String savedModel = getSavedRewordingModel(provider);
                    int pos = modelIds.indexOf(savedModel);
                    if (pos >= 0) rewordingModelSpn.setSelection(pos);

                    rewordingModelSpn.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                            String modelId = modelIds.get(position);
                            saveRewordingModel(getSelectedRewordingProvider(), modelId);
                            currentRewordingModelId = modelId;
                            updateParameterUI(getSelectedRewordingProvider(), modelId);
                        }
                        @Override
                        public void onNothingSelected(AdapterView<?> adapterView) {}
                    });
                });
            } catch (Exception e) {
                if (seq != rewordingModelFetchSeq || isFinishing()) return;
                runOnUiThread(() -> {
                    if (rewordingModelProgress != null) rewordingModelProgress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.dictate_model_fetch_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ─── Parameter UI ───

    /**
     * Dynamically generates parameter fields from ParameterRegistry.
     * Only parameters that have a matching Pref in PARAM_PREFS are shown.
     * Supports FLOAT_RANGE (SeekBar), INT_RANGE (EditText), ENUM (Spinner).
     * Implements mutuallyExclusiveWith logic for Anthropic temperature/top_p.
     */
    private void updateParameterUI(AIProvider provider, String modelId) {
        if (rewordingParameterContainer == null) return;
        rewordingParameterContainer.removeAllViews();

        Map<String, Pref<?>> providerPrefs = PARAM_PREFS.get(provider);
        if (providerPrefs == null) return;

        List<ParameterDef> defs = ParameterRegistry.getCompletionParameters(provider, modelId);

        // Track views for mutuallyExclusiveWith logic
        Map<String, View[]> paramViews = new HashMap<>(); // name -> [control, label]

        for (ParameterDef def : defs) {
            Pref<?> pref = providerPrefs.get(def.getName());
            if (pref == null) continue; // No Pref defined yet - skip

            switch (def.getType()) {
                case FLOAT_RANGE:
                    addFloatRangeField(def, (Pref<Float>) pref, paramViews);
                    break;
                case INT_RANGE:
                    addIntRangeField(def, (Pref<Integer>) pref, paramViews);
                    break;
                case ENUM:
                    addEnumField(def, (Pref<String>) pref, paramViews);
                    break;
            }
        }

        // Apply mutuallyExclusiveWith initial state
        for (ParameterDef def : defs) {
            if (def.getMutuallyExclusiveWith() != null && providerPrefs.containsKey(def.getName())) {
                applyMutualExclusionState(def, providerPrefs, paramViews);
            }
        }
    }

    private void addFloatRangeField(ParameterDef def, Pref<Float> pref, Map<String, View[]> paramViews) {
        float saved = DictatePrefsKt.get(sp, pref);
        float min = def.getMin() != null ? def.getMin().floatValue() : 0f;
        float max = def.getMax() != null ? def.getMax().floatValue() : 2f;
        int steps = Math.round((max - min) * 10);

        String displayName = formatParamName(def.getName());

        // Label
        TextView label = new TextView(this);
        label.setText(formatFloatLabel(displayName, saved));
        label.setTextColor(getColor(R.color.dictate_blue));
        label.setPadding(0, 32, 0, 8);
        rewordingParameterContainer.addView(label);

        // SeekBar: position 0 = default (-1), positions 1..steps+1 = min..max
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(steps + 1); // +1 for the "Default" position at 0
        seekBar.setProgress(saved < 0 ? 0 : Math.round((saved - min) * 10) + 1);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float value;
                if (progress == 0) {
                    value = -1f;
                    label.setText(displayName + ": Default");
                } else {
                    value = min + (progress - 1) / 10f;
                    label.setText(formatFloatLabel(displayName, value));
                }
                sp.edit().putFloat(pref.getKey(), value).apply();
                handleMutualExclusion(def, value >= 0, paramViews);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        rewordingParameterContainer.addView(seekBar);

        paramViews.put(def.getName(), new View[]{seekBar, label});
    }

    private void addIntRangeField(ParameterDef def, Pref<Integer> pref, Map<String, View[]> paramViews) {
        int saved = DictatePrefsKt.get(sp, pref);
        String displayName = formatParamName(def.getName());

        // Label
        TextView label = new TextView(this);
        String rangeHint = "";
        if (def.getMin() != null && def.getMax() != null) {
            rangeHint = String.format(Locale.US, " (%d-%d)", def.getMin().intValue(), def.getMax().intValue());
        }
        label.setText(displayName + rangeHint);
        label.setTextColor(getColor(R.color.dictate_blue));
        label.setPadding(0, 32, 0, 8);
        rewordingParameterContainer.addView(label);

        // EditText
        EditText editText = new EditText(this);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        editText.setHint("Default");
        if (saved >= 0) editText.setText(String.valueOf(saved));
        editText.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                String text = editable.toString().trim();
                int value;
                if (text.isEmpty()) {
                    value = -1; // sentinel for server default
                } else {
                    try {
                        value = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        value = -1;
                    }
                }
                sp.edit().putInt(pref.getKey(), value).apply();
            }
        });
        rewordingParameterContainer.addView(editText);

        paramViews.put(def.getName(), new View[]{editText, label});
    }

    private void addEnumField(ParameterDef def, Pref<String> pref, Map<String, View[]> paramViews) {
        String saved = DictatePrefsKt.get(sp, pref);
        String displayName = formatParamName(def.getName());
        List<String> values = def.getEnumValues();
        if (values == null || values.isEmpty()) return;

        // Label
        TextView label = new TextView(this);
        label.setText(displayName);
        label.setTextColor(getColor(R.color.dictate_blue));
        label.setPadding(0, 32, 0, 8);
        rewordingParameterContainer.addView(label);

        // Spinner with "Default" as first entry
        java.util.ArrayList<String> entries = new java.util.ArrayList<>();
        entries.add("Default");
        entries.addAll(values);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        // Restore saved selection
        int selectedPos = 0;
        if (saved != null && !saved.isEmpty()) {
            int idx = values.indexOf(saved);
            if (idx >= 0) selectedPos = idx + 1; // +1 for "Default" entry
        }
        spinner.setSelection(selectedPos);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = position == 0 ? "" : values.get(position - 1);
                sp.edit().putString(pref.getKey(), value).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });
        rewordingParameterContainer.addView(spinner);

        paramViews.put(def.getName(), new View[]{spinner, label});
    }

    /**
     * When a parameter with mutuallyExclusiveWith is set to a non-default value,
     * disable the exclusive parameter's controls.
     */
    private void handleMutualExclusion(ParameterDef def, boolean isActive, Map<String, View[]> paramViews) {
        if (def.getMutuallyExclusiveWith() == null) return;
        View[] exclusiveViews = paramViews.get(def.getMutuallyExclusiveWith());
        if (exclusiveViews == null) return;

        for (View v : exclusiveViews) {
            if (v != null) {
                v.setEnabled(!isActive);
                v.setAlpha(isActive ? 0.4f : 1f);
            }
        }
    }

    /**
     * Applies initial mutual exclusion state based on saved values.
     */
    private void applyMutualExclusionState(ParameterDef def, Map<String, Pref<?>> providerPrefs,
                                           Map<String, View[]> paramViews) {
        Pref<?> pref = providerPrefs.get(def.getName());
        if (pref == null) return;

        boolean isActive;
        Object saved = DictatePrefsKt.get(sp, pref);
        if (saved instanceof Float) {
            isActive = ((Float) saved) >= 0f;
        } else if (saved instanceof Integer) {
            isActive = ((Integer) saved) >= 0;
        } else if (saved instanceof String) {
            isActive = !((String) saved).isEmpty();
        } else {
            return;
        }

        handleMutualExclusion(def, isActive, paramViews);
    }

    private String formatParamName(String name) {
        // "max_completion_tokens" -> "Max Completion Tokens"
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1));
        }
        return sb.toString();
    }

    private String formatFloatLabel(String displayName, float value) {
        if (value < 0) return displayName + ": Default";
        return String.format(Locale.US, "%s: %.1f", displayName, value);
    }

    // ─── Provider Helpers ───

    private AIProvider getSelectedTranscriptionProvider() {
        int pos = transcriptionProviderSpn.getSelectedItemPosition();
        if (pos < 0 || pos >= transcriptionProviders.size()) return AIProvider.OPENAI;
        return transcriptionProviders.get(pos);
    }

    private AIProvider getSelectedRewordingProvider() {
        int pos = rewordingProviderSpn.getSelectedItemPosition();
        if (pos < 0 || pos >= rewordingProviders.size()) return AIProvider.OPENAI;
        return rewordingProviders.get(pos);
    }

    private Pref<String> getTranscriptionApiKeyPref(AIProvider provider) {
        switch (provider) {
            case OPENAI: return Pref.TranscriptionApiKeyOpenAI.INSTANCE;
            case GROQ: return Pref.TranscriptionApiKeyGroq.INSTANCE;
            case CUSTOM: return Pref.TranscriptionApiKeyCustom.INSTANCE;
            default: return Pref.TranscriptionApiKeyOpenAI.INSTANCE;
        }
    }

    private Pref<String> getRewordingApiKeyPref(AIProvider provider) {
        switch (provider) {
            case OPENAI: return Pref.RewordingApiKeyOpenAI.INSTANCE;
            case GROQ: return Pref.RewordingApiKeyGroq.INSTANCE;
            case ANTHROPIC: return Pref.RewordingApiKeyAnthropic.INSTANCE;
            case OPENROUTER: return Pref.RewordingApiKeyOpenRouter.INSTANCE;
            case CUSTOM: return Pref.RewordingApiKeyCustom.INSTANCE;
            default: return Pref.RewordingApiKeyOpenAI.INSTANCE;
        }
    }

    private String getSavedTranscriptionModel(AIProvider provider) {
        switch (provider) {
            case OPENAI: return DictatePrefsKt.get(sp, Pref.TranscriptionOpenAIModel.INSTANCE);
            case GROQ: return DictatePrefsKt.get(sp, Pref.TranscriptionGroqModel.INSTANCE);
            default: return "";
        }
    }

    private void saveTranscriptionModel(AIProvider provider, String modelId) {
        switch (provider) {
            case OPENAI:
                sp.edit().putString(Pref.TranscriptionOpenAIModel.INSTANCE.getKey(), modelId).apply();
                break;
            case GROQ:
                sp.edit().putString(Pref.TranscriptionGroqModel.INSTANCE.getKey(), modelId).apply();
                break;
        }
    }

    private String getSavedRewordingModel(AIProvider provider) {
        switch (provider) {
            case OPENAI: return DictatePrefsKt.get(sp, Pref.RewordingOpenAIModel.INSTANCE);
            case GROQ: return DictatePrefsKt.get(sp, Pref.RewordingGroqModel.INSTANCE);
            case OPENROUTER: return DictatePrefsKt.get(sp, Pref.RewordingOpenRouterModel.INSTANCE);
            case ANTHROPIC: return DictatePrefsKt.get(sp, Pref.RewordingAnthropicModel.INSTANCE);
            default: return "";
        }
    }

    private void saveRewordingModel(AIProvider provider, String modelId) {
        switch (provider) {
            case OPENAI:
                sp.edit().putString(Pref.RewordingOpenAIModel.INSTANCE.getKey(), modelId).apply();
                break;
            case GROQ:
                sp.edit().putString(Pref.RewordingGroqModel.INSTANCE.getKey(), modelId).apply();
                break;
            case OPENROUTER:
                sp.edit().putString(Pref.RewordingOpenRouterModel.INSTANCE.getKey(), modelId).apply();
                break;
            case ANTHROPIC:
                sp.edit().putString(Pref.RewordingAnthropicModel.INSTANCE.getKey(), modelId).apply();
                break;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
