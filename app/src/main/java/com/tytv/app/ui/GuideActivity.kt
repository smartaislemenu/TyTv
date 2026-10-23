package com.tytv.app.ui

import android.app.AlertDialog
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
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.data.ChannelRepository
import com.tytv.app.data.ProviderStore
import com.tytv.app.epg.EpgLoader
import com.tytv.app.epg.EpgMatcher
import com.tytv.app.epg.EpgParser
import com.tytv.app.epg.EpgPreload
import com.tytv.app.epg.EpgProgramme
import com.tytv.app.model.CatchupUrl
import com.tytv.app.epg.EpgStore
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV guide: channels on the left, the selected channel's programmes
 * on the right, with the currently-airing programme highlighted.
 * D-pad left/right switches panes, up/down scrolls. Tapping the
 * programme that is on now plays the channel; tapping a future
 * programme shows its details.
 *
 * Guide data comes only from addresses the user entered (a
 * provider's own EPG address, the provider's own xmltv.php for Xtream
 * logins, or the global address in Setup). A channel with no matched
 * guide data says so — listings are never guessed.
 */
class GuideActivity : FragmentActivity() {

    private sealed class ProgrammeItem {
        data class DayHeader(val text: String) : ProgrammeItem()
        data class Row(val programme: EpgProgramme, val isCurrent: Boolean) : ProgrammeItem()
    }

    private lateinit var channelAdapter: GuideChannelAdapter
    private lateinit var programmeAdapter: ProgrammeAdapter
    private lateinit var channelTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private lateinit var clockText: TextView

