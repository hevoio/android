package com.hevodata.android;

import com.hevodata.android.util.HLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;

import static com.hevodata.android.ConfigurationChecker.LOGTAG;

class SessionMetadata {
    private long mEventsCounter, mSessionStartEpoch;
    private String mSessionID;
    private Random mRandom;

    SessionMetadata() {
        initSession();
        mRandom = new Random();
    }

    protected void initSession() {
        mEventsCounter = 0L;
        mSessionID = Long.toHexString(new Random().nextLong());
        mSessionStartEpoch = System.currentTimeMillis() / 1000;
    }

    public JSONObject getMetadataForEvent() {
        JSONObject metadataJson = new JSONObject();
        try {
            metadataJson.put("h_event_id", Long.toHexString(mRandom.nextLong()));
            metadataJson.put("h_session_id", mSessionID);
            metadataJson.put("h_session_seq_id", mEventsCounter);
            metadataJson.put("h_session_start_sec", mSessionStartEpoch);
            mEventsCounter++;
        } catch (JSONException e) {
            HLog.e(LOGTAG, "Cannot create session metadata JSON object", e);
        }

        return metadataJson;
    }
}
