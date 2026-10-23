package com.tytv.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.timeshift.Recording
import com.tytv.app.timeshift.RecordingsStore

/** Saved recordings, on-device only. Tap to play, long-press to delete. */
class RecordingsActivity : FragmentActivity() {

    private lateinit var adapter: RecordingsAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)
        findViewById<TextView>(R.id.list_title).text = getString(R.string.recordings_title)
        val list = findViewById<RecyclerView>(R.id.list_items)
        emptyText = findViewById(R.id.list_empty)
        list.layoutManager = LinearLayoutManager(this)
        adapter = RecordingsAdapter(
            onClick = { recording ->
                startActivity(
                    PlaybackActivity.intentForFile(this, recording.file, recording.name)
                )
            },
            onDelete = { recording ->
                AlertDialog.Builder(this)
                    .setTitle(recording.name)
                    .setMessage(R.string.recording_delete_confirm)
                    .setPositiveButton(R.string.recording_delete) { _, _ ->
                        if (RecordingsStore.delete(recording)) refresh() else {
                            Toast.makeText(this, R.string.recording_delete_failed, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                    .setNegativeButton(R.string.recording_cancel, null)
                    .show()
            }
        )
        list.adapter = adapter
        refresh()
    }

    private fun refresh() {
        val recordings = RecordingsStore.list(this)
        adapter.submit(recordings)
        emptyText.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        if (recordings.isEmpty()) emptyText.text = getString(R.string.recordings_empty)
    }

    private class RecordingsAdapter(
        private val onClick: (Recording) -> Unit,
        private val onDelete: (Recording) -> Unit
    ) : RecyclerView.Adapter<RecordingsAdapter.Holder>() {

        private var items: List<Recording> = emptyList()

        fun submit(newItems: List<Recording>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
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
            val recording = items[position]
            holder.name.text = recording.name
            val mb = recording.sizeBytes / (1024 * 1024)
            holder.sub.text = "$mb MB"
            holder.itemView.setOnClickListener { onClick(recording) }
            holder.itemView.setOnLongClickListener {
                onDelete(recording)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
