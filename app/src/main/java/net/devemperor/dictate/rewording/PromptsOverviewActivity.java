package net.devemperor.dictate.rewording;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import net.devemperor.dictate.R;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.dao.PromptDao;
import net.devemperor.dictate.database.entity.PromptEntity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PromptsOverviewActivity extends AppCompatActivity {

    PromptDao promptDao;
    List<PromptEntity> data;
    RecyclerView recyclerView;
    PromptsOverviewAdapter adapter;
    ItemTouchHelper itemTouchHelper;

    ActivityResultLauncher<Intent> addEditPromptLauncher;
    private ActivityResultLauncher<String> exportPromptsLauncher;
    private ActivityResultLauncher<String[]> importPromptsLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_prompts_overview);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_prompts_overview), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.dictate_prompts);
        }

        promptDao = DictateDatabase.getInstance(this).promptDao();
        data = new ArrayList<>(promptDao.getAll());

        recyclerView = findViewById(R.id.prompts_overview_rv);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PromptsOverviewAdapter(data, new PromptsOverviewAdapter.AdapterCallback() {
            @Override
            public void onItemClicked(int position) {
                PromptEntity entity = data.get(position);
                Intent intent = new Intent(PromptsOverviewActivity.this, PromptEditActivity.class);
                intent.putExtra("net.devemperor.dictate.prompt_edit_activity_id", entity.getId());
                addEditPromptLauncher.launch(intent);
            }

            @Override
            public void onDeleteClicked(int position) {
                confirmDeletePrompt(position);
            }

            @Override
            public void onDuplicateClicked(int position) {
                duplicatePrompt(position);
            }

            @Override
            public void onStartDrag(RecyclerView.ViewHolder holder) {
                itemTouchHelper.startDrag(holder);
            }
        });
        // Header + cards share the RecyclerView so the info text scrolls with the list.
        recyclerView.setAdapter(new ConcatAdapter(new PromptsInfoHeaderAdapter(), adapter));

        itemTouchHelper = new ItemTouchHelper(new PromptReorderCallback(new PromptReorderCallback.Listener() {
            @Override
            public boolean onItemMoved(int fromPosition, int toPosition) {
                data.add(toPosition, data.remove(fromPosition));
                adapter.notifyItemMoved(fromPosition, toPosition);
                return true;
            }

            @Override
            public void onDragFinished() {
                persistOrder();
            }
        }));
        itemTouchHelper.attachToRecyclerView(recyclerView);

        updateEmptyState();

        ExtendedFloatingActionButton addPromptBtn = findViewById(R.id.prompts_overview_add_btn);
        addPromptBtn.setOnClickListener(v -> launchAddPrompt());
        MaterialButton emptyAddBtn = findViewById(R.id.prompts_overview_empty_add_btn);
        emptyAddBtn.setOnClickListener(v -> launchAddPrompt());

        exportPromptsLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) {
                        exportPrompts(uri);
                    }
                }
        );

        importPromptsLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        importPrompts(uri);
                    }
                }
        );

        addEditPromptLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        int updatedId = -1;
                        int addedId = -1;
                        if (result.getData() != null) {
                            updatedId = result.getData().getIntExtra("updated_id", -1);
                            addedId = result.getData().getIntExtra("added_id", -1);
                        }
                        if (updatedId != -1) {
                            PromptEntity updatedPrompt = promptDao.getById(updatedId);
                            for (int i = 0; i < data.size(); i++) {
                                if (data.get(i).getId() == updatedId) {
                                    data.set(i, updatedPrompt);
                                    adapter.notifyItemChanged(i);
                                    break;
                                }
                            }
                        } else if (addedId != -1) {
                            data.add(promptDao.getById(addedId));
                            adapter.notifyItemInserted(data.size() - 1);
                            updateEmptyState();
                        }
                    }
                }
        );
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_prompts_overview, menu);
        return true;
    }

    private void launchAddPrompt() {
        Intent intent = new Intent(PromptsOverviewActivity.this, PromptEditActivity.class);
        addEditPromptLauncher.launch(intent);
    }

    private void confirmDeletePrompt(int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictate_delete_prompt)
                .setMessage(R.string.dictate_delete_prompt_message)
                .setPositiveButton(R.string.dictate_yes, (di, i) -> {
                    if (position >= data.size()) return;
                    promptDao.deleteById(data.get(position).getId());
                    data.remove(position);
                    adapter.notifyItemRemoved(position);
                    persistOrder();
                    updateEmptyState();
                })
                .setNegativeButton(R.string.dictate_no, null)
                .show();
    }

    /**
     * Inserts a copy of the prompt at {@code position} directly after it. The
     * copy is written with a unique tail position first, then the whole list is
     * resequenced so every row's {@code pos} matches its list index again.
     */
    private void duplicatePrompt(int position) {
        PromptEntity source = data.get(position);
        PromptEntity copy = PromptListMutations.copyOf(source, getString(R.string.dictate_prompts_copy_suffix), data.size());
        int copyId = (int) promptDao.insert(copy);
        data.add(position + 1, promptDao.getById(copyId));
        adapter.notifyItemInserted(position + 1);
        persistOrder();
        showToast(R.string.dictate_prompts_duplicated);
    }

    /** Persists {@code pos = list index} for every row whose stored pos drifted. */
    private void persistOrder() {
        List<PromptEntity> changed = PromptListMutations.resequenced(data);
        for (int i = 0; i < changed.size(); i++) {
            PromptEntity entity = changed.get(i);
            promptDao.update(entity);
            for (int j = 0; j < data.size(); j++) {
                if (data.get(j).getId() == entity.getId()) {
                    data.set(j, entity);
                    break;
                }
            }
        }
    }

    private void exportPrompts(Uri uri) {
        List<PromptEntity> prompts = promptDao.getAll();
        JSONObject root;
        try {
            root = PromptImportExport.buildExport(prompts);
        } catch (JSONException e) {
            showToast(R.string.dictate_prompts_export_failed);
            return;
        }

        try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) {
                showToast(R.string.dictate_prompts_export_failed);
                return;
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                writer.write(root.toString(2));
                writer.flush();
            } catch (JSONException e) {
                // Serialisation failure must degrade like every other export
                // error (toast + abort), never crash the app.
                showToast(R.string.dictate_prompts_export_failed);
                return;
            }
            showToast(R.string.dictate_prompts_export_success);
        } catch (IOException e) {
            showToast(R.string.dictate_prompts_export_failed);
        }
    }

    private void importPrompts(Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                showToast(R.string.dictate_prompts_import_failed);
                return;
            }
            String json = readStream(inputStream);
            List<PromptEntity> importedPrompts = parsePrompts(json);
            if (importedPrompts.isEmpty()) {
                showToast(R.string.dictate_prompts_import_no_prompts);
                return;
            }
            showImportModeDialog(importedPrompts);
        } catch (IOException | JSONException e) {
            showToast(R.string.dictate_prompts_import_failed);
        }
    }

    private String readStream(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    private List<PromptEntity> parsePrompts(String json) throws JSONException {
        return PromptImportExport.parse(json);
    }

    private void showImportModeDialog(List<PromptEntity> importedPrompts) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictate_prompts_import_mode_title)
                .setMessage(R.string.dictate_prompts_import_mode_message)
                .setPositiveButton(R.string.dictate_prompts_import_mode_replace, (dialog, which) -> replacePrompts(importedPrompts))
                .setNegativeButton(R.string.dictate_prompts_import_mode_add, (dialog, which) -> appendPrompts(importedPrompts))
                .setNeutralButton(R.string.dictate_cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void replacePrompts(List<PromptEntity> importedPrompts) {
        List<PromptEntity> sanitized = new ArrayList<>(importedPrompts.size());
        for (int i = 0; i < importedPrompts.size(); i++) {
            PromptEntity entity = importedPrompts.get(i);
            sanitized.add(net.devemperor.dictate.config.PromptProvenance.stamped(
                    new PromptEntity(0, i, entity.getName(), entity.getPrompt(), entity.getRequiresSelection(), entity.getAutoApply(), entity.getType())));
        }
        promptDao.deleteAll();
        promptDao.insertAll(sanitized);
        reloadPrompts();
        showToast(R.string.dictate_prompts_import_success);
    }

    private void appendPrompts(List<PromptEntity> importedPrompts) {
        int startPos = promptDao.count();
        List<PromptEntity> sanitized = new ArrayList<>(importedPrompts.size());
        for (int i = 0; i < importedPrompts.size(); i++) {
            PromptEntity entity = importedPrompts.get(i);
            sanitized.add(net.devemperor.dictate.config.PromptProvenance.stamped(
                    new PromptEntity(0, startPos + i, entity.getName(), entity.getPrompt(), entity.getRequiresSelection(), entity.getAutoApply(), entity.getType())));
        }
        promptDao.insertAll(sanitized);
        reloadPrompts();
        showToast(R.string.dictate_prompts_import_success);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void reloadPrompts() {
        data.clear();
        data.addAll(promptDao.getAll());
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean isEmpty = data.isEmpty();
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        findViewById(R.id.prompts_overview_empty_container).setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private void showToast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        } else if (itemId == R.id.menu_prompts_overview_export) {
            exportPromptsLauncher.launch(getString(R.string.dictate_prompts_export_filename));
            return true;
        } else if (itemId == R.id.menu_prompts_overview_import) {
            importPromptsLauncher.launch(new String[]{"application/json"});
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
