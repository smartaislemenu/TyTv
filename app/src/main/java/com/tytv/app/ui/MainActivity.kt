package com.tytv.app.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.tytv.app.R
import com.tytv.app.update.UpdateCheckResult
import com.tytv.app.update.UpdateChecker
import com.tytv.app.update.UpdateDialog
import com.tytv.app.update.UpdateFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
            checkForUpdateSilently()
        }
    }

    /**
     * Silent launch check: runs off the main thread and shows UI only
     * when an update is actually available. Up-to-date and failed checks
     * stay completely silent so launch is never blocked or interrupted.
     */
    private fun checkForUpdateSilently() {
        lifecycleScope.launch {
            when (val result = UpdateChecker.check(this@MainActivity)) {
                is UpdateCheckResult.Available ->
                    UpdateDialog.show(this@MainActivity, result.info) {
                        UpdateFlow.start(this@MainActivity, result.info)
                    }
                else -> Unit
            }
        }
    }
}
