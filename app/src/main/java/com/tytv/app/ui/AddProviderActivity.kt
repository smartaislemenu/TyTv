package com.tytv.app.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R
import com.tytv.app.data.ProviderStore

/**
 * Provider-add form, remote friendly: arrows move focus, select opens
 * the on-screen keyboard for a field or saves.
 *
 * Two provider types: an M3U playlist URL, or an Xtream Codes login
 * (server address + username + password for the provider's own
 * player_api.php). Xtream credentials stay on this device only and
 * go nowhere except that provider's own server.
 */
class AddProviderActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_provider)
        val nameInput = findViewById<EditText>(R.id.provider_name_input)
        val typeSpinner = findViewById<Spinner>(R.id.provider_type_spinner)
        val m3uGroup = findViewById<LinearLayout>(R.id.m3u_group)
        val urlInput = findViewById<EditText>(R.id.provider_url_input)
        val xtreamGroup = findViewById<LinearLayout>(R.id.xtream_group)
        val serverInput = findViewById<EditText>(R.id.xtream_server_input)
        val userInput = findViewById<EditText>(R.id.xtream_user_input)
        val passInput = findViewById<EditText>(R.id.xtream_pass_input)
        val epgInput = findViewById<EditText>(R.id.epg_url_input)
        findViewById<TextView>(R.id.xtream_note).text =
            getString(R.string.xtream_note)

        val types = listOf(
            getString(R.string.provider_type_m3u),
            getString(R.string.provider_type_xtream)
        )
        // Custom item layouts: the platform default uses dark text,
        // which is unreadable on this app's dark background — the
        // dropdown looked blank on TV. White text on both states.
        typeSpinner.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            types
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val isXtream = position == 1
                m3uGroup.visibility = if (isXtream) View.GONE else View.VISIBLE
                xtreamGroup.visibility = if (isXtream) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val saveButton = findViewById<Button>(R.id.save_button)
        // Done on the last field's keyboard saves, so there is
        // always a way forward without hunting for the button.
        epgInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveButton.performClick()
                true
            } else {
                false
            }
        }
        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val isXtream = typeSpinner.selectedItemPosition == 1
            if (name.isEmpty() || name.contains('|')) {
                Toast.makeText(this, R.string.provider_name_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Optional guide address for this provider. Blank means:
            // Xtream logins use their own xmltv.php, others fall back
            // to the global address in Setup (or no guide at all).
            val epgUrl = epgInput.text.toString().trim()
            if (epgUrl.contains('|')) {
                Toast.makeText(this, R.string.epg_url_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isXtream) {
                val server = serverInput.text.toString().trim()
                val user = userInput.text.toString().trim()
                val pass = passInput.text.toString().trim()
                if (server.isEmpty() || user.isEmpty() || pass.isEmpty() ||
                    server.contains('|') || user.contains('|') || pass.contains('|')
                ) {
                    Toast.makeText(this, R.string.xtream_fields_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ProviderStore.addXtreamProvider(this, name, server, user, pass, epgUrl)
            } else {
                val url = urlInput.text.toString().trim()
                if (url.isEmpty() || url.contains('|')) {
                    Toast.makeText(this, R.string.provider_url_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ProviderStore.addProvider(this, name, url, epgUrl)
            }
            Toast.makeText(this, R.string.provider_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
