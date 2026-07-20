package net.devemperor.dictate.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import net.devemperor.dictate.R;
import net.devemperor.dictate.SimpleTextWatcher;
import net.devemperor.dictate.ai.ElevenLabsKeytermsParser;
import net.devemperor.dictate.config.ActiveProfile;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.Pref;
import net.devemperor.dictate.shared.config.ModelRefEntity;
import net.devemperor.dictate.shared.config.ProviderType;

import java.util.List;

public class SystemPromptsActivity extends AppCompatActivity {

    // C3: style/system prompt selection + keyterms live on the ACTIVE profile / its transcription
    // model ref (spec §4.5, §4.7) — sp keeps only device-local editor state (raw keyterms text).
    private SharedPreferences sp;
    private DictateDatabase db;

    private RadioGroup transcriptionStylePromptRg;
    private RadioButton transcriptionStylePromptNothingRb;
    private RadioButton transcriptionStylePromptPredefinedRb;
    private RadioButton transcriptionStylePromptCustomRb;
    private EditText transcriptionStylePromptCustomEt;

    private RadioGroup rewordingSystemPromptRg;
    private RadioButton rewordingSystemPromptNothingRb;
    private RadioButton rewordingSystemPromptPredefinedRb;
    private RadioButton rewordingSystemPromptCustomRb;
    private EditText rewordingSystemPromptCustomEt;

