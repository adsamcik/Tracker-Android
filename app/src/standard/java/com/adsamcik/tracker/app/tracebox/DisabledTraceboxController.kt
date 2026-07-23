package com.adsamcik.tracker.app.tracebox

import android.content.Intent
import android.net.Uri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DisabledTraceboxController @Inject constructor() : TraceboxController {
    private val mutableState = MutableStateFlow(
        TraceboxUiState(TraceboxAvailability.UNAVAILABLE, enabled = false),
    )
    override val state: StateFlow<TraceboxUiState> = mutableState

    override fun setEnabled(enabled: Boolean) = Unit
    override fun prepareStandardPackage() = Unit
    override fun approvalIntent(): Intent? = null
    override fun acceptApprovalResult(result: Intent?) = Unit
    override fun shareIntent(): Intent? = null
    override fun createSaveIntent(): Intent? = null
    override fun save(destination: Uri) = Unit
    override fun cancelPackage() = Unit
    override fun deleteAllTraceboxData() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StandardTraceboxModule {
    @Binds
    abstract fun bindTraceboxController(controller: DisabledTraceboxController): TraceboxController
}
