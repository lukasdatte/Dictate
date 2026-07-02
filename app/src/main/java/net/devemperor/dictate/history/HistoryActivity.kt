package net.devemperor.dictate.history

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import net.devemperor.dictate.R
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionType

/**
 * History list screen — paged, background-loaded, diff-updated
 * (F-054 / history-pagination-and-scale §2).
 *
 * All data concerns live in [HistoryViewModel]; this activity only
 * wires UI events into the view model and collects three flows:
 *
 *  - `pagingData` → [HistoryAdapter.submitData] (incremental pages),
 *  - `refreshEvents` (coalesced ActiveJobRegistry ticks) →
 *    [HistoryAdapter.refresh],
 *  - `loadStateFlow` → empty-state visibility.
 *
 * No DAO call runs on the main thread here — the screen is
 * independent of the app-wide `allowMainThreadQueries()` flag.
 */
class HistoryActivity : AppCompatActivity() {

    private val viewModel: HistoryViewModel by viewModels { HistoryViewModel.factory(this) }
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_history)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.dictate_history)
        }

        adapter = HistoryAdapter(object : HistoryAdapter.Callback {
            override fun onItemClicked(session: SessionEntity) {
                val intent = Intent(this@HistoryActivity, HistoryDetailActivity::class.java)
                intent.putExtra(EXTRA_SESSION_ID, session.id)
                startActivity(intent)
            }

            override fun onItemLongClicked(session: SessionEntity) {
                MaterialAlertDialogBuilder(this@HistoryActivity)
                    .setTitle(R.string.dictate_history_delete_session_title)
                    .setMessage(R.string.dictate_history_delete_session_message)
                    .setPositiveButton(R.string.dictate_yes) { _, _ ->
                        // Off-main-thread delete; Room's invalidation
                        // tracker refreshes the PagingSource — no manual
                        // notifyItemRemoved bookkeeping.
                        viewModel.deleteSession(session.id)
                    }
                    .setNegativeButton(R.string.dictate_no, null)
                    .show()
            }
        })

        findViewById<RecyclerView>(R.id.history_rv).apply {
            setHasFixedSize(false)
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }

        // Filter chips — applied without debounce (explicit user action).
        findViewById<ChipGroup>(R.id.history_filter_chip_group)
            .setOnCheckedStateChangeListener { _, checkedIds ->
                val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
                viewModel.setTypeFilter(
                    when (checkedId) {
                        R.id.history_chip_recordings -> SessionType.RECORDING.name
                        R.id.history_chip_rewording -> SessionType.REWORDING.name
                        R.id.history_chip_post_processing -> SessionType.POST_PROCESSING.name
                        else -> null // history_chip_all
                    }
                )
            }

        // Search — debounced + wildcard-escaped inside the view model.
        findViewById<SearchView>(R.id.history_search_view)
            .setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setSearchQuery(newText)
                    return true
                }
            })

        findViewById<MaterialButton>(R.id.history_delete_all_btn).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictate_history_delete_all_title)
                .setMessage(R.string.dictate_history_delete_all_message)
                .setPositiveButton(R.string.dictate_yes) { _, _ -> viewModel.deleteAllSessions() }
                .setNegativeButton(R.string.dictate_no, null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pagingData.collect { adapter.submitData(it) }
                }
                // K3: reactive badges — a coalesced registry tick reloads
                // the pages so persisted status and the running-badge
                // overlay both reflect the latest state (HistoryRow folds
                // the running flag into the diffed item).
                launch {
                    viewModel.refreshEvents.collect { adapter.refresh() }
                }
                // Empty-state + delete-all enablement. loadStateFlow
                // emissions are dispatched after the corresponding
                // presenter update, so itemCount is in sync here.
                launch {
                    adapter.loadStateFlow.collect { loadState ->
                        if (loadState.refresh is LoadState.NotLoading) {
                            updateEmptyState(adapter.itemCount == 0)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Detail screen may have mutated sessions outside this activity's
        // Room instance observers (e.g. reprocess) — refresh is cheap and
        // runs on the paging background executor.
        adapter.refresh()
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        findViewById<View>(R.id.history_no_sessions_tv).visibility =
            if (isEmpty) View.VISIBLE else View.GONE
        findViewById<View>(R.id.history_delete_all_btn).isEnabled = !isEmpty
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        /** Java-interop: HistoryDetailActivity reads this as a static field. */
        const val EXTRA_SESSION_ID = "session_id"
    }
}
