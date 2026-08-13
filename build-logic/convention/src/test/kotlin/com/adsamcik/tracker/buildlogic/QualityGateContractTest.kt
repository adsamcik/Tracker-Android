package com.adsamcik.tracker.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QualityGateContractTest {
    @Test
    fun `every KMP JVM and Android host suite reaches ciCheck`() {
        val expectedKmpTasks = setOf(
            ":core:model:jvmTest",
            ":core:model:testAndroidHostTest",
            ":stats:api:jvmTest",
            ":stats:api:testAndroidHostTest",
            ":stats:engine:jvmTest",
            ":stats:engine:testAndroidHostTest",
            ":tracker:control:jvmTest",
            ":tracker:control:testAndroidHostTest",
        )

        assertEquals(
            expectedKmpTasks,
            QualityGateContract.ciUnitTestDependencies.filterTo(mutableSetOf()) { task ->
                task.endsWith(":jvmTest") || task.endsWith(":testAndroidHostTest")
            },
        )
        expectedKmpTasks.forEach { task ->
            assertTrue(
                QualityGateContract.isReachableFromCiCheck(task),
                "$task must propagate failures to ciCheck",
            )
        }
    }

    @Test
    fun `Detekt lint Room and architecture failures reach ciCheck`() {
        val representativeFailureTasks = listOf(
            ":detekt",
            ":feature:map:lintRelease",
            ":checkRoomSchemaDrift",
            ":app:testDebugUnitTest",
            ":ciArchitectureCheck",
        )

        representativeFailureTasks.forEach { task ->
            assertTrue(
                QualityGateContract.isReachableFromCiCheck(task),
                "$task must propagate failures to ciCheck",
            )
        }
    }

    @Test
    fun `native release verification reaches ciCheck`() {
        assertTrue(
            QualityGateContract.isReachableFromCiCheck(":verifyReleaseSqliteRuntime"),
        )
    }
}
