package com.tytv.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.data.ChannelRepository
import com.tytv.app.model.Channel
import kotlinx.coroutines.launch

/** Searches every loaded channel by name. D-pad friendly: type, results update live.
 *
 * Pick mode (EXTRA_PICK): tapping a result returns it to the caller
 * instead of playing it — used by multi-view tile filling.
 */
class SearchActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PICK = "extra_pick"
        private const val RESULT_NAME = "picked_name"
        private const val RESULT_URL = "picked_url"
        private const val RESULT_GROUP = "picked_group"
        private const val RESULT_PROVIDER = "picked_provider"
        private const val RESULT_TVG_ID = "picked_tvg_id"
        private const val RESULT_NUMBER = "picked_number"
        private const val RESULT_KIND = "picked_kind"

        fun pickedChannel(data: Intent): Channel? {
            val url = data.getStringExtra(RESULT_URL) ?: return null
            val name = data.getStringExtra(RESULT_NAME) ?: return null
            return Channel(
                name = name,
                url = url,
                group = data.getStringExtra(RESULT_GROUP) ?: "",
                logo = null,
                provider = data.getStringExtra(RESULT_PROVIDER) ?: "",
                tvgId = data.getStringExtra(RESULT_TVG_ID),
                number = if (data.hasExtra(RESULT_NUMBER)) {
                    data.getIntExtra(RESULT_NUMBER, -1).takeIf { it > 0 }
                } else null,
                kind = try {
                    com.tytv.app.model.ChannelKind.valueOf(
                        data.getStringExtra(RESULT_KIND) ?: "LIVE"
                    )
                } catch (e: Exception) {
                    com.tytv.app.model.ChannelKind.LIVE
                }
            )
        }
    }

    private lateinit var adapter: ChannelRowAdapter
    private lateinit var statusText: TextView
    private var allChannels: List<Channel> = emptyList()
    private var query: String = ""
    private var pickMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        val input = findViewById<EditText>(R.id.search_input)
        val results = findViewById<RecyclerView>(R.id.search_results)
        statusText = findViewById(R.id.search_status)
        pickMode = intent.getBooleanExtra(EXTRA_PICK, false)
        results.layoutManager = LinearLayoutManager(this)
        adapter = ChannelRowAdapter(
            onClick = { channel ->
                if (pickMode) {
                    val result = Intent().apply {
                        putExtra(RESULT_NAME, channel.name)
                        putExtra(RESULT_URL, channel.url)
                        putExtra(RESULT_GROUP, channel.group)
                        putExtra(RESULT_PROVIDER, channel.provider)
                        putExtra(RESULT_TVG_ID, channel.tvgId)
                        channel.number?.let { putExtra(RESULT_NUMBER, it) }
                        putExtra(RESULT_KIND, channel.kind.name)
                    }
                    setResult(RESULT_OK, result)
                    finish()
                } else {
                    ChannelMenu.play(this, channel)
                }
            },
            onLongPress = { channel ->
                ChannelMenu.show(this, channel) { applyFilter() }
            }
        )
        results.adapter = adapter
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.home_loading)
        lifecycleScope.launch {
            val outcome = ChannelRepository.loadAll(this@SearchActivity, false)
            allChannels = outcome.channels
            applyFilter()
            if (allChannels.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.home_empty)
            }
        }
    }

    private fun applyFilter() {
        val visible = allChannels.filter { !ChannelPrefs.isHidden(this, it) }
        val filtered = if (query.isEmpty()) {
            emptyList()
        } else {
            visible.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.submit(filtered)
        when {
            filtered.isNotEmpty() -> statusText.visibility = View.GONE
            query.isNotEmpty() -> {
                statusText.text = getString(R.string.search_no_results, query)
                statusText.visibility = View.VISIBLE
            }
            allChannels.isNotEmpty() -> statusText.visibility = View.GONE
            // Otherwise the loading/empty text set in onCreate stays.
        }
    }
}
