package com.hevodata.android;


import android.test.AndroidTestCase;

import com.hevodata.android.ResourceReader;

public class ResourceReaderTest extends AndroidTestCase {
    public void setUp() {
        mDrawables = new ResourceReader.Drawables(TEST_PACKAGE_NAME, getContext());
        mIds = new ResourceReader.Ids(TEST_PACKAGE_NAME, getContext());
    }

    public void testSystemIdExists() {
        assertTrue(mDrawables.knownIdName("android:ic_lock_idle_alarm"));
        assertEquals(mDrawables.idFromName("android:ic_lock_idle_alarm"), android.R.drawable.ic_lock_idle_alarm);
        assertEquals(mDrawables.nameForId(android.R.drawable.ic_lock_idle_alarm), "android:ic_lock_idle_alarm");

        assertTrue(mIds.knownIdName("android:primary"));
        assertEquals(mIds.idFromName("android:primary"), android.R.id.primary);
        assertEquals(mIds.nameForId(android.R.id.primary), "android:primary");
    }

    public void testIdDoesntExist() {
        assertFalse(mDrawables.knownIdName("NO_SUCH_ID"));
        assertNull(mDrawables.nameForId(0x7f098888));

        assertFalse(mIds.knownIdName("NO_SUCH_ID"));
        assertNull(mIds.nameForId(0x7f098888));
    }

    private ResourceReader.Drawables mDrawables;
    private ResourceReader.Ids mIds;

    private static final String TEST_PACKAGE_NAME = "com.hevodata.android.test_r_package";
}
