# String Jargon Elimination - Quick Reference
**Before/After Comparison**

## tracker module (18 changes)

### Line 24-25: Update Count
```diff
- <string name="collection_count_title">Collection count</string>
- <string name="collection_count_value">%d collections</string>
+ <string name="collection_count_title">Update count</string>
+ <string name="collection_count_value">%d updates</string>
```

### Line 43: Error Message
```diff
- <string name="error_nothing_to_track">You need to track more than time</string>
+ <string name="error_nothing_to_track">Enable at least one tracking option (location, steps, or activity)</string>
```

### Line 68: Notification
```diff
- <string name="notification_starting">Starting Tracker service</string>
+ <string name="notification_starting">Starting Tracker</string>
```

### Line 75: Permission Message
```diff
- <string name="permissions_tracker_timer_message">%1$s timer needs more permissions to start tracking.</string>
+ <string name="permissions_tracker_timer_message">%1$s location method requires additional permissions to start tracking</string>
```

### Line 79: Auto-Tracking Summary
```diff
- <string name="settings_auto_tracking_transition_summary">Transitions may reduce battery usage when auto tracking, but activity changes might be less reactive.</string>
+ <string name="settings_auto_tracking_transition_summary">Battery-efficient mode (slightly slower activity detection)</string>
```

### Line 97: Timer Setting
```diff
- <string name="settings_tracker_timer_clock">Timer</string>
+ <string name="settings_tracker_timer_clock">Interval-based</string>
```

### Line 100: Timer Summary
```diff
- <string name="settings_tracker_timer_summary">Currently active: %s. Component that serves as update - triggers collection</string>
+ <string name="settings_tracker_timer_summary">Location update method: %s</string>
```

### Line 101: Timer Title
```diff
- <string name="settings_tracker_timer_title">Timers</string>
+ <string name="settings_tracker_timer_title">Location update method</string>
```

### Line 102: Min Distance Summary
```diff
- <string name="settings_tracking_min_distance_summary">"Minimum distance between collections."</string>
+ <string name="settings_tracking_min_distance_summary">Minimum distance between location updates</string>
```

### Line 103: Min Distance Title
```diff
- <string name="settings_tracking_min_distance_title">Minimum distance between collections</string>
+ <string name="settings_tracking_min_distance_title">Minimum distance between updates</string>
```

### Line 119: Min Time Summary
```diff
- <string name="settings_tracking_min_time_summary">Collections will not trigger faster than this.</string>
+ <string name="settings_tracking_min_time_summary">Minimum time between location updates</string>
```

### Line 120: Min Time Title
```diff
- <string name="settings_tracking_min_time_title">Minimum delay between collections</string>
+ <string name="settings_tracking_min_time_title">Minimum delay between updates</string>
```

### Line 135: Notice Summary
```diff
- <string name="settings_tracking_notice_summary">Due to technical reasons, most changes to tracker settings require restart of tracking. (If tracking is active just stop it and start it again. Closing and opening app might not help)</string>
+ <string name="settings_tracking_notice_summary">Changes take effect when you restart tracking</string>
```

### Line 153: Required Accuracy Summary
```diff
- <string name="settings_tracking_required_accuracy_summary">Locations will need to be at least this accurate to trigger a collection.</string>
+ <string name="settings_tracking_required_accuracy_summary">Location updates require this accuracy level</string>
```

### Line 162-163: Shortcuts
```diff
- <string name="shortcut_start_tracking_long">Start tracking service</string>
- <string name="shortcut_stop_tracking_long">Stop tracking service</string>
+ <string name="shortcut_start_tracking_long">Start location tracking</string>
+ <string name="shortcut_stop_tracking_long">Stop location tracking</string>
```

### Line 175: Collections Title
```diff
- <string name="tracker_collections_title">Collections</string>
+ <string name="tracker_collections_title">Updates</string>
```

---

## app module (5 changes)

### Line 49: Clear Preferences Message
```diff
- <string name="settings_clear_preferences_message">This will remove all stored app preferences (feature toggles, local UI settings). Tracking data is unaffected. Continue?</string>
+ <string name="settings_clear_preferences_message">This will reset all app preferences (feature toggles, UI settings). Your tracking data won't be affected. Continue?</string>
```

### Line 123: Remove Data Summary
```diff
- <string name="settings_remove_all_collected_data_summary">Deletes data acquired during collection (Custom activities, settings etc. will be kept)</string>
+ <string name="settings_remove_all_collected_data_summary">Deletes your tracking data (custom activities and settings will be kept)</string>
```

### Line 237: Auto-Tracking Explanation
```diff
- <string name="auto_tracking_explanation">Auto-tracking uses device sensors and activity recognition to detect activities. All processing happens on your device.</string>
+ <string name="auto_tracking_explanation">Auto-tracking uses your device's sensors to detect when you're walking, running, or in a vehicle. All data stays on your device.</string>
```

### Line 456: Crash Storage Note
```diff
- <string name="debug_crash_storage_note">These crashes are stored locally and can be exported for analysis.</string>
+ <string name="debug_crash_storage_note">Crash reports are saved on your device and can be exported for debugging.</string>
```

### Line 458: Crash Info Message
```diff
- <string name="debug_crash_info_message">• Crashes are automatically captured when the app unexpectedly terminates\n• Data is stored securely on your device\n• No data is sent automatically\n• You can export crash logs for debugging</string>
+ <string name="debug_crash_info_message">• Crash reports are automatically saved when the app closes unexpectedly\n• All data stays on your device\n• Nothing is sent automatically\n• You can export crash logs for debugging</string>
```

---

## statistics module (2 changes)

### Line 11: Collections
```diff
- <string name="stats_collections">Collection count</string>
+ <string name="stats_collections">Update count</string>
```

### Line 29: No Sessions
```diff
- <string name="stats_no_tracker_sessions">No tracker sessions. Yet.</string>
+ <string name="stats_no_tracker_sessions">No tracking sessions yet. Start tracking to see your activity!</string>
```

---

## map module (2 changes)

### Line 60: Map Quality Summary
```diff
- <string name="settings_map_quality_summary">Scale relative to default resolution.</string>
+ <string name="settings_map_quality_summary">Higher values show more detail (may slow rendering on older devices)</string>
```

### Line 62: Visit Threshold Summary
```diff
- <string name="settings_map_visit_threshold_summary">Heatmap will heat up only with visits with larger difference than threshold.</string>
+ <string name="settings_map_visit_threshold_summary">Minimum time to stay in one place before it's counted as a visit</string>
```

---

## Summary Stats

**Total Changes:** 27 strings  
**Modules Affected:** 4  
**Jargon Terms Eliminated:**
- ❌ "collection" (11 instances)
- ❌ "component" (1 instance)
- ❌ "trigger" (3 instances)
- ❌ "timer" (4 instances)
- ❌ "service" (3 instances)
- ❌ "acquired" (1 instance)
- ❌ "stored" (3 instances)
- ❌ "captured" (1 instance)

**Improvements:**
✅ Task-oriented language  
✅ Actionable error messages  
✅ Stronger privacy messaging  
✅ Confident tone (no apologetic phrases)  
✅ Concrete examples over abstractions  
✅ Shorter, clearer descriptions
