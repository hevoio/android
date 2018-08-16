package com.hevodata.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Message;

import com.hevodata.android.HevoAPI;
import com.hevodata.android.PersistentIdentity;
import com.hevodata.android.ResourceIds;

import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TestUtils {
    public static byte[] bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("This is not an android device, or a compatible java. WHO ARE YOU?");
        }
    }

    public static class CleanHevoAPI extends HevoAPI {
        public CleanHevoAPI(final Context context, final Future<SharedPreferences> referrerPreferences) {
            super(context, referrerPreferences, false);
        }

        @Override
        PersistentIdentity getPersistentIdentity(final Context context, final Future<SharedPreferences> referrerPreferences) {
            final String prefsName = "com.hevodata.android.HevoAPI_";
            final SharedPreferences ret = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            ret.edit().clear().commit();

            final String timeEventsPrefsName = "com.hevodata.android.HevoAPI.TimeEvents_";
            final SharedPreferences timeSharedPrefs = context.getSharedPreferences(timeEventsPrefsName, Context.MODE_PRIVATE);
            timeSharedPrefs.edit().clear().commit();

            final String hevoPrefsName = "com.hevodata.android.Hevo";
            final SharedPreferences mpSharedPrefs = context.getSharedPreferences(hevoPrefsName, Context.MODE_PRIVATE);
            mpSharedPrefs.edit().clear().putBoolean("has_launched", true).apply();

            return super.getPersistentIdentity(context, referrerPreferences);
        }

        @Override
        boolean sendAppOpen() {
            return false;
        }
    }

    public static class TestResourceIds implements ResourceIds {
        public TestResourceIds(final Map<String, Integer> anIdMap) {
            mIdMap = anIdMap;
        }

        @Override
        public boolean knownIdName(String name) {
            return mIdMap.containsKey(name);
        }

        @Override
        public int idFromName(String name) {
            return mIdMap.get(name);
        }

        @Override
        public String nameForId(int id) {
            for (Map.Entry<String, Integer> entry : mIdMap.entrySet()) {
                if (entry.getValue() == id) {
                    return entry.getKey();
                }
            }

            return null;
        }

        private final Map<String, Integer> mIdMap;
    }

    public static class EmptyPreferences implements Future<SharedPreferences> {
        public EmptyPreferences(Context context) {
            mPrefs = context.getSharedPreferences("HEVO_TEST_PREFERENCES", Context.MODE_PRIVATE);
            mPrefs.edit().clear().commit();
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public SharedPreferences get() throws InterruptedException, ExecutionException {
            return mPrefs;
        }

        @Override
        public SharedPreferences get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
        {
            return mPrefs;
        }

        private SharedPreferences mPrefs;
    };

    /**
     * Stub/Mock handler that just runs stuff synchronously
     */
    public static class SynchronousHandler extends Handler {
        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            dispatchMessage(msg);
            return true;
        }
    }

}
