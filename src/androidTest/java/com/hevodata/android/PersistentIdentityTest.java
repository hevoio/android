package com.hevodata.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.test.AndroidTestCase;

import com.hevodata.android.PersistentIdentity;
import com.hevodata.android.SharedPreferencesLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

public class PersistentIdentityTest extends AndroidTestCase {
    public void setUp() {
        SharedPreferences referrerPrefs = getContext().getSharedPreferences(TEST_REFERRER_PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences.Editor referrerEditor = referrerPrefs.edit();
        referrerEditor.clear();
        referrerEditor.putString("referrer", "REFERRER");
        referrerEditor.putString("utm_source", "SOURCE VALUE");
        referrerEditor.putString("utm_medium", "MEDIUM VALUE");
        referrerEditor.putString("utm_campaign", "CAMPAIGN NAME VALUE");
        referrerEditor.putString("utm_content", "CONTENT VALUE");
        referrerEditor.putString("utm_term", "TERM VALUE");
        referrerEditor.commit();

        SharedPreferences testPreferences = getContext().getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences.Editor prefsEditor = testPreferences.edit();
        prefsEditor.clear();
        prefsEditor.putString("events_distinct_id", "EVENTS DISTINCT ID");
        prefsEditor.putString("people_distinct_id", "PEOPLE DISTINCT ID");
        prefsEditor.putString("push_id", "PUSH ID");
        prefsEditor.putString("waiting_array", "[ {\"thing\": 1}, {\"thing\": 2} ]");
        prefsEditor.putString("super_properties", "{\"thing\": \"superprops\"}");
        prefsEditor.commit();

        SharedPreferences timeEventsPreferences = getContext().getSharedPreferences(TEST_TIME_EVENTS_PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences.Editor timeEventsEditor = timeEventsPreferences.edit();
        timeEventsEditor.clear();
        timeEventsEditor.commit();

        SharedPreferencesLoader loader = new SharedPreferencesLoader();
        Future<SharedPreferences> referrerLoader = loader.loadPreferences(getContext(), TEST_REFERRER_PREFERENCES, null);
        Future<SharedPreferences> testLoader = loader.loadPreferences(getContext(), TEST_PREFERENCES, null);
        Future<SharedPreferences> timeEventsLoader = loader.loadPreferences(getContext(), TEST_TIME_EVENTS_PREFERENCES, null);
        Future<SharedPreferences> hevoLoader = loader.loadPreferences(getContext(), TEST_HEVO_PREFERENCES, null);

        mPersistentIdentity = new PersistentIdentity(referrerLoader, testLoader, timeEventsLoader, hevoLoader);
    }

    public void testReferrerProperties() {
        final Map<String, String> props = mPersistentIdentity.getReferrerProperties();
        assertEquals("REFERRER", props.get("referrer"));
        assertEquals("SOURCE VALUE", props.get("utm_source"));
        assertEquals("MEDIUM VALUE", props.get("utm_medium"));
        assertEquals("CAMPAIGN NAME VALUE", props.get("utm_campaign"));
        assertEquals("CONTENT VALUE", props.get("utm_content"));
        assertEquals("TERM VALUE", props.get("utm_term"));

        final Map<String, String> newPrefs = new HashMap<String, String>();
        newPrefs.put("referrer", "BJORK");
        newPrefs.put("mystery", "BOO!");
        newPrefs.put("utm_term", "NEW TERM");
        PersistentIdentity.writeReferrerPrefs(getContext(), TEST_REFERRER_PREFERENCES, newPrefs);

        final Map<String, String> propsAfterChange = mPersistentIdentity.getReferrerProperties();
        assertFalse(propsAfterChange.containsKey("utm_medium"));
        assertFalse(propsAfterChange.containsKey("utm_source"));
        assertFalse(propsAfterChange.containsKey("utm_campaign"));
        assertFalse(propsAfterChange.containsKey("utm_content"));
        assertEquals("BJORK", propsAfterChange.get("referrer"));
        assertEquals("NEW TERM", propsAfterChange.get("utm_term"));
        assertEquals("BOO!", propsAfterChange.get("mystery"));
    }

    public void testUnsetEventsId() {
        final SharedPreferences testPreferences = getContext().getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE);
        testPreferences.edit().clear().commit();
        final String eventsId = mPersistentIdentity.getEventsDistinctId();
        assertTrue(Pattern.matches("^[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}$", eventsId));

        final String autoId = testPreferences.getString("events_distinct_id", "NOPE");
        assertEquals(autoId, eventsId);

        mPersistentIdentity.setEventsDistinctId("TEST ID TO SET");
        final String heardId = mPersistentIdentity.getEventsDistinctId();
        assertEquals("TEST ID TO SET", heardId);

        final String storedId = testPreferences.getString("events_distinct_id", "NOPE");
        assertEquals("TEST ID TO SET", storedId);
    }

    public void testPushId() {
        final String pushId = mPersistentIdentity.getPushId();
        assertEquals("PUSH ID", pushId);

        mPersistentIdentity.clearPushId();
        final String noId = mPersistentIdentity.getPushId();
        assertNull(noId);

        mPersistentIdentity.storePushId("STORED PUSH ID");
        final String storedId = mPersistentIdentity.getPushId();
        assertEquals("STORED PUSH ID", storedId);

        final SharedPreferences testPreferences = getContext().getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE);
        assertEquals("STORED PUSH ID", testPreferences.getString("push_id", "FAIL"));
    }

    private PersistentIdentity mPersistentIdentity;
    private static final String TEST_PREFERENCES = "TEST PERSISTENT PROPERTIES PREFS";
    private static final String TEST_REFERRER_PREFERENCES  = "TEST REFERRER PREFS";
    private static final String TEST_TIME_EVENTS_PREFERENCES  = "TEST TIME EVENTS PREFS";
    private static final String TEST_HEVO_PREFERENCES  = "TEST HEVOPREFS";
}
