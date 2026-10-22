# Gradle Build Fix Guide - React Native Android

## Problem: Kotlin Plugin Resolution Error

### Error Message
```
Plugin [id: 'org.jetbrains.kotlin.jvm', version: '1.8.0'] was not found in any of the following sources:
- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:1.8.0')
```

### Root Cause
- React Native 0.73.x uses older Gradle configuration with includeBuild for React Native gradle plugin
- The internal React Native gradle plugin requires specific Kotlin version that wasn't resolving
- Gradle 8.2 with React Native 0.73 had compatibility issues with Kotlin plugin resolution
- SSL certificate issues preventing dependency download

## Solution: Upgrade to React Native 0.86.0

The most reliable fix is to upgrade to React Native 0.86.0 which uses the new Gradle configuration format that matches modern Android development practices.

### Step-by-Step Fix

#### 1. Update package.json
```json
{
  "dependencies": {
    "react": "19.2.3",
    "react-native": "0.86.0",
    "@react-navigation/native": "^6.1.9",
    "@react-navigation/native-stack": "^6.9.17",
    "react-native-safe-area-context": "^5.5.2"
  },
  "devDependencies": {
    "@babel/core": "^7.25.2",
    "@babel/preset-env": "^7.25.3",
    "@babel/runtime": "^7.25.0",
    "@react-native-community/cli": "20.1.0",
    "@react-native-community/cli-platform-android": "20.1.0",
    "@react-native-community/cli-platform-ios": "20.1.0",
    "@react-native/babel-preset": "0.86.0",
    "@react-native/eslint-config": "0.86.0",
    "@react-native/metro-config": "0.86.0",
    "@react-native/typescript-config": "0.86.0"
  },
  "engines": {
    "node": ">= 22.11.0"
  }
}
```

#### 2. Update gradle-wrapper.properties
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
networkTimeout=10000
```

#### 3. Update android/build.gradle
```gradle
buildscript {
    ext {
        buildToolsVersion = "36.0.0"
        minSdkVersion = 24
        compileSdkVersion = 36
        targetSdkVersion = 36
        ndkVersion = "27.1.12297006"
        kotlinVersion = "2.1.20"
    }
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle")
        classpath("com.facebook.react:react-native-gradle-plugin")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin")
    }
}

apply plugin: "com.facebook.react.rootproject"
```

#### 4. Update android/settings.gradle
```gradle
pluginManagement { includeBuild("../node_modules/@react-native/gradle-plugin") }
plugins { id("com.facebook.react.settings") }
extensions.configure(com.facebook.react.ReactSettingsExtension){ ex -> ex.autolinkLibrariesFromCommand() }
rootProject.name = 'YourProjectName'
include ':app'
includeBuild('../node_modules/@react-native/gradle-plugin')
```

#### 5. Update android/app/build.gradle
```gradle
apply plugin: "com.android.application"
apply plugin: "org.jetbrains.kotlin.android"
apply plugin: "com.facebook.react"

react {
    autolinkLibrariesWithApp()
}

def enableProguardInReleaseBuilds = false

def jscFlavor = 'io.github.react-native-community:jsc-android:2026004.+'

android {
    ndkVersion rootProject.ext.ndkVersion
    buildToolsVersion rootProject.ext.buildToolsVersion
    compileSdk rootProject.ext.compileSdkVersion

    namespace "com.your.package"

    defaultConfig {
        applicationId "com.your.package"
        minSdkVersion rootProject.ext.minSdkVersion
        targetSdkVersion rootProject.ext.targetSdkVersion
        versionCode 1
        versionName "1.0"
    }
    // ... rest of config
}

dependencies {
    implementation("com.facebook.react:react-android")

    if (hermesEnabled.toBoolean()) {
        implementation("com.facebook.react:hermes-android")
    } else {
        implementation jscFlavor
    }
}
```

#### 6. Update android/gradle.properties
Remove this line (not supported in React Native 0.82+):
```
newArchEnabled=false
```

#### 7. Update babel.config.js
```javascript
module.exports = {
  presets: ['module:@react-native/babel-preset'],
};
```

#### 8. Update MainApplication.java
Change return type from `Boolean` to `boolean`:
```java
@Override
protected boolean isHermesEnabled() {
  return BuildConfig.IS_HERMES_ENABLED;
}
```

#### 9. Update AndroidManifest.xml for TV
```xml
<uses-feature android:name="android.software.leanback" android:required="false" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />

<application
    android:supportsRtl="true"
    android:screenOrientation="fullSensor"
    android:banner="@mipmap/ic_launcher">

    <activity
        android:screenOrientation="fullSensor"
        android:exported="true"
        android:launchMode="singleTask">

        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
            <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

#### 10. Generate debug keystore (if missing)
```bash
cd android/app
keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

## Key Changes Summary

| Component | Old Version | New Version |
|-----------|-------------|-------------|
| React Native | 0.73.9 | 0.86.0 |
| React | 18.2.0 | 19.2.3 |
| Gradle | 8.2 | 9.3.1 |
| compileSdk | 34 | 36 |
| targetSdk | 34 | 36 |
| minSdk | 21 | 24 |
| Kotlin | 1.8.0 | 2.1.20 |
| NDK | 23.1.7779620 | 27.1.12297006 |
| Babel Preset | metro-react-native-babel-preset | @react-native/babel-preset |

## Why This Works

1. **React Native 0.86.0** uses the new Gradle configuration format that doesn't rely on includeBuild for the React Native gradle plugin
2. **Gradle 9.3.1** has better Kotlin plugin resolution and is the version used by the React Native team
3. **Kotlin 2.1.20** is the version bundled with React Native 0.86.0 and is properly available in repositories
4. **New configuration format** uses version catalogs (implicit) which handles dependency resolution better
5. **Removed newArchEnabled** - React Native 0.82+ always uses New Architecture by default

## Alternative: Stay on React Native 0.73.x

If you must stay on React Native 0.73.x, try these fixes:

1. Add pluginManagement to settings.gradle:
```gradle
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

2. Use Gradle 8.9 instead of 8.2
3. Update Kotlin version in node_modules/@react-native/gradle-plugin/build.gradle.kts to 1.9.0

However, this approach is less reliable and may still have issues. Upgrading to 0.86.0 is recommended.

## Common Issues After Upgrade

### Resource compilation errors
- Copy launcher icons from a working project
- Ensure all drawable resources exist

### Java compilation errors
- Change `Boolean` to `boolean` in MainApplication.java isHermesEnabled()
- Update deprecated API usage

### C++ build errors
- Remove react-native-screens if using React Native 0.86+ (it's included)
- Clean build: `./gradlew clean`

## Verification

After applying these changes:
```bash
npm install
cd android
./gradlew clean
./gradlew assembleRelease
```

The APK should build successfully at: `android/app/build/outputs/apk/release/app-release.apk`
