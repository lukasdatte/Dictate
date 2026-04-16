package net.devemperor.dictate.history;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.devemperor.dictate.R;
import net.devemperor.dictate.core.ActiveJobRegistry;
import net.devemperor.dictate.core.ActiveJobRegistryObserver;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.dao.SessionDao;
import net.devemperor.dictate.database.entity.SessionEntity;
import net.devemperor.dictate.database.entity.SessionType;

import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "session_id";

    private SessionDao sessionDao;
    private List<SessionEntity> data;
    private HistoryAdapter adapter;
    private String currentFilter = null; // null = all
    private String currentSearch = null;

    @SuppressLint("NotifyDataSetChanged")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_history);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_history), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.dictate_history);
        }

        sessionDao = DictateDatabase.getInstance(this).sessionDao();
        data = new ArrayList<>();

        RecyclerView recyclerView = findViewById(R.id.history_rv);
        recyclerView.setHasFixedSize(false);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new HistoryAdapter(data, new HistoryAdapter.AdapterCallback() {
            @Override
            public void onItemClicked(SessionEntity session) {
                Intent intent = new Intent(HistoryActivity.this, HistoryDetailActivity.class);
                intent.putExtra(EXTRA_SESSION_ID, session.getId());
                startActivity(intent);
            }

            @Override
            public void onItemLongClicked(SessionEntity session, int position) {
                new MaterialAlertDialogBuilder(HistoryActivity.this)
                        .setTitle(R.string.dictate_history_delete_session_title)
                        .setMessage(R.string.dictate_history_delete_session_message)
                        .setPositiveButton(R.string.dictate_yes, (dialog, which) -> {
                            sessionDao.deleteById(session.getId());
                            data.remove(position);
                            adapter.notifyItemRemoved(position);
                            updateEmptyState();
                        })
                        .setNegativeButton(R.string.dictate_no, null)
                        .show();
            }
        });
        recyclerView.setAdapter(adapter);

        // Filter chips
        ChipGroup chipGroup = findViewById(R.id.history_filter_chip_group);
        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.history_chip_all) {
                currentFilter = null;
            } else if (checkedId == R.id.history_chip_recordings) {
                currentFilter = SessionType.RECORDING.name();
            } else if (checkedId == R.id.history_chip_rewording) {
                currentFilter = SessionType.REWORDING.name();
            } else if (checkedId == R.id.history_chip_post_processing) {
                currentFilter = SessionType.POST_PROCESSING.name();
            }
            refreshData();
        });

        // Search
        SearchView searchView = findViewById(R.id.history_search_view);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                currentSearch = newText != null && !newText.trim().isEmpty() ? newText.trim() : null;
                refreshData();
                return true;
            }
        });

        // Delete all button
        MaterialButton deleteAllBtn = findViewById(R.id.history_delete_all_btn);
        deleteAllBtn.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictate_history_delete_all_title)
                .setMessage(R.string.dictate_history_delete_all_message)
                .setPositiveButton(R.string.dictate_yes, (dialog, which) -> {
                    sessionDao.deleteAll();
                    data.clear();
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                })
                .setNegativeButton(R.string.dictate_no, null)
                .show());

        refreshData();

        // K3: reactive badges. When a job starts/stops for any session, reload
        // the list so persistent status (finalOutputText, SessionStatus) and
        // the running-badge overlay both reflect the latest state. We ignore
        // the snapshot contents and let `HistoryAdapter.applyStatusBadge` read
        // `ActiveJobRegistry.isActive(sessionId)` during rebind.
        ActiveJobRegistryObserver.observe(this, snapshot -> refreshData());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshData();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshData() {
        List<SessionEntity> newData;
        if (currentSearch != null && !currentSearch.isEmpty()) {
            newData = sessionDao.search(currentSearch);
            // Apply type filter on search results
            if (currentFilter != null) {
                List<SessionEntity> filtered = new ArrayList<>();
                for (SessionEntity s : newData) {
                    if (currentFilter.equals(s.getType())) {
                        filtered.add(s);
                    }
                }
                newData = filtered;
            }
        } else if (currentFilter != null) {
            newData = sessionDao.getByType(currentFilter);
        } else {
            newData = sessionDao.getAll();
        }
        data.clear();
        data.addAll(newData);
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        findViewById(R.id.history_no_sessions_tv).setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.history_delete_all_btn).setEnabled(!data.isEmpty());
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
