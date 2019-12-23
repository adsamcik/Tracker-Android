package com.adsamcik.tracker.shared.base.data

import android.content.Context
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.data.SessionActivity

@Suppress("unused")
enum class NativeSessionActivity(val id: Long) {
	WALK(-2) {
		override val nameRes: Int = R.string.activity_walk
		override val iconName: String = R.drawable::ic_directions_walk_white.name
	},
	RUN(-3) {
		override val nameRes: Int = R.string.activity_run
		override val iconName: String = R.drawable::ic_outline_directions_run_24px.name
	},
	BICYCLE(-4) {
		override val nameRes: Int = R.string.activity_bicycle
		override val iconName: String = R.drawable::ic_baseline_directions_bike.name
	},
	VEHICLE(-5) {
		override val nameRes: Int = R.string.activity_vehicle
		override val iconName: String = R.drawable::ic_directions_car_white_24dp.name
	},
	SWIM(-6) {
		override val nameRes: Int = R.string.activity_swim
		override val iconName: String = R.drawable::swim.name
	},
	TENIS(-7) {
		override val nameRes: Int = R.string.activity_tennis
		override val iconName: String = R.drawable::tennis.name
	},
	VOLLEYBALL(-8) {
		override val nameRes: Int = R.string.activity_volleyball
		override val iconName: String = R.drawable::volleyball.name
	},
	//SOCCER
	FOOTBALL(-9) {
		override val nameRes: Int = R.string.activity_football
		override val iconName: String = R.drawable::soccer.name
	},
	RUGBY(-10) {
		override val nameRes: Int = R.string.activity_rugby
		override val iconName: String = R.drawable::rugby.name
	},
	MARTIAL_ARTS(-11) {
		override val nameRes: Int = R.string.activity_martial_arts
		override val iconName: String = R.drawable::mixed_martial_arts.name
	},
	HOCKEY(-12) {
		override val nameRes: Int = R.string.activity_hockey
		override val iconName: String = R.drawable::hockey_sticks.name
	},
	HANDBALL(-13) {
		override val nameRes: Int = R.string.activity_handball
		override val iconName: String = R.drawable::handball.name
	},
	GOLF(-14) {
		override val nameRes: Int = R.string.activity_golf
		override val iconName: String = R.drawable::golf.name
	},
	BASKETBALL(-15) {
		override val nameRes: Int = R.string.activity_basketball
		override val iconName: String = R.drawable::basketball.name
	},
	BASEBALL(-16) {
		override val nameRes: Int = R.string.activity_baseball
		override val iconName: String = R.drawable::baseball_bat.name
	},
	SOFTBALL(-17) {
		override val nameRes: Int = R.string.activity_softball
		override val iconName: String = R.drawable::baseball_bat.name
	},
	BADMINTON(-18) {
		override val nameRes: Int = R.string.activity_badminton
		override val iconName: String = R.drawable::badminton.name
	},
	HIKING(-19) {
		override val nameRes: Int = R.string.activity_hiking
		override val iconName: String = R.drawable::hiking.name
	},
	CRICKET(-20) {
		override val nameRes: Int = R.string.activity_cricket
		override val iconName: String = R.drawable::cricket.name
	},
	RACE(-21) {
		override val nameRes: Int = R.string.activity_race
		override val iconName: String = R.drawable::racing_helmet.name
	},
	SKATE(-22) {
		override val nameRes: Int = R.string.activity_skate
		override val iconName: String = R.drawable::skate.name
	},
	SKI(-23) {
		override val nameRes: Int = R.string.activity_ski
		override val iconName: String = R.drawable::ic_icons8_skiing.name
	},
	SNOWBOARD(-24) {
		override val nameRes: Int = R.string.activity_snowboard
		override val iconName: String = R.drawable::ic_icons8_skiing.name
	},
	HORSERIDE(-25) {
		override val nameRes: Int = R.string.activity_horseride
		override val iconName: String = R.drawable::horseshoe.name
	},
	SAILING(-26) {
		override val nameRes: Int = R.string.activity_sailing
		override val iconName: String = R.drawable::sailing.name
	},
	CANOE(-27) {
		override val nameRes: Int = R.string.activity_canoe
		override val iconName: String = R.drawable::rowing.name
	},
	KAYAK(-28) {
		override val nameRes: Int = R.string.activity_kayak
		override val iconName: String = R.drawable::rowing.name
	},
	ROWING(-29) {
		override val nameRes: Int = R.string.activity_rowing
		override val iconName: String = R.drawable::rowing.name
	},
	DIVE(-30) {
		override val nameRes: Int = R.string.activity_dive
		override val iconName: String = R.drawable::diving_flippers.name
	},
	AIRPLANE(-31) {
		override val nameRes: Int = R.string.activity_airplane
		override val iconName: String = R.drawable::airplane.name
	},
	FERRY(-32) {
		override val nameRes: Int = R.string.activity_ferry
		override val iconName: String = R.drawable::ferry.name
	},
	AIRBALLOON(-33) {
		override val nameRes: Int = R.string.activity_airballoon
		override val iconName: String = R.drawable::airballoon.name
	},
	TRAIN(-34) {
		override val nameRes: Int = R.string.activity_train
		override val iconName: String = R.drawable::train.name
	};

	/**
	 * Resource id of name for native session activity.
	 */
	abstract val nameRes: Int

	/**
	 * Name of the icon, Allows for dynamic retrieval.
	 */
	abstract val iconName: String

	fun getSessionActivity(context: Context): SessionActivity {
		return SessionActivity(id, context.getString(nameRes), iconName)
	}
}
