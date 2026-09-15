package com.adsamcik.tracker.app.tracking;

import android.content.Context;
import com.adsamcik.tracker.impexp.exporter.AmbientStepsPortableOrigin;
import com.adsamcik.tracker.impexp.exporter.AmbientStepsPortableSourceBackend;
import com.adsamcik.tracker.impexp.exporter.Exporter;
import com.adsamcik.tracker.impexp.exporter.PortableAmbientStepsExporter;
import com.adsamcik.tracker.impexp.importer.FileImportStream;
import com.adsamcik.tracker.impexp.importer.file.FileImport;
import com.adsamcik.tracker.impexp.importer.file.PortableAmbientStepsFileImport;
import com.adsamcik.tracker.impexp.importer.file.PortableAmbientStepsImportDependencies;
import com.adsamcik.tracker.impexp.portable.PortableAmbientStepsJsonV1Codec;
import com.adsamcik.tracker.shared.base.database.AppDatabase;
import com.adsamcik.tracker.shared.base.database.dao.ImportedAmbientStepsDao;
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore;
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientSteps;
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDay;
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientSteps;
import com.adsamcik.tracker.stats.api.repository.ReexportImportedAmbientSteps;
import com.adsamcik.tracker.stats.data.repository.ImportedAmbientStepsRoomReader;
import com.adsamcik.tracker.stats.data.repository.RoomExportPortableAmbientSteps;
import com.adsamcik.tracker.stats.data.repository.RoomImportPortableAmbientSteps;
import com.adsamcik.tracker.stats.data.repository.RoomDeleteImportedAmbientStepsDay;
import com.adsamcik.tracker.stats.data.repository.RoomReexportImportedAmbientSteps;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import kotlin.Unit;
import kotlinx.coroutines.CoroutineDispatcher;

/** Java bridge over Kotlin-internal file and Room implementations already owned by the app. */
final class PortableAmbientStepsFileInternals {
    private PortableAmbientStepsFileInternals() {}

    static Exporter nativeFileExporter(
            AppDatabase database,
            CoroutineDispatcher dispatcher
    ) {
        return fileExporter(database, dispatcher, AmbientStepsPortableOrigin.NATIVE);
    }

    static Exporter importedFileExporter(
            AppDatabase database,
            CoroutineDispatcher dispatcher
    ) {
        return fileExporter(database, dispatcher, AmbientStepsPortableOrigin.IMPORTED);
    }

    static FileImport fileImporter(
            AppDatabase database,
            CollectedDataLifecycleStore lifecycleStore,
            CoroutineDispatcher dispatcher
    ) {
        ImportPortableAmbientSteps importer = new RoomImportPortableAmbientSteps(
                database,
                importedDao(database),
                dispatcher
        );
        PortableAmbientStepsImportDependencies dependencies =
                new PortableAmbientStepsImportDependencies(importer, lifecycleStore);
        return new PortableAmbientStepsFileImport(
                ignored -> dependencies,
                new PortableAmbientStepsJsonV1Codec()
        );
    }

    static DeleteImportedAmbientStepsDay dayDeleter(
            AppDatabase database,
            CoroutineDispatcher dispatcher
    ) {
        return new RoomDeleteImportedAmbientStepsDay(
                database,
                importedDao(database),
                dispatcher
        );
    }

    static FileImportStream boundStream(
            byte[] bytes,
            String fileName,
            String entryKey,
            String jobId,
            long receivedAtMs
    ) {
        FileImportStream stream = new FileImportStream(
                fileName,
                entryKey,
                () -> new ByteArrayInputStream(bytes),
                () -> Unit.INSTANCE
        );
        try {
            Method binder = null;
            for (Method method : FileImportStream.class.getDeclaredMethods()) {
                if (method.getName().startsWith("withImportReceipt")) {
                    binder = method;
                    break;
                }
            }
            if (binder == null) {
                throw new IllegalStateException("FileImportStream receipt binder is missing");
            }
            binder.setAccessible(true);
            return (FileImportStream) binder.invoke(stream, jobId, receivedAtMs);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not bind the durable import receipt", failure);
        }
    }

    private static Exporter fileExporter(
            AppDatabase database,
            CoroutineDispatcher dispatcher,
            AmbientStepsPortableOrigin origin
    ) {
        ExportPortableAmbientSteps nativeExporter =
                new RoomExportPortableAmbientSteps(database, dispatcher);
        ReexportImportedAmbientSteps importedExporter =
                new RoomReexportImportedAmbientSteps(
                        new ImportedAmbientStepsRoomReader(
                                database,
                                importedDao(database),
                                dispatcher
                        ),
                        dispatcher
                );
        AmbientStepsPortableSourceBackend backend =
                new AmbientStepsPortableSourceBackend(nativeExporter, importedExporter);
        return new PortableAmbientStepsExporter(
                origin,
                (Context ignored) -> backend,
                new PortableAmbientStepsJsonV1Codec()
        );
    }

    private static ImportedAmbientStepsDao importedDao(AppDatabase database) {
        try {
            for (Method method : AppDatabase.class.getDeclaredMethods()) {
                if (method.getName().startsWith("importedAmbientStepsDao")) {
                    method.setAccessible(true);
                    return (ImportedAmbientStepsDao) method.invoke(database);
                }
            }
            throw new IllegalStateException("Imported Ambient Steps DAO accessor is missing");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not resolve the imported Ambient Steps DAO", failure);
        }
    }
}
