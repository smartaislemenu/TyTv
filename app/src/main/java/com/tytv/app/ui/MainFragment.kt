package com.tytv.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.data.ChannelRepository
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import com.tytv.app.update.UpdateCheckResult
import com.tytv.app.update.UpdateChecker
import com.tytv.app.update.UpdateDialog
import com.tytv.app.update.UpdateFlow
import kotlinx.coroutines.launch

/**
 * Apollo-style home screen: a category sidebar on the left and a
 * fast-scrolling windowed list on the right. The list is a
 * RecyclerView, so only visible rows are bound — thousands of
 * channels scroll smoothly, unlike the old Leanback rows that built
 * one card per channel for the whole playlist.
 *
 * Sections: Live TV, Movies, Series, Sports, Favorites. Sidebar also
 * holds Search, Recordings, Setup, and Refresh. Channels load from
 * the on-device cache instantly and refresh in the background, so a
 * relaunch never sits on an empty screen while a multi-MB playlist
 * downloads again. Favorites, hidden channels, and group renames
 * work exactly as before (same on-device storage).
 */
class MainFragment : Fragment() {

    private enum class Section(val title: String) {
        LIVE("Live TV"),
        MOVIES("Movies"),
        SERIES("Series"),
        SPORTS("Sports"),
        FAVORITES("Favorites")
    }

    private sealed class DisplayItem {
        data class Header(val text: String) : DisplayItem()
        data class Row(val channel: Channel) : DisplayItem()
    }

    private val sidebarTitles = listOf(
        "Live TV", "Movies", "Series", "Sports", "Favorites",
        "Search", "Recordings", "Setup", "Refresh"
    )

    private lateinit var sidebarList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var sectionTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var channelAdapter: ChannelAdapter

    private var allChannels: List<Channel> = emptyList()
    private var lastGroups: List<Pair<String, String>> = emptyList()
    private var currentSection = Section.LIVE
    private var lastRefreshMs = 0L
    private var initialLoadDone = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sidebarList = view.findViewById(R.id.sidebar_list)
        channelList = view.findViewById(R.id.channel_list)
        sectionTitle = view.findViewById(R.id.section_title)
        statusText = view.findViewById(R.id.status_text)

        sidebarList.layoutManager = LinearLayoutManager(requireContext())
        sidebarList.adapter = SidebarAdapter(sidebarTitles) { position ->
            onSidebarSelected(position)
        }
        channelList.layoutManager = LinearLayoutManager(requireContext())
        channelAdapter = ChannelAdapter(
            onClick = { channel -> ChannelMenu.play(requireContext(), channel) },
            onLongPress = { channel ->
                ChannelMenu.show(requireContext(), channel) { applySection() }
            }
        )
        channelList.adapter = channelAdapter

