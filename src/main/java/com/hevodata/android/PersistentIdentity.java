package com.hevodata.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import com.hevodata.android.util.HLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

// In order to use writeEdits, we have to suppress the linter's check for commit()/apply()
@SuppressLint("CommitPrefEdits")
class PersistentIdentity {

    // Should ONLY be called from an OnPrefsLoadedListener (since it should NEVER be called concurrently)
    public static JSONArray waitingPeopleRecordsForSending(SharedPreferences storedPreferences) {
        JSONArray ret = null;
        final String peopleDistinctId = storedPreferences.getString("people_distinct_id", null);
        final String waitingPeopleRecords = storedPreferences.getString("waiting_array", null);
        if ((null != waitingPeopleRecords) && (null != peopleDistinctId)) {
            JSONArray waitingObjects = null;
            try {
                waitingObjects = new JSONArray(waitingPeopleRecords);
            } catch (final JSONException e) {
                HLog.e(LOGTAG, "Waiting people records were unreadable.");
                return null;
            }

            ret = new JSONArray();
            for (int i = 0; i < waitingObjects.length(); i++) {
                try {
                    final JSONObject ob = waitingObjects.getJSONObject(i);
                    ob.put("$distinct_id", peopleDistinctId);
                    ret.put(ob);
                } catch (final JSONException e) {
                    HLog.e(LOGTAG, "Unparsable object found in waiting people records", e);
                }
            }

            final SharedPreferences.Editor editor = storedPreferences.edit();
            editor.remove("waiting_array");
            writeEdits(editor);
        }
        return ret;
    }

