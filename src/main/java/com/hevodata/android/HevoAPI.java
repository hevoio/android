package com.hevodata.android;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.hevodata.android.util.HLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Future;


/**
 * Core class for interacting with Hevo Analytics.
 *
 * <p>Call {@link #getInstance(Context, boolean)} with
 * your main application activity and your Hevo API token as arguments
 * an to get an instance you can use to report how users are using your
 * application.
 *
 *
 * <p>The Hevo library will periodically send information to
 * Hevo servers, so your application will need to have
 * <tt>android.permission.INTERNET</tt>. In addition, to preserve
 * battery life, messages to Hevo servers may not be sent immediately
 * The library will send messages periodically throughout the lifetime
 * of your application, but you will need to call {@link #flush()}
 * before your application is completely shutdown to ensure all of your
 * events are sent.
 *
 * <p>A typical use-case for the library might look like this:
 *
 * <pre>
 * {@code
 * public class MainActivity extends Activity {
 *      HevoAPI mHevo;
 *
 *      public void onCreate(Bundle saved) {
 *          mHevo = HevoAPI.getInstance(this);
 *          ...
 *      }
 *
 *      public void whenSomethingInterestingHappens(int flavor) {
 *          JSONObject properties = new JSONObject();
 *          properties.put("flavor", flavor);
 *          mHevo.track("Something Interesting Happened", properties);
 *          ...
 *      }
 *
 *      public void onDestroy() {
 *          mHevo.flush();
 *          super.onDestroy();
 *      }
 * }
 * }
 * </pre>
 *
 * <p>In addition to this documentation, you may wish to take a look at
 * <a href="https://github.com/hevoio/sample-android-hevo-integration">the Hevo sample Android application</a>.
 * It demonstrates a variety of techniques, including
 *
 * <p>There are also <a href="https://hevo.com/docs/">step-by-step getting started documents</a>
 * available at hevo.com
 *
 * @see <a href="https://hevo.com/docs/integration-libraries/android">getting started documentation for tracking events</a>
 * @see <a href="https://github.com/hevo/sample-android-hevo-integration">The Hevo Android sample application</a>
 */
public class HevoAPI {
    /**
     * String version of the library.
     */
    private final Context mContext;
    private final AnalyticsMessages mMessages;
    private final HevoConfig mConfig;
    private final PersistentIdentity mPersistentIdentity;
    private final Map<String, String> mDeviceInfo;
    private final Map<String, Long> mEventTimings;
    private HevoActivityLifecycleCallbacks mHevoActivityLifecycleCallbacks;
    private final SessionMetadata mSessionMetadata;

    // Maps each token to a singleton HevoAPI instance
    private static final Map<Context, HevoAPI> sInstance = new HashMap<>();
    private static final SharedPreferencesLoader sPrefsLoader = new SharedPreferencesLoader();
    private static Future<SharedPreferences> sReferrerPrefs;

    private static final String LOGTAG = "HevoAPI.API";
    private static final String APP_LINKS_LOGTAG = "HevoAPI.AL";

    /**
     * You shouldn't instantiate HevoAPI objects directly.
     * Use HevoAPI.getInstance to get an instance.
     */
    HevoAPI(Context context, Future<SharedPreferences> referrerPreferences, boolean optOutTrackingDefault) {
        this(context, referrerPreferences, HevoConfig.getInstance(context), optOutTrackingDefault);
    }