        sidebarList.post {
            (sidebarList.layoutManager as LinearLayoutManager)
                .findViewByPosition(0)?.requestFocus()
        }
        load(forceRefresh = false, silent = false)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply so renames/favorites changed elsewhere show at once.
        if (initialLoadDone) applySection()
        val now = System.currentTimeMillis()
        if (initialLoadDone && now - lastRefreshMs > 60_000) {
            refreshInBackground()
        }
    }

    private fun onSidebarSelected(position: Int) {
        when (position) {
            0 -> switchSection(Section.LIVE)
            1 -> switchSection(Section.MOVIES)
            2 -> switchSection(Section.SERIES)
            3 -> switchSection(Section.SPORTS)
            4 -> switchSection(Section.FAVORITES)
            5 -> startActivity(Intent(requireContext(), SearchActivity::class.java))
            6 -> startActivity(Intent(requireContext(), RecordingsActivity::class.java))
            7 -> showSetupMenu()
            8 -> load(forceRefresh = true, silent = false)
        }
    }

    private fun switchSection(section: Section) {
        currentSection = section
        applySection()
    }

    private fun load(forceRefresh: Boolean, silent: Boolean) {
        val context = requireContext()
        if (!silent) {
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.home_loading)
        }
        lifecycleScope.launch {
            val outcome = ChannelRepository.loadAll(context, forceRefresh)
            allChannels = outcome.channels
            lastGroups = allChannels.map { it.provider to it.group }.distinct()
            lastRefreshMs = System.currentTimeMillis()
            initialLoadDone = true
            applySection()
            if (outcome.channels.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = if (outcome.failures.isEmpty()) {
                    getString(R.string.home_empty)
                } else {
                    getString(R.string.home_load_failed, outcome.failures.joinToString("\n"))
                }
            } else {
                statusText.visibility = View.GONE
                if (!silent) {
                    for (failure in outcome.failures) {
                        Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                    }
                }
            }
            // First load came from cache: refresh live in the background
            // so the list is fresh without making launch wait on it.
            if (!forceRefresh && outcome.fromCache) refreshInBackground()
        }
    }

    private fun refreshInBackground() {
        val context = requireContext()
        lifecycleScope.launch {
            val outcome = ChannelRepository.loadAll(context, true)
            if (outcome.channels.isNotEmpty()) {
                allChannels = outcome.channels
                lastGroups = allChannels.map { it.provider to it.group }.distinct()
                lastRefreshMs = System.currentTimeMillis()
                applySection()
            }
        }
    }

    private fun applySection() {
        val context = requireContext()
        sectionTitle.text = currentSection.title
        val visible = allChannels.filter { !ChannelPrefs.isHidden(context, it) }
        val sectionChannels = when (currentSection) {
            Section.LIVE -> visible.filter { it.kind == ChannelKind.LIVE }
            Section.MOVIES -> visible.filter { it.kind == ChannelKind.MOVIE }
            Section.SERIES -> visible.filter { it.kind == ChannelKind.SERIES }
            Section.SPORTS -> visible.filter {
                it.kind == ChannelKind.LIVE &&
                    (it.group.contains("sport", ignoreCase = true) ||
                        it.name.contains("sport", ignoreCase = true))
            }
            Section.FAVORITES -> {
                val keys = ChannelPrefs.favoriteKeys(context)
                visible.filter { keys.contains(it.provider to it.url) }
            }
        }
        val items = mutableListOf<DisplayItem>()
        if (currentSection == Section.FAVORITES) {
            for (channel in sectionChannels) items += DisplayItem.Row(channel)
        } else {
            val providersPerGroup = sectionChannels.groupBy { it.group }
                .mapValues { entry -> entry.value.map { it.provider }.distinct() }
            for ((providerAndGroup, groupChannels) in
                sectionChannels.groupBy { it.provider to it.group }) {
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
                items += DisplayItem.Header(header)
                for (channel in groupChannels) items += DisplayItem.Row(channel)
            }
        }
        channelAdapter.submit(items)
        if (sectionChannels.isEmpty() && allChannels.isNotEmpty()) {
            statusText.visibility = View.VISIBLE
            statusText.text = when (currentSection) {
                Section.SPORTS -> getString(R.string.home_sports_empty)
                Section.FAVORITES -> getString(R.string.favorites_empty_description)
                else -> getString(R.string.home_section_empty)
            }
        } else if (allChannels.isNotEmpty()) {
            statusText.visibility = View.GONE
        }
    }

    private fun showSetupMenu() {
        val context = requireContext()
        val items = arrayOf(
            getString(R.string.add_provider_title),
            getString(R.string.buffer_title),
            getString(R.string.check_updates_title),
            getString(R.string.hidden_channels_title),
            getString(R.string.rename_groups_title)
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.setup_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(context, AddProviderActivity::class.java))
                    1 -> startActivity(Intent(context, BufferSettingsActivity::class.java))
                    2 -> checkForUpdatesManually()
                    3 -> startActivity(Intent(context, ManageHiddenActivity::class.java))
                    4 -> startActivity(
                        RenameGroupsActivity.intent(context, lastGroups)
                    )
                }
            }
            .show()
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

    private class SidebarAdapter(
        private val titles: List<String>,
        private val onSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<SidebarAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.sidebar_title)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sidebar, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.title.text = titles[position]
            holder.itemView.setOnClickListener { onSelected(position) }
        }

        override fun getItemCount(): Int = titles.size
    }

    private class ChannelAdapter(
        private val onClick: (Channel) -> Unit,
        private val onLongPress: (Channel) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<DisplayItem> = emptyList()

        fun submit(newItems: List<DisplayItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.group_header)
        }

        class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.channel_name)
            val sub: TextView = view.findViewById(R.id.channel_sub)
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is DisplayItem.Header -> 0
            is DisplayItem.Row -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderHolder(inflater.inflate(R.layout.item_group_header, parent, false))
            } else {
                RowHolder(inflater.inflate(R.layout.item_channel_row, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is DisplayItem.Header -> {
                    (holder as HeaderHolder).text.text = item.text
                }
                is DisplayItem.Row -> {
                    val row = holder as RowHolder
                    row.name.text = item.channel.name
                    row.sub.text = "${item.channel.group} • ${item.channel.provider}"
                    holder.itemView.setOnClickListener { onClick(item.channel) }
                    holder.itemView.setOnLongClickListener {
                        onLongPress(item.channel)
                        true
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
