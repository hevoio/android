package com.hevodata.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;

import com.hevodata.android.util.HLog;
import com.hevodata.android.util.OfflineMode;

import java.security.GeneralSecurityException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;


/**
 * Stores global configuration options for the Hevo library. You can enable and disable configuration
 * options using &lt;meta-data&gt; tags inside of the &lt;application&gt; tag in your AndroidManifest.xml.
 * All settings are optional, and default to reasonable recommended values. Most users will not have to
 * set any options.
 *
 * Hevo understands the following options:
 *
 * <dl>
 *     <dt>com.hevodata.android.EnableDebugLogging</dt>
 *     <dd>A boolean value. If true, emit more detailed log messages. Defaults to false</dd>
 *
 *     <dt>com.hevodata.android.BulkUploadLimit</dt>
 *     <dd>An integer count of messages, the maximum number of messages to queue before an upload attempt. This value should be less than 50.</dd>
 *
 *     <dt>com.hevodata.android.FlushInterval</dt>
 *     <dd>An integer number of milliseconds, the maximum time to wait before an upload if the bulk upload limit isn't reached.</dd>
 *
 *     <dt>com.hevodata.android.DataExpiration</dt>
 *     <dd>An integer number of milliseconds, the maximum age of records to send to Hevo. Corresponds to Hevo's server-side limit on record age.</dd>
 *
 *     <dt>com.hevodata.android.MinimumDatabaseLimit</dt>
 *     <dd>An integer number of bytes. Hevo attempts to limit the size of its persistent data
 *          queue based on the storage capacity of the device, but will always allow queing below this limit. Higher values
 *          will take up more storage even when user storage is very full.</dd>
 *
 *     <dt>com.hevodata.android.DisableAppOpenEvent</dt>
 *     <dd>A boolean value. If true, do not send an "$app_open" event when the HevoAPI object is created for the first time. Defaults to true - the $app_open event will not be sent by default.</dd>
 *
 *     <dt>com.hevodata.android.CaptureAutomaticEvents</dt>
 *     <dd>A boolean value. If not set automatic events will not be replicated.</dd>
 *
 *     <dt>com.hevodata.android.IntegrationEndpoint</dt>
 *     <dd>A string URL. If present, the library will attempt to send events to this endpoint.</dd>
 *
 *     <dt>com.hevodata.android.MinimumSessionDuration</dt>
 *     <dd>An integer number. The minimum session duration (ms) that is tracked in automatic events. Defaults to 10000 (10 seconds).</dd>
 *
 *     <dt>com.hevodata.android.SessionTimeoutDuration</dt>
 *     <dd>An integer number. The maximum session duration (ms) that is tracked in automatic events. Defaults to Integer.MAX_VALUE (no maximum session duration).</dd>

 *     <dt>com.hevodata.android.TestMode</dt>
 *     <dd>A boolean value. If true, in-app notifications won't be marked as seen. Defaults to false.</dd>
 * </dl>
 *
 */
public class HevoConfig {

    public static final String VERSION = BuildConfig.HEVO_VERSION;

    public static boolean DEBUG = false;

    // Name for persistent storage of app referral SharedPreferences
    static final String REFERRER_PREFS_NAME = "com.hevodata.android.ReferralInfo";

    // Instances are safe to store, since they're immutable and always the same.
    public static HevoConfig getInstance(Context context) {
        synchronized (sInstanceLock) {
            if (null == sInstance) {
                final Context appContext = context.getApplicationContext();
                sInstance = readConfig(appContext);
            }
        }

        return sInstance;
    }

