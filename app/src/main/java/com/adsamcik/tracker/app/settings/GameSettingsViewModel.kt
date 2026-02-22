package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GameSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = Preferences.getPref(context)

    private val _challengeEnabled = MutableStateFlow(true)
    val challengeEnabled: StateFlow<Boolean> = _challengeEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            PreferenceFlows.boolean(
                context,
                com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_key,
                com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_default
            ).collect { _challengeEnabled.value = it }
        }
    }

    fun setChallengeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.edit {
                setBoolean(
                    com.adsamcik.tracker.game.R.string.settings_game_challenge_enable_key,
                    enabled
                )
            }
            _challengeEnabled.value = enabled
        }
    }
}
