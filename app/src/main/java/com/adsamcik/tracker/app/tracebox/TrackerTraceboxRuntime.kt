package com.adsamcik.tracker.app.tracebox

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticKind
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticSink
import com.adsamcik.tracker.diagnostics.TrackerDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tracebox.Tracebox
import dev.tracebox.TraceboxConfiguration
import dev.tracebox.api.Diagnostics
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.generated.GeneratedDiagnostics
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process bootstrap for Tracker's sole crash and diagnostic recorder.
 *
 * The main process invokes this before Hilt's Application.onCreate. The dedicated Tracebox handler
 * process never invokes it, so the handler cannot recursively install a client.
 */
internal object TrackerTraceboxRuntime {
    private val lock = Any()

    @Volatile
    private var installed: TraceboxHandle? = null

    fun install(context: Context): TraceboxHandle = synchronized(lock) {
        installed ?: Tracebox.install(
            context,
            TraceboxConfiguration.Builder()
                .setInitialProfile(DiagnosticsProfile.STANDARD_DIAGNOSTICS)
                .setPersistRequestedProfile(true)
                .build(),
        ).also { handle ->
            installTrackerDiagnosticSink(handle.diagnostics)
            installed = handle
        }
    }
}

internal fun installTrackerDiagnosticSink(diagnostics: Diagnostics) {
    TrackerDiagnostics.install(
        TrackerDiagnosticSink { code ->
            when (code.kind) {
                TrackerDiagnosticKind.BREADCRUMB -> {
                    GeneratedDiagnostics.breadcrumb(
                        diagnostics = diagnostics,
                        code = code.wireCode,
                        monotonic_time_ns = SystemClock.elapsedRealtimeNanos().toULong(),
                    )
                }

                TrackerDiagnosticKind.HANDLED_ERROR -> {
                    GeneratedDiagnostics.handledError(
                        diagnostics = diagnostics,
                        kind = code.wireCode,
                        frame_count = 0u,
                    )
                }
            }
        },
    )
}

@Singleton
class TrackerTraceboxHandleProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    val handle: TraceboxHandle = TrackerTraceboxRuntime.install(context)
}

internal fun isTrackerMainProcessName(currentProcessName: String?, packageName: String): Boolean =
    currentProcessName == packageName

internal fun isTraceboxHandlerProcessName(
    currentProcessName: String?,
    packageName: String,
): Boolean = currentProcessName == "$packageName:tracebox_handler"

internal fun currentTrackerProcessName(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return android.app.Application.getProcessName()
    }
    val fromManager = try {
        context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            ?.firstOrNull { it.pid == android.os.Process.myPid() }
            ?.processName
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
    return fromManager ?: readBoundedProcessName()
}

private fun readBoundedProcessName(): String? {
    val bytes = ByteArray(MAX_PROCESS_NAME_BYTES)
    val count = try {
        FileInputStream("/proc/self/cmdline").use { input -> input.read(bytes) }
    } catch (_: IOException) {
        return null
    } catch (_: SecurityException) {
        return null
    }
    if (count <= 0) return null
    val length = (0 until count).firstOrNull { bytes[it] == 0.toByte() } ?: count
    return bytes.copyOfRange(0, length)
        .toString(Charsets.UTF_8)
        .takeIf(String::isNotBlank)
}

private const val MAX_PROCESS_NAME_BYTES = 256
