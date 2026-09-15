package com.adsamcik.tracker.app.tracking;

import com.adsamcik.tracker.shared.base.database.AppDatabase;
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority;
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate;
import com.adsamcik.tracker.shared.base.time.Clock;
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore;
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker;
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps;
import com.adsamcik.tracker.stats.api.repository.ImportPortableSteps;
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository;
import com.adsamcik.tracker.stats.data.repository.DefaultTrackingHistoryRepository;
import com.adsamcik.tracker.stats.data.repository.LogicalTrackingHistoryReader;
import com.adsamcik.tracker.stats.data.repository.PortableStepsRoomReader;
import com.adsamcik.tracker.stats.data.repository.RoomExportPortableSteps;
import com.adsamcik.tracker.stats.data.repository.StepsSegmentHistorySelector;
import com.adsamcik.tracker.tracker.source.importer.RoomImportPortableSteps;
import kotlinx.coroutines.CoroutineDispatcher;

/**
 * Java-only test bridge over Kotlin-internal production implementations from sibling modules.
 *
 * The application already owns these modules at runtime. Keeping this bridge in the test source set
 * avoids widening production implementation visibility or adding a synthetic coordinator solely
 * for a cross-module contract test.
 */
final class PortableStepsRoundTripInternals {
    private PortableStepsRoundTripInternals() {}

    static ExportPortableSteps exporter(
            AppDatabase database,
            SourceProductLaneExecutionAuthority laneAuthority,
            CoroutineDispatcher dispatcher
    ) {
        StepsSegmentHistorySelector selector =
                new StepsSegmentHistorySelector(database, laneAuthority);
        return new RoomExportPortableSteps(
                new PortableStepsRoomReader(database, selector, laneAuthority),
                dispatcher
        );
    }

    static ImportPortableSteps importer(
            AppDatabase database,
            CollectedDataLifecycleStore lifecycleStore,
            TrackingStartupGate startupGate,
            Clock clock,
            CoroutineDispatcher dispatcher,
            MetricDirtyTracker dirtyTracker,
            ExportPortableSteps nativeExporter
    ) {
        return new RoomImportPortableSteps(
                database,
                lifecycleStore,
                startupGate,
                clock,
                dispatcher,
                dirtyTracker,
                nativeExporter
        );
    }

    static TrackingHistoryRepository history(
            AppDatabase database,
            SourceProductLaneExecutionAuthority laneAuthority,
            CoroutineDispatcher dispatcher
    ) {
        StepsSegmentHistorySelector selector =
                new StepsSegmentHistorySelector(database, laneAuthority);
        return new DefaultTrackingHistoryRepository(
                database,
                selector,
                new LogicalTrackingHistoryReader(database, selector),
                dispatcher
        );
    }
}