    /**
     * You shouldn't instantiate HevoAPI objects directly.
     * Use HevoAPI.getInstance to get an instance.
     */
    HevoAPI(Context context, Future<SharedPreferences> referrerPreferences, HevoConfig config, boolean optOutTrackingDefault) {
        mContext = context;
        mConfig = config;

        final Map<String, String> deviceInfo = new HashMap<String, String>();
        deviceInfo.put("$android_lib_version", HevoConfig.VERSION);
        deviceInfo.put("$android_os", "Android");
        deviceInfo.put("$android_os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);
        deviceInfo.put("$android_manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
        deviceInfo.put("$android_brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
        deviceInfo.put("$android_model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);
        try {
            final PackageManager manager = mContext.getPackageManager();
            final PackageInfo info = manager.getPackageInfo(mContext.getPackageName(), 0);
            deviceInfo.put("$android_app_version", info.versionName);
            deviceInfo.put("$android_app_version_code", Integer.toString(info.versionCode));
        } catch (final PackageManager.NameNotFoundException e) {
            HLog.e(LOGTAG, "Exception getting app version name", e);
        }
        mDeviceInfo = Collections.unmodifiableMap(deviceInfo);

        mSessionMetadata = new SessionMetadata();
        mPersistentIdentity = getPersistentIdentity(context, referrerPreferences);
        mEventTimings = mPersistentIdentity.getTimeEvents();
        mMessages = getAnalyticsMessages();

        if (optOutTrackingDefault) {
            optOutTracking();
        }

        // TODO reading persistent identify immediately forces the lazy load of the preferences, and defeats the
        // purpose of PersistentIdentity's laziness.
        if (mPersistentIdentity.isFirstLaunch(HDbAdapter.getInstance(mContext).getDatabaseFile().exists())) {
            track(AutomaticEvents.FIRST_OPEN, null, true);
            track(ReservedEvents.INSTALLATION, new JSONObject(), false);

            mPersistentIdentity.setHasLaunched();
        }

        registerHevoActivityLifecycleCallbacks();

        if (sendAppOpen()) {
            track("$app_open", null);
        }

        if (!mPersistentIdentity.isFirstIntegration()) {
            mPersistentIdentity.setIsIntegrated();
        }

        if (mPersistentIdentity.isNewVersion(deviceInfo.get("$android_app_version_code"))) {
            try {
                final JSONObject messageProps = new JSONObject();
                messageProps.put(AutomaticEvents.VERSION_UPDATED, deviceInfo.get("$android_app_version"));
                track(AutomaticEvents.APP_UPDATED, messageProps, true);
            } catch (JSONException e) {}

        }

        ExceptionHandler.init();
    }

    /**
     * Get the instance of HevoAPI associated with your Hevo project token.
     *
     * <p>Use getInstance to get a reference to a shared
     * instance of HevoAPI you can use to send events
     * and People Analytics updates to Hevo.</p>
     * <p>getInstance is thread safe, but the returned instance is not,
     * and may be shared with other callers of getInstance.
     * The best practice is to call getInstance, and use the returned HevoAPI,
     * object from a single thread (probably the main UI thread of your application).</p>
     * <p>If you do choose to track events from multiple threads in your application,
     * you should synchronize your calls on the instance itself, like so:</p>
     * <pre>
     * {@code
     * HevoAPI instance = HevoAPI.getInstance(context, token);
     * synchronized(instance) { // Only necessary if the instance will be used in multiple threads.
     *     instance.track(...)
     * }
     * }
     * </pre>
     *
     * @param context The application context you are tracking
     * @return an instance of HevoAPI associated with your project
     */
    public static HevoAPI getInstance(Context context) {
        return getInstance(context, false);
    }

    /**
     * Get the instance of HevoAPI associated with your Hevo project token.
     *
     * <p>Use getInstance to get a reference to a shared
     * instance of HevoAPI you can use to send events
     * and People Analytics updates to Hevo.</p>
     * <p>getInstance is thread safe, but the returned instance is not,
     * and may be shared with other callers of getInstance.
     * The best practice is to call getInstance, and use the returned HevoAPI,
     * object from a single thread (probably the main UI thread of your application).</p>
     * <p>If you do choose to track events from multiple threads in your application,
     * you should synchronize your calls on the instance itself, like so:</p>
     * <pre>
     * {@code
     * HevoAPI instance = HevoAPI.getInstance(context, token);
     * synchronized(instance) { // Only necessary if the instance will be used in multiple threads.
     *     instance.track(...)
     * }
     * }
     * </pre>
     *
     * @param context The application context you are tracking
     * @param optOutTrackingDefault Whether or not Hevo can start tracking by default. See
     *     {@link #optOutTracking()}.
     * @return an instance of HevoAPI associated with your project
     */
    public static HevoAPI getInstance(Context context, boolean optOutTrackingDefault) {
        if (null == context) {
            return null;
        }
        synchronized (sInstance) {
            final Context appContext = context.getApplicationContext();

            if (null == sReferrerPrefs) {
                sReferrerPrefs = sPrefsLoader.loadPreferences(context, HevoConfig.REFERRER_PREFS_NAME, null);
            }

            Map <Context, HevoAPI> instances = sInstance;
            HevoAPI instance = instances.get(appContext);
            if (null == instance && ConfigurationChecker.checkBasicConfiguration(appContext)) {
                instance = new HevoAPI(appContext, sReferrerPrefs, optOutTrackingDefault);
                registerAppLinksListeners(context, instance);
                instances.put(appContext, instance);
            }

            checkIntentForInboundAppLink(context);

            return instance;
        }
    }

    /**
     * This function creates a distinct_id alias from alias to original. If original is null, then it will create an alias
     * to the current events distinct_id, which may be the distinct_id randomly generated by the Hevo library
     * before {@link #identify(String)} is called.
     *
     * <p>This call does not identify the user after. You must still call both {@link #identify(String)} and
     *
     * @param alias the new distinct_id that should represent original.
     * @param original the old distinct_id that alias will be mapped to.
     */
    public void alias(String alias, String original) {
        if (hasOptedOutTracking()) return;
        if (original == null) {
            original = getDistinctId();
        }
        if (alias.equals(original)) {
            HLog.w(LOGTAG, "Attempted to alias identical distinct_ids " + alias + ". Alias message will not be sent.");
            return;
        }

        try {
            final JSONObject j = new JSONObject();
            j.put("alias", alias);
            j.put("original", original);
            track(ReservedEvents.ALIAS, j);
        } catch (final JSONException e) {
            HLog.e(LOGTAG, "Failed to alias", e);
        }
        flush();
    }

    /**
     * Associate all future calls to {@link #track(String, JSONObject)} with the user identified by
     * the given distinct id.
     *
     * <p>This call does not identify the user for People Analytics;
     * the same distinct_id for both calls, and using a distinct_id that is easy
     * to associate with the given user, for example, a server-side account identifier.
     *
     * <p>Calls to {@link #track(String, JSONObject)} made before corresponding calls to
     * identify will use an internally generated distinct id, which means it is best
     * to call identify early to ensure that your Hevo funnels and retention
     * analytics can continue to track the user throughout their lifetime. We recommend
     * calling identify as early as you can.
     *
     * <p>Once identify is called, the given distinct id persists across restarts of your
     * application.
     *
     * @param distinctId a string uniquely identifying this user. Events sent to
     *     Hevo using the same disinct_id will be considered associated with the
     *     same visitor/customer for retention and funnel reporting, so be sure that the given
     *     value is globally unique for each individual user you intend to track.
     *
     */
    public void identify(String distinctId) {
        distinctId = distinctId == null ? "" : distinctId.trim();
        if (hasOptedOutTracking() || distinctId.isEmpty()) {
            return;
        }
        try {
            String oldIdentity = getDistinctId();
            if (!oldIdentity.equals(distinctId)) {
                final JSONObject j = new JSONObject();
                j.put("old_identity", oldIdentity);
                j.put("new_identity", distinctId);
                track(ReservedEvents.IDENTITY_CHANGE, j);
            }
        } catch (JSONException e) {
            HLog.e(LOGTAG, "Failed to set ", e);
        }
        synchronized (mPersistentIdentity) {
            mPersistentIdentity.setEventsDistinctId(distinctId);
        }
    }

    /**
     * Begin timing of an event. Calling timeEvent("Thing") will not send an event, but
     * when you eventually call track("Thing"), your tracked event will be sent with a "$duration"
     * property, representing the number of seconds between your calls.
     *
     * @param eventName the name of the event to track with timing.
     */
    public void timeEvent(final String eventName) {
        if (hasOptedOutTracking()) return;
        final long writeTime = System.currentTimeMillis();
        synchronized (mEventTimings) {
            mEventTimings.put(eventName, writeTime);
            mPersistentIdentity.addTimeEvent(eventName, writeTime);
        }
    }

    /**
     * Retrieves the time elapsed for the named event since timeEvent() was called.
     *
     * @param eventName the name of the event to be tracked that was previously called with timeEvent()
     */
    public double eventElapsedTime(final String eventName) {
        final long currentTime = System.currentTimeMillis();
        Long startTime;
        synchronized (mEventTimings) {
            startTime = mEventTimings.get(eventName);
        }
        return startTime == null ? 0 : (double)((currentTime - startTime) / 1000);
    }

    /**
     * Track an event.
     *
     * <p>Every call to track eventually results in a data point sent to Hevo. These data points
     * are what are measured, counted, and broken down to create your Hevo reports. Events
     * have a string name, and an optional set of name/value pairs that describe the properties of
     * that event.
     *
     * @param eventName The name of the event to send
     * @param properties A Map containing the key value pairs of the properties to include in this event.
     *                   Pass null if no extra properties exist.
     *
     * See also {@link #track(String, org.json.JSONObject)}
     */
    public void trackMap(String eventName, Map<String, Object> properties) {
        if (hasOptedOutTracking()) return;
        if (null == properties) {
            track(eventName, null);
        } else {
            try {
                track(eventName, new JSONObject(properties));
            } catch (NullPointerException e) {
                HLog.w(LOGTAG, "Can't have null keys in the properties of trackMap!");
            }
        }
    }

    /**
     * Track an event.
     *
     * <p>Every call to track eventually results in a data point sent to Hevo. These data points
     * are what are measured, counted, and broken down to create your Hevo reports. Events
     * have a string name, and an optional set of name/value pairs that describe the properties of
     * that event.
     *
     * @param eventName The name of the event to send
     * @param properties A JSONObject containing the key value pairs of the properties to include in this event.
     *                   Pass null if no extra properties exist.
     */
    // DO NOT DOCUMENT, but track() must be thread safe since it is used to track events in
    // notifications from the UI thread, which might not be our HevoAPI "home" thread.
    // This MAY CHANGE IN FUTURE RELEASES, so minimize code that assumes thread safety
    // (and perhaps document that code here).
    public void track(String eventName, JSONObject properties) {
        if (hasOptedOutTracking()) return;
        track(eventName, properties, false);
    }

    /**
     * Equivalent to {@link #track(String, JSONObject)} with a null argument for properties.
     * Consider adding properties to your tracking to get the best insights and experience from Hevo.
     * @param eventName the name of the event to send
     */
    public void track(String eventName) {
        if (hasOptedOutTracking()) return;
        track(eventName, null);
    }

    /**
     * Push all queued Hevo events and People Analytics changes to Hevo servers.
     *
     * <p>Events and People messages are pushed gradually throughout
     * the lifetime of your application. This means that to ensure that all messages
     * are sent to Hevo when your application is shut down, you will
     * need to call flush() to let the Hevo library know it should
     * send all remaining messages to the server. We strongly recommend
     * placing a call to flush() in the onDestroy() method of
     * your main application activity.
     */
    public void flush() {
        if (hasOptedOutTracking()) return;
        mMessages.postToServer();
    }

    /**
     * Returns a json object of the user's current super properties
     *
     *<p>SuperProperties are a collection of properties that will be sent with every event to Hevo,
     * and persist beyond the lifetime of your application.
     */
      public JSONObject getSuperProperties() {
          JSONObject ret = new JSONObject();
          mPersistentIdentity.addSuperPropertiesToObject(ret, null);
          return ret;
      }

    /**
     * Returns the string id currently being used to uniquely identify the user associated
     * with events sent using {@link #track(String, JSONObject)}. Before any calls to
     * {@link #identify(String)}, this will be an id automatically generated by the library.
     *
     * <p>The id returned by getDistinctId is independent of the distinct id used to identify
     * any People Analytics properties in Hevo. To read and write that identifier,
     *
     * @return The distinct id associated with event tracking
     *
     * @see #identify(String)
     */
    public String getDistinctId() {
        return mPersistentIdentity.getEventsDistinctId();
     }

    /**
     * Register properties that will be sent with every subsequent call to {@link #track(String, JSONObject)}.
     *
     * <p>SuperProperties are a collection of properties that will be sent with every event to Hevo,
     * and persist beyond the lifetime of your application.
     *
     * <p>Setting a superProperty with registerSuperProperties will store a new superProperty,
     * possibly overwriting any existing superProperty with the same name (to set a
     * superProperty only if it is currently unset, use {@link #registerSuperPropertiesOnce(JSONObject)})
     *
     * <p>SuperProperties will persist even if your application is taken completely out of memory.
     * to remove a superProperty, call {@link #unregisterSuperProperty(String)} or {@link #clearSuperProperties()}
     *
     * @param superProperties    A Map containing super properties to register
     *
     * See also {@link #registerSuperProperties(org.json.JSONObject)}
     */
    public void registerSuperPropertiesMap(Map<String, Object> superProperties) {
        if (hasOptedOutTracking()) return;
        if (null == superProperties) {
            HLog.e(LOGTAG, "registerSuperPropertiesMap does not accept null properties");
            return;
        }

        try {
            registerSuperProperties(new JSONObject(superProperties));
        } catch (NullPointerException e) {
            HLog.w(LOGTAG, "Can't have null keys in the properties of registerSuperPropertiesMap");
        }
    }

    /**
     * Register properties that will be sent with every subsequent call to {@link #track(String, JSONObject)}.
     *
     * <p>SuperProperties are a collection of properties that will be sent with every event to Hevo,
     * and persist beyond the lifetime of your application.
     *
     * <p>Setting a superProperty with registerSuperProperties will store a new superProperty,
     * possibly overwriting any existing superProperty with the same name (to set a
     * superProperty only if it is currently unset, use {@link #registerSuperPropertiesOnce(JSONObject)})
     *
     * <p>SuperProperties will persist even if your application is taken completely out of memory.
     * to remove a superProperty, call {@link #unregisterSuperProperty(String)} or {@link #clearSuperProperties()}
     *
     * @param superProperties    A JSONObject containing super properties to register
     * @see #registerSuperPropertiesOnce(JSONObject)
     * @see #unregisterSuperProperty(String)
     * @see #clearSuperProperties()
     */
    public void registerSuperProperties(JSONObject superProperties) {
        if (hasOptedOutTracking()) return;
        mPersistentIdentity.registerSuperProperties(superProperties);
        this.track(ReservedEvents.INSTALLATION, new JSONObject(), false);
    }

    /**
     * Remove a single superProperty, so that it will not be sent with future calls to {@link #track(String, JSONObject)}.
     *
     * <p>If there is a superProperty registered with the given name, it will be permanently
     * removed from the existing superProperties.
     * To clear all superProperties, use {@link #clearSuperProperties()}
     *
     * @param superPropertyName name of the property to unregister
     * @see #registerSuperProperties(JSONObject)
     */
    public void unregisterSuperProperty(String superPropertyName) {
        if (hasOptedOutTracking()) return;
        mPersistentIdentity.unregisterSuperProperty(superPropertyName);
        this.track(ReservedEvents.INSTALLATION, new JSONObject(), false);
        mPersistentIdentity.resetSuperProperties(superPropertyName);
    }

    /**
     * Register super properties for events, only if no other super property with the
     * same names has already been registered.
     *
     * <p>Calling registerSuperPropertiesOnce will never overwrite existing properties.
     *
     * @param superProperties A Map containing the super properties to register.
     *
     * See also {@link #registerSuperPropertiesOnce(org.json.JSONObject)}
     */
    public void registerSuperPropertiesOnceMap(Map<String, Object> superProperties) {
        if (hasOptedOutTracking()) return;
        if (null == superProperties) {
            HLog.e(LOGTAG, "registerSuperPropertiesOnceMap does not accept null properties");
            return;
        }

        try {
            registerSuperPropertiesOnce(new JSONObject(superProperties));
        } catch (NullPointerException e) {
            HLog.w(LOGTAG, "Can't have null keys in the properties of registerSuperPropertiesOnce!");
        }
    }

    /**
     * Register super properties for events, only if no other super property with the
     * same names has already been registered.
     *
     * <p>Calling registerSuperPropertiesOnce will never overwrite existing properties.
     *
     * @param superProperties A JSONObject containing the super properties to register.
     * @see #registerSuperProperties(JSONObject)
     */
    public void registerSuperPropertiesOnce(JSONObject superProperties) {
        if (hasOptedOutTracking()) return;
        mPersistentIdentity.registerSuperPropertiesOnce(superProperties);
        this.track(ReservedEvents.INSTALLATION, new JSONObject(), false);
    }

    /**
     * Erase all currently registered superProperties.
     *
     * <p>Future tracking calls to Hevo will not contain the specific
     * superProperties registered before the clearSuperProperties method was called.
     *
     * <p>To remove a single superProperty, use {@link #unregisterSuperProperty(String)}
     *
     * @see #registerSuperProperties(JSONObject)
     */
    public void clearSuperProperties() {
        mPersistentIdentity.clearSuperProperties();
        this.track(ReservedEvents.INSTALLATION, new JSONObject(), false);
        mPersistentIdentity.resetSuperProperties();
    }

    /**
     * Updates super properties in place. Given a SuperPropertyUpdate object, will
     * pass the current values of SuperProperties to that update and replace all
     * results with the return value of the update. Updates are synchronized on
     * the underlying super properties store, so they are guaranteed to be thread safe
     * (but long running updates may slow down your tracking.)
     *
     * @param update A function from one set of super properties to another. The update should not return null.
     */
    public void updateSuperProperties(SuperPropertyUpdate update) {
        if (hasOptedOutTracking()) return;
        mPersistentIdentity.updateSuperProperties(update);
        this.track(ReservedEvents.INSTALLATION, new JSONObject(), false);
    }

    /**
     * Clears tweaks and all distinct_ids, superProperties, and push registrations from persistent storage.
     * Will not clear referrer information.
     */
    public void reset() {
        // Will clear distinct_ids, superProperties, notifications, experiments,
        // and waiting People Analytics properties. Will have no effect
        // on messages already queued to send with AnalyticsMessages.
        mPersistentIdentity.clearPreferences();
        this.track(ReservedEvents.INSTALLATION, new JSONObject(), false);
        identify(getDistinctId());
        flush();
    }

    /**
     * Returns an unmodifiable map that contains the device description properties
     * that will be sent to Hevo. These are not all of the default properties,
     * but are a subset that are dependant on the user's device or installed version
     * of the host application, and are guaranteed not to change while the app is running.
     */
    public Map<String, String> getDeviceInfo() {
        return mDeviceInfo;
    }

    /**
     * Use this method to opt-out a user from tracking. Events and people updates that haven't been
     * flushed yet will be deleted. Use {@link #flush()} before calling this method if you want
     * to send all the queues to Hevo before.
     *
     * This method will also remove any user-related information from the device.
     */
    public void optOutTracking() {
        getAnalyticsMessages().emptyTrackingQueues();
        mPersistentIdentity.clearPreferences();
        synchronized (mEventTimings) {
            mEventTimings.clear();
            mPersistentIdentity.clearTimeEvents();
        }
        mPersistentIdentity.clearReferrerProperties();
        mPersistentIdentity.setOptOutTracking(true);
    }

    /**
     * Use this method to opt-in an already opted-out user from tracking. People updates and track
     * calls will be sent to Hevo after using this method.
     * This method will internally track an opt-in event to your project. If you want to identify
     * the opt-in event and/or pass properties to the event, see {@link #optInTracking(String)} and
     * {@link #optInTracking(String, JSONObject)}
     *
     * See also {@link #optOutTracking()}.
     */
    public void optInTracking() {
        optInTracking(null, null);
    }

    /**
     * Use this method to opt-in an already opted-out user from tracking. People updates and track
     * calls will be sent to Hevo after using this method.
     * This method will internally track an opt-in event to your project.
     *
     * @param distinctId Optional string to use as the distinct ID for events.
     *                   This will call {@link #identify(String)}.
     *                   If you use people profiles make sure you manually call
     *
     * See also {@link #optInTracking(String)}, {@link #optInTracking(String, JSONObject)} and
     *  {@link #optOutTracking()}.
     */
    public void optInTracking(String distinctId) {
        optInTracking(distinctId, null);
    }

    /**
     * Use this method to opt-in an already opted-out user from tracking. People updates and track
     * calls will be sent to Hevo after using this method.
     * This method will internally track an opt-in event to your project.
     *
     * @param distinctId Optional string to use as the distinct ID for events.
     *                   This will call {@link #identify(String)}.
     *                   If you use people profiles make sure you manually call
     * @param properties Optional JSONObject that could be passed to add properties to the
     *                   opt-in event that is sent to Hevo.
     *
     * See also {@link #optInTracking()} and {@link #optOutTracking()}.
     */
    public void optInTracking(String distinctId, JSONObject properties) {
        mPersistentIdentity.setOptOutTracking(false);
        if (distinctId != null) {
            identify(distinctId);
        }
        track("$opt_in", properties);
    }
    /**
     * Will return true if the user has opted out from tracking. See {@link #optOutTracking()} and
     * {@link HevoAPI#getInstance(Context, boolean)} for more information.
     *
     * @return true if user has opted out from tracking. Defaults to false.
     */
    public boolean hasOptedOutTracking() {
        return mPersistentIdentity.getOptOutTracking();
    }

    /**
     * Attempt to register HevoActivityLifecycleCallbacks to the application's event lifecycle.
     * Once registered, we can automatically check for and show in-app notifications
     * when any Activity is opened.
     *
     * This is only available if the android version is >= 16. You can disable livecycle callbacks by setting
     * com.hevodata.android.HevoConfig.AutoShowHevoUpdates to false in your AndroidManifest.xml
     *
     * This function is automatically called when the library is initialized unless you explicitly
     * set com.hevodata.android.HevoConfig.AutoShowHevoUpdates to false in your AndroidManifest.xml
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    void registerHevoActivityLifecycleCallbacks() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (mContext.getApplicationContext() instanceof Application) {
                final Application app = (Application) mContext.getApplicationContext();
                mHevoActivityLifecycleCallbacks = new HevoActivityLifecycleCallbacks(this, mConfig);
                app.registerActivityLifecycleCallbacks(mHevoActivityLifecycleCallbacks);
            } else {
                HLog.i(LOGTAG, "Context is not an Application, Hevo will not automatically show in-app notifications or A/B test experiments. We won't be able to automatically flush on an app background.");
            }
        }
    }

    /**
     * Based on the application's event lifecycle this method will determine whether the app
     * is running in the foreground or not.
     *
     * If your build version is below 14 this method will always return false.
     *
     * @return True if the app is running in the foreground.
     */
    public boolean isAppInForeground() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (mHevoActivityLifecycleCallbacks != null) {
                return mHevoActivityLifecycleCallbacks.isInForeground();
            }
        } else {
            HLog.e(LOGTAG, "Your build version is below 14. This method will always return false.");
        }

        return false;
    }

    void onBackground() {
        flush();
    }

    void onForeground() {
        mSessionMetadata.initSession();
    }

    // when OS-level events occur.
    interface InstanceProcessor {
        public void process(HevoAPI m);
    }

    static void allInstances(InstanceProcessor processor) {
        synchronized (sInstance) {
            for (final HevoAPI instance : sInstance.values()) {
                processor.process(instance);
            }
        }
    }

    ////////////////////////////////////////////////////////////////////
    // Conveniences for testing. These methods should not be called by
    // non-test client code.

    AnalyticsMessages getAnalyticsMessages() {
        return AnalyticsMessages.getInstance(mContext);
    }

    PersistentIdentity getPersistentIdentity(final Context context, Future<SharedPreferences> referrerPreferences) {
        final SharedPreferencesLoader.OnPrefsLoadedListener listener = new SharedPreferencesLoader.OnPrefsLoadedListener() {
            @Override
            public void onPrefsLoaded(SharedPreferences preferences) {
                final JSONArray records = PersistentIdentity.waitingPeopleRecordsForSending(preferences);
            }
        };

        final String prefsName = "com.hevodata.android.HevoAPI_";
        final Future<SharedPreferences> storedPreferences = sPrefsLoader.loadPreferences(context, prefsName, listener);

        final String timeEventsPrefsName = "com.hevodata.android.HevoAPI.TimeEvents_";
        final Future<SharedPreferences> timeEventsPrefs = sPrefsLoader.loadPreferences(context, timeEventsPrefsName, null);

        final String hevoPrefsName = "com.hevodata.android.Hevo";
        final Future<SharedPreferences> hevoPrefs = sPrefsLoader.loadPreferences(context, hevoPrefsName, null);

        return new PersistentIdentity(referrerPreferences, storedPreferences, timeEventsPrefs, hevoPrefs);
    }

    boolean sendAppOpen() {
        return !mConfig.getDisableAppOpenEvent();
    }

    ////////////////////////////////////////////////////
    protected void flushNoDecideCheck() {
        if (hasOptedOutTracking()) return;
        mMessages.postToServer();
    }

    protected void track(String eventName, JSONObject properties, boolean isAutomaticEvent) {
        if (hasOptedOutTracking() || !mConfig.getCaptureAutomaticEvents()) {
            return;
        }

        final Long eventBegin;
        synchronized (mEventTimings) {
            eventBegin = mEventTimings.get(eventName);
            mEventTimings.remove(eventName);
            mPersistentIdentity.removeTimeEvent(eventName);
        }

        try {
            final JSONObject messageProps = new JSONObject();

            final Map<String, String> referrerProperties = mPersistentIdentity.getReferrerProperties();
            for (final Map.Entry<String, String> entry : referrerProperties.entrySet()) {
                final String key = entry.getKey();
                final String value = entry.getValue();
                messageProps.put(key, value);
            }

            mPersistentIdentity.addSuperPropertiesToObject(messageProps, eventName);

            // Don't allow super properties or referral properties to override these fields,
            // but DO allow the caller to override them in their given properties.
            final double timeSecondsDouble = (System.currentTimeMillis()) / 1000.0;
            final long timeSeconds = (long) timeSecondsDouble;
            messageProps.put("time", timeSeconds);
            messageProps.put("distinct_id", getDistinctId());

            if (null != eventBegin) {
                final double eventBeginDouble = ((double) eventBegin) / 1000.0;
                final double secondsElapsed = timeSecondsDouble - eventBeginDouble;
                messageProps.put("$duration", secondsElapsed);
            }

            if (null != properties) {
                final Iterator<?> propIter = properties.keys();
                while (propIter.hasNext()) {
                    final String key = (String) propIter.next();
                    if (!properties.isNull(key)) {
                        messageProps.put(key, properties.get(key));
                    }
                }
            }

            final AnalyticsMessages.EventDescription eventDescription =
                    new AnalyticsMessages.EventDescription(eventName, messageProps,
                            isAutomaticEvent, mSessionMetadata.getMetadataForEvent());
            mMessages.eventsMessage(eventDescription);
        } catch (final JSONException e) {
            HLog.e(LOGTAG, "Exception tracking event " + eventName, e);
        }
    }

    private static void registerAppLinksListeners(Context context, final HevoAPI hevo) {
        // Register a BroadcastReceiver to receive com.parse.bolts.measurement_event and track a call to hevo
        try {
            final Class<?> clazz = Class.forName("android.support.v4.content.LocalBroadcastManager");
            final Method methodGetInstance = clazz.getMethod("getInstance", Context.class);
            final Method methodRegisterReceiver = clazz.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class);
            final Object localBroadcastManager = methodGetInstance.invoke(null, context);
            methodRegisterReceiver.invoke(localBroadcastManager, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final JSONObject properties = new JSONObject();
                    final Bundle args = intent.getBundleExtra("event_args");
                    if (args != null) {
                        for (final String key : args.keySet()) {
                            try {
                                properties.put(key, args.get(key));
                            } catch (final JSONException e) {
                                HLog.e(APP_LINKS_LOGTAG, "failed to add key \"" + key + "\" to properties for tracking bolts event", e);
                            }
                        }
                    }
                    hevo.track("$" + intent.getStringExtra("event_name"), properties);
                }
            }, new IntentFilter("com.parse.bolts.measurement_event"));
        } catch (final InvocationTargetException e) {
            HLog.d(APP_LINKS_LOGTAG, "Failed to invoke LocalBroadcastManager.registerReceiver() -- App Links tracking will not be enabled due to this exception", e);
        } catch (final ClassNotFoundException e) {
            HLog.d(APP_LINKS_LOGTAG, "To enable App Links tracking android.support.v4 must be installed: " + e.getMessage());
        } catch (final NoSuchMethodException e) {
            HLog.d(APP_LINKS_LOGTAG, "To enable App Links tracking android.support.v4 must be installed: " + e.getMessage());
        } catch (final IllegalAccessException e) {
            HLog.d(APP_LINKS_LOGTAG, "App Links tracking will not be enabled due to this exception: " + e.getMessage());
        }
    }

    private static void checkIntentForInboundAppLink(Context context) {
        // call the Bolts getTargetUrlFromInboundIntent method simply for a side effect
        // if the intent is the result of an App Link, it'll trigger al_nav_in
        // https://github.com/BoltsFramework/Bolts-Android/blob/1.1.2/Bolts/src/bolts/AppLinks.java#L86
        if (context instanceof Activity) {
            try {
                final Class<?> clazz = Class.forName("bolts.AppLinks");
                final Intent intent = ((Activity) context).getIntent();
                final Method getTargetUrlFromInboundIntent = clazz.getMethod("getTargetUrlFromInboundIntent", Context.class, Intent.class);
                getTargetUrlFromInboundIntent.invoke(null, context, intent);
            } catch (final InvocationTargetException e) {
                HLog.d(APP_LINKS_LOGTAG, "Failed to invoke bolts.AppLinks.getTargetUrlFromInboundIntent() -- Unable to detect inbound App Links", e);
            } catch (final ClassNotFoundException e) {
                HLog.d(APP_LINKS_LOGTAG, "Please install the Bolts library >= 1.1.2 to track App Links: " + e.getMessage());
            } catch (final NoSuchMethodException e) {
                HLog.d(APP_LINKS_LOGTAG, "Please install the Bolts library >= 1.1.2 to track App Links: " + e.getMessage());
            } catch (final IllegalAccessException e) {
                HLog.d(APP_LINKS_LOGTAG, "Unable to detect inbound App Links: " + e.getMessage());
            }
        } else {
            HLog.d(APP_LINKS_LOGTAG, "Context is not an instance of Activity. To detect inbound App Links, pass an instance of an Activity to getInstance.");
        }
    }
}