    /**
     * The HevoAPI will use the system default SSL socket settings under ordinary circumstances.
     * That means it will ignore settings you associated with the default SSLSocketFactory in the
     * schema registry or in underlying HTTP libraries. If you'd prefer for Hevo to use your
     * own SSL settings, you'll need to call setSSLSocketFactory early in your code, like this
     *
     * {@code
     * <pre>
     *     HevoConfig.getInstance(context).setSSLSocketFactory(someCustomizedSocketFactory);
     * </pre>
     * }
     *
     * Your settings will be globally available to all Hevo instances, and will be used for
     * all SSL connections in the library. The call is thread safe, but should be done before
     * your first call to HevoAPI.getInstance to insure that the library never uses it's
     * default.
     *
     * The given socket factory may be used from multiple threads, which is safe for the system
     * SSLSocketFactory class, but if you pass a subclass you should ensure that it is thread-safe
     * before passing it to Hevo.
     *
     * @param factory an SSLSocketFactory that
     */
    public synchronized void setSSLSocketFactory(SSLSocketFactory factory) {
        mSSLSocketFactory = factory;
    }

    /**
     * {@link OfflineMode} allows Hevo to be in-sync with client offline internal logic.
     * If you want to integrate your own logic with Hevo you'll need to call
     * {@link #setOfflineMode(OfflineMode)} early in your code, like this
     *
     * {@code
     * <pre>
     *     HevoConfig.getInstance(context).setOfflineMode(OfflineModeImplementation);
     * </pre>
     * }
     *
     * Your settings will be globally available to all Hevo instances, and will be used across
     * all the library. The call is thread safe, but should be done before
     * your first call to HevoAPI.getInstance to insure that the library never uses it's
     * default.
     *
     * The given {@link OfflineMode} may be used from multiple threads, you should ensure that
     * your implementation is thread-safe before passing it to Hevo.
     *
     * @param offlineMode client offline implementation to use on Hevo
     */
    public synchronized void setOfflineMode(OfflineMode offlineMode) {
        mOfflineMode = offlineMode;
    }

    HevoConfig(Bundle metaData, Context context) {

        // By default, we use a clean, FACTORY default SSLSocket. In general this is the right
        // thing to do, and some other third party libraries change the
        SSLSocketFactory foundSSLFactory;
        try {
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            foundSSLFactory = sslContext.getSocketFactory();
        } catch (final GeneralSecurityException e) {
            HLog.i("HevoAPI.Conf", "System has no SSL support. Built-in events editor will not be available", e);
            foundSSLFactory = null;
        }
        mSSLSocketFactory = foundSSLFactory;

        DEBUG = metaData.getBoolean("com.hevodata.android.EnableDebugLogging", false);
        if (DEBUG) {
            HLog.setLevel(HLog.VERBOSE);
        }

        mBulkUploadLimit = metaData.getInt("com.hevodata.android.BulkUploadLimit", 40); // 40 records default
        mFlushInterval = metaData.getInt("com.hevodata.android.FlushInterval", 60 * 1000); // one minute default
        mMinimumDatabaseLimit = metaData.getInt("com.hevodata.android.MinimumDatabaseLimit", 20 * 1024 * 1024); // 20 Mb
        mDisableAppOpenEvent = metaData.getBoolean("com.hevodata.android.DisableAppOpenEvent", true);
        mMinSessionDuration = metaData.getInt("com.hevodata.android.MinimumSessionDuration", 10 * 1000); // 10 seconds
        mSessionTimeoutDuration = metaData.getInt("com.hevodata.android.SessionTimeoutDuration", Integer.MAX_VALUE); // no timeout by default
        mTestMode = metaData.getBoolean("com.hevodata.android.TestMode", false);
        mCaptureAutomaticEvents = metaData.getBoolean("com.hevodata.android.CaptureAutomaticEvents", true);

        long defaultDataExpiration = 1000 * 60 * 60 * 24 * 5; // 5 days default
        mDataExpiration = metaData.getLong("com.hevodata.android.DataExpiration", defaultDataExpiration);

        String integrationEndpoint = metaData.getString("com.hevodata.android.IntegrationEndpoint");
        if (integrationEndpoint != null && !integrationEndpoint.isEmpty()) {
            setIntegrationEndpoint(integrationEndpoint);
        } else {
            mIntegrationEndpoint = null;
        }

        showWarnings();

        HLog.v(LOGTAG, toString());
    }