    public static void writeReferrerPrefs(Context context, String preferencesName, Map<String, String> properties) {
        synchronized (sReferrerPrefsLock) {
            final SharedPreferences referralInfo = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
            final SharedPreferences.Editor editor = referralInfo.edit();
            editor.clear();
            for (final Map.Entry<String, String> entry : properties.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            writeEdits(editor);
            sReferrerPrefsDirty = true;
        }
    }

    public PersistentIdentity(Future<SharedPreferences> referrerPreferences, Future<SharedPreferences> storedPreferences, Future<SharedPreferences> timeEventsPreferences, Future<SharedPreferences> hevoPreferences) {
        mLoadReferrerPreferences = referrerPreferences;
        mLoadStoredPreferences = storedPreferences;
        mTimeEventsPreferences = timeEventsPreferences;
        mHevoPreferences = hevoPreferences;
        mSuperPropertiesCache = null;
        mReferrerPropertiesCache = null;
        mIdentitiesLoaded = false;
        mReferrerChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                synchronized (sReferrerPrefsLock) {
                    readReferrerProperties();
                    sReferrerPrefsDirty = false;
                }
            }
        };
    }

    public synchronized void addSuperPropertiesToObject(JSONObject ob, String eventName) {
        if (eventName != null && !eventName.equals(ReservedEvents.INSTALLATION)) {
            return;
        }
        final JSONObject superProperties = this.getSuperPropertiesCache();
        final Iterator<?> superIter = superProperties.keys();
        while (superIter.hasNext()) {
            final String key = (String) superIter.next();

            try {
                ob.put(key, superProperties.get(key));
            } catch (JSONException e) {
                HLog.e(LOGTAG, "Object read from one JSON Object cannot be written to another", e);
            }
        }
    }

    public synchronized void updateSuperProperties(SuperPropertyUpdate updates) {
        final JSONObject oldPropCache = getSuperPropertiesCache();
        final JSONObject copy = new JSONObject();

        try {
            final Iterator<String> keys = oldPropCache.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                final Object v = oldPropCache.get(k);
                copy.put(k, v);
            }
        } catch (JSONException e) {
            HLog.e(LOGTAG, "Can't copy from one JSONObject to another", e);
            return;
        }

        final JSONObject replacementCache = updates.update(copy);
        if (replacementCache == null) {
            HLog.w(LOGTAG, "An update to Hevo's super properties returned null, and will have no effect.");
            return;
        }

        mSuperPropertiesCache = replacementCache;
        storeSuperProperties();
    }

    public Map<String, String> getReferrerProperties() {
        synchronized (sReferrerPrefsLock) {
            if (sReferrerPrefsDirty || null == mReferrerPropertiesCache) {
                readReferrerProperties();
                sReferrerPrefsDirty = false;
            }
        }
        return mReferrerPropertiesCache;
    }

    public void clearReferrerProperties() {
        synchronized (sReferrerPrefsLock) {
            try {
                final SharedPreferences referrerPrefs = mLoadReferrerPreferences.get();
                final SharedPreferences.Editor prefsEdit = referrerPrefs.edit();
                prefsEdit.clear();
                writeEdits(prefsEdit);
            } catch (final ExecutionException e) {
                HLog.e(LOGTAG, "Cannot load referrer properties from shared preferences.", e.getCause());
            } catch (final InterruptedException e) {
                HLog.e(LOGTAG, "Cannot load referrer properties from shared preferences.", e);
            }
        }
    }

    public synchronized String getEventsDistinctId() {
        if (! mIdentitiesLoaded) {
            readIdentities();
        }
        return mEventsDistinctId;
    }

    public synchronized void setEventsDistinctId(String eventsDistinctId) {
        if (! mIdentitiesLoaded) {
            readIdentities();
        }

        mEventsDistinctId = eventsDistinctId;
        writeIdentities();
    }

    public synchronized void clearPreferences() {
        // Will clear distinct_ids, superProperties,
        // and waiting People Analytics properties. Will have no effect
        // on messages already queued to send with AnalyticsMessages.

        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor prefsEdit = prefs.edit();
            prefsEdit.clear();
            writeEdits(prefsEdit);
            readSuperProperties();
            readIdentities();
        } catch (final ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (final InterruptedException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public void clearTimeEvents() {
        try {
            final SharedPreferences prefs = mTimeEventsPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.clear();
            writeEdits(editor);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    public synchronized void registerSuperProperties(JSONObject superProperties) {
        final JSONObject propCache = getSuperPropertiesCache();

        for (final Iterator<?> iter = superProperties.keys(); iter.hasNext(); ) {
            final String key = (String) iter.next();
            try {
               propCache.put(key, superProperties.get(key));
            } catch (final JSONException e) {
                HLog.e(LOGTAG, "Exception registering super property.", e);
            }
        }

        storeSuperProperties();
    }

    public synchronized void storePushId(String registrationId) {
        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putString("push_id", registrationId);
            writeEdits(editor);
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Can't write push id to shared preferences", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Can't write push id to shared preferences", e);
        }
    }

    public synchronized void clearPushId() {
        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.remove("push_id");
            writeEdits(editor);
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Can't write push id to shared preferences", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Can't write push id to shared preferences", e);
        }
    }

    public synchronized String getPushId() {
        String ret = null;
        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            ret = prefs.getString("push_id", null);
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Can't write push id to shared preferences", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Can't write push id to shared preferences", e);
        }
        return ret;
    }

    public synchronized void unregisterSuperProperty(String superPropertyName) {
        final JSONObject propCache = getSuperPropertiesCache();
        try {
            Object propertyValue = propCache.get(superPropertyName);
            if (propertyValue instanceof JSONObject) {
                propertyValue = nullifyJsonObject((JSONObject) propertyValue);
            } else if (propertyValue instanceof JSONArray) {
                propertyValue = nullifyJsonArray((JSONArray) propertyValue);
            } else {
                propertyValue = null;
            }
            propCache.put(superPropertyName, propertyValue);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        storeSuperProperties();
    }

    private JSONObject nullifyJsonObject(JSONObject jsonObject) {
        try {
            for (final Iterator<?> iterator = jsonObject.keys(); iterator.hasNext(); ) {
                final String key = (String) iterator.next();
                Object value = jsonObject.get(key);
                if (value instanceof JSONObject) {
                    value = nullifyJsonObject((JSONObject) value);
                } else if (value instanceof JSONArray) {
                    value = nullifyJsonArray((JSONArray) value);
                } else {
                    value = null;
                }
                jsonObject.put(key, value);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject;
    }

    private JSONArray nullifyJsonArray(JSONArray jsonArray) {
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                Object value = jsonArray.get(i);
                if (value instanceof JSONObject) {
                    value = nullifyJsonObject((JSONObject) value);
                } else if (value instanceof JSONArray) {
                    value = nullifyJsonArray((JSONArray) value);
                } else {
                    value = null;
                }
                jsonArray.put(i, value);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonArray;
    }

    public Map<String, Long> getTimeEvents() {
        Map<String, Long> timeEvents = new HashMap<>();

        try {
            final SharedPreferences prefs = mTimeEventsPreferences.get();

            Map<String, ?> allEntries = prefs.getAll();
            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                timeEvents.put(entry.getKey(), Long.valueOf(entry.getValue().toString()));
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        return timeEvents;
    }

    // access is synchronized outside (mEventTimings)
    public void removeTimeEvent(String timeEventName) {
        try {
            final SharedPreferences prefs = mTimeEventsPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.remove(timeEventName);
            writeEdits(editor);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    // access is synchronized outside (mEventTimings)
    public void addTimeEvent(String timeEventName, Long timeEventTimestamp) {
        try {
            final SharedPreferences prefs = mTimeEventsPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(timeEventName, timeEventTimestamp);
            writeEdits(editor);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    public synchronized void registerSuperPropertiesOnce(JSONObject superProperties) {
        final JSONObject propCache = getSuperPropertiesCache();

        for (final Iterator<?> iter = superProperties.keys(); iter.hasNext(); ) {
            final String key = (String) iter.next();
            if (! propCache.has(key)) {
                try {
                    propCache.put(key, superProperties.get(key));
                } catch (final JSONException e) {
                    HLog.e(LOGTAG, "Exception registering super property.", e);
                }
            }
        }// for

        storeSuperProperties();
    }

    public synchronized void clearSuperProperties() {
        mSuperPropertiesCache = nullifyJsonObject(getSuperPropertiesCache());
        storeSuperProperties();
    }

    public synchronized void resetSuperProperties() {
        mSuperPropertiesCache = new JSONObject();
        storeSuperProperties();
    }

    public synchronized void resetSuperProperties(String key) {
        final JSONObject propCache = getSuperPropertiesCache();
        propCache.remove(key);
        storeSuperProperties();
    }

    public synchronized boolean isFirstIntegration() {
        boolean firstLaunch = false;
        try {
            SharedPreferences prefs = mHevoPreferences.get();
            firstLaunch = prefs.getBoolean("app_is_first_launch", false);
        }  catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Couldn't read internal Hevo shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Couldn't read internal Hevo from shared preferences.", e);
        }
        return firstLaunch;
    }

    public synchronized void setIsIntegrated() {
        try {
            SharedPreferences.Editor hevoEditor = mHevoPreferences.get().edit();
            hevoEditor.putBoolean("app_is_first_launch", true);
            writeEdits(hevoEditor);
        } catch (ExecutionException e) {
            HLog.e(LOGTAG, "Couldn't write internal Hevo shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            HLog.e(LOGTAG, "Couldn't write internal Hevo from shared preferences.", e);
        }
    }

    public synchronized boolean isNewVersion(String versionCode) {
        if (versionCode == null) {
            return false;
        }

        Integer version = Integer.valueOf(versionCode);
        try {
            if (sPreviousVersionCode == null) {
                SharedPreferences hevoPreferences = mHevoPreferences.get();
                sPreviousVersionCode = hevoPreferences.getInt("latest_version_code", -1);
                if (sPreviousVersionCode == -1) {
                    sPreviousVersionCode = version;
                    SharedPreferences.Editor hevoPreferencesEditor = mHevoPreferences.get().edit();
                    hevoPreferencesEditor.putInt("latest_version_code", version);
                    writeEdits(hevoPreferencesEditor);
                }
            }

            if (sPreviousVersionCode.intValue() < version.intValue()) {
                SharedPreferences.Editor hevoPreferencesEditor = mHevoPreferences.get().edit();
                hevoPreferencesEditor.putInt("latest_version_code", version);
                writeEdits(hevoPreferencesEditor);
                return true;
            }
        } catch (ExecutionException e) {
            HLog.e(LOGTAG, "Couldn't write internal Hevo shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            HLog.e(LOGTAG, "Couldn't write internal Hevo from shared preferences.", e);
        }

        return false;
    }

    public synchronized boolean isFirstLaunch(boolean dbExists) {
        if (sIsFirstAppLaunch == null) {
            try {
                SharedPreferences hevoPreferences = mHevoPreferences.get();
                boolean hasLaunched = hevoPreferences.getBoolean("has_launched", false);
                if (hasLaunched) {
                    sIsFirstAppLaunch = false;
                } else {
                    sIsFirstAppLaunch = !dbExists;
                }
            } catch (ExecutionException e) {
                sIsFirstAppLaunch = false;
            } catch (InterruptedException e) {
                sIsFirstAppLaunch = false;
            }
        }

        return sIsFirstAppLaunch;
    }

    public synchronized void setHasLaunched() {
        try {
            SharedPreferences.Editor hevoPreferencesEditor = mHevoPreferences.get().edit();
            hevoPreferencesEditor.putBoolean("has_launched", true);
            writeEdits(hevoPreferencesEditor);
        } catch (ExecutionException e) {
            HLog.e(LOGTAG, "Couldn't write internal Hevo shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            HLog.e(LOGTAG, "Couldn't write internal Hevo shared preferences.", e);
        }
    }

    public synchronized void setOptOutTracking(boolean optOutTracking) {
        mIsUserOptOut = optOutTracking;
        writeOptOutFlag();
    }

    public synchronized boolean getOptOutTracking() {
        if (mIsUserOptOut == null) {
            readOptOutFlag();
        }

        return mIsUserOptOut;
    }

    //////////////////////////////////////////////////

    // Must be called from a synchronized setting
    private JSONObject getSuperPropertiesCache() {
        if (mSuperPropertiesCache == null) {
            readSuperProperties();
        }
        return mSuperPropertiesCache;
    }

    // All access should be synchronized on this
    private void readSuperProperties() {
        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final String props = prefs.getString("super_properties", "{}");
            HLog.v(LOGTAG, "Loading Super Properties " + props);
            mSuperPropertiesCache = new JSONObject(props);
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Cannot load superProperties from SharedPreferences.", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Cannot load superProperties from SharedPreferences.", e);
        } catch (final JSONException e) {
            HLog.e(LOGTAG, "Cannot parse stored superProperties");
            storeSuperProperties();
        } finally {
            if (mSuperPropertiesCache == null) {
                mSuperPropertiesCache = new JSONObject();
            }
        }
    }

    // All access should be synchronized on this
    private void readReferrerProperties() {
        mReferrerPropertiesCache = new HashMap<String, String>();

        try {
            final SharedPreferences referrerPrefs = mLoadReferrerPreferences.get();
            referrerPrefs.unregisterOnSharedPreferenceChangeListener(mReferrerChangeListener);
            referrerPrefs.registerOnSharedPreferenceChangeListener(mReferrerChangeListener);

            final Map<String, ?> prefsMap = referrerPrefs.getAll();
            for (final Map.Entry<String, ?> entry : prefsMap.entrySet()) {
                final String prefsName = entry.getKey();
                final Object prefsVal = entry.getValue();
                mReferrerPropertiesCache.put(prefsName, prefsVal.toString());
            }
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Cannot load referrer properties from shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Cannot load referrer properties from shared preferences.", e);
        }
    }

    // All access should be synchronized on this
    private void storeSuperProperties() {
        if (mSuperPropertiesCache == null) {
            HLog.e(LOGTAG, "storeSuperProperties should not be called with uninitialized superPropertiesCache.");
            return;
        }

        final String props = mSuperPropertiesCache.toString();
        HLog.v(LOGTAG, "Storing Super Properties " + props);

        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putString("super_properties", props);
            writeEdits(editor);
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Cannot store superProperties in shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Cannot store superProperties in shared preferences.", e);
        }
    }

    // All access should be synchronized on this
    private void readIdentities() {
        SharedPreferences prefs = null;
        try {
            prefs = mLoadStoredPreferences.get();
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Cannot read distinct ids from sharedPreferences.", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Cannot read distinct ids from sharedPreferences.", e);
        }

        if (prefs == null) {
            return;
        }

        mEventsDistinctId = prefs.getString("events_distinct_id", null);
        mPeopleDistinctId = prefs.getString("people_distinct_id", null);
        mWaitingPeopleRecords = null;

        final String storedWaitingRecord = prefs.getString("waiting_array", null);
        if (storedWaitingRecord != null) {
            try {
                mWaitingPeopleRecords = new JSONArray(storedWaitingRecord);
            } catch (final JSONException e) {
                HLog.e(LOGTAG, "Could not interpret waiting people JSON record " + storedWaitingRecord);
            }
        }

        if (mEventsDistinctId == null) {
            mEventsDistinctId = UUID.randomUUID().toString();
            writeIdentities();
        }

        mIdentitiesLoaded = true;
    }

    private void readOptOutFlag() {
        SharedPreferences prefs = null;
        try {
            prefs = mHevoPreferences.get();
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Cannot read opt out flag from sharedPreferences.", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Cannot read opt out flag from sharedPreferences.", e);
        }

        if (prefs == null) {
            return;
        }

        mIsUserOptOut = prefs.getBoolean("opt_out", false);
    }

    private void writeOptOutFlag() {
        try {
            final SharedPreferences prefs = mHevoPreferences.get();
            final SharedPreferences.Editor prefsEditor = prefs.edit();
            prefsEditor.putBoolean("opt_out", mIsUserOptOut);
            writeEdits(prefsEditor);
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Can't write opt-out shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Can't write opt-out shared preferences.", e);
        }
    }
    // All access should be synchronized on this
    private void writeIdentities() {
        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor prefsEditor = prefs.edit();

            prefsEditor.putString("events_distinct_id", mEventsDistinctId);
            prefsEditor.putString("people_distinct_id", mPeopleDistinctId);
            if (mWaitingPeopleRecords == null) {
                prefsEditor.remove("waiting_array");
            } else {
                prefsEditor.putString("waiting_array", mWaitingPeopleRecords.toString());
            }
            writeEdits(prefsEditor);
        } catch (final ExecutionException e) {
            HLog.e(LOGTAG, "Can't write distinct ids to shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            HLog.e(LOGTAG, "Can't write distinct ids to shared preferences.", e);
        }
    }

    private static void writeEdits(final SharedPreferences.Editor editor) {
        editor.apply();
    }

    private final Future<SharedPreferences> mLoadStoredPreferences;
    private final Future<SharedPreferences> mLoadReferrerPreferences;
    private final Future<SharedPreferences> mTimeEventsPreferences;
    private final Future<SharedPreferences> mHevoPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener mReferrerChangeListener;
    private JSONObject mSuperPropertiesCache;
    private Map<String, String> mReferrerPropertiesCache;
    private boolean mIdentitiesLoaded;
    private String mEventsDistinctId;
    private String mPeopleDistinctId;
    private JSONArray mWaitingPeopleRecords;
    private Boolean mIsUserOptOut;
    private static Integer sPreviousVersionCode;
    private static Boolean sIsFirstAppLaunch;

    private static boolean sReferrerPrefsDirty = true;
    private static final Object sReferrerPrefsLock = new Object();
    private static final String DELIMITER = ",";
    private static final String LOGTAG = "HevoAPI.PIdentity";
}
