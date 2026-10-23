package com.tytv.app.ui

import android.app.AlertDialog
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
import com.tytv.app.data.ChannelPrefs
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
 * Leanback browse screen: a Setup row, a Favorites row, then one row per
 * provider group, in the provider's original group order. Channels from
 * every provider are merged; duplicates are kept and tagged with their
 * provider name.
 *
 * Long-press (remote select held down) on a channel card opens a menu:
 * Play, Add/Remove Favorite, Hide channel, Rename group. Hidden channels
 * appear in no row; they are brought back from Setup → Hidden channels.
 * A group's row header shows the provider's ORIGINAL group name first,
 * with the user's label alongside it — the original is never replaced.
 */
class MainFragment : BrowseSupportFragment() {

    private val cardPresenter = CardPresenter().apply {
        onChannelLongPress = { channel -> showChannelMenu(channel) }
    }

    /** (provider, original group) pairs from the last load, for the rename screen. */
    private var lastGroups: List<Pair<String, String>> = emptyList()

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
                    ActionCard.HIDDEN_CHANNELS ->
                        startActivity(Intent(requireContext(), ManageHiddenActivity::class.java))
                    ActionCard.RENAME_GROUPS ->
                        startActivity(RenameGroupsActivity.intent(requireContext(), lastGroups))
                    ActionCard.FAVORITES_HELP ->
                        Toast.makeText(
                            requireContext(),
                            R.string.favorites_help,
                            Toast.LENGTH_LONG
                        ).show()
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
        val context = requireContext()
        lastGroups = channels.map { it.provider to it.group }.distinct()
        val visible = channels.filter { !ChannelPrefs.isHidden(context, it) }
        val favoriteKeys = ChannelPrefs.favoriteKeys(context)

        val newAdapter = ArrayObjectAdapter(ListRowPresenter())
        var headerId = 0L
        val setupAdapter = ArrayObjectAdapter(cardPresenter).apply {
            add(ActionCard("Add provider", "Add an M3U playlist URL", ActionCard.ADD_PROVIDER))
            add(
                ActionCard(
                    "Buffer size",
                    "Current: ${BufferSettings.get(context).label}",
                    ActionCard.BUFFER_SETTINGS
                )
            )
            add(
                ActionCard(
                    getString(R.string.check_updates_title),
                    getString(
                        R.string.check_updates_version,
                        UpdateChecker.installedVersionName(context) ?: "?"
                    ),
                    ActionCard.CHECK_UPDATES
                )
            )
            add(
                ActionCard(
                    getString(R.string.hidden_channels_title),
                    getString(R.string.hidden_channels_description),
                    ActionCard.HIDDEN_CHANNELS
                )
            )
            add(
                ActionCard(
                    getString(R.string.rename_groups_title),
                    getString(R.string.rename_groups_description),
                    ActionCard.RENAME_GROUPS
                )
            )
        }
        newAdapter.add(ListRow(HeaderItem(headerId++, "Setup"), setupAdapter))

        // Favorites row, always present so it is easy to find. A favorited
        // channel that is hidden stays hidden (hidden wins everywhere).
        val favoritesAdapter = ArrayObjectAdapter(cardPresenter)
        val favoriteChannels = visible.filter { favoriteKeys.contains(it.provider to it.url) }
        if (favoriteChannels.isEmpty()) {
            favoritesAdapter.add(
                ActionCard(
                    getString(R.string.favorites_empty_title),
                    getString(R.string.favorites_empty_description),
                    ActionCard.FAVORITES_HELP
                )
            )
        } else {
            for (channel in favoriteChannels) favoritesAdapter.add(channel)
        }
        newAdapter.add(
            ListRow(
                HeaderItem(headerId++, getString(R.string.favorites_title)),
                favoritesAdapter
            )
        )

        // One row per provider group, in first-appearance order. Grouping by
        // (provider, group) keeps renames per-provider: two providers with
        // the same group name get separate rows, and a shared group name
        // shows its provider in the header so the rows stay tellable apart.
        val providersPerGroup = visible.groupBy { it.group }
            .mapValues { entry -> entry.value.map { it.provider }.distinct() }
        for ((providerAndGroup, groupChannels) in visible.groupBy { it.provider to it.group }) {
            val (provider, group) = providerAndGroup
            val display = ChannelPrefs.displayGroup(
                group,
                ChannelPrefs.getLabel(context, provider, group)
            )
            val header = if ((providersPerGroup[group]?.size ?: 0) > 1) {
                "$display • $provider"
            } else {
                display
            }
            val rowAdapter = ArrayObjectAdapter(cardPresenter)
            for (channel in groupChannels) rowAdapter.add(channel)
            newAdapter.add(ListRow(HeaderItem(headerId++, header), rowAdapter))
        }
        adapter = newAdapter
    }

    /**
     * Channel menu for a long-pressed card. A plain list dialog: D-pad
     * up/down moves between items, select runs one, back dismisses it.
     * Favorite/hide changes reload the rows so the Favorites row and the
     * group rows update immediately.
     */
    private fun showChannelMenu(channel: Channel) {
        val context = requireContext()
        val isFavorite = ChannelPrefs.isFavorite(context, channel)
        val items = arrayOf(
            getString(R.string.channel_menu_play),
            getString(
                if (isFavorite) {
                    R.string.channel_menu_remove_favorite
                } else {
                    R.string.channel_menu_add_favorite
                }
            ),
            getString(R.string.channel_menu_hide),
            getString(R.string.channel_menu_rename_group)
        )
        AlertDialog.Builder(context)
            .setTitle(channel.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(PlaybackActivity.intent(context, channel))
                    1 -> {
                        ChannelPrefs.setFavorite(context, channel, !isFavorite)
                        Toast.makeText(
                            context,
                            if (isFavorite) {
                                R.string.channel_unfavorited
                            } else {
                                R.string.channel_favorited
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                        loadChannels()
                    }
                    2 -> {
                        ChannelPrefs.hide(context, channel)
                        Toast.makeText(
                            context,
                            R.string.channel_hidden,
                            Toast.LENGTH_LONG
                        ).show()
                        loadChannels()
                    }
                    3 -> startActivity(
                        RenameGroupActivity.intent(context, channel.provider, channel.group)
                    )
                }
            }
            .show()
    }

    private fun fetchUrl(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "TYTV/0.1")
        connection.inputStream.bufferedReader().use { return it.readText() }
    }
}
