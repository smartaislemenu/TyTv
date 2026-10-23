package com.tytv.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Applies the user's font choice to every activity's layout once its
 * onCreate has run (so setContentView has already inflated). Fragment
 * views and RecyclerView item views are created later, so those
 * inflation points apply the font themselves (see FontApplier).
 */
class TytvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                try {
                    com.tytv.app.ui.FontApplier.apply(activity.window.decorView)
                } catch (e: Exception) {
                    // A font failure must never break an activity launch.
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