    private var channels: List<Channel> = emptyList()
    private var selected: Channel? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            applyProgrammes()
            clockHandler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        clockText = findViewById(R.id.guide_clock)
        statusText = findViewById(R.id.guide_status)
        channelTitle = findViewById(R.id.guide_channel_title)
        emptyText = findViewById(R.id.guide_empty)
        val channelList = findViewById<RecyclerView>(R.id.guide_channels)
        val programmeList = findViewById<RecyclerView>(R.id.guide_programmes)
        channelList.layoutManager = LinearLayoutManager(this)
        programmeList.layoutManager = LinearLayoutManager(this)
        channelAdapter = GuideChannelAdapter { channel ->
            selected = channel
            applyProgrammes()
        }
        programmeAdapter = ProgrammeAdapter { programme, isCurrent ->
            val channel = selected ?: return@ProgrammeAdapter
            val now = System.currentTimeMillis()
            if (isCurrent) {
                ChannelMenu.play(this, channel)
            } else if (programme.endMs <= now) {
                // Past programme: offer catch-up when the provider
                // supports it, and say so plainly when it does not.
                val catchupUrl = CatchupUrl.build(channel, programme.startMs, now)
                val details = getString(
                    R.string.guide_details,
                    formatTimeRange(programme),
                    programme.description.ifEmpty {
                        getString(R.string.guide_no_description)
                    }
                )
                val builder = AlertDialog.Builder(this)
                    .setTitle(programme.title)
                    .setMessage(
                        if (catchupUrl == null) {
                            "$details\n\n${getString(R.string.guide_no_catchup)}"
                        } else {
                            details
                        }
                    )
                if (catchupUrl != null) {
                    builder.setPositiveButton(R.string.guide_watch_from_start) { _, _ ->
                        startActivity(
                            PlaybackActivity.intent(this, channel.copy(url = catchupUrl))
                        )
                    }
                }
                builder.setNegativeButton(R.string.recording_cancel, null).show()
            } else {
                // Future programme: watch live now, set a reminder,
                // or schedule a recording from the reminder alert.
                AlertDialog.Builder(this)
                    .setTitle(programme.title)
                    .setMessage(
                        getString(
                            R.string.guide_details,
                            formatTimeRange(programme),
                            programme.description.ifEmpty {
                                getString(R.string.guide_no_description)
                            }
                        )
                    )
                    .setPositiveButton(R.string.channel_menu_play) { _, _ ->
                        ChannelMenu.play(this, channel)
                    }
                    .setNeutralButton(R.string.reminder_remind_me) { _, _ ->
                        ReminderScheduler.setReminder(this, channel, programme, false)
                    }
                    .setNegativeButton(R.string.reminder_record_it) { _, _ ->
                        ReminderScheduler.setReminder(this, channel, programme, true)
                    }
                    .show()
            }
        }
        channelList.adapter = channelAdapter
        programmeList.adapter = programmeAdapter
        updateClock()
        load()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
    }

    private fun updateClock() {
        val now = Date()
        clockText.text = SimpleDateFormat("EEEE, MMM d • h:mm a", Locale.US).format(now)
    }

    private fun load() {
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.guide_loading)
        lifecycleScope.launch {
            val outcome = ChannelRepository.loadAll(this@GuideActivity, false)
            val live = outcome.channels.filter {
                it.kind == ChannelKind.LIVE && !ChannelPrefs.isHidden(this@GuideActivity, it)
            }
            channels = live
            // Guides first from each provider's cache file (instant),
            // then fresh from the network (replaces the cache index).
            val providers = ProviderStore.getProviders(this@GuideActivity)
            withContext(Dispatchers.IO) {
                for (provider in providers) {
                    try {
                        val cache = EpgStore.cacheFile(this@GuideActivity, provider.name)
                        if (cache.exists()) {
                            val data = cache.inputStream().use { EpgParser.parse(it) }
                            EpgStore.merge(provider.name, data)
                        }
                    } catch (e: Exception) {
                    }
                }
                providers.map { provider ->
                    async { EpgLoader.loadForProvider(this@GuideActivity, provider) }
                }.awaitAll()
            }
            channelAdapter.submit(channels)
            statusText.visibility = View.GONE
            if (channels.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                emptyText.text = getString(R.string.home_empty)
            } else {
                selected = channels.first()
                applyProgrammes()
            }
        }
    }

    private fun applyProgrammes() {
        val channel = selected ?: return
        channelTitle.text = channel.name
        val now = System.currentTimeMillis()
        val programmes = EpgStore.findFor(channel)
        if (programmes.isNullOrEmpty()) {
            programmeAdapter.submit(emptyList())
            emptyText.visibility = View.VISIBLE
            // Say plainly WHY it is empty: no address configured at
            // all reads differently from an address that returned
            // nothing for this channel.
            emptyText.text = if (EpgPreload.noAddressProviders.contains(channel.provider)) {
                getString(R.string.guide_no_address, channel.provider)
            } else {
                getString(R.string.guide_no_data, channel.name)
            }
            return
        }
        emptyText.visibility = View.GONE
        val upcoming = programmes.filter { it.endMs > now }
        val items = mutableListOf<ProgrammeItem>()
        var currentDay = ""
        for (programme in upcoming) {
            val day = dayLabel(programme.startMs, now)
            if (day != currentDay) {
                currentDay = day
                items += ProgrammeItem.DayHeader(day)
            }
            val isCurrent = now >= programme.startMs && now < programme.endMs
            items += ProgrammeItem.Row(programme, isCurrent)
        }
        programmeAdapter.submit(items)
    }

    private fun dayLabel(startMs: Long, nowMs: Long): String {
        val startDay = Calendar.getInstance().apply { timeInMillis = startMs }
        val nowDay = Calendar.getInstance().apply { timeInMillis = nowMs }
        val tomorrow = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return when {
            startDay.get(Calendar.YEAR) == nowDay.get(Calendar.YEAR) &&
                startDay.get(Calendar.DAY_OF_YEAR) == nowDay.get(Calendar.DAY_OF_YEAR) ->
                getString(R.string.guide_today)
            startDay.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR) &&
                startDay.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR) ->
                getString(R.string.guide_tomorrow)
            else -> SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date(startMs))
        }
    }

    private fun formatTimeRange(programme: EpgProgramme): String {
        val format = SimpleDateFormat("h:mm a", Locale.US)
        return "${format.format(Date(programme.startMs))} – ${format.format(Date(programme.endMs))}"
    }

    private inner class GuideChannelAdapter(
        private val onSelected: (Channel) -> Unit
    ) : RecyclerView.Adapter<GuideChannelAdapter.Holder>() {

        private var items: List<Channel> = emptyList()

        fun submit(newItems: List<Channel>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.channel_name)
            val sub: TextView = view.findViewById(R.id.channel_sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel_row, parent, false)
            FontApplier.apply(view)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = items[position]
            holder.name.text = channel.name
            val now = System.currentTimeMillis()
            val current = EpgStore.findFor(channel)?.let { EpgMatcher.currentAt(it, now) }
            holder.sub.text = if (current != null) {
                getString(R.string.guide_now_playing, current.title)
            } else {
                getString(R.string.guide_no_data_short)
            }
            holder.itemView.setOnClickListener { onSelected(channel) }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class ProgrammeAdapter(
        private val onSelected: (EpgProgramme, Boolean) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<ProgrammeItem> = emptyList()

        fun submit(newItems: List<ProgrammeItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.group_header)
        }

        inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
            val time: TextView = view.findViewById(R.id.programme_time)
            val nowBadge: TextView = view.findViewById(R.id.programme_now)
            val title: TextView = view.findViewById(R.id.programme_title)
            val desc: TextView = view.findViewById(R.id.programme_desc)
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is ProgrammeItem.DayHeader -> 0
            is ProgrammeItem.Row -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = if (viewType == 0) {
                inflater.inflate(R.layout.item_group_header, parent, false)
            } else {
                inflater.inflate(R.layout.item_programme_row, parent, false)
            }
            FontApplier.apply(view)
            return if (viewType == 0) HeaderHolder(view) else RowHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ProgrammeItem.DayHeader -> {
                    (holder as HeaderHolder).text.text = item.text
                }
                is ProgrammeItem.Row -> {
                    val row = holder as RowHolder
                    row.time.text = formatTimeRange(item.programme)
                    row.nowBadge.visibility = if (item.isCurrent) View.VISIBLE else View.GONE
                    row.title.text = item.programme.title
                    row.desc.text = item.programme.description
                    holder.itemView.setOnClickListener {
                        onSelected(item.programme, item.isCurrent)
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
