package org.sqlite.database.sqlite;

/**
 * Adds the missing read-only entry point to the pinned SQLite Android binding.
 *
 * <p>The binding exposes its session transaction modes but not a database-level deferred
 * transaction method. This package bridge mirrors SQLiteDatabase's reference handling and uses
 * its own read-only connection flags. The caller must keep the transaction on one thread and
 * finish it through the ordinary setTransactionSuccessful/endTransaction methods.
 */
public final class SQLiteReadOnlyTransactionBridge {
    private SQLiteReadOnlyTransactionBridge() {}

    /** Begins a deferred transaction on the current thread's read-only pooled connection. */
    public static void begin(SQLiteDatabase database, SQLiteTransactionListener listener) {
        database.acquireReference();
        try {
            database.getThreadSession().beginTransaction(
                    SQLiteSession.TRANSACTION_MODE_DEFERRED,
                    listener,
                    database.getThreadDefaultConnectionFlags(true),
                    null);
        } finally {
            database.releaseReference();
        }
    }
}
