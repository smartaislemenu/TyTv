package com.tytv.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.data.ScoresApi
import com.tytv.app.settings.AppPrefs
import kotlinx.coroutines.launch

/**
 * Full scores list, grouped by league, refreshing every minute
 * while open. Scores come from ESPN's public scoreboard feeds and
 * are never linked to channels — a row here is information only.
 * When the feed fails, the screen says so instead of showing
 * stale or invented scores.
 */
class ScoresActivity : FragmentActivity() {

    private sealed class Item {
        data class Header(val text: String) : Item()
        data class Game(val game: ScoresApi.Game) : Item()
    }

    private lateinit var statusText: TextView
    private lateinit var list: RecyclerView
    private val adapter = ScoresAdapter()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            load()
            refreshHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scores)
        statusText = findViewById(R.id.scores_status)
        list = findViewById(R.id.scores_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
        refreshHandler.postDelayed(refreshRunnable, 60_000L)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun load() {
        if (!AppPrefs.scoresEnabled(this)) {
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.scores_off)
            adapter.submit(emptyList())
            return
        }
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.scores_loading)
        lifecycleScope.launch {
            val games = ScoresApi.fetchAll()
            if (games.isEmpty()) {
                statusText.text = getString(R.string.scores_failed)
                adapter.submit(emptyList())
                return@launch
            }
            statusText.visibility = View.GONE
            val items = mutableListOf<Item>()
            for ((league, leagueGames) in games.groupBy { it.league }) {
                items += Item.Header(league)
                for (game in leagueGames) items += Item.Game(game)
            }
            adapter.submit(items)
        }
    }

    private inner class ScoresAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()

        fun submit(newItems: List<Item>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.group_header)
        }

        inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.channel_name)
            val sub: TextView = view.findViewById(R.id.channel_sub)
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Item.Header -> 0
            is Item.Game -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderHolder(inflater.inflate(R.layout.item_group_header, parent, false))
            } else {
                val view = inflater.inflate(R.layout.item_channel_row, parent, false)
                FontApplier.apply(view)
                RowHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder as HeaderHolder).text.text = item.text
                is Item.Game -> {
                    val row = holder as RowHolder
                    row.name.text = item.game.headline
                    row.sub.text = "${item.game.league} • ${item.game.status}"
                    // Score rows carry no channel art: hide the logo slot.
                    holder.itemView.findViewById<View>(R.id.channel_logo)?.visibility =
                        View.GONE
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
