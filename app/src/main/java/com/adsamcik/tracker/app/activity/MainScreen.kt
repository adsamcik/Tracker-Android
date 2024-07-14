package com.adsamcik.tracker.app.activity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.AppBarDefaults
import androidx.compose.material.BottomAppBar
import androidx.compose.material.ContentAlpha
import androidx.compose.material.FabPosition
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidViewBinding
import com.adsamcik.tracker.R
import com.adsamcik.tracker.tracker.ui.fragment.FragmentTracker


@Composable
fun MainScreen() {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* Handle tracker click here */ },
                backgroundColor = MaterialTheme.colors.background,
            ) {
                // Icon for the tracker, replace with your tracker icon
                Icon(painterResource(id = R.drawable.baseline_dashboard_24), contentDescription = "Tracker")
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        isFloatingActionButtonDocked = true,
        bottomBar = {
            BottomAppBar(windowInsets = AppBarDefaults.bottomAppBarWindowInsets,
                cutoutShape = MaterialTheme.shapes.large.copy(CornerSize(percent = 50))) {
                // Leading icons should typically have a high content alpha
                CompositionLocalProvider(LocalContentAlpha provides ContentAlpha.high) {
                    IconButton(onClick = { /* doSomething() */ }) {
                        Icon(painterResource(id = R.drawable.ic_pie_chart_black_24dp), contentDescription = "Localized description")
                    }
                    IconButton(onClick = { /* doSomething() */ }) {
                        Icon(painterResource(id = R.drawable.ic_outline_map_24dp), contentDescription = "Localized description")
                    }
                    Spacer(Modifier.weight(1f, true))
                    IconButton(onClick = { /* doSomething() */ }) {
                        Icon(painterResource(id = R.drawable.ic_outline_games_24dp), contentDescription = "Localized description")
                    }
                }
            }
        }
    ) { innerPadding ->
        AndroidViewBinding(FragmentTracker::inflate)
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MainScreen()
}

@Composable
fun MyApp() {
    MaterialTheme {
        MainScreen()
    }
}