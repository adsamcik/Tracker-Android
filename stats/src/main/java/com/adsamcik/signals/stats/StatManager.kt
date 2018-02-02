package com.adsamcik.signals.stats

import android.content.Context
import android.content.SharedPreferences
import com.adsamcik.signalcollector.data.StatDay
import com.adsamcik.utilities.Assist
import com.adsamcik.utilities.Constants
import com.adsamcik.utilities.Preferences
import com.google.gson.Gson
import java.util.HashSet
import kotlin.collections.ArrayList

class StatManager {
    companion object {
        private const val MAX_DAY_DIFF_STATS = 7

        /**
         * Counts all stats and combines them to a single StatDay object
         * @param context context
         * @return sum of all StatDays
         */
        fun countStats(context: Context): StatDay {
            val sp = Preferences.getPref(context)
            val result = getCurrent(sp)
            val set = fromJson(sp.getStringSet(Preferences.PREF_STATS_LAST_7_DAYS, null), 0)

            result += set
            return result
        }

        /**
         * Creates stat day with today values
         * @param sp shared preferences
         * @return Today StatDay
         */
        private fun getCurrent(sp: SharedPreferences): StatDay =
                StatDay(sp.getInt(Preferences.PREF_STATS_MINUTES, 0), sp.getInt(Preferences.PREF_STATS_LOCATIONS_FOUND, 0), sp.getInt(Preferences.PREF_STATS_WIFI_FOUND, 0), sp.getInt(Preferences.PREF_STATS_CELL_FOUND, 0), 0, sp.getLong(Preferences.PREF_STATS_UPLOADED, 0))

        /**
         * Method loads data to list and checks if they are not too old
         * @param set string set
         * @param age how much should stats age
         * @return list with items that are not too old
         */
        private fun fromJson(set: Set<String>?, age: Int): MutableList<StatDay> {
            val statDays = ArrayList<StatDay>()
            if (set == null)
                return statDays
            val gson = Gson()
            set
                    .map { gson.fromJson(it, StatDay::class.java) }
                    .filterTo(statDays) { age <= 0 || it.age(age) < MAX_DAY_DIFF_STATS }
            return statDays
        }

        /**
         * Checks if current day should be archived and clears up old StatDays
         * @param context context
         */
        fun checkStatsDay(context: Context) {
            val preferences = Preferences.getPref(context)

            val todayUTC = Assist.todayUTC
            val dayDiff = (todayUTC - preferences.getLong(Preferences.PREF_STATS_STAT_LAST_DAY, -1)).toInt() / Constants.DAY_IN_MILLISECONDS
            if (dayDiff > 0) {
                var stringStats: MutableSet<String>? = preferences.getStringSet(Preferences.PREF_STATS_LAST_7_DAYS, null)
                val stats = fromJson(stringStats, dayDiff)

                stats.add(getCurrent(preferences))

                if (stringStats == null)
                    stringStats = HashSet()
                else
                    stringStats.clear()

                val gson = Gson()
                stats.mapTo(stringStats) { gson.toJson(it) }

                preferences.edit()
                        .putLong(Preferences.PREF_STATS_STAT_LAST_DAY, todayUTC)
                        .putStringSet(Preferences.PREF_STATS_LAST_7_DAYS, stringStats)
                        .putInt(Preferences.PREF_STATS_MINUTES, 0)
                        .putInt(Preferences.PREF_STATS_LOCATIONS_FOUND, 0)
                        .putInt(Preferences.PREF_STATS_WIFI_FOUND, 0)
                        .putInt(Preferences.PREF_STATS_CELL_FOUND, 0)
                        .putLong(Preferences.PREF_STATS_UPLOADED, 0)
                        .apply()
            }
        }
    }
}