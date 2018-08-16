package com.hevodata.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.test.AndroidTestCase;

import com.hevodata.android.util.HttpService;
import com.hevodata.android.util.RemoteService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

public class AutomaticEventsTest extends AndroidTestCase {

    private HevoAPI mCleanHevoAPI;
    private static final int MAX_TIMEOUT_POLL = 6500;
    final private BlockingQueue<String> mPerformRequestEvents = new LinkedBlockingQueue<>();
    private byte[] mDecideResponse;
    private int mTrackedEvents;
    private CountDownLatch mLatch = new CountDownLatch(1);
    private HDbAdapter mockAdapter;
    private CountDownLatch mMinRequestsLatch;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Future<SharedPreferences> mMockReferrerPreferences = new TestUtils.EmptyPreferences(getContext());
        mTrackedEvents = 0;
        mMinRequestsLatch = new CountDownLatch(2); // First Time Open and Update
        final RemoteService mockPoster = new HttpService() {
            @Override
            public byte[] performRequest(
                    String endpointUrl, String rawMessage, SSLSocketFactory socketFactory) {

                if (null == rawMessage) {
                    if (mDecideResponse == null) {
                        return TestUtils.bytes("{\"notifications\":[], \"automatic_events\": true}");
                    }
                    return mDecideResponse;
                }

                try {
                    JSONArray jsonArray = new JSONArray(rawMessage);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        mPerformRequestEvents.put(jsonArray.getJSONObject(i).getString("event"));
                        mMinRequestsLatch.countDown();
                    }
                    return TestUtils.bytes("1\n");
                } catch (JSONException e) {
                    throw new RuntimeException("Malformed data passed to test mock", e);
                } catch (InterruptedException e) {
                    throw new RuntimeException("Could not write message to reporting queue for tests.", e);
                }
            }
        };

        getContext().deleteDatabase("hevo");

        mockAdapter = new HDbAdapter(getContext()) {
            @Override
            public void cleanupEvents(String last_id, boolean includeAutomaticEvents) {
                super.cleanupEvents(last_id, includeAutomaticEvents);
            }

            @Override
            public int addJSON(JSONObject j, boolean isAutomaticRecord) {
                mTrackedEvents++;
                mLatch.countDown();
                return super.addJSON(j, isAutomaticRecord);
            }
        };

        final AnalyticsMessages automaticAnalyticsMessages = new AnalyticsMessages(getContext()) {

            @Override
            protected RemoteService getPoster() {
                return mockPoster;
            }

            @Override
            protected HDbAdapter makeDbAdapter(Context context) {
                return mockAdapter;
            }

            @Override
            protected Worker createWorker() {
                return new Worker() {
                    @Override
                    protected Handler restartWorkerThread() {
                        final HandlerThread thread = new HandlerThread("com.hevodata.android.AnalyticsWorker", Process.THREAD_PRIORITY_BACKGROUND);
                        thread.start();
                        return new AnalyticsMessageHandler(thread.getLooper());
                    }
                };
            }
        };

        mCleanHevoAPI = new HevoAPI(getContext(), mMockReferrerPreferences, false) {

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
                mpSharedPrefs.edit().clear().putInt("latest_version_code", -2).commit(); // -1 is the default value

                return super.getPersistentIdentity(context, referrerPreferences);
            }

            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return automaticAnalyticsMessages;
            }
        };
    }

    @Override
    protected void tearDown() throws Exception {
        mMinRequestsLatch.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS);
        super.tearDown();
    }

    public void testAutomaticOneInstance() throws InterruptedException {
        int calls = 3; // First Time Open, App Update, An Event One
        mLatch = new CountDownLatch(calls);
        mCleanHevoAPI.track("An event One");
        mCleanHevoAPI.flush();
        assertTrue(mLatch.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(calls, mTrackedEvents);
        assertEquals(AutomaticEvents.FIRST_OPEN, mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(AutomaticEvents.APP_UPDATED, mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals("An event One", mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(null, mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }

    public void testDisableAutomaticEvents() throws InterruptedException {
        mDecideResponse = TestUtils.bytes("{\"notifications\":[], \"automatic_events\": false}");

        int calls = 3; // First Time Open, App Update, An Event Three
        mLatch = new CountDownLatch(calls);
        mCleanHevoAPI.track("An Event Three");
        assertTrue(mLatch.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(calls, mTrackedEvents);

        mCleanHevoAPI.track("Automatic Event Two", null, true); // dropped
        mCleanHevoAPI.track("Automatic Event Three", null, true); // dropped
        mCleanHevoAPI.track("Automatic Event Four", null, true); // dropped
        mCleanHevoAPI.flush();
        assertEquals("An Event Three", mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(null, mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));

        String[] noEvents = mockAdapter.generateDataString(true);
        assertNull(noEvents);

        mCleanHevoAPI.flush();
        assertEquals(null, mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }

    public void testAutomaticMultipleInstances() throws InterruptedException {
        mDecideResponse = TestUtils.bytes("{\"notifications\":[], \"automatic_events\": true}");
        int initialCalls = 2;
        mLatch = new CountDownLatch(initialCalls);
        final CountDownLatch secondLatch = new CountDownLatch(initialCalls);
        final BlockingQueue<String> secondPerformedRequests =  new LinkedBlockingQueue<>();

        final HttpService mpSecondPoster = new HttpService() {
            @Override
            public byte[] performRequest(
                    String endpointUrl, String rawMessage, SSLSocketFactory socketFactory) {

                if (null == rawMessage) {
                    return TestUtils.bytes("{\"notifications\":[], \"automatic_events\": false}");
                }

                try {
                    JSONArray jsonArray = new JSONArray(rawMessage);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        secondPerformedRequests.put(jsonArray.getJSONObject(i).getString("event"));
                    }
                    return TestUtils.bytes("1\n");
                } catch (JSONException e) {
                    throw new RuntimeException("Malformed data passed to test mock", e);
                } catch (InterruptedException e) {
                    throw new RuntimeException("Could not write message to reporting queue for tests.", e);
                }
            }
        };

        final HDbAdapter mpSecondDbAdapter = new HDbAdapter(getContext()) {
            @Override
            public void cleanupEvents(String last_id, boolean includeAutomaticEvents) {
                super.cleanupEvents(last_id, includeAutomaticEvents);
            }

            @Override
            public int addJSON(JSONObject j, boolean isAutomaticRecord) {
                secondLatch.countDown();
                return super.addJSON(j, isAutomaticRecord);
            }
        };

        final AnalyticsMessages mpSecondAnalyticsMessages = new AnalyticsMessages(getContext()) {
            @Override
            protected RemoteService getPoster() {
                return mpSecondPoster;
            }

            @Override
            protected HDbAdapter makeDbAdapter(Context context) {
                return mpSecondDbAdapter;
            }

            @Override
            protected Worker createWorker() {
                return new Worker() {
                    @Override
                    protected Handler restartWorkerThread() {
                        final HandlerThread thread = new HandlerThread("com.hevodata.android.AnalyticsWorker", Process.THREAD_PRIORITY_BACKGROUND);
                        thread.start();
                        return new AnalyticsMessageHandler(thread.getLooper());
                    }
                };
            }
        };

        HevoAPI mpSecondInstance = new TestUtils.CleanHevoAPI(getContext(), new TestUtils.EmptyPreferences(getContext())) {
            @Override
            AnalyticsMessages getAnalyticsMessages() {
                return mpSecondAnalyticsMessages;
            }
        };

        assertTrue(mLatch.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(initialCalls, mTrackedEvents);

        assertTrue(secondLatch.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        mLatch = new CountDownLatch(HevoConfig.getInstance(getContext()).getBulkUploadLimit() - initialCalls);
        for (int i = 0; i < HevoConfig.getInstance(getContext()).getBulkUploadLimit() - initialCalls; i++) {
            mCleanHevoAPI.track("Track event " + i);
        }
        assertTrue(mLatch.await(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));

        assertEquals(AutomaticEvents.FIRST_OPEN, mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals(AutomaticEvents.APP_UPDATED, mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        for (int i = 0; i < HevoConfig.getInstance(getContext()).getBulkUploadLimit() - initialCalls; i++) {
            assertEquals("Track event " + i, mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        }

        assertNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));

        assertNull(secondPerformedRequests.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));

        mpSecondInstance.flush();
        mCleanHevoAPI.track("First Instance Event One");
        mpSecondInstance.track("Second Instance Event One");
        mpSecondInstance.track("Second Instance Event Two");
        mpSecondInstance.flush();

        assertEquals("Second Instance Event One", secondPerformedRequests.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertEquals("Second Instance Event Two", secondPerformedRequests.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
        assertNull(secondPerformedRequests.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));

        assertNull(mPerformRequestEvents.poll(MAX_TIMEOUT_POLL, TimeUnit.MILLISECONDS));
    }
}
