package com.tytv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.model.Channel

/** Plain windowed channel rows, shared by Search and Series episodes. */
class ChannelRowAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongPress: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<ChannelRowAdapter.Holder>() {

    private var items: List<Channel> = emptyList()

    fun submit(newItems: List<Channel>) {
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
        val channel = items[position]
        holder.name.text = channel.name
        holder.sub.text = "${channel.group} • ${channel.provider}"
        holder.itemView.setOnClickListener { onClick(channel) }
        if (onLongPress != null) {
            holder.itemView.setOnLongClickListener {
                onLongPress.invoke(channel)
                true
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
