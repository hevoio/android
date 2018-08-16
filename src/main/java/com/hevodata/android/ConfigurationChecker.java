package com.hevodata.android;

import android.content.Context;
import android.content.pm.PackageManager;

import com.hevodata.android.util.HLog;

class ConfigurationChecker {

    public static String LOGTAG = "HevoAPI.ConfigurationChecker";

    public static boolean checkBasicConfiguration(Context context) {
        final PackageManager packageManager = context.getPackageManager();
        final String packageName = context.getPackageName();

        if (packageManager == null || packageName == null) {
            HLog.w(LOGTAG, "Can't check configuration when using a Context with null packageManager or packageName");
            return false;
        }
        if (PackageManager.PERMISSION_GRANTED != packageManager.checkPermission("android.permission.INTERNET", packageName)) {
            HLog.w(LOGTAG, "Package does not have permission android.permission.INTERNET - Hevo will not work at all!");
            HLog.i(LOGTAG, "You can fix this by adding the following to your AndroidManifest.xml file:\n" +
                    "<uses-permission android:name=\"android.permission.INTERNET\" />");
            return false;
        }

        return true;
    }
}
