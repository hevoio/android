package com.hevodata.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.test.AndroidTestCase;

import com.hevodata.android.util.HttpService;
import com.hevodata.android.util.RemoteService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

public class OptOutTest extends AndroidTestCase {

    private HevoAPI mHevoAPI;
    private static final String TOKEN = "Opt Out Test Token";
    final private BlockingQueue<String> mPerformRequestEvents = new LinkedBlockingQueue<>();
    final private BlockingQueue<String> mStoredEvents = new LinkedBlockingQueue<>();
    final private BlockingQueue<String> mStoredPeopleUpdates = new LinkedBlockingQueue<>();
    private CountDownLatch mCleanUpCalls = new CountDownLatch(1);

    private HDbAdapter mMockAdapter;
    private Future<SharedPreferences> mMockReferrerPreferences;
    private AnalyticsMessages mAnalyticsMessages;
    private PersistentIdentity mPersistentIdentity;
    private static final int MAX_TIMEOUT_POLL = 6500;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockReferrerPreferences = new TestUtils.EmptyPreferences(getContext());

        final RemoteService mockPoster = new HttpService() {
            @Override
            public byte[] performRequest(String endpointUrl, String rawMessage, SSLSocketFactory socketFactory) {
                if (rawMessage != null) {
                    try {
                        JSONArray jsonArray = new JSONArray(rawMessage);
                        for (int i = 0; i < jsonArray.length(); i++) {
                            mPerformRequestEvents.put(jsonArray.getJSONObject(i).toString());
                        }
                        return TestUtils.bytes("1\n");
                    } catch (JSONException e) {
                        throw new RuntimeException("Malformed data passed to test mock", e);
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Could not write message to reporting queue for tests.", e);
                    }

                }

                return TestUtils.bytes("{\"notifications\":[], \"automatic_events\": false}");
            }
        };

        mMockAdapter = getMockDBAdapter();
        mAnalyticsMessages = new AnalyticsMessages(getContext()) {
            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }

