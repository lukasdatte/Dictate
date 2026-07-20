package net.devemperor.dictate.peers.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.devemperor.dictate.R
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.shared.config.SubscriptionMode

/**
 * The read-only Android Peer Explorer (Block E3, peer-katalog.md §8.3): a settings page listing
 * every local copy whose provenance names a peer, grouped by peer — subscribed, one-shot and forked.
 *
 * Read-only by design (F7): Android offers nothing, manages no visibility, and edits nothing here —
 * the phone is a *subscriber*. The list is derived purely from the Room provenance columns via
 * [PeerCopiesOverview]; sync-now/unsubscribe arrive together with the Room-backed subscriber store
 * (the delegated sync-adapter work), which is also when rows gain actions. Until a copy exists the
 * page states that honestly instead of hiding.
 *
 * `APISettingsActivity` pattern: XML shell, programmatic rows, direct DAO reads (the DB allows
 * main-thread queries and the lists are small — the same trade the settings hub makes).
 */
class PeerExplorerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_peer_explorer)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_peer_explorer)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.dictate_peer_explorer_title)
        }
        load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        val db = DictateDatabase.getInstance(this)
        render(
            PeerCopiesOverview.build(
                providers = db.providerConfigDao().getAll(),
                credentials = db.apiCredentialDao().getAll(),
                models = db.modelRefDao().getAll(),
                profiles = db.profileDao().getAll(),
                prompts = db.promptDao().getAll(),
            ),
        )
    }

    private fun render(groups: List<PeerCopiesOverview.PeerGroup>) {
        val list = findViewById<LinearLayout>(R.id.peer_explorer_list)
        val empty = findViewById<TextView>(R.id.peer_explorer_empty)
        list.removeAllViews()
        empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE

        groups.forEach { group ->
            list.addView(TextView(this).apply {
                text = group.peerId
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                setPadding(0, dp(12), 0, dp(4))
            })
            group.copies.forEach { copy ->
                list.addView(TextView(this).apply {
                    text = getString(R.string.dictate_peer_explorer_row, copy.label, copy.kind.name, modeLabel(copy))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setPadding(dp(8), dp(4), 0, dp(4))
                })
            }
        }
    }

    private fun modeLabel(copy: PeerCopiesOverview.Copy): String = when {
        copy.isFork -> getString(R.string.dictate_peer_explorer_mode_forked)
        copy.mode == SubscriptionMode.ONE_SHOT -> getString(R.string.dictate_peer_explorer_mode_one_shot)
        else -> getString(R.string.dictate_peer_explorer_mode_subscribed)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
