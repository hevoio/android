package com.hevodata.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import com.hevodata.android.util.HLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * SQLite database adapter for HevoAPI.
 *
 * <p>Not thread-safe. Instances of this class should only be used
 * by a single thread.
 */
class HDbAdapter {
    private static final String LOGTAG = "HevoAPI.Database";
    private static final Map<Context, HDbAdapter> sInstances = new HashMap<>();

    private static final String EVENTS_TABLE_NAME = "events";
    private static final String KEY_DATA = "data";
    private static final String KEY_CREATED_AT = "created_at";
    private static final String KEY_AUTOMATIC_DATA = "automatic_data";

    private static final int DB_UPDATE_ERROR = -1;
    public static final int DB_OUT_OF_MEMORY_ERROR = -2;
    public static final int DB_UNDEFINED_CODE = -3;

    private static final String DATABASE_NAME = "hevo";
    private static final int DATABASE_VERSION = 5;

    private static final String CREATE_EVENTS_TABLE =
            "CREATE TABLE " + EVENTS_TABLE_NAME + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    KEY_DATA + " TEXT NOT NULL, " +
                    KEY_CREATED_AT + " INTEGER NOT NULL, " +
                    KEY_AUTOMATIC_DATA + " INTEGER DEFAULT 0)";
    private static final String EVENTS_TIME_INDEX =
        "CREATE INDEX IF NOT EXISTS time_idx ON " + EVENTS_TABLE_NAME +
        " (" + KEY_CREATED_AT + ");";

    private final MPDatabaseHelper mDb;

    private static class MPDatabaseHelper extends SQLiteOpenHelper {
        MPDatabaseHelper(Context context, String dbName) {
            super(context, dbName, null, DATABASE_VERSION);
            mDatabaseFile = context.getDatabasePath(dbName);
            mConfig = HevoConfig.getInstance(context);
        }

        /**
         * Completely deletes the DB file from the file system.
         */
        void deleteDatabase() {
            close();
            mDatabaseFile.delete();
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            HLog.v(LOGTAG, "Creating a new Hevo events DB");

            db.execSQL(CREATE_EVENTS_TABLE);
            db.execSQL(EVENTS_TIME_INDEX);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            HLog.v(LOGTAG, "Upgrading app, replacing Hevo events DB");

//            if (newVersion == 5) {
//                migrateTableFrom4To5(db);
//            } else {
//                db.execSQL("DROP TABLE IF EXISTS " + Table.EVENTS.getName());
//                db.execSQL(CREATE_EVENTS_TABLE);
//                db.execSQL(EVENTS_TIME_INDEX);
//            }
        }

        boolean belowMemThreshold() {
            if (mDatabaseFile.exists()) {
                return Math.max(mDatabaseFile.getUsableSpace(), mConfig.getMinimumDatabaseLimit()) >= mDatabaseFile.length();
            }
            return true;
        }

        private final File mDatabaseFile;
        private final HevoConfig mConfig;
    }

    HDbAdapter(Context context) {
        this(context, DATABASE_NAME);
    }

    HDbAdapter(Context context, String dbName) {
        mDb = new MPDatabaseHelper(context, dbName);
    }

    public static HDbAdapter getInstance(Context context) {
        synchronized (sInstances) {
            final Context appContext = context.getApplicationContext();
            HDbAdapter ret;
            if (! sInstances.containsKey(appContext)) {
                ret = new HDbAdapter(appContext);
                sInstances.put(appContext, ret);
            } else {
                ret = sInstances.get(appContext);
            }
            return ret;
        }
    }

    /**
     * Adds a JSON string representing an event with properties or a person record
     * to the SQLiteDatabase.
     * @param j the JSON to record
     * @param isAutomaticRecord mark the record as an automatic event or not
     * @return the number of rows in the table, or DB_OUT_OF_MEMORY_ERROR/DB_UPDATE_ERROR
     * on failure
     */
    public int addJSON(JSONObject j, boolean isAutomaticRecord) {
        // we are aware of the race condition here, but what can we do..?
        if (!this.belowMemThreshold()) {
            HLog.e(LOGTAG, "There is not enough space left on the device to store Hevo data, so data was discarded");
            return DB_OUT_OF_MEMORY_ERROR;
        }

        Cursor c = null;
        int count = DB_UPDATE_ERROR;

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();

            final ContentValues cv = new ContentValues();
            cv.put(KEY_DATA, j.toString());
            cv.put(KEY_CREATED_AT, System.currentTimeMillis());
            cv.put(KEY_AUTOMATIC_DATA, isAutomaticRecord);
            db.insert(EVENTS_TABLE_NAME, null, cv);

            c = db.rawQuery("SELECT COUNT(*) FROM " + EVENTS_TABLE_NAME, null);
            c.moveToFirst();
            count = c.getInt(0);
        } catch (final SQLiteException e) {
            HLog.e(LOGTAG, "Could not add Hevo data to table " + EVENTS_TABLE_NAME + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            if (c != null) {
                c.close();
                c = null;
            }
            mDb.deleteDatabase();
        } finally {
            if (c != null) {
                c.close();
            }
            mDb.close();
        }
        return count;
    }

