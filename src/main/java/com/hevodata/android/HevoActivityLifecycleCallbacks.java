package com.hevodata.android;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
class HevoActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable check;
    private boolean mIsForeground = true;
    private boolean mPaused = true;
    private static Double sStartSessionTime;
    private static final int CHECK_DELAY = 500;
    private final HevoAPI hevoAPI;
    private final HevoConfig mConfig;

    public HevoActivityLifecycleCallbacks(HevoAPI hevoAPI, HevoConfig config) {
        this.hevoAPI = hevoAPI;
        mConfig = config;
        if (sStartSessionTime == null) {
            sStartSessionTime = (double) System.currentTimeMillis();
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }

    @Override
    public void onActivityPaused(final Activity activity) {
        mPaused = true;

        if (check != null) {
            mHandler.removeCallbacks(check);
        }

        mHandler.postDelayed(check = new Runnable(){
            @Override
            public void run() {
                if (mIsForeground && mPaused) {
                    mIsForeground = false;
                    try {
                        double sessionLength = System.currentTimeMillis() - sStartSessionTime;
                        if (sessionLength >= mConfig.getMinimumSessionDuration() && sessionLength < mConfig.getSessionTimeoutDuration()) {
                            NumberFormat nf = NumberFormat.getNumberInstance(Locale.ENGLISH);
                            nf.setMaximumFractionDigits(1);
                            String sessionLengthString = nf.format((System.currentTimeMillis() - sStartSessionTime) / 1000);
                            JSONObject sessionProperties = new JSONObject();
                            sessionProperties.put(AutomaticEvents.SESSION_LENGTH, sessionLengthString);
                            hevoAPI.track(AutomaticEvents.SESSION, sessionProperties, true);
                            hevoAPI.track(ReservedEvents.INSTALLATION, new JSONObject(), false);
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    hevoAPI.onBackground();
                }
            }
        }, CHECK_DELAY);
    }

    @Override
    public void onActivityDestroyed(Activity activity) { }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }

    @Override
    public void onActivityResumed(Activity activity) {
        mPaused = false;
        boolean wasBackground = !mIsForeground;
        mIsForeground = true;

        if (check != null) {
            mHandler.removeCallbacks(check);
        }

        if (wasBackground) {
            // App is in foreground now
            sStartSessionTime = (double) System.currentTimeMillis();
            hevoAPI.onForeground();
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {}

    protected boolean isInForeground() {
        return mIsForeground;
    }
}