    private TextInputLayout keytermsTil;
    private TextInputEditText keytermsEt;
    private TextView keytermsStatusTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_system_prompts);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_system_prompts), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.dictate_system_prompts);
        }

        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);
        db = DictateDatabase.getInstance(this);

        transcriptionStylePromptRg = findViewById(R.id.transcription_style_prompt_rg);
        transcriptionStylePromptNothingRb = findViewById(R.id.transcription_style_prompt_nothing_rb);
        transcriptionStylePromptPredefinedRb = findViewById(R.id.transcription_style_prompt_predefined_rb);
        transcriptionStylePromptCustomRb = findViewById(R.id.transcription_style_prompt_custom_rb);
        transcriptionStylePromptCustomEt = findViewById(R.id.transcription_style_prompt_custom_et);
        ImageView transcriptionStylePromptHelpIv = findViewById(R.id.transcription_style_prompt_help_iv);

        rewordingSystemPromptRg = findViewById(R.id.rewording_system_prompt_rg);
        rewordingSystemPromptNothingRb = findViewById(R.id.rewording_system_prompt_nothing_rb);
        rewordingSystemPromptPredefinedRb = findViewById(R.id.rewording_system_prompt_predefined_rb);
        rewordingSystemPromptCustomRb = findViewById(R.id.rewording_system_prompt_custom_rb);
        rewordingSystemPromptCustomEt = findViewById(R.id.rewording_system_prompt_custom_et);

        setupTranscriptionStylePrompt();
        setupRewordingSystemPrompt();
        setupKeyterms();

        transcriptionStylePromptHelpIv.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/docs/guides/speech-to-text#prompting"));
            startActivity(browserIntent);
        });
    }

    private void setupTranscriptionStylePrompt() {
        changeTranscriptionSelection(ActiveProfile.stylePromptSelection(sp, db));
        transcriptionStylePromptCustomEt.setText(ActiveProfile.stylePromptCustomText(sp, db));

        transcriptionStylePromptRg.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.transcription_style_prompt_nothing_rb) {
                changeTranscriptionSelection(0);
            } else if (checkedId == R.id.transcription_style_prompt_predefined_rb) {
                changeTranscriptionSelection(1);
            } else if (checkedId == R.id.transcription_style_prompt_custom_rb) {
                changeTranscriptionSelection(2);
            }
        });

        transcriptionStylePromptCustomEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                ActiveProfile.setStylePromptCustomText(sp, db, s.toString());
            }
        });
    }

    private void setupRewordingSystemPrompt() {
        changeRewordingSelection(ActiveProfile.systemPromptSelection(sp, db));
        rewordingSystemPromptCustomEt.setText(ActiveProfile.systemPromptCustomText(sp, db));

        rewordingSystemPromptRg.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rewording_system_prompt_nothing_rb) {
                changeRewordingSelection(0);
            } else if (checkedId == R.id.rewording_system_prompt_predefined_rb) {
                changeRewordingSelection(1);
            } else if (checkedId == R.id.rewording_system_prompt_custom_rb) {
                changeRewordingSelection(2);
            }
        });

        rewordingSystemPromptCustomEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                ActiveProfile.setSystemPromptCustomText(sp, db, s.toString());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateKeytermsEnabled();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void changeTranscriptionSelection(int selection) {
        transcriptionStylePromptNothingRb.setChecked(selection == 0);
        transcriptionStylePromptPredefinedRb.setChecked(selection == 1);
        transcriptionStylePromptCustomRb.setChecked(selection == 2);
        transcriptionStylePromptCustomEt.setEnabled(selection == 2);
        ActiveProfile.setStylePromptSelection(sp, db, selection);
    }

    private void changeRewordingSelection(int selection) {
        rewordingSystemPromptNothingRb.setChecked(selection == 0);
        rewordingSystemPromptPredefinedRb.setChecked(selection == 1);
        rewordingSystemPromptCustomRb.setChecked(selection == 2);
        rewordingSystemPromptCustomEt.setEnabled(selection == 2);
        ActiveProfile.setSystemPromptSelection(sp, db, selection);
    }

    private void setupKeyterms() {
        keytermsTil = findViewById(R.id.elevenlabs_keyterms_til);
        keytermsEt = findViewById(R.id.elevenlabs_keyterms_et);
        keytermsStatusTv = findViewById(R.id.elevenlabs_keyterms_status_tv);
        keytermsEt.setText(DictatePrefsKt.get(sp, Pref.ElevenLabsKeytermsRaw.INSTANCE));

        updateKeytermsEnabled();

        updateKeytermsStatus(keytermsTil, keytermsStatusTv,
            ElevenLabsKeytermsParser.INSTANCE.parse(
                DictatePrefsKt.get(sp, Pref.ElevenLabsKeytermsRaw.INSTANCE)));

        keytermsEt.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String raw = s.toString();

                ElevenLabsKeytermsParser.ParseResult result = ElevenLabsKeytermsParser.INSTANCE.parse(raw);

                // Raw text (incl. comments) is device-local editor state; the parsed JSON is the
                // shareable model parameter (spec §4.5).
                DictatePrefsKt.put(sp.edit(), Pref.ElevenLabsKeytermsRaw.INSTANCE, raw).apply();
                ActiveProfile.setTranscriptionKeyterms(sp, db,
                        ElevenLabsKeytermsParser.INSTANCE.toJson(result.getTerms()));

                updateKeytermsStatus(keytermsTil, keytermsStatusTv, result);
            }
        });
    }

    private void updateKeytermsEnabled() {
        ProviderType provider = ActiveProfile.transcriptionProviderType(sp, db);
        ModelRefEntity modelRef = ActiveProfile.transcriptionModelRef(sp, db);
        boolean enabled = provider == ProviderType.ELEVENLABS
                && modelRef != null && "scribe_v2".equals(modelRef.getModelId());
        keytermsEt.setEnabled(enabled);
    }

    private void updateKeytermsStatus(TextInputLayout til, TextView statusTv,
                                       ElevenLabsKeytermsParser.ParseResult result) {
        if (!result.getErrors().isEmpty()) {
            til.setError(formatKeytermsErrors(result.getErrors()));
        } else {
            til.setError(null);
        }

        int termCount = result.getTerms().size();
        int commentCount = result.getCommentCount();
        if (termCount == 0 && commentCount == 0) {
            statusTv.setVisibility(View.GONE);
        } else {
            statusTv.setVisibility(View.VISIBLE);
            String status = getResources().getQuantityString(
                R.plurals.dictate_keyterms_status_terms, termCount, termCount);
            if (commentCount > 0) {
                status += ", " + getResources().getQuantityString(
                    R.plurals.dictate_keyterms_status_comments, commentCount, commentCount);
            }
            statusTv.setText(status);
        }
    }

    private String formatKeytermsErrors(List<ElevenLabsKeytermsParser.ValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        for (ElevenLabsKeytermsParser.ValidationError err : errors) {
            String reason;
            switch (err.getReason()) {
                case TOO_LONG:
                    reason = getString(R.string.dictate_keyterms_error_too_long);
                    break;
                case TOO_MANY_WORDS:
                    reason = getString(R.string.dictate_keyterms_error_too_many_words);
                    break;
                case TOO_MANY_TERMS:
                    reason = getString(R.string.dictate_keyterms_error_too_many_terms);
                    break;
                default:
                    reason = "";
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append("\"").append(err.getTerm()).append("\": ").append(reason);
        }
        return sb.toString();
    }
}