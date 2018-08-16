<p align="center">
  <img src="https://hevo.io/img/logo-8.png" alt="Hevo Android Library" height="150"/>
</p>

# Table of Contents

<!-- MarkdownTOC -->

- [Quick Start Guide](#quick-start-guide)
    - [Installation](#installation)
    - [Integration](#integration)
- [I want to know more!](#i-want-to-know-more)
- [Want to Contribute?](#want-to-contribute)
- [Changelog](#changelog)
- [License](#license)

<!-- /MarkdownTOC -->

<a name="quick-start-guide"></a>
# Quick Start Guide

<!-- Check out our **[official documentation](https://hevo.com/help/reference/android)** for more in depth information on installing and using Hevo on Android. -->

<a name="installation"></a>
## Installation

### Dependencies in *app/build.gradle*

Add Hevo and Google Play Services to the `dependencies` section in *app/build.gradle*

```gradle
compile "com.hevodata:android:1.+"
```

### Permissions in *app/src/main/AndroidManifest.xml*

```xml
<!-- This permission is required to allow the application to send events and properties to Hevo -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- This permission is optional but recommended so we can be smart about when to send data  -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- This permission is optional but recommended so events will contain information about bluetooth state -->
<uses-permission android:name="android.permission.BLUETOOTH" />
```

<a name="integration"></a>
## Integration

### Initialization

Initialize Hevo in your main activity *app/src/main/java/com/hevo/example/myapplication/MainActivity.java*. Usually this should be done in [onCreate](https://developer.android.com/reference/android/app/Activity.html#onCreate(android.os.Bundle)).

```java
String projectToken = YOUR_PROJECT_TOKEN; // e.g.: "1ef7e30d2a58d27f4b90c42e31d6d7ad" 
HevoAPI hevo = HevoAPI.getInstance(this, projectToken);
```
Remember to replace `YOUR_PROJECT_TOKEN` with the token provided to you on hevo.com.

### Tracking

With the `hevo` object created in [the last step](#integration) a call to `track` is all you need to start sending events to Hevo.

```java
hevo.track("Event name no props")

JSONObject props = new JSONObject();
props.put("Prop name", "Prop value");
props.put("Prop 2", "Value 2");
hevo.track("Event name", props);
```

Have any questions? Reach out to [dev@hevodata.com](mailto:dev@hevodata.com) to speak to someone smart, quickly.

