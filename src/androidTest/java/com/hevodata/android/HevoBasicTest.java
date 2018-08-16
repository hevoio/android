package com.hevodata.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;

import com.hevodata.android.util.HttpService;
import com.hevodata.android.util.RemoteService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

public class HevoBasicTest extends AndroidTestCase {

    @Override
    protected void setUp() throws Exception {
        mMockPreferences = new TestUtils.EmptyPreferences(getContext());
        AnalyticsMessages messages = AnalyticsMessages.getInstance(getContext());
        messages.hardKill();
        Thread.sleep(2000);

        try {
            SystemInformation systemInformation = SystemInformation.getInstance(mContext);

            final StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("&properties=");
            JSONObject properties = new JSONObject();
            properties.putOpt("$android_lib_version", HevoConfig.VERSION);
            properties.putOpt("$android_app_version", systemInformation.getAppVersionName());
            properties.putOpt("$android_version", Build.VERSION.RELEASE);
            properties.putOpt("$android_app_release", systemInformation.getAppVersionCode());
            properties.putOpt("$android_device_model", Build.MODEL);
            queryBuilder.append(URLEncoder.encode(properties.toString(), "utf-8"));
            mAppProperties = queryBuilder.toString();
        } catch (Exception ignored) {}
    } // end of setUp() method definition

    public void testVersionsMatch() {
        assertEquals(BuildConfig.HEVO_VERSION, HevoConfig.VERSION);
    }

    public void testGeneratedDistinctId() {
        HevoAPI hevo = new TestUtils.CleanHevoAPI(getContext(), mMockPreferences);
        String generatedId1 = hevo.getDistinctId();
        assertNotNull(generatedId1);

        hevo.reset();
        String generatedId2 = hevo.getDistinctId();
        assertNotNull(generatedId2);
        assertTrue(generatedId1.equals(generatedId2));
    }

    public void testDeleteDB() {
        Map<String, String> beforeMap = new HashMap<String, String>();
        beforeMap.put("added", "before");
        JSONObject before = new JSONObject(beforeMap);

        Map<String, String> afterMap = new HashMap<String,String>();
        afterMap.put("added", "after");
        JSONObject after = new JSONObject(afterMap);

        HDbAdapter adapter = new HDbAdapter(getContext(), "DeleteTestDB");
        adapter.addJSON(before, true);
        adapter.deleteDB();

        String[] emptyEventsData = adapter.generateDataString(true);
        assertEquals(emptyEventsData, null);

        adapter.addJSON(after, true);

        try {
            String[] someEventsData = adapter.generateDataString(true);
            JSONArray someEvents = new JSONArray(someEventsData[1]);
            assertEquals(someEvents.length(), 1);
            assertEquals(someEvents.getJSONObject(0).get("added"), "after");
        } catch (JSONException e) {
            fail("Unexpected JSON or lack thereof in HDbAdapter test");
        }
    }

