package com.adsamcik.tracker.app.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidViewBinding
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import com.adsamcik.tracker.app.ui.theme.AppTheme
import com.adsamcik.tracker.app.ui.MainRoot
import com.adsamcik.tracker.app.ui.navigation.Routes
import com.adsamcik.tracker.shared.utils.activity.CoreUIActivity
// StyleController system bar hooks are obsolete; rely on AppTheme + default insets
import com.adsamcik.tracker.tracker.ui.fragment.FragmentTracker
import android.view.View

/**
 * Dedicated Compose-first Main activity. Keeps legacy MainActivity intact.
 */
class MainActivityCompose : CoreUIActivity() {

    private val selectedTab = mutableStateOf("map")
    private var trackerFragmentAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme_Translucent)
    // System bar styling handled by Material 3/AppTheme now
        super.onCreate(savedInstanceState)

        // Restore selected tab
        savedInstanceState?.getString(KEY_SELECTED_TAB)?.let { restored ->
            selectedTab.value = restored
        }

        // Handle initial intent
        handleIntent(intent)

    setContent { ComposeRoot(selectedTab) }

    // Ensure background tracker fragment is attached after views exist
    window?.decorView?.post { ensureTrackerFragmentAttached() }

    // Back handling is implemented in Compose via BackHandler in MainRoot
    }

    override fun onStart() {
        super.onStart()
        if (!OnboardingActivity.isOnboardingCompleted(this)) {
            startActivity(OnboardingActivity.createIntent(this))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
    val openGame = intent?.getBooleanExtra("openGame", false) == true
    if (openGame) selectedTab.value = Routes.Game
    }

    // Obsolete: style controller system bar integration removed

    @Composable
    private fun ComposeRoot(selected: MutableState<String>) {
        AppTheme {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    // Host legacy XML/fragment layout in the background
                    AndroidViewBinding(com.adsamcik.tracker.databinding.ActivityUiBinding::inflate)
                    // Try attaching tracker fragment as soon as layout is in the tree
                    LaunchedEffect(Unit) { window?.decorView?.post { ensureTrackerFragmentAttached() } }

                    // Compose Navigation root; content layers above background
                    MainRoot(startDestination = selected.value) { route ->
                        if (selected.value != route) selected.value = route
                    }
                }
            }
        }
    }

    private fun ensureTrackerFragmentAttached() {
        if (trackerFragmentAttached) return
        val container: View? = findViewById(R.id.tracker_placeholder)
        if (container == null) return
        val tag = FragmentTracker::class.java.simpleName
        val current = supportFragmentManager.findFragmentByTag(tag)
        val fragment = current ?: FragmentTracker()
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.tracker_placeholder, fragment, tag)
            .commitAllowingStateLoss()
        trackerFragmentAttached = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TAB, selectedTab.value)
    }

    companion object {
        private const val KEY_SELECTED_TAB = "main_selected_tab"
    }
}
