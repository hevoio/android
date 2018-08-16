package com.hevodata.android;


import org.json.JSONException;
import org.json.JSONObject;

public class ExceptionHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "HevoAPI.Exception";

    private static final int SLEEP_TIMEOUT_MS = 400;

    private static ExceptionHandler sInstance;
    private final Thread.UncaughtExceptionHandler mDefaultExceptionHandler;

    public ExceptionHandler() {
        mDefaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    public static void init() {
        if (sInstance == null) {
            synchronized (ExceptionHandler.class) {
                if (sInstance == null) {
                    sInstance = new ExceptionHandler();
                }
            }
        }
    }

    @Override
    public void uncaughtException(final Thread t, final Throwable e) {
        // Only one worker thread - giving priority to storing the event first and then flush
        HevoAPI.allInstances(new HevoAPI.InstanceProcessor() {
            @Override
            public void process(HevoAPI hevo) {
                try {
                    final JSONObject messageProp = new JSONObject();
                    messageProp.put(AutomaticEvents.APP_CRASHED_REASON, e.toString());
                    hevo.track(AutomaticEvents.APP_CRASHED, messageProp, true);
                } catch (JSONException e) {}
            }
        });

        HevoAPI.allInstances(new HevoAPI.InstanceProcessor() {
            @Override
            public void process(HevoAPI hevo) {
                hevo.flushNoDecideCheck();
            }
        });

        if (mDefaultExceptionHandler != null) {
            mDefaultExceptionHandler.uncaughtException(t, e);
        } else {
            killProcessAndExit();
        }
    }

    private void killProcessAndExit() {
        try {
            Thread.sleep(SLEEP_TIMEOUT_MS);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }
}
