package net.devemperor.dictate.rewording;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import net.devemperor.dictate.R;
import net.devemperor.dictate.SimpleTextWatcher;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.dao.PromptDao;
import net.devemperor.dictate.database.entity.PromptEntity;
import net.devemperor.dictate.database.entity.PromptType;

public class PromptEditActivity extends AppCompatActivity {

    private PromptDao promptDao;
    private EditText promptNameEt;
    private EditText promptPromptEt;
    private MaterialSwitch promptRequiresSelectionSwitch;
    private MaterialSwitch promptAutoApplySwitch;
    private MaterialButtonToggleGroup typeToggle;
    private View requiresSelectionContainer;
    private View autoApplyContainer;
    private MaterialButton savePromptBtn;
    private int promptId;

    private String initialName = "";
    private String initialPrompt = "";
    private boolean initialRequiresSelection = true;
    private boolean initialAutoApply = false;
    private PromptType currentType = PromptType.PROMPT;
    private PromptType initialType = PromptType.PROMPT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_prompt_edit);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_prompt_edit), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.dictate_edit_prompt);
        }

        promptNameEt = findViewById(R.id.prompt_edit_name_et);
        promptPromptEt = findViewById(R.id.prompt_edit_prompt_et);
        promptRequiresSelectionSwitch = findViewById(R.id.prompt_edit_requires_selection_switch);
        promptAutoApplySwitch = findViewById(R.id.prompt_edit_auto_apply_switch);
        typeToggle = findViewById(R.id.prompt_edit_type_toggle);
        requiresSelectionContainer = findViewById(R.id.prompt_edit_requires_selection_container);
        autoApplyContainer = findViewById(R.id.prompt_edit_auto_apply_container);
        savePromptBtn = findViewById(R.id.prompt_edit_save_btn);

        promptDao = DictateDatabase.getInstance(this).promptDao();

        promptId = getIntent().getIntExtra("net.devemperor.dictate.prompt_edit_activity_id", -1);
        if (promptId != -1) {
            PromptEntity entity = promptDao.getById(promptId);
            if (entity != null) {
                promptNameEt.setText(entity.getName());
                promptPromptEt.setText(entity.getPrompt());
                promptRequiresSelectionSwitch.setChecked(entity.getRequiresSelection());
                promptAutoApplySwitch.setChecked(entity.getAutoApply());
                currentType = entity.getTypeEnum();
                initialName = entity.getName();
                initialPrompt = entity.getPrompt();
                initialRequiresSelection = entity.getRequiresSelection();
                initialAutoApply = entity.getAutoApply();
                initialType = currentType;
            } else {
                promptId = -1;
            }
        }

        if (promptId == -1) {
            initialName = "";
            initialPrompt = "";
            initialRequiresSelection = true;
            initialAutoApply = false;
            currentType = PromptType.PROMPT;
            initialType = PromptType.PROMPT;
        }

        // Reflect the loaded type, then react to user toggles. The initial
        // check() must precede the listener so it doesn't count as a change.
        typeToggle.check(currentType == PromptType.TEXT
                ? R.id.prompt_edit_type_text_btn : R.id.prompt_edit_type_prompt_btn);
        applyTypeVisibility(currentType);
        typeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            currentType = checkedId == R.id.prompt_edit_type_text_btn
                    ? PromptType.TEXT : PromptType.PROMPT;
            applyTypeVisibility(currentType);
            updateSaveButtonState();
        });

        SimpleTextWatcher tw = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                updateSaveButtonState();
            }
        };
        promptNameEt.addTextChangedListener(tw);
        promptPromptEt.addTextChangedListener(tw);

        promptRequiresSelectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateSaveButtonState());
        promptAutoApplySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateSaveButtonState());

        updateSaveButtonState();

        savePromptBtn.setOnClickListener(v -> savePrompt());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            handleBackNavigation();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    private void updateSaveButtonState() {
        boolean isValid = !promptNameEt.getText().toString().trim().isEmpty()
                && !promptPromptEt.getText().toString().trim().isEmpty();
        savePromptBtn.setEnabled(isValid);
    }

    /**
     * Shows/hides the type-specific controls: a TEXT pill inserts its content
     * literally, so {@code requiresSelection} / {@code autoApply} are meaningless
     * and hidden, and the prompt field carries the literal-text hint.
     */
    private void applyTypeVisibility(PromptType type) {
        boolean isText = type == PromptType.TEXT;
        requiresSelectionContainer.setVisibility(isText ? View.GONE : View.VISIBLE);
        autoApplyContainer.setVisibility(isText ? View.GONE : View.VISIBLE);
        promptPromptEt.setHint(isText
                ? R.string.dictate_prompt_type_text_hint
                : R.string.dictate_edit_prompt_prompt_hint);
    }

    private boolean hasUnsavedChanges() {
        String currentName = promptNameEt.getText().toString();
        String currentPrompt = promptPromptEt.getText().toString();
        boolean currentRequiresSelection = promptRequiresSelectionSwitch.isChecked();
        boolean currentAutoApply = promptAutoApplySwitch.isChecked();

        if (promptId == -1
                && currentName.isEmpty()
                && currentPrompt.isEmpty()
                && currentRequiresSelection
                && !currentAutoApply
                && currentType == PromptType.PROMPT) {
            return false;
        }

        return !currentName.equals(initialName)
                || !currentPrompt.equals(initialPrompt)
                || currentRequiresSelection != initialRequiresSelection
                || currentAutoApply != initialAutoApply
                || currentType != initialType;
    }

    private void handleBackNavigation() {
        if (!hasUnsavedChanges()) {
            finish();
            return;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictate_unsaved_changes_title)
                .setMessage(R.string.dictate_unsaved_changes_message)
                .setPositiveButton(R.string.dictate_save, (dialog, which) -> savePrompt())
                .setNegativeButton(R.string.dictate_discard_changes, (dialog, which) -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .setNeutralButton(R.string.dictate_cancel, (dialog, which) -> dialog.dismiss());

        androidx.appcompat.app.AlertDialog dialog = builder.show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setEnabled(savePromptBtn.isEnabled());
    }

    private void savePrompt() {
        if (!savePromptBtn.isEnabled()) return;

        String name = promptNameEt.getText().toString();
        String prompt = promptPromptEt.getText().toString();
        // A TEXT pill has no selection/auto-apply semantics — force both off so a
        // pill switched to TEXT never carries stale flags into the queue paths.
        boolean isText = currentType == PromptType.TEXT;
        boolean requiresSelection = !isText && promptRequiresSelectionSwitch.isChecked();
        boolean autoApply = !isText && promptAutoApplySwitch.isChecked();

        Intent result = new Intent();
        if (promptId == -1) {
            PromptEntity newEntity = new PromptEntity(0, promptDao.count(), name, prompt, requiresSelection, autoApply, currentType.name());
            long addId = promptDao.insert(newEntity);
            result.putExtra("added_id", (int) addId);
        } else {
            PromptEntity existing = promptDao.getById(promptId);
            if (existing != null) {
                PromptEntity updated = new PromptEntity(existing.getId(), existing.getPos(), name, prompt, requiresSelection, autoApply, currentType.name());
                promptDao.update(updated);
            }
            result.putExtra("updated_id", promptId);
        }

        setResult(RESULT_OK, result);
        finish();
    }
}
