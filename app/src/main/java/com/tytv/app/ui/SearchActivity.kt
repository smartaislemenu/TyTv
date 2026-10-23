package com.tytv.app.ui

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

/** Searches every loaded channel by name. D-pad friendly: type, results update live. */
class SearchActivity : FragmentActivity() {

    private lateinit var adapter: ChannelRowAdapter
    private lateinit var statusText: TextView
    private var allChannels: List<Channel> = emptyList()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        val input = findViewById<EditText>(R.id.search_input)
        val results = findViewById<RecyclerView>(R.id.search_results)
        statusText = findViewById(R.id.search_status)
        results.layoutManager = LinearLayoutManager(this)
        adapter = ChannelRowAdapter(
            onClick = { channel -> ChannelMenu.play(this, channel) },
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
