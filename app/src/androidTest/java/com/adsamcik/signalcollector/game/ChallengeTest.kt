package com.adsamcik.signalcollector.game

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.signalcollector.database.AppDatabase
import org.junit.Before


class ChallengeTest {
	private lateinit var cellDao: ChallengeDao

	@Before
	fun setup() {
		cellDao = AppDatabase.getTestDatabase(ApplicationProvider.getApplicationContext<Context>()).challengeDao()
	}
}