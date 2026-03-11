package com.adsamcik.tracker.shared.utils.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme

/**
 * Base activity for detail screens using Jetpack Compose
 */
abstract class ComposeDetailActivity : ComponentActivity() {
    
    private var titleText by mutableStateOf("")
    private var actions by mutableStateOf(emptyList<@Composable () -> Unit>())
    
    /**
     * Configuration class for the activity
     */
    data class Configuration(
        var showBackButton: Boolean = true,
        var title: String = ""
    )
    
    /**
     * Override to configure the activity
     */
    open fun onConfigure(configuration: Configuration): Unit = Unit
    
    /**
     * The main content of the activity
     */
    @Composable
    abstract fun Content()
    
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val configuration = Configuration()
        onConfigure(configuration)
        
        titleText = configuration.title
        
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = titleText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                navigationIcon = if (configuration.showBackButton) {
                                    {
                                        IconButton(onClick = { finish() }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = stringResource(com.adsamcik.tracker.shared.utils.R.string.action_navigate_back)
                                            )
                                        }
                                    }
                                } else {
                                    {}
                                },
                                actions = {
                                    actions.forEach { action ->
                                        action()
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    ) { paddingValues ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        ) {
                            Content()
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Set the activity title
     */
    fun setActivityTitle(title: String) {
        titleText = title
    }
    
    /**
     * Set the activity title from string resource
     */
    fun setActivityTitle(@StringRes titleRes: Int) {
        titleText = getString(titleRes)
    }
    
    /**
     * Add action buttons to the app bar
     */
    fun updateActions(newActions: List<@Composable () -> Unit>) {
        actions = newActions
    }
}