    private void showWarnings() {
        if (this.mIntegrationEndpoint == null) {
            HLog.w(LOGTAG, "integration endpoint is not set, events will not be streamed");
        }
    }

    // Max size of queue before we require a flush. Must be below the limit the service will accept.
    public int getBulkUploadLimit() {
        return mBulkUploadLimit;
    }

    // Target max milliseconds between flushes. This is advisory.
    public int getFlushInterval() {
        return mFlushInterval;
    }

    // Throw away records that are older than this in milliseconds. Should be below the server side age limit for events.
    public long getDataExpiration() {
        return mDataExpiration;
    }

    public int getMinimumDatabaseLimit() { return mMinimumDatabaseLimit; }

    public boolean getDisableAppOpenEvent() {
        return mDisableAppOpenEvent;
    }

    private boolean getTestMode() {
        return mTestMode;
    }

    boolean getCaptureAutomaticEvents() {
        return mCaptureAutomaticEvents;
    }

    // Preferred URL for tracking events
    public String getEventsEndpoint() {
        return mIntegrationEndpoint;
    }

    private void setIntegrationEndpoint(String eventsEndpoint) {
        mIntegrationEndpoint = eventsEndpoint;
    }

    public int getMinimumSessionDuration() {
        return mMinSessionDuration;
    }

    public int getSessionTimeoutDuration() {
        return mSessionTimeoutDuration;
    }

    // This method is thread safe, and assumes that SSLSocketFactory is also thread safe
    // (At this writing, all HttpsURLConnections in the framework share a single factory,
    // so this is pretty safe even if the docs are ambiguous)
    public synchronized SSLSocketFactory getSSLSocketFactory() {
        return mSSLSocketFactory;
    }

    // This method is thread safe, and assumes that OfflineMode is also thread safe
    public synchronized OfflineMode getOfflineMode() {
        return mOfflineMode;
    }

    ///////////////////////////////////////////////

    // Package access for testing only- do not call directly in library code
    static HevoConfig readConfig(Context appContext) {
        final String packageName = appContext.getPackageName();
        try {
            final ApplicationInfo appInfo = appContext.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            Bundle configBundle = appInfo.metaData;
            if (configBundle == null) {
                configBundle = new Bundle();
            }
            return new HevoConfig(configBundle, appContext);
        } catch (final NameNotFoundException e) {
            throw new RuntimeException("Can't configure Hevo with package name " + packageName, e);
        }
    }

    @Override
    public String toString() {
        return "Hevo (" + VERSION + ") configured with:\n" +
                "    BulkUploadLimit " + getBulkUploadLimit() + "\n" +
                "    FlushInterval " + getFlushInterval() + "\n" +
                "    DataExpiration " + getDataExpiration() + "\n" +
                "    MinimumDatabaseLimit " + getMinimumDatabaseLimit() + "\n" +
                "    DisableAppOpenEvent " + getDisableAppOpenEvent() + "\n" +
                "    EnableDebugLogging " + DEBUG + "\n" +
                "    TestMode " + getTestMode() + "\n" +
                "    mCaptureAutomaticEvents " + getCaptureAutomaticEvents() + "\n" +
                "    EventsEndpoint " + getEventsEndpoint() + "\n" +
                "    MinimumSessionDuration: " + getMinimumSessionDuration() + "\n" +
                "    SessionTimeoutDuration: " + getSessionTimeoutDuration();
    }

    private final int mBulkUploadLimit;
    private final int mFlushInterval;
    private final int mMinimumDatabaseLimit;
    private final int mMinSessionDuration;
    private final int mSessionTimeoutDuration;
    private final long mDataExpiration;
    private final boolean mTestMode;
    private final boolean mDisableAppOpenEvent;
    private final boolean mCaptureAutomaticEvents;
    private String mIntegrationEndpoint;

    // Mutable, with synchronized accessor and mutator
    private SSLSocketFactory mSSLSocketFactory;
    private OfflineMode mOfflineMode;

    private static HevoConfig sInstance;
    private static final Object sInstanceLock = new Object();
    private static final String LOGTAG = "HevoAPI.Conf";
}