    public void testLooperDestruction() {

        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();

        final HDbAdapter explodingDb = new HDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject message, boolean isAutomatic) {
                if (!isAutomatic) {
                    messages.add(message);
                    throw new RuntimeException("BANG!");
                }

                return 0;
            }
        };

        final AnalyticsMessages explodingMessages = new AnalyticsMessages(getContext()) {
            // This will throw inside of our worker thread.
            @Override
            public HDbAdapter makeDbAdapter(Context context) {
                return explodingDb;
            }
        };
        HevoAPI hevo = new TestUtils.CleanHevoAPI(getContext(), mMockPreferences) {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return explodingMessages;
            }
        };

        try {
            hevo.reset();
            assertFalse(explodingMessages.isDead());

            hevo.track("event1", null);
            JSONObject found = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(found);
            Thread.sleep(1000);
            assertTrue(explodingMessages.isDead());

            hevo.track("event2", null);
            JSONObject shouldntFind = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNull(shouldntFind);
            assertTrue(explodingMessages.isDead());
        } catch (InterruptedException e) {
            fail("Unexpected interruption");
        }
    }

    public void testEventOperations() throws JSONException {
        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();

        final HDbAdapter eventOperationsAdapter = new HDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject message, boolean isAutomatic) {
                if (!isAutomatic) {
                    messages.add(message);
                }

                return 1;
            }
        };

        final AnalyticsMessages eventOperationsMessages = new AnalyticsMessages(getContext()) {
            // This will throw inside of our worker thread.
            @Override
            public HDbAdapter makeDbAdapter(Context context) {
                return eventOperationsAdapter;
            }
        };

        HevoAPI hevo = new TestUtils.CleanHevoAPI(getContext(), mMockPreferences) {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return eventOperationsMessages;
            }
        };

        JSONObject jsonObj1 = new JSONObject();
        JSONObject jsonObj2 = new JSONObject();
        JSONObject jsonObj3 = new JSONObject();
        JSONObject jsonObj4 = new JSONObject();
        Map<String, Object> mapObj1 = new HashMap<>();
        Map<String, Object> mapObj2 = new HashMap<>();
        Map<String, Object> mapObj3 = new HashMap<>();
        Map<String, Object> mapObj4 = new HashMap<>();

        jsonObj1.put("TRACK JSON STRING", "TRACK JSON STRING VALUE");
        jsonObj2.put("TRACK JSON INT", 1);
        jsonObj3.put("TRACK JSON STRING ONCE", "TRACK JSON STRING ONCE VALUE");
        jsonObj4.put("TRACK JSON STRING ONCE", "SHOULD NOT SEE ME");

        mapObj1.put("TRACK MAP STRING", "TRACK MAP STRING VALUE");
        mapObj2.put("TRACK MAP INT", 1);
        mapObj3.put("TRACK MAP STRING ONCE", "TRACK MAP STRING ONCE VALUE");
        mapObj4.put("TRACK MAP STRING ONCE", "SHOULD NOT SEE ME");

        try {
            JSONObject message;
            JSONObject properties;

            hevo.track("event1", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event1", message.getString("event"));

            hevo.track("event2", jsonObj1);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event2", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(jsonObj1.getString("TRACK JSON STRING"), properties.getString("TRACK JSON STRING"));

            hevo.trackMap("event3", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event3", message.getString("event"));

            hevo.trackMap("event4", mapObj1);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event4", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(mapObj1.get("TRACK MAP STRING"), properties.getString("TRACK MAP STRING"));

            hevo.registerSuperProperties(jsonObj2);
            hevo.registerSuperPropertiesOnce(jsonObj3);
            hevo.registerSuperPropertiesOnce(jsonObj4);
            hevo.registerSuperPropertiesMap(mapObj2);
            hevo.registerSuperPropertiesOnceMap(mapObj3);
            hevo.registerSuperPropertiesOnceMap(mapObj4);

            hevo.track("event5", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event5", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertEquals(jsonObj2.getInt("TRACK JSON INT"), properties.getInt("TRACK JSON INT"));
            assertEquals(jsonObj3.getString("TRACK JSON STRING ONCE"), properties.getString("TRACK JSON STRING ONCE"));
            assertEquals(mapObj2.get("TRACK MAP INT"), properties.getInt("TRACK MAP INT"));
            assertEquals(mapObj3.get("TRACK MAP STRING ONCE"), properties.getString("TRACK MAP STRING ONCE"));

            hevo.unregisterSuperProperty("TRACK JSON INT");
            hevo.track("event6", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event6", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertFalse(properties.has("TRACK JSON INT"));

            hevo.clearSuperProperties();
            hevo.track("event7", null);
            message = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("event7", message.getString("event"));
            properties = message.getJSONObject("properties");
            assertFalse(properties.has("TRACK JSON STRING ONCE"));
        } catch (InterruptedException e) {
            fail("Unexpected interruption");
        }
    }

    public void testMessageQueuing() {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final SynchronizedReference<Boolean> isIdentifiedRef = new SynchronizedReference<>();
        isIdentifiedRef.set(false);

        final HDbAdapter mockAdapter = new HDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject message, boolean isAutomaticEvent) {
                if (!isAutomaticEvent) {
                    try {
                        messages.put("TABLE events");
                        messages.put(message.toString());
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                return super.addJSON(message, isAutomaticEvent);
            }
        };
        mockAdapter.cleanupEvents(Long.MAX_VALUE);

        final RemoteService mockPoster = new HttpService() {
            @Override
            public byte[] performRequest(String endpointUrl, String rawMessage, SSLSocketFactory socketFactory) {
                final boolean isIdentified = isIdentifiedRef.get();
                if (null == rawMessage) {
                    if (isIdentified) {
                        assertEquals("DECIDE_ENDPOINT?version=1&lib=android&token=Test+Message+Queuing&distinct_id=PEOPLE+ID" + mAppProperties, endpointUrl);
                    } else {
                        assertEquals("DECIDE_ENDPOINT?version=1&lib=android&token=Test+Message+Queuing&distinct_id=EVENTS+ID" + mAppProperties, endpointUrl);
                    }
                    return TestUtils.bytes("{}");
                }

                try {
                    messages.put("SENT FLUSH " + endpointUrl);
                    messages.put(rawMessage);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                return TestUtils.bytes("1\n");
            }
        };


        final HevoConfig mockConfig = new HevoConfig(new Bundle(), getContext()) {
            @Override
            public int getFlushInterval() {
                return -1;
            }

            @Override
            public int getBulkUploadLimit() {
                return 40;
            }

            @Override
            public String getEventsEndpoint() {
                return "EVENTS_ENDPOINT";
            }

            @Override
            public boolean getDisableAppOpenEvent() { return true; }
        };

        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            protected HDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }

            @Override
            protected HevoConfig getConfig(Context context) {
                return mockConfig;
            }

            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }
        };

        HevoAPI metrics = new TestUtils.CleanHevoAPI(getContext(), mMockPreferences) {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        };

        metrics.identify("EVENTS ID");

        // Test filling up the message queue
        for (int i=0; i < mockConfig.getBulkUploadLimit() - 1; i++) {
            metrics.track("frequent event", null);
        }

        metrics.track("final event", null);
        String expectedJSONMessage = "<No message actually received>";

        try {
            for (int i=0; i < mockConfig.getBulkUploadLimit() - 1; i++) {
                String messageTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
                assertEquals("TABLE events", messageTable);

                expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
                JSONObject message = new JSONObject(expectedJSONMessage);
                assertEquals("frequent event", message.getString("event"));
            }

            String messageTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE events", messageTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject message = new JSONObject(expectedJSONMessage);
            assertEquals("final event", message.getString("event"));

            String messageFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH EVENTS_ENDPOINT", messageFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray bigFlush = new JSONArray(expectedJSONMessage);
            assertEquals(mockConfig.getBulkUploadLimit(), bigFlush.length());

            metrics.track("next wave", null);
            metrics.flush();

            String nextWaveTable = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("TABLE events", nextWaveTable);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject nextWaveMessage = new JSONObject(expectedJSONMessage);
            assertEquals("next wave", nextWaveMessage.getString("event"));

            String manualFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH EVENTS_ENDPOINT", manualFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray nextWave = new JSONArray(expectedJSONMessage);
            assertEquals(1, nextWave.length());

            JSONObject nextWaveEvent = nextWave.getJSONObject(0);
            assertEquals("next wave", nextWaveEvent.getString("event"));

            isIdentifiedRef.set(true);
            metrics.flush();

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONObject peopleMessage = new JSONObject(expectedJSONMessage);

            assertEquals("PEOPLE ID", peopleMessage.getString("$distinct_id"));
            assertEquals("yup", peopleMessage.getJSONObject("$set").getString("prop"));

            String peopleFlush = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("SENT FLUSH PEOPLE_ENDPOINT", peopleFlush);

            expectedJSONMessage = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
            JSONArray peopleSent = new JSONArray(expectedJSONMessage);
            assertEquals(1, peopleSent.length());

        } catch (InterruptedException e) {
            fail("Expected a log message about hevo communication but did not receive it.");
        } catch (JSONException e) {
            fail("Expected a JSON object message and got something silly instead: " + expectedJSONMessage);
        }
    }

    public void testPersistence() {
        HevoAPI metricsOne = new HevoAPI(getContext(), mMockPreferences, false);
        metricsOne.reset();

        JSONObject props;
        try {
            props = new JSONObject("{ 'a' : 'value of a', 'b' : 'value of b' }");
        } catch (JSONException e) {
            throw new RuntimeException("Can't construct fixture for super properties test.");
        }

        metricsOne.clearSuperProperties();
        metricsOne.registerSuperProperties(props);
        metricsOne.identify("Expected Events Identity");

        // We exploit the fact that any metrics object with the same token
        // will get their values from the same persistent store.

        final List<Object> messages = new ArrayList<Object>();
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription heard) {
                if (!heard.isAutomatic()) {
                    messages.add(heard);
                }
            }
        };

        class ListeningAPI extends HevoAPI {
            public ListeningAPI(Context c, Future<SharedPreferences> prefs) {
                super(c, prefs, false);
            }

            @Override
        PersistentIdentity getPersistentIdentity(final Context context, final Future<SharedPreferences> referrerPreferences) {
                final String hevoPrefsName = "com.hevodata.android.Hevo";
                final SharedPreferences mpSharedPrefs = context.getSharedPreferences(hevoPrefsName, Context.MODE_PRIVATE);
                mpSharedPrefs.edit().clear().putBoolean("has_launched", true).commit();

                return super.getPersistentIdentity(context, referrerPreferences);
            }

            @Override
            boolean sendAppOpen() {
                return false;
            }

            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        }

        HevoAPI differentToken = new ListeningAPI(getContext(), mMockPreferences);

        differentToken.track("other event", null);

        assertEquals(1, messages.size());

        AnalyticsMessages.EventDescription eventMessage = (AnalyticsMessages.EventDescription) messages.get(0);

        try {
            JSONObject eventProps = eventMessage.getProperties();
            String sentId = eventProps.getString("distinct_id");
            String sentA = eventProps.optString("a");
            String sentB = eventProps.optString("b");

            assertFalse("Expected Events Identity".equals(sentId));
            assertEquals("", sentA);
            assertEquals("", sentB);
        } catch (JSONException e) {
            fail("Event message has an unexpected shape " + e);
        }

        messages.clear();

        HevoAPI metricsTwo = new ListeningAPI(getContext(), mMockPreferences);

        metricsTwo.track("eventname", null);

        assertEquals(2, messages.size());
    }

    public void testTrackInThread() throws InterruptedException, JSONException {
        class TestThread extends Thread {
            BlockingQueue<JSONObject> mMessages;

            public TestThread(BlockingQueue<JSONObject> messages) {
                this.mMessages = messages;
            }

            @Override
            public void run() {

                final HDbAdapter dbMock = new HDbAdapter(getContext()) {
                    @Override
                    public int addJSON(JSONObject message, boolean isAutomatic) {
                        if (!isAutomatic) {
                            mMessages.add(message);
                        }

                        return 1;
                    }
                };

                final AnalyticsMessages analyticsMessages = new AnalyticsMessages(getContext()) {
                    @Override
                    public HDbAdapter makeDbAdapter(Context context) {
                        return dbMock;
                    }
                };

                HevoAPI hevo = new TestUtils.CleanHevoAPI(getContext(), mMockPreferences) {
                    @Override
                    protected AnalyticsMessages getAnalyticsMessages() {
                        return analyticsMessages;
                    }
                };
                hevo.reset();
                hevo.track("test in thread", new JSONObject());
            }
        }

        //////////////////////////////

        final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();
        TestThread testThread = new TestThread(messages);
        testThread.start();
        JSONObject found = messages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(found);
        assertEquals(found.getString("event"), "test in thread");
        assertTrue(found.getJSONObject("properties").has("$bluetooth_version"));
    }

    public void testConfiguration() {
        final ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.metaData = new Bundle();
        appInfo.metaData.putInt("com.hevodata.android.BulkUploadLimit", 1);
        appInfo.metaData.putInt("com.hevodata.android.FlushInterval", 2);
        appInfo.metaData.putInt("com.hevodata.android.DataExpiration", 3);
        appInfo.metaData.putBoolean("com.hevodata.android.AutoShowHevoUpdates", false);
        appInfo.metaData.putBoolean("com.hevodata.android.DisableGestureBindingUI", true);
        appInfo.metaData.putBoolean("com.hevodata.android.DisableEmulatorBindingUI", true);
        appInfo.metaData.putBoolean("com.hevodata.android.DisableAppOpenEvent", true);

        appInfo.metaData.putString("com.hevodata.android.EventsEndpoint", "EVENTS ENDPOINT");

        final PackageManager packageManager = new MockPackageManager() {
            @Override
            public ApplicationInfo getApplicationInfo(String packageName, int flags) {
                assertEquals(packageName, "TEST PACKAGE NAME");
                assertTrue((flags & PackageManager.GET_META_DATA) == PackageManager.GET_META_DATA);
                return appInfo;
            }
        };

        final Context context = new MockContext() {
            @Override
            public String getPackageName() {
                return "TEST PACKAGE NAME";
            }

            @Override
            public PackageManager getPackageManager() {
                return packageManager;
            }
        };

        final HevoConfig testConfig = HevoConfig.readConfig(context);
        assertEquals(1, testConfig.getBulkUploadLimit());
        assertEquals(2, testConfig.getFlushInterval());
        assertEquals(3, testConfig.getDataExpiration());
        assertEquals(true, testConfig.getDisableAppOpenEvent());
        assertEquals("EVENTS ENDPOINT", testConfig.getEventsEndpoint());
    }

    public void testAlias() {
        final RemoteService mockPoster = new HttpService() {
            @Override
            public byte[] performRequest(String endpointUrl, String rawMessage, SSLSocketFactory socketFactory) {
                try {
                    JSONArray msg = new JSONArray(rawMessage);
                    JSONObject event = msg.getJSONObject(0);
                    JSONObject properties = event.getJSONObject("properties");

                    assertEquals(event.getString("event"), "$create_alias");
                    assertEquals(properties.getString("distinct_id"), "old id");
                    assertEquals(properties.getString("alias"), "new id");
                } catch (JSONException e) {
                    throw new RuntimeException("Malformed data passed to test mock", e);
                }
                return TestUtils.bytes("1\n");
            }
        };

        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }
        };

        HevoAPI metrics = new TestUtils.CleanHevoAPI(getContext(), mMockPreferences) {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                 return listener;
            }
        };

        // Check that we post the alias immediately
        metrics.identify("old id");
        metrics.alias("new id", "old id");
    }

    public void testSessionMetadata() throws InterruptedException, JSONException {
        final BlockingQueue<JSONObject> storedJsons = new LinkedBlockingQueue<>();
        final BlockingQueue<AnalyticsMessages.EventDescription> eventsMessages = new LinkedBlockingQueue<>();
        final HDbAdapter mockAdapter = new HDbAdapter(getContext()) {
            @Override
            public int addJSON(JSONObject j, boolean isAutomaticRecord) {
                storedJsons.add(j);
                return super.addJSON(j, isAutomaticRecord);
            }
        };
        final AnalyticsMessages listener = new AnalyticsMessages(getContext()) {
            @Override
            public void eventsMessage(EventDescription eventDescription) {
                if (!eventDescription.isAutomatic()) {
                    eventsMessages.add(eventDescription);
                    super.eventsMessage(eventDescription);
                }
            }

            @Override
            protected HDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }
        };
        HevoAPI metrics = new TestUtils.CleanHevoAPI(getContext(), mMockPreferences) {
            @Override
            protected AnalyticsMessages getAnalyticsMessages() {
                return listener;
            }

            @Override
            protected void track(String eventName, JSONObject properties, boolean isAutomaticEvent) {
                if (!isAutomaticEvent) {
                    super.track(eventName, properties, isAutomaticEvent);
                }
            }
        };

        metrics.track("First Event");
        metrics.track("Second Event");
        metrics.track("Third Event");
        metrics.track("Fourth Event");

        for (int i = 0; i < 4; i++) {
            JSONObject sessionMetadata = eventsMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getSessionMetadata();
            assertTrue(sessionMetadata.has("$h_event_id"));
            assertTrue(sessionMetadata.has("$h_session_id"));
            assertTrue(sessionMetadata.has("$h_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$h_session_seq_id"));
        }
        assertNull(eventsMessages.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));

        for (int i = 0; i < 4; i++) {
            JSONObject sessionMetadata = storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$h_metadata");
            assertTrue(sessionMetadata.has("$h_event_id"));
            assertTrue(sessionMetadata.has("$h_session_id"));
            assertTrue(sessionMetadata.has("$h_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$h_session_seq_id"));
        }

        for (int i = 0; i < 3; i++) {
            JSONObject sessionMetadata = storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS).getJSONObject("$h_metadata");
            assertTrue(sessionMetadata.has("$h_event_id"));
            assertTrue(sessionMetadata.has("$h_session_id"));
            assertTrue(sessionMetadata.has("$h_session_start_sec"));

            assertEquals(i, sessionMetadata.getInt("$h_session_seq_id"));
        }
        assertNull(storedJsons.poll(POLL_WAIT_SECONDS, TimeUnit.SECONDS));
    }

    private Future<SharedPreferences> mMockPreferences;

    private static final int POLL_WAIT_SECONDS = 10;

    private String mAppProperties;
}
