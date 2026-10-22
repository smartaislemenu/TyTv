package com.tytv.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.tytv.app.R
import com.tytv.app.data.ProviderStore

/**
 * Simple provider-add form. The name and URL fields and the Save button
 * are all focusable, so the screen works with a TV remote: arrows move
 * focus, select opens the on-screen keyboard for a field or saves.
 */
class AddProviderActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_provider)
        val nameInput = findViewById<EditText>(R.id.provider_name_input)
        val urlInput = findViewById<EditText>(R.id.provider_url_input)
        findViewById<Button>(R.id.save_button).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            if (name.isEmpty() || url.isEmpty() || name.contains('|') || url.contains('|')) {
                Toast.makeText(this, "Enter a name and a valid M3U URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ProviderStore.addProvider(this, name, url)
            Toast.makeText(this, "Provider saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
