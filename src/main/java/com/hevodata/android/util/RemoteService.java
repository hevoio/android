package com.hevodata.android.util;

import android.content.Context;

import java.io.IOException;

import javax.net.ssl.SSLSocketFactory;


public interface RemoteService {
    boolean isOnline(Context context, OfflineMode offlineMode);

    void checkIsHevoBlocked(String host);

    byte[] performRequest(String endpointUrl, String rawMessage, SSLSocketFactory socketFactory)
            throws ServiceUnavailableException, IOException;

    class ServiceUnavailableException extends Exception {
        public ServiceUnavailableException(String message, String strRetryAfter) {
            super(message);
            int retry;
            try {
                retry = Integer.parseInt(strRetryAfter);
            } catch (NumberFormatException e) {
                retry = 0;
            }
            mRetryAfter = retry;
        }

        public int getRetryAfter() {
            return mRetryAfter;
        }

        private final int mRetryAfter;
    }
}
