package com.adsamcik.signalcollector.activities

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.adsamcik.signalcollector.extensions.startActivity
import com.adsamcik.signalcollector.notifications.NotificationChannels
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.adsamcik.signalcollector.utility.Shortcuts
import com.adsamcik.signalcollector.utility.TrackingLocker




/**
 * LaunchActivity is activity that should always be called first when user should see the layout
 * Not only does it allow for easy switching of appropriate Activities, but it also shows SplashScreen and initializes basic services
 */
class LaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        startActivity<MainActivity> { }

        if (Build.VERSION.SDK_INT >= 25)
            Shortcuts.initializeShortcuts(this)

        if (Build.VERSION.SDK_INT >= 26)
            NotificationChannels.prepareChannels(this)

        ActivityWakerService.pokeWithCheck(this)

        TrackingLocker.initializeFromPersistence(this)

        overridePendingTransition(0, 0)
        finish()
    }
}
