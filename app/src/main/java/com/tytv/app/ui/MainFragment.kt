package com.tytv.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.tytv.app.R
import com.tytv.app.data.ProviderStore
import com.tytv.app.model.Channel
import com.tytv.app.parser.M3uParser
import com.tytv.app.settings.BufferSettings
import com.tytv.app.update.UpdateCheckResult
import com.tytv.app.update.UpdateChecker
import com.tytv.app.update.UpdateDialog
import com.tytv.app.update.UpdateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Leanback browse screen: a Setup row plus one row per provider group,
 * in the provider's original group order. Channels from every provider
 * are merged; duplicates are kept and tagged with their provider name.
 */
class MainFragment : BrowseSupportFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "TYTV"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = Color.parseColor("#111827")
        adapter = ArrayObjectAdapter(ListRowPresenter())
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is ActionCard -> when (item.action) {
                    ActionCard.ADD_PROVIDER ->
                        startActivity(Intent(requireContext(), AddProviderActivity::class.java))
                    ActionCard.BUFFER_SETTINGS ->
                        startActivity(Intent(requireContext(), BufferSettingsActivity::class.java))
                    ActionCard.CHECK_UPDATES -> checkForUpdatesManually()
                }
                is Channel -> startActivity(PlaybackActivity.intent(requireContext(), item))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadChannels()
    }

    /**
     * Manual check: always reports a result — an update dialog, an
     * "up to date" toast, or a "couldn't check" toast. Never silent.
     */
    private fun checkForUpdatesManually() {
        val activity = requireActivity()
        lifecycleScope.launch {
            when (val result = UpdateChecker.check(requireContext())) {
                is UpdateCheckResult.Available -> {
                    // requireActivity() is a FragmentActivity (host is MainActivity).
                    UpdateDialog.show(activity, result.info) {
                        UpdateFlow.start(activity, result.info)
                    }
                }
                is UpdateCheckResult.UpToDate ->
                    Toast.makeText(
                        requireContext(),
                        R.string.update_up_to_date,
                        Toast.LENGTH_SHORT
                    ).show()
                is UpdateCheckResult.Failed ->
                    Toast.makeText(
                        requireContext(),
                        R.string.update_check_failed,
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    private fun loadChannels() {
        lifecycleScope.launch {
            val providers = ProviderStore.getProviders(requireContext())
            val all = mutableListOf<Channel>()
            var failures = 0
            for (provider in providers) {
                try {
                    val text = withContext(Dispatchers.IO) { fetchUrl(provider.m3uUrl) }
                    all += M3uParser.parse(text, provider.name)
                } catch (e: Exception) {
                    failures++
                }
            }
            if (failures > 0) {
                Toast.makeText(
                    requireContext(),
                    "$failures provider(s) failed to load",
                    Toast.LENGTH_SHORT
                ).show()
            }
            buildRows(all)
        }
    }

    private fun buildRows(channels: List<Channel>) {
        val newAdapter = ArrayObjectAdapter(ListRowPresenter())
        var headerId = 0L
        val setupAdapter = ArrayObjectAdapter(CardPresenter()).apply {
            add(ActionCard("Add provider", "Add an M3U playlist URL", ActionCard.ADD_PROVIDER))
            add(
                ActionCard(
                    "Buffer size",
                    "Current: ${BufferSettings.get(requireContext()).label}",
                    ActionCard.BUFFER_SETTINGS
                )
            )
            add(
                ActionCard(
                    getString(R.string.check_updates_title),
                    getString(
                        R.string.check_updates_version,
                        UpdateChecker.installedVersionName(requireContext()) ?: "?"
                    ),
                    ActionCard.CHECK_UPDATES
                )
            )
        }
        newAdapter.add(ListRow(HeaderItem(headerId++, "Setup"), setupAdapter))
        for ((group, groupChannels) in channels.groupBy { it.group }) {
            val rowAdapter = ArrayObjectAdapter(CardPresenter())
            for (channel in groupChannels) rowAdapter.add(channel)
            newAdapter.add(ListRow(HeaderItem(headerId++, group), rowAdapter))
        }
        adapter = newAdapter
    }

    private fun fetchUrl(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "TYTV/0.1")
        connection.inputStream.bufferedReader().use { return it.readText() }
    }
}
