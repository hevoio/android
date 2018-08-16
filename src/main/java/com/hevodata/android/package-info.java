/**
 * This package contains the interface to Hevo that you can use from your
 * Android apps. You can use Hevo to send events, update people analytics properties,
 * display push notifications and other Hevo-driven content to your users.
 * <p>
 * The primary interface to Hevo services is in {@link com.hevodata.android.HevoAPI}.
 * At it's simplest, you can send events with
 * <pre>
 * {@code
 *
 * HevoAPI hevo = HevoAPI.getInstance(context, HEVO_TOKEN);
 * hevo.track("Library integrated", null);
 *
 * }
 * </pre>
 * <p>
 * In addition to this reference documentation, you can also see our overview
 * and getting started documentation at
 * <a href="https://hevo.com/help/reference/android" target="_blank"
 * >https://hevo.com/help/reference/android</a>
 */
package com.hevodata.android;