    /**
     * Removes events with an _id <= last_id from table
     * @param last_id the last id to delete
     * @param includeAutomaticEvents whether or not automatic events should be included in the cleanup
     */
    public void cleanupEvents(String last_id, boolean includeAutomaticEvents) {
        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            StringBuilder deleteQuery = new StringBuilder("_id <= " + last_id);

            if (!includeAutomaticEvents) {
                deleteQuery.append(" AND " + KEY_AUTOMATIC_DATA + "=0");
            }
            db.delete(EVENTS_TABLE_NAME, deleteQuery.toString(), null);
        } catch (final SQLiteException e) {
            HLog.e(LOGTAG, "Could not clean sent Hevo records from " + EVENTS_TABLE_NAME + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    /**
     * Removes events before time.
     * @param time the unix epoch in milliseconds to remove events before
     */
    public void cleanupEvents(long time) {
        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            db.delete(EVENTS_TABLE_NAME, KEY_CREATED_AT + " <= " + time, null);
        } catch (final SQLiteException e) {
            HLog.e(LOGTAG, "Could not clean timed-out Hevo records from " + EVENTS_TABLE_NAME + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    /**
     * Removes all events given a project token.
     */
    public void cleanupAllEvents() {
        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            db.delete(EVENTS_TABLE_NAME, null, null);
        } catch (final SQLiteException e) {
            HLog.e(LOGTAG, "Could not clean timed-out Hevo records from " + EVENTS_TABLE_NAME + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    /**
     * Removes automatic events.
     */
    public synchronized void cleanupAutomaticEvents() {
        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            db.delete(EVENTS_TABLE_NAME, KEY_AUTOMATIC_DATA + " = 1", null);
        } catch (final SQLiteException e) {
            HLog.e(LOGTAG, "Could not clean automatic Hevo records from " + EVENTS_TABLE_NAME + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    public void deleteDB() {
        mDb.deleteDatabase();
    }


    /**
     * Returns the data string to send to Hevo and the maximum ID of the row that
     * we're sending, so we know what rows to delete when a track request was successful.
     *
     * @param includeAutomaticEvents whether or not it should include pre-track records
     * @return String array containing the maximum ID, the data string
     * representing the events (or null if none could be successfully retrieved) and the total
     * current number of events in the queue.
     */
    public String[] generateDataString(boolean includeAutomaticEvents) {
        Cursor c = null;
        Cursor queueCountCursor = null;
        String data = null;
        String last_id = null;
        String queueCount = null;
        final SQLiteDatabase db = mDb.getReadableDatabase();

        try {
            StringBuilder rawDataQuery = new StringBuilder("SELECT * FROM " + EVENTS_TABLE_NAME);
            StringBuilder queueCountQuery = new StringBuilder("SELECT COUNT(*) FROM " + EVENTS_TABLE_NAME);
            if (!includeAutomaticEvents) {
                rawDataQuery.append(" WHERE " + KEY_AUTOMATIC_DATA + " = 0 ");
                queueCountQuery.append(" WHERE " + KEY_AUTOMATIC_DATA + " = 0");
            }

            rawDataQuery.append(" ORDER BY " + KEY_CREATED_AT + " ASC LIMIT 50");
            c = db.rawQuery(rawDataQuery.toString(), null);

            queueCountCursor = db.rawQuery(queueCountQuery.toString(), null);
            queueCountCursor.moveToFirst();
            queueCount = String.valueOf(queueCountCursor.getInt(0));

            final JSONArray arr = new JSONArray();

            while (c.moveToNext()) {
                if (c.isLast()) {
                    last_id = c.getString(c.getColumnIndex("_id"));
                }
                try {
                    final JSONObject j = new JSONObject(c.getString(c.getColumnIndex(KEY_DATA)));
                    arr.put(j);
                } catch (final JSONException e) {
                    // Ignore this object
                }
            }

            if (arr.length() > 0) {
                data = arr.toString();
            }
        } catch (final SQLiteException e) {
            HLog.e(LOGTAG, "Could not pull records for Hevo out of database " + EVENTS_TABLE_NAME + ". Waiting to send.", e);

            // We'll dump the DB on write failures, but with reads we can
            // let things ride in hopes the issue clears up.
            // (A bit more likely, since we're opening the DB for read and not write.)
            // A corrupted or disk-full DB will be cleaned up on the next write or clear call.
            last_id = null;
            data = null;
        } finally {
            mDb.close();
            if (c != null) {
                c.close();
            }
            if (queueCountCursor != null) {
                queueCountCursor.close();
            }
        }

        if (last_id != null && data != null) {
            return new String[]{last_id, data, queueCount};
        }
        return null;
    }

    public File getDatabaseFile() {
        return mDb.mDatabaseFile;
    }

    /* For testing use only, do not call from in production code */
    protected boolean belowMemThreshold() {
        return mDb.belowMemThreshold();
    }
}
