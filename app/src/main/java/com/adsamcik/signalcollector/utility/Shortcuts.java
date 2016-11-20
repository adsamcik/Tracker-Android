package com.adsamcik.signalcollector.utility;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.activities.ShortcutActivity;
import com.adsamcik.signalcollector.services.TrackerService;

import java.util.ArrayList;
import java.util.List;

@TargetApi(25)
public class Shortcuts {
	public static final String TRACKING_ID = "Tracking";
	public static final String ACTION = "com.adsamcik.signalcollector.SHORTCUT";
	public static final String ACTION_STRING = "ShortcutAction";

	/**
	 * Initializes shortcuts
	 *
	 * @param context context
	 * @return true if not initialized
	 */
	public static boolean initializeShortcuts(@NonNull Context context) {
		ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
		//if (shortcutManager.getDynamicShortcuts().size() == 0) {
		ArrayList<ShortcutInfo> shortcuts = new ArrayList<>(1);
		if (!TrackerService.isRunning())
			shortcuts.add(createShortcut(context, TRACKING_ID, context.getString(R.string.shortcut_start_tracking), context.getString(R.string.shortcut_start_tracking_long), R.drawable.ic_play, ShortcutType.START_COLLECTION));
		else
			shortcuts.add(createShortcut(context, TRACKING_ID, context.getString(R.string.shortcut_stop_tracking), context.getString(R.string.shortcut_stop_tracking_long), R.drawable.ic_pause, ShortcutType.STOP_COLLECTION));

		shortcutManager.setDynamicShortcuts(shortcuts);
		return false;
		//}
		//return true;
	}


	private static ShortcutInfo createShortcut(@NonNull Context context, @NonNull String id, @NonNull String shortLabel, @Nullable String longLabel, @DrawableRes int iconResource, @NonNull ShortcutType action) {
		ShortcutInfo.Builder shortcutBuilder = new ShortcutInfo.Builder(context, id)
				.setShortLabel(shortLabel)
				.setIcon(Icon.createWithResource(context, iconResource))
				.setIntent(new Intent(context, ShortcutActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setAction(ACTION).putExtra(ACTION_STRING, action.ordinal()));
		if (longLabel != null)
			shortcutBuilder.setLongLabel(longLabel);
		return shortcutBuilder.build();
	}

	public static void updateShortcut(@NonNull Context context, @NonNull String id, @NonNull String shortLabel, @Nullable String longLabel, @DrawableRes int iconResource, @NonNull ShortcutType action) {
		initializeShortcuts(context);
		ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
		List<ShortcutInfo> shortcuts = shortcutManager.getDynamicShortcuts();
		for (int i = 0; i < shortcuts.size(); i++) {
			if (shortcuts.get(i).getId().equals(id)) {
				shortcuts.set(i, createShortcut(context, id, shortLabel, longLabel, iconResource, action));
				break;
			}
		}
		shortcutManager.updateShortcuts(shortcuts);
	}


	public enum ShortcutType {
		START_COLLECTION,
		STOP_COLLECTION
	}
}