            @Override
            protected HDbAdapter makeDbAdapter(Context context) {
                return mMockAdapter;
            }
        };
    }

    @Override
    protected void tearDown() throws Exception {
        if (mPersistentIdentity != null) {
            mPersistentIdentity.clearPreferences();
            mPersistentIdentity = null;
        }
        mHevoAPI.optInTracking();
        mMockAdapter.deleteDB();
        super.tearDown();
    }

    /**
     * Init Hevo without tracking.
     *
     * Make sure that after initialization no events are stored nor flushed.
     * Check that super properties, unidentified people updates or people distinct ID are
     * not stored in the device.
     *
     * @throws InterruptedException
     */
    public void testOptOutDefaultFlag() throws InterruptedException {
        mCleanUpCalls = new CountDownLatch(2); // optOutTrack calls
        mHevoAPI = new HevoAPI(getContext(), mMockReferrerPreferences, true) {
            @Override
            PersistentIdentity getPersistentIdentity(Context context, Future<SharedPreferences> referrerPreferences) {
                mPersistentIdentity = super.getPersistentIdentity(context, referrerPreferences);
                return mPersistentIdentity;
            }

            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mAnalyticsMessages;
            }
        };
        mHevoAPI.flush();
        assertEquals(null, mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(0, mHevoAPI.getSuperProperties().length());
        assertTrue(mCleanUpCalls.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }

    /**
     * Check that calls to optInTracking()/optOutTracking() updates hasOptedOutTracking()
     *
     * @throws InterruptedException
     */
    public void testHasOptOutTrackingOrNot() throws InterruptedException {
        mCleanUpCalls = new CountDownLatch(4); // optOutTrack calls
        mHevoAPI = new HevoAPI(getContext(), mMockReferrerPreferences, true) {
            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mAnalyticsMessages;
            }
        };

        assertTrue(mHevoAPI.hasOptedOutTracking());
        mHevoAPI.optInTracking();
        assertFalse(mHevoAPI.hasOptedOutTracking());
        mHevoAPI.optOutTracking();
        assertTrue(mHevoAPI.hasOptedOutTracking());
        assertTrue(mCleanUpCalls.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }

    /**
     * Test that events are dropped when a user opts out. After opting in, an event should be sent.
     *
     * @throws InterruptedException
     */
    public void testDropEventsAndOptInEvent() throws InterruptedException {
        mHevoAPI = new TestUtils.CleanHevoAPI(getContext(), mMockReferrerPreferences) {
            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mAnalyticsMessages;
            }
        };

        for (int i = 0; i < 20; i++) {
            mHevoAPI.track("An Event");
        }
        for (int i = 0; i < 20; i++) {
            assertEquals("An Event", mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        }

        mCleanUpCalls = new CountDownLatch(2);
        mHevoAPI.optOutTracking();
        mMockAdapter = getMockDBAdapter();
        assertTrue(mCleanUpCalls.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertNull(mMockAdapter.generateDataString(true));

        mHevoAPI.optInTracking();
        assertEquals("$opt_in", mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        forceFlush();
        assertNotNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }

    /**
     * Track calls before and after opting out
     */
    public void testTrackCalls() throws InterruptedException, JSONException {
        mHevoAPI = new HevoAPI(getContext(), mMockReferrerPreferences,false) {
            @Override
            PersistentIdentity getPersistentIdentity(Context context, Future<SharedPreferences> referrerPreferences) {
                mPersistentIdentity = super.getPersistentIdentity(context, referrerPreferences);
                return mPersistentIdentity;
            }

            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mAnalyticsMessages;
            }
        };

        mHevoAPI.timeEvent("Time Event");
        mHevoAPI.trackMap("Event with map", new HashMap<String, Object>());
        mHevoAPI.track("Event with properties", new JSONObject());
        assertEquals(1, mPersistentIdentity.getTimeEvents().size());

        mCleanUpCalls = new CountDownLatch(2);
        mHevoAPI.optOutTracking();
        assertTrue(mCleanUpCalls.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        mStoredEvents.clear();
        assertEquals(0,         mPersistentIdentity.getTimeEvents().size());

        mHevoAPI.timeEvent("Time Event");
        assertEquals(0,         mPersistentIdentity.getTimeEvents().size());
        mHevoAPI.track("Time Event");

        mHevoAPI.optInTracking();
        mHevoAPI.track("Time Event");
        mHevoAPI.timeEvent("Time Event");
        assertEquals(1,         mPersistentIdentity.getTimeEvents().size());
        mHevoAPI.track("Time Event");

        mMockAdapter = getMockDBAdapter();
        assertEquals("$opt_in", mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals("Time Event", mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals("Time Event", mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertNull(mStoredEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));

        String[] data = mMockAdapter.generateDataString(true);
        JSONArray pendingEventsArray = new JSONArray(data[1]);
        assertEquals(3, pendingEventsArray.length());
        assertEquals("$opt_in", pendingEventsArray.getJSONObject(0).getString("event"));
        assertEquals("Time Event", pendingEventsArray.getJSONObject(1).getString("event"));
        assertEquals("Time Event", pendingEventsArray.getJSONObject(2).getString("event"));
        assertFalse(pendingEventsArray.getJSONObject(1).getJSONObject("properties").has("$duration"));
        assertTrue(pendingEventsArray.getJSONObject(2).getJSONObject("properties").has("$duration"));

        forceFlush();
        for (int i = 0; i < 3; i++) {
            assertNotNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        }
        assertNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(0, mPersistentIdentity.getTimeEvents().size());
    }

    private void forceFlush() {
        mAnalyticsMessages.postToServer();
    }

    private HDbAdapter getMockDBAdapter() {
        return new HDbAdapter(getContext()) {

            @Override
            public void cleanupAllEvents() {
                mCleanUpCalls.countDown();
                super.cleanupAllEvents();
            }

            @Override
            public int addJSON(JSONObject j, boolean isAutomaticRecord) {
                int result = super.addJSON(j, isAutomaticRecord);
                try {
                    mStoredEvents.put(j.getString("event"));
                } catch (Exception e) {
                    throw new RuntimeException("Malformed data passed to test mock adapter", e);
                }

                return result;
            }
        };
    }
}
