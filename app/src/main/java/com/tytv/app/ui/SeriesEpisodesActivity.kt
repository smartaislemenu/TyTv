package com.tytv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.data.ProviderStore
import com.tytv.app.data.ProviderType
import com.tytv.app.data.XtreamClient
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import kotlinx.coroutines.launch

/** Episode list for one Xtream series. Tapping an episode plays it. */
class SeriesEpisodesActivity : FragmentActivity() {

    companion object {
        private const val EXTRA_PROVIDER = "extra_provider"
        private const val EXTRA_SERIES_ID = "extra_series_id"
        private const val EXTRA_SERIES_NAME = "extra_series_name"

        fun intent(
            context: Context,
            providerName: String,
            seriesId: String,
            seriesName: String
        ): Intent {
            return Intent(context, SeriesEpisodesActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER, providerName)
                putExtra(EXTRA_SERIES_ID, seriesId)
                putExtra(EXTRA_SERIES_NAME, seriesName)
            }
        }
    }

    private lateinit var adapter: ChannelRowAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)
        val providerName = intent.getStringExtra(EXTRA_PROVIDER) ?: ""
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""
        val seriesName = intent.getStringExtra(EXTRA_SERIES_NAME) ?: ""
        findViewById<TextView>(R.id.list_title).text = seriesName
        val list = findViewById<RecyclerView>(R.id.list_items)
        emptyText = findViewById(R.id.list_empty)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ChannelRowAdapter(
            onClick = { channel -> ChannelMenu.play(this, channel) }
        )
        list.adapter = adapter
        emptyText.visibility = View.VISIBLE
        emptyText.text = getString(R.string.home_loading)
        lifecycleScope.launch {
            val provider = ProviderStore.providerByName(this@SeriesEpisodesActivity, providerName)
            if (provider == null || provider.type != ProviderType.XTREAM) {
                emptyText.text = getString(R.string.series_provider_missing)
                return@launch
            }
            val result = XtreamClient.fetchSeriesEpisodes(
                provider.server, provider.username, provider.password, seriesId
            )
            result.onSuccess { episodes ->
                val channels = episodes.map { episode ->
                    Channel(
                        name = episode.name,
                        url = episode.url,
                        group = seriesName,
                        logo = null,
                        provider = providerName,
                        kind = ChannelKind.MOVIE
                    )
                }
                adapter.submit(channels)
                emptyText.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
                if (channels.isEmpty()) emptyText.text = getString(R.string.series_no_episodes)
            }.onFailure { e ->
                emptyText.text = getString(R.string.series_load_failed, e.message ?: "")
                Toast.makeText(
                    this@SeriesEpisodesActivity,
                    getString(R.string.series_load_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
