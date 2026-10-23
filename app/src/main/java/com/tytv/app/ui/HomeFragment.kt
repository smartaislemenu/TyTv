package com.tytv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tytv.app.R
import com.tytv.app.data.ProviderStore

/**
 * TYTV home: big tiles for each section, full width — no sidebar.
 * Tapping a tile opens that section full-screen; Back returns here.
 * When no provider is saved yet, a hero card puts the M3U / Xtream
 * choice front and centre instead of burying it in a form dropdown.
 */
class HomeFragment : Fragment() {

    private sealed class Tile(val title: String, val sub: String) {
        class SectionTile(title: String, sub: String, val section: String) : Tile(title, sub)
        class ActivityTile(title: String, sub: String, val target: Class<*>) : Tile(title, sub)
        class RefreshTile(title: String, sub: String) : Tile(title, sub)
    }

    private val tiles = listOf(
        Tile.SectionTile("Live TV", "Channels, now & next", "LIVE"),
        Tile.SectionTile("Movies", "Poster wall", "MOVIES"),
        Tile.SectionTile("TV Shows", "Series & episodes", "SERIES"),
        Tile.SectionTile("Sports Live", "Games & scores", "SPORTS"),
        Tile.SectionTile("Favorites", "Your saved channels", "FAVORITES"),
        Tile.SectionTile("Recent", "Watched lately", "RECENT"),
        Tile.ActivityTile("Guide", "What's on", GuideActivity::class.java),
        Tile.ActivityTile("Grid guide", "Cable-style grid", GridGuideActivity::class.java),
        Tile.ActivityTile("Search", "Find anything", SearchActivity::class.java),
        Tile.ActivityTile("Recordings", "Saved recordings", RecordingsActivity::class.java),
        Tile.ActivityTile("Scores", "Live scores", ScoresActivity::class.java),
        Tile.ActivityTile("Settings", "Providers, fonts, PIN", SettingsActivity::class.java),
        Tile.RefreshTile("Refresh", "Reload playlists"),
    )

    private lateinit var hero: View
    private lateinit var tileList: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home_tiles, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hero = view.findViewById(R.id.home_hero)
        tileList = view.findViewById(R.id.home_tiles)
        tileList.layoutManager = GridLayoutManager(requireContext(), 4)
        tileList.adapter = TileAdapter(tiles) { tile -> onTile(tile) }
        view.findViewById<View>(R.id.home_hero_m3u).setOnClickListener {
            openAddProvider(AddProviderActivity.TYPE_M3U)
        }
        view.findViewById<View>(R.id.home_hero_xtream).setOnClickListener {
            openAddProvider(AddProviderActivity.TYPE_XTREAM)
        }
        refreshHero()
        tileList.post {
            (tileList.layoutManager as GridLayoutManager)
                .findViewByPosition(0)?.requestFocus()
        }
        // The home screen IS the loaded content — the boot splash
        // (real load only, no timer) fades the moment it renders.
        (activity as? MainActivity)?.hideSplash()
    }

    override fun onResume() {
        super.onResume()
        // A provider added elsewhere hides the hero on return.
        refreshHero()
    }

    private fun refreshHero() {
        hero.visibility =
            if (ProviderStore.getProviders(requireContext()).isEmpty()) View.VISIBLE
            else View.GONE
    }

    private fun openAddProvider(type: String) {
        val intent = Intent(requireContext(), AddProviderActivity::class.java)
        intent.putExtra(AddProviderActivity.EXTRA_TYPE, type)
        startActivity(intent)
    }

    private fun onTile(tile: Tile) {
        when (tile) {
            is Tile.SectionTile -> {
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.fragment_container,
                        MainFragment.newInstance(tile.section, false)
                    )
                    .addToBackStack(null)
                    .commit()
            }
            is Tile.ActivityTile -> {
                startActivity(Intent(requireContext(), tile.target))
            }
            is Tile.RefreshTile -> {
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.fragment_container,
                        MainFragment.newInstance("LIVE", true)
                    )
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    private class TileAdapter(
        private val tiles: List<Tile>,
        private val onClick: (Tile) -> Unit
    ) : RecyclerView.Adapter<TileAdapter.Holder>() {

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.home_tile_title)
            val sub: TextView = itemView.findViewById(R.id.home_tile_sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_home_tile, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tile = tiles[position]
            holder.title.text = tile.title
            holder.sub.text = tile.sub
            holder.itemView.setOnClickListener { onClick(tile) }
        }

        override fun getItemCount(): Int = tiles.size
    }
}
