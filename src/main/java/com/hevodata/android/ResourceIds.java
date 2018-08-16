package com.hevodata.android;

/**
 * This interface is for internal use in the Hevo library, and should not be included in
 * client code.
 */
public interface ResourceIds {
    boolean knownIdName(String name);
    int idFromName(String name);
    String nameForId(int id);
}
