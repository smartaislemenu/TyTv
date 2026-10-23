package com.tytv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.data.ChannelRepository
import com.tytv.app.data.ContinueStore
import com.tytv.app.data.RecentStore
import com.tytv.app.data.ScoresApi
import com.tytv.app.settings.AppPrefs
import com.tytv.app.epg.EpgMatcher
import com.tytv.app.epg.EpgPreload
import com.tytv.app.epg.EpgStore
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import com.tytv.app.model.GroupKind
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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
        FAVORITES("Favorites"),
        RECENT("Recent")
    }

    private sealed class DisplayItem {
        data class Header(val text: String) : DisplayItem()
        data class Row(val channel: Channel) : DisplayItem()
        /** Poster card for the Movies / Series grids. */
        data class Poster(val channel: Channel) : DisplayItem()
        /** Live score line in the Sports section (not a channel). */
        data class Score(val game: ScoresApi.Game) : DisplayItem()
    }

    companion object {
        private const val ARG_SECTION = "section"
        private const val ARG_FORCE_REFRESH = "force_refresh"

        fun newInstance(section: String, forceRefresh: Boolean): MainFragment {
            return MainFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION, section)
                    putBoolean(ARG_FORCE_REFRESH, forceRefresh)
                }
            }
        }
    }

    private lateinit var channelList: RecyclerView
    private lateinit var sectionTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var channelAdapter: ChannelAdapter

    private var allChannels: List<Channel> = emptyList()
    private var lastGroups: List<Pair<String, String>> = emptyList()
    private var liveScores: List<ScoresApi.Game> = emptyList()
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
        channelList = view.findViewById(R.id.channel_list)
        sectionTitle = view.findViewById(R.id.section_title)
        statusText = view.findViewById(R.id.status_text)

        channelList.layoutManager = LinearLayoutManager(requireContext())
        channelAdapter = ChannelAdapter(
            onClick = { channel -> ChannelMenu.play(requireContext(), channel) },
            onLongPress = { channel ->
                ChannelMenu.show(requireContext(), channel) { applySection() }
            },
            // The adapter is a plain nested class (Kotlin forbids class
            // declarations inside an inner class), so the section it
            // renders under arrives as a lambda read at bind time.
            currentSection = { currentSection }
        )
        channelList.adapter = channelAdapter

        // The section arrives from the home tile; the Refresh tile
        // asks for a forced reload instead of the cached load.
        val initialSection = try {
            Section.valueOf(arguments?.getString(ARG_SECTION) ?: "LIVE")
        } catch (e: IllegalArgumentException) {
            Section.LIVE
        }
        val startForceRefresh = arguments?.getBoolean(ARG_FORCE_REFRESH) ?: false
        switchSection(initialSection)
        channelList.post {
            (channelList.layoutManager as LinearLayoutManager)
                .findViewByPosition(0)?.requestFocus()
        }
        load(forceRefresh = startForceRefresh, silent = false)
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

    private fun switchSection(section: Section) {
        currentSection = section
        // The Sports section's scores strip loads lazily (and shares
        // the 60-second feed cache with the Scores screen).
        if (section == Section.SPORTS && AppPrefs.scoresEnabled(requireContext())) {
            lifecycleScope.launch {
                val live = ScoresApi.fetchLive()
                if (currentSection == Section.SPORTS) {
                    liveScores = live
                    applySection()
                }
            }
        }
        // Movies / Series browse as a poster grid (the reference
        // layout); every other section stays a plain list.
        if (section == Section.MOVIES || section == Section.SERIES) {
            val grid = GridLayoutManager(requireContext(), 4)
            grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (position < 0 || position >= channelAdapter.itemCount) return 4
                    return if (channelAdapter.getItemViewType(position) == 0) 4 else 1
                }
            }
            channelList.layoutManager = grid
        } else {
            channelList.layoutManager = LinearLayoutManager(requireContext())
        }
        applySection()
    }

    private fun load(forceRefresh: Boolean, silent: Boolean) {
        val context = requireContext()
        // Guide time offset from Settings, before any guide lookup.
        EpgStore.timeOffsetMs = EpgStore.getTimeOffsetHours(context) * 3_600_000L
        if (!silent) {
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.home_loading)
        }
        lifecycleScope.launch {
            // Progressive first load: show each provider's channels
            // the moment its batch finishes, with live parse counts
            // while it downloads — a big playlist no longer sits on
            // a blank screen until the whole thing is unpacked.
            val update = if (silent) null else object : ChannelRepository.LoadUpdate {
                override fun onProviderProgress(provider: String, channelsSoFar: Int) {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        statusText.visibility = View.VISIBLE
                        statusText.text = getString(
                            R.string.home_loading_provider, provider, channelsSoFar
                        )
                    }
                }

                override fun onProviderDone(provider: String, channels: List<Channel>) {
                    activity?.runOnUiThread {
                        if (!isAdded || channels.isEmpty()) return@runOnUiThread
                        allChannels = allChannels.filter { it.provider != provider } + channels
                        lastGroups = allChannels.map { it.provider to it.group }.distinct()
                        initialLoadDone = true
                        applySection()
                        if (allChannels.isNotEmpty()) statusText.visibility = View.GONE
                    }
                }
            }
            val outcome = ChannelRepository.loadAll(context, forceRefresh, update)
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
            // Load ended (content, empty state, or failure): the
            // splash has served its real-load purpose either way.
            (activity as? MainActivity)?.hideSplash()
            // First load came from cache: refresh live in the background
            // so the list is fresh without making launch wait on it.
            if (!forceRefresh && outcome.fromCache) refreshInBackground()
            // Guide preload: runs once per launch, right after the
            // channels land, so the Guide and the row now/next lines
            // already have data when the viewer opens them.
            lifecycleScope.launch(Dispatchers.IO) {
                if (EpgPreload.preload(context)) {
                    activity?.runOnUiThread { if (isAdded) applySection() }
                }
            }
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
            // Sports is a second view, not a move: a sports channel
            // stays in its original Live TV group and also appears
            // here. Detection covers league and network names, not
            // just groups with "sport" in them.
            Section.SPORTS -> visible.filter { GroupKind.isSports(it) }
            Section.FAVORITES -> {
                val keys = ChannelPrefs.favoriteKeys(context)
                visible.filter { keys.contains(it.provider to it.url) }
            }
            // Recent keeps recency order (flat, like Favorites) and
            // only shows live channels that still exist in the playlist.
            Section.RECENT -> {
                val live = visible.filter { it.kind == ChannelKind.LIVE }
                val byKey = live.associateBy { it.provider to it.url }
                RecentStore.keys(context).mapNotNull { byKey[it] }
            }
        }
        val items = mutableListOf<DisplayItem>()
        if (currentSection == Section.FAVORITES || currentSection == Section.RECENT) {
            for (channel in sectionChannels) items += DisplayItem.Row(channel)
        } else {
            // Continue watching leads the Movies grid: unfinished
            // movies first, in recency order, then the provider groups.
            if (currentSection == Section.MOVIES) {
                val byKey = sectionChannels.associateBy { it.provider to it.url }
                val resume = ContinueStore.list(context)
                    .mapNotNull { byKey[it.provider to it.url] }
                if (resume.isNotEmpty()) {
                    items += DisplayItem.Header(getString(R.string.continue_watching))
                    for (channel in resume) items += DisplayItem.Poster(channel)
                }
            }
            // Live scores lead the Sports section (when the feed
            // cooperated and the toggle is on). Score rows open the
            // full Scores screen; they are not channels and never play.
            if (currentSection == Section.SPORTS && liveScores.isNotEmpty()) {
                items += DisplayItem.Header(getString(R.string.scores_live_now))
                for (game in liveScores.take(12)) items += DisplayItem.Score(game)
            }
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
                // Numbered channels sort by their provider number;
                // unnumbered ones keep playlist order (stable sort).
                val ordered = groupChannels.sortedWith(
                    compareBy<Channel> { it.number == null }.thenBy { it.number }
                )
                val posterSection = currentSection == Section.MOVIES ||
                    currentSection == Section.SERIES
                for (channel in ordered) {
                    items += if (posterSection) {
                        DisplayItem.Poster(channel)
                    } else {
                        DisplayItem.Row(channel)
                    }
                }
            }
        }
        channelAdapter.submit(items)
        // First rendered content dismisses the boot splash (real
        // load only — hideSplash is idempotent, so later section
        // switches are harmless no-ops).
        if (items.isNotEmpty()) (activity as? MainActivity)?.hideSplash()
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

    private class ChannelAdapter(
        private val onClick: (Channel) -> Unit,
        private val onLongPress: (Channel) -> Unit,
        private val currentSection: () -> Section
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
            val logo: ImageView = view.findViewById(R.id.channel_logo)
            val name: TextView = view.findViewById(R.id.channel_name)
            val sub: TextView = view.findViewById(R.id.channel_sub)
        }

        class PosterHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.poster_image)
            val title: TextView = view.findViewById(R.id.poster_title)
            val progress: android.widget.ProgressBar =
                view.findViewById(R.id.poster_progress)
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is DisplayItem.Header -> 0
            is DisplayItem.Row -> 1
            is DisplayItem.Poster -> 2
            is DisplayItem.Score -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = when (viewType) {
                0 -> inflater.inflate(R.layout.item_group_header, parent, false)
                2 -> inflater.inflate(R.layout.item_poster_card, parent, false)
                else -> inflater.inflate(R.layout.item_channel_row, parent, false)
            }
            FontApplier.apply(view)
            return when (viewType) {
                0 -> HeaderHolder(view)
                2 -> PosterHolder(view)
                else -> RowHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is DisplayItem.Header -> {
                    (holder as HeaderHolder).text.text = item.text
                }
                is DisplayItem.Row -> {
                    val row = holder as RowHolder
                    val channel = item.channel
                    row.name.text = if (channel.number != null) {
                        "#${channel.number} • ${channel.name}"
                    } else {
                        channel.name
                    }
                    // Recycled holders may carry a hidden logo from a
                    // score row; restore it before loading this logo.
                    row.logo.visibility = View.VISIBLE
                    LogoLoader.load(row.logo, channel.logo)
                    val guideLine = nowNextLine(channel)
                    row.sub.text = when {
                        // Sports rows carry a red LIVE badge while a
                        // programme is on, like the reference cards.
                        guideLine != null && currentSection() == Section.SPORTS -> {
                            val badge = SpannableString("● LIVE  ")
                            badge.setSpan(
                                ForegroundColorSpan(0xFFFF5252.toInt()),
                                0, badge.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            SpannableStringBuilder(badge).append(guideLine)
                        }
                        guideLine != null -> guideLine
                        else -> "${channel.group} • ${channel.provider}"
                    }
                    holder.itemView.setOnClickListener { onClick(channel) }
                    holder.itemView.setOnLongClickListener {
                        onLongPress(channel)
                        true
                    }
                }
                is DisplayItem.Score -> {
                    val row = holder as RowHolder
                    val game = item.game
                    row.logo.visibility = View.GONE
                    row.name.text = game.headline
                    row.sub.text = "${game.league} • ${game.status}"
                    holder.itemView.setOnClickListener {
                        val context = holder.itemView.context
                        context.startActivity(Intent(context, ScoresActivity::class.java))
                    }
                    holder.itemView.setOnLongClickListener(null)
                }
                is DisplayItem.Poster -> {
                    val poster = holder as PosterHolder
                    val channel = item.channel
                    poster.title.text = channel.name
                    LogoLoader.load(poster.image, channel.logo, 360)
                    val saved = ContinueStore.get(
                        holder.itemView.context, channel.provider, channel.url
                    )
                    if (saved != null) {
                        poster.progress.visibility = View.VISIBLE
                        poster.progress.progress = (saved.fraction * 100).toInt()
                    } else {
                        poster.progress.visibility = View.GONE
                    }
                    holder.itemView.setOnClickListener { onClick(channel) }
                    holder.itemView.setOnLongClickListener {
                        onLongPress(channel)
                        true
                    }
                }
            }
        }

        /** "Now: X • Next: Y" from the loaded guide, or null when the
         *  guide has nothing for this channel yet. Never blocks: the
         *  lookup is in-memory only, and the row falls back to
         *  "group • provider" when it comes back empty. */
        private fun nowNextLine(channel: Channel): String? {
            val programmes = try {
                EpgStore.findFor(channel)
            } catch (e: Exception) {
                null
            } ?: return null
            if (programmes.isEmpty()) return null
            val now = System.currentTimeMillis()
            val current = EpgMatcher.currentAt(programmes, now) ?: return null
            val next = EpgMatcher.nextAfter(programmes, now)
            return if (next != null) {
                "Now: ${current.title} • Next: ${next.title}"
            } else {
                "Now: ${current.title}"
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
