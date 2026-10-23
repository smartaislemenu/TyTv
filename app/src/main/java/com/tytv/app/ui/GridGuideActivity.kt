package com.tytv.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.data.ChannelPrefs
import com.tytv.app.data.ChannelRepository
import com.tytv.app.epg.EpgProgramme
import com.tytv.app.epg.EpgStore
import com.tytv.app.model.CatchupUrl
import com.tytv.app.model.Channel
import com.tytv.app.model.ChannelKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Cable-style grid guide: channels down the left, a 3-hour timeline
 * across the top, programme blocks in between. Every row shares one
 * horizontal scroll position so the timeline stays aligned; the
 * channel labels stay fixed while the blocks scroll. ON NOW blocks
 * are gold-tinted. Tapping a current programme plays its channel;
 * tapping a future one shows its details.
 *
 * Rows are a RecyclerView (recycled), so even a multi-thousand
 * channel list stays smooth — only visible rows build blocks.
 */
class GridGuideActivity : FragmentActivity() {

    /** 30 minutes of timeline per this many dp. */
    private val dpPerHalfHour = 200f
    private val windowHours = 3

    private lateinit var headerScroll: HorizontalScrollView
    private lateinit var headerRow: LinearLayout
    private lateinit var rowsView: RecyclerView
    private var sharedScrollX = 0
    private var syncing = false

    private var windowStartMs = 0L
    private var windowEndMs = 0L
    private var channels: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid_guide)
        headerScroll = findViewById(R.id.grid_header_scroll)
        headerRow = findViewById(R.id.grid_header_row)
        rowsView = findViewById(R.id.grid_rows)
        rowsView.layoutManager = LinearLayoutManager(this)
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        windowStartMs = cal.timeInMillis
        windowEndMs = windowStartMs + windowHours * 3_600_000L
        buildHeader()
        lifecycleScope.launch {
            val outcome = ChannelRepository.loadAll(this@GridGuideActivity, false)
            val live = outcome.channels.filter {
                it.kind == ChannelKind.LIVE && !ChannelPrefs.isHidden(this@GridGuideActivity, it)
            }
            channels = SurfOrder.liveOrder(live)
            rowsView.adapter = GridAdapter()
            if (channels.isEmpty()) {
                Toast.makeText(
                    this@GridGuideActivity,
                    R.string.home_empty,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()

    /** Time labels at each 30-minute mark, sized to the block scale. */
    private fun buildHeader() {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val cal = Calendar.getInstance().apply { timeInMillis = windowStartMs }
        while (cal.timeInMillis < windowEndMs) {
            val label = TextView(this).apply {
                text = fmt.format(cal.time)
                setTextColor(0xFF9CA3AF.toInt())
                textSize = 14f
                setPadding(dpToPx(4f), dpToPx(4f), 0, dpToPx(4f))
            }
            label.layoutParams = LinearLayout.LayoutParams(
                dpToPx(dpPerHalfHour), LinearLayout.LayoutParams.WRAP_CONTENT
            )
            headerRow.addView(label)
            cal.add(Calendar.MINUTE, 30)
        }
    }

    /** Programmes overlapping the window, clipped to it. */
    private fun windowProgrammes(channel: Channel): List<EpgProgramme> {
        val programmes = try {
            EpgStore.findFor(channel)
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return programmes.filter { it.endMs > windowStartMs && it.startMs < windowEndMs }
    }

    /** Pushes one horizontal scroll position to every row + the header. */
    private fun syncScrollFrom(source: HorizontalScrollView) {
        if (syncing) return
        val x = source.scrollX
        if (x == sharedScrollX) return
        syncing = true
        sharedScrollX = x
        headerScroll.scrollTo(x, 0)
        for (i in 0 until rowsView.childCount) {
            val row = rowsView.getChildAt(i)
            val scroll = row?.findViewById<HorizontalScrollView>(R.id.grid_row_scroll)
            if (scroll != null && scroll != source) scroll.scrollTo(x, 0)
        }
        syncing = false
    }

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.grid_channel_name)
            val scroll: HorizontalScrollView = view.findViewById(R.id.grid_row_scroll)
            val blocks: LinearLayout = view.findViewById(R.id.grid_row_blocks)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_grid_row, parent, false)
            FontApplier.apply(view)
            val holder = Holder(view)
            holder.scroll.setOnScrollChangeListener { v, _, _, _, _ ->
                syncScrollFrom(v as HorizontalScrollView)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = channels[position]
            holder.name.text = channel.name
            holder.blocks.removeAllViews()
            val programmes = windowProgrammes(channel)
            if (programmes.isEmpty()) {
                val empty = TextView(holder.itemView.context).apply {
                    text = holder.itemView.context.getString(R.string.grid_no_data)
                    setTextColor(0xFF6B7280.toInt())
                    textSize = 14f
                    setPadding(dpToPx(8f), dpToPx(12f), 0, dpToPx(12f))
                }
                holder.blocks.addView(empty)
            } else {
                val now = System.currentTimeMillis()
                val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                for (programme in programmes) {
                    val clipStart = maxOf(programme.startMs, windowStartMs)
                    val clipEnd = minOf(programme.endMs, windowEndMs)
                    val minutes = ((clipEnd - clipStart) / 60_000L).toFloat()
                    val widthDp = maxOf(minutes * (dpPerHalfHour / 30f), 80f)
                    val isCurrent = now >= programme.startMs && now < programme.endMs
                    val block = TextView(holder.itemView.context).apply {
                        text = "${fmt.format(java.util.Date(programme.startMs))}\n${programme.title}"
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 14f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
                        isFocusable = true
                        background = holder.itemView.context.getDrawable(
                            if (isCurrent) R.drawable.grid_now_bg
                            else R.drawable.item_focus_bg
                        )
                        setOnClickListener {
                            if (isCurrent) {
                                ChannelMenu.play(holder.itemView.context, channel)
                            } else {
                                showDetails(channel, programme, fmt)
                            }
                        }
                    }
                    block.layoutParams = LinearLayout.LayoutParams(
                        dpToPx(widthDp), dpToPx(56f)
                    ).apply {
                        marginEnd = dpToPx(2f)
                    }
                    holder.blocks.addView(block)
                }
            }
            // Recycled rows restore the shared scroll position.
            holder.scroll.post { holder.scroll.scrollTo(sharedScrollX, 0) }
        }

        override fun getItemCount(): Int = channels.size
    }

    private fun showDetails(
        channel: Channel,
        programme: EpgProgramme,
        fmt: SimpleDateFormat
    ) {
        val time = "${fmt.format(java.util.Date(programme.startMs))} – " +
            fmt.format(java.util.Date(programme.endMs))
        val message = StringBuilder(time)
        if (programme.description.isNotBlank()) {
            message.append("\n\n").append(programme.description)
        }
        val now = System.currentTimeMillis()
        val catchupUrl = if (programme.endMs <= now) {
            CatchupUrl.build(channel, programme.startMs, now)
        } else null
        if (programme.endMs <= now && catchupUrl == null) {
            message.append("\n\n").append(getString(R.string.guide_no_catchup))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("${channel.name}: ${programme.title}")
            .setMessage(message.toString())
        if (programme.endMs <= now) {
            if (catchupUrl != null) {
                builder.setPositiveButton(R.string.guide_watch_from_start) { _, _ ->
                    startActivity(
                        PlaybackActivity.intent(this, channel.copy(url = catchupUrl))
                    )
                }
            } else {
                builder.setPositiveButton(R.string.channel_menu_play) { _, _ ->
                    ChannelMenu.play(this, channel)
                }
            }
            builder.setNegativeButton(android.R.string.cancel, null)
        } else {
            // Future programme: watch live now, set a reminder,
            // or schedule a recording from the reminder alert.
            builder.setPositiveButton(R.string.channel_menu_play) { _, _ ->
                ChannelMenu.play(this, channel)
            }
            builder.setNeutralButton(R.string.reminder_remind_me) { _, _ ->
                ReminderScheduler.setReminder(this, channel, programme, false)
            }
            builder.setNegativeButton(R.string.reminder_record_it) { _, _ ->
                ReminderScheduler.setReminder(this, channel, programme, true)
            }
        }
        builder.show()
    }
}
