# GPS蓝牙传输 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 Java + XML 实现一个全中文深色科技风的 Android 应用「GPS蓝牙传输」，把本机 GPS 以标准 NMEA 0183 通过蓝牙 SPP 实时输出给其他设备，支持服务器/客户端两种连接方式，可后台持续传输，且**无任何开机自启/自动运行**能力。

**Architecture:** 单 Activity + 底部导航 4 Fragment；`GpsOutputService`（前台服务）持有 `LocationProvider` → `TransmitEngine` → `BluetoothLinkManager` 链路；UI 只通过 `TransmitterState` 单例观察状态。NMEA 生成与发送节流为纯 Java 无框架依赖，可用 JVM 单元测试覆盖。UI 不直接引用 Service。

**Tech Stack:** Java 17、minSdk 24 / compileSdk 34、Gradle 8.12、AGP 8.7.3、AppCompat 1.7.0、Material 1.12.0、ConstraintLayout 2.1.4、RecyclerView 1.3.2、JUnit 4.13.2。

**Spec:** `docs/superpowers/specs/2026-09-23-gps-bluetooth-transmitter-design.md`

## Global Constraints

- 应用名 `GPS蓝牙传输`，包名 `com.gpsbt.transfer`，`namespace` 同包名。
- 界面文案**全部中文**，所有字符串写在 `values/strings.xml`，不提供其它语言资源。
- 技术栈必须是**纯 Java + XML 布局**，禁止引入 Kotlin 插件与 Compose。
- `minSdk 24` / `targetSdk 34` / `compileSdk 34` / `buildToolsVersion 35.0.0`；禁止使用需要 SDK 35+ 的依赖版本（如 core 1.15.x）。
- Manifest **不得**包含 `BOOT_COMPLETED` 接收器，**不得**申请 `RECEIVE_BOOT_COMPLETED`、`BLUETOOTH_SCAN`、`ACCESS_BACKGROUND_LOCATION`、`WAKE_LOCK`。
- Service 只能由用户点击「开始传输」显式启动，`exported=false`，`START_NOT_STICKY`。
- 深色科技风配色：背景渐变 `#0A0E1A→#0D1526`，卡片 `#141C31`，描边 `#223050`，主色青 `#00E5C7`，辅色蓝 `#3D8BFF`，强调橙 `#FF8A3D`，错误红 `#FF5C6C`，主文字 `#E6EDF7`，次文字 `#8A97B0`，禁用 `#4A5878`；所有数值用 `monospace` 字体。
- 本项目目录**不是 git 仓库**，计划中的「检查点」步骤代替 git commit；若用户要求初始化仓库，先 `git init` 再提交。
- 构建命令统一为：`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew <task>`。
- 每个任务结束时必须实际运行验证命令并把结果贴进报告，禁止声称"应该可以"。

---

## File Structure（文件职责总表）

| 路径 | 职责 |
|---|---|
| `settings.gradle` / `build.gradle` / `gradle.properties` / `gradlew*` | 构建配置 |
| `local.properties` | 本机 SDK 路径（不入库） |
| `app/build.gradle` | 模块配置、依赖 |
| `app/proguard-rules.pro` | 混淆规则 |
| `app/src/main/AndroidManifest.xml` | 权限、Activity、Service |
| `.../MainActivity.java` | 单 Activity、权限请求、底部导航容器 |
| `.../ui/fragment/HomeFragment.java` | 主页交互 |
| `.../ui/fragment/SatelliteFragment.java` | 卫星页 |
| `.../ui/fragment/LogFragment.java` | 日志页 |
| `.../ui/fragment/SettingsFragment.java` | 设置页 |
| `.../service/GpsOutputService.java` | 前台服务主控 |
| `.../engine/TransmitEngine.java` | 定频发送节流 + 计数 |
| `.../engine/LocationProvider.java` | LocationManager / GnssStatus 封装 |
| `.../nmea/NmeaGenerator.java` | 五类 NMEA 语句生成 |
| `.../nmea/NmeaChecksum.java` | XOR 校验和 |
| `.../nmea/NmeaInput.java` | 与框架无关的定位数据载体 |
| `.../nmea/NmeaSentenceType.java` | 语句开关枚举 |
| `.../bt/BluetoothLinkManager.java` | SPP 服务器/客户端连接 |
| `.../bt/ConnectionMode.java` | `SERVER` / `CLIENT` |
| `.../bt/BtState.java` | 蓝牙连接状态枚举 |
| `.../state/TransmitterState.java` | 全局状态单例 + 观察者 |
| `.../state/TransmitStats.java` | 统计快照 |
| `.../state/LogBus.java` | 内存日志总线 |
| `.../util/Prefs.java` | SharedPreferences 封装 |
| `.../util/Formatters.java` | 中文格式化 |
| `.../ui/view/PulseButtonView.java` | 脉冲主按钮 |
| `.../ui/view/SatelliteRadarView.java` | 卫星天空雷达 |
| `.../ui/view/StatCardView.java` | 数据卡片 |
| `.../ui/adapter/PairedDeviceAdapter.java` | 已配对设备列表 |
| `.../ui/adapter/SatelliteAdapter.java` | 卫星列表 |
| `.../ui/adapter/LogAdapter.java` | 日志列表 |
| `app/src/test/java/com/gpsbt/transfer/nmea/*Test.java` | NMEA 单元测试 |
| `app/src/test/java/com/gpsbt/transfer/engine/TransmitEngineTest.java` | 发送节流单元测试 |
| `app/src/test/java/com/gpsbt/transfer/state/TransmitterStateTest.java` | 状态通知单元测试 |
| `README.md` | 中文使用与调试指南 |

---

### Task 1: 工程骨架可编译（Gradle + 最小界面）

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`, `local.properties`, `app/build.gradle`, `app/proguard-rules.pro`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/gpsbt/transfer/MainActivity.java`
- Create: `app/src/main/res/values/strings.xml`, `colors.xml`, `themes.xml`, `dimens.xml`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/drawable/app_bg.xml`, `ic_launcher_background.xml`, `ic_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`
- Create: `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`

**Interfaces:**
- Produces: `com.gpsbt.transfer.MainActivity#onCreate(Bundle)`，后续任务往里加 Fragment 容器与权限逻辑
- Produces: 颜色资源名 `color_background_start`/`color_background_end`/`color_card`/`color_card_stroke`/`color_primary`/`color_accent_blue`/`color_warning`/`color_error`/`color_text_primary`/`color_text_secondary`/`color_text_disabled`

- [ ] **Step 1: 写 `settings.gradle`**

```gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GpsBtTransfer"
include ':app'
```

- [ ] **Step 2: 写根 `build.gradle`**

```gradle
plugins {
    id 'com.android.application' version '8.7.3' apply false
}
```

- [ ] **Step 3: 写 `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.parallel=true
org.gradle.caching=true
```

- [ ] **Step 4: 写 `app/build.gradle`**

```gradle
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.gpsbt.transfer'
    compileSdk 34
    buildToolsVersion '35.0.0'

    defaultConfig {
        applicationId 'com.gpsbt.transfer'
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName '1.0.0'
        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
        resourceConfigurations += ['zh']
    }

    buildTypes {
        debug {
            minifyEnabled false
            applicationIdSuffix ''
        }
        release {
            minifyEnabled false
            shrinkResources false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.returnDefaultValues = true
    }

    packagingOptions {
        resources.excludes += ['META-INF/*.version']
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.core:core:1.13.1'
    implementation 'androidx.fragment:fragment:1.8.5'

    testImplementation 'junit:junit:4.13.2'
}
```

- [ ] **Step 5: 写 `app/proguard-rules.pro`**

```proguard
# 保留服务入口，避免被混淆后系统找不到
-keep class com.gpsbt.transfer.service.GpsOutputService { *; }
-keep class com.gpsbt.transfer.MainActivity { *; }
```

- [ ] **Step 6: 写 `local.properties`**

```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

- [ ] **Step 7: 生成 Gradle Wrapper**

Run:
```bash
ls -d /c/Users/<用户名>/.gradle/wrapper/dists/gradle-8.12-bin/*/gradle-8.12/bin/gradle
```
然后用上一步输出的真实路径执行（示例路径，`*` 用真实哈希目录替换）：
```bash
"/c/Users/<用户名>/.gradle/wrapper/dists/gradle-8.12-bin/<hash>/gradle-8.12/bin/gradle" wrapper --gradle-version 8.12 --distribution-type bin
```
Expected: 生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`。

- [ ] **Step 8: 写 `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 定位 -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <!-- 蓝牙：旧版 -->
    <uses-permission
        android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <!-- 蓝牙：Android 12 及以上 -->
    <uses-permission
        android:name="android.permission.BLUETOOTH_CONNECT"
        android:minSdkVersion="31" />

    <!-- 前台服务 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission
        android:name="android.permission.FOREGROUND_SERVICE_LOCATION"
        android:minSdkVersion="34" />
    <uses-permission
        android:name="android.permission.POST_NOTIFICATIONS"
        android:minSdkVersion="33" />

    <!-- 本机 GPS 输出不依赖任何传感器硬件，声明为可选 -->
    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />
    <uses-feature android:name="android.hardware.location.gps" android:required="false" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="false"
        android:theme="@style/Theme.GpsBt">

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 9: 写颜色与主题**

`app/src/main/res/values/colors.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="color_background_start">#0A0E1A</color>
    <color name="color_background_end">#0D1526</color>
    <color name="color_card">#141C31</color>
    <color name="color_card_stroke">#223050</color>
    <color name="color_primary">#00E5C7</color>
    <color name="color_primary_dark">#00A88F</color>
    <color name="color_accent_blue">#3D8BFF</color>
    <color name="color_warning">#FF8A3D</color>
    <color name="color_error">#FF5C6C</color>
    <color name="color_text_primary">#E6EDF7</color>
    <color name="color_text_secondary">#8A97B0</color>
    <color name="color_text_disabled">#4A5878</color>
    <color name="color_ic_launcher_background">#0A0E1A</color>
</resources>
```

`app/src/main/res/values/themes.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">

    <style name="Theme.GpsBt" parent="Theme.Material3.Dark.NoActionBar">
        <item name="colorPrimary">@color/color_primary</item>
        <item name="colorOnPrimary">#06231E</item>
        <item name="colorSecondary">@color/color_accent_blue</item>
        <item name="colorOnSecondary">#FFFFFF</item>
        <item name="colorError">@color/color_error</item>
        <item name="android:colorBackground">@color/color_background_start</item>
        <item name="colorSurface">@color/color_card</item>
        <item name="colorOnSurface">@color/color_text_primary</item>
        <item name="colorOnSurfaceVariant">@color/color_text_secondary</item>
        <item name="android:windowBackground">@color/color_background_start</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar" tools:targetApi="m">false</item>
    </style>

    <style name="TextAppearance.GpsBt.Title" parent="TextAppearance.Material3.TitleLarge">
        <item name="android:textColor">@color/color_text_primary</item>
        <item name="android:letterSpacing">0.02</item>
    </style>

    <style name="TextAppearance.GpsBt.Body" parent="TextAppearance.Material3.BodyMedium">
        <item name="android:textColor">@color/color_text_secondary</item>
    </style>

    <style name="TextAppearance.GpsBt.Value" parent="TextAppearance.Material3.TitleMedium">
        <item name="android:fontFamily">monospace</item>
        <item name="android:textColor">@color/color_text_primary</item>
    </style>
</resources>
```

- [ ] **Step 10: 写字符串资源（中文）**

`app/src/main/res/values/strings.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">GPS蓝牙传输</string>
    <string name="loading">正在初始化…</string>
</resources>
```

`app/src/main/res/values/dimens.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <dimen name="screen_margin">20dp</dimen>
    <dimen name="card_radius">20dp</dimen>
    <dimen name="card_padding">16dp</dimen>
    <dimen name="card_gap">12dp</dimen>
</resources>
```

- [ ] **Step 11: 写最小布局 `activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/fragment_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/app_bg" />
```

`app/src/main/res/drawable/app_bg.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:angle="270"
        android:startColor="@color/color_background_start"
        android:endColor="@color/color_background_end"
        android:type="linear" />
</shape>
```

- [ ] **Step 12: 写 `MainActivity.java`**

`app/src/main/java/com/gpsbt/transfer/MainActivity.java`：
```java
package com.gpsbt.transfer;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState == null) {
            replace(new Fragment());
        }
    }

    private void replace(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
```
（Task 11 会用导航版本覆盖本文件。）

- [ ] **Step 13: 写启动图标与备份规则**

`app/src/main/res/drawable/ic_launcher_background.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#0A0E1A"
        android:pathData="M0,0h108v108h-108z" />
    <path
        android:fillColor="#141C31"
        android:pathData="M22,22h64v64h-64z" />
</vector>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- 卫星波纹 -->
    <path
        android:strokeColor="#3D8BFF"
        android:strokeWidth="3"
        android:fillColor="#00000000"
        android:pathData="M36,72a18,18 0 0,1 36,0" />
    <path
        android:strokeColor="#00E5C7"
        android:strokeWidth="3"
        android:fillColor="#00000000"
        android:pathData="M28,72a26,26 0 0,1 52,0" />
    <!-- 定位点 -->
    <path
        android:fillColor="#00E5C7"
        android:pathData="M54,34m-9,0a9,9 0 1,0 18,0a9,9 0 1,0 -18,0" />
    <path
        android:fillColor="#E6EDF7"
        android:pathData="M54,70l-6,-10h12z" />
</vector>
```

`app/src/main/res/mipmap-anydpi/ic_launcher.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/ic_launcher_background" />
    <item android:drawable="@drawable/ic_launcher_foreground" />
</layer-list>
```

`app/src/main/res/mipmap-anydpi/ic_launcher_round.xml`：内容同上。
`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```
`mipmap-anydpi-v26/ic_launcher_round.xml`：内容同上。

`app/src/main/res/xml/backup_rules.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <include domain="sharedpref" path="." />
</full-backup-content>
```
`app/src/main/res/xml/data_extraction_rules.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <include domain="sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <include domain="sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```

- [ ] **Step 14: 编译验证**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug --console=plain 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 15: 检查点**

确认 APK 存在：
```bash
ls -la "工作区根目录/GPS蓝牙传输/app/build/outputs/apk/debug/"
```
报告：构建日志关键行 + APK 大小。

---

### Task 2: NMEA 校验和、数据载体、语句开关（TDD）

**Files:**
- Create: `app/src/test/java/com/gpsbt/transfer/nmea/NmeaChecksumTest.java`
- Create: `app/src/main/java/com/gpsbt/transfer/nmea/NmeaChecksum.java`
- Create: `app/src/main/java/com/gpsbt/transfer/nmea/NmeaInput.java`
- Create: `app/src/main/java/com/gpsbt/transfer/nmea/NmeaSentenceType.java`

**Interfaces:**
- Produces: `NmeaChecksum.xor(String body)` → `int`；`NmeaChecksum.toHex(int)` → `String`；`NmeaChecksum.withChecksum(String sentence)` → `String`
- Produces: `NmeaInput`（字段 `valid`/`utcMillis`/`latitude`/`longitude`/`altitudeM`/`speedMps`/`bearingDeg`/`accuracyM`/`satellitesUsed`，内部 `List<Sat> satellites`，方法 `addSat(Sat)`、`satellites()`、`satellitesInView()`）
- Produces: `NmeaInput.Sat(int prn, int elevationDeg, int azimuthDeg, int snr, boolean used)`
- Produces: `NmeaSentenceType.{GGA,RMC,GSA,GSV,VTG}`；`NmeaSentenceType.all()` → `EnumSet<NmeaSentenceType>`

- [ ] **Step 1: 写失败测试 `NmeaChecksumTest`**

```java
package com.gpsbt.transfer.nmea;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NmeaChecksumTest {

    @Test
    public void knownGgaExample_checksumIs47() {
        String body = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,";
        assertEquals(0x47, NmeaChecksum.xor(body));
        assertEquals("47", NmeaChecksum.toHex(0x47));
    }

    @Test
    public void knownRmcExample_checksumIs6A() {
        String body = "GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W";
        assertEquals("6A", NmeaChecksum.toHex(NmeaChecksum.xor(body)));
    }

    @Test
    public void hexIsAlwaysTwoUpperDigits() {
        assertEquals("00", NmeaChecksum.toHex(0));
        assertEquals("0F", NmeaChecksum.toHex(15));
        assertEquals("FF", NmeaChecksum.toHex(0x1FF));
        assertEquals("A0", NmeaChecksum.toHex(-96));
    }

    @Test
    public void withChecksum_appendsStarAndCrLf() {
        String sentence = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,";
        assertEquals(sentence + "*47\r\n", NmeaChecksum.withChecksum(sentence));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --tests 'com.gpsbt.transfer.nmea.NmeaChecksumTest' --console=plain 2>&1 | tail -15
```
Expected: 编译失败 / 测试报 `cannot find symbol NmeaChecksum`。

- [ ] **Step 3: 实现 `NmeaChecksum`**

`app/src/main/java/com/gpsbt/transfer/nmea/NmeaChecksum.java`：
```java
package com.gpsbt.transfer.nmea;

import java.util.Locale;

/** NMEA 0183 校验和：$ 与 * 之间所有字符的 XOR，输出两位大写十六进制。 */
public final class NmeaChecksum {

    private NmeaChecksum() {
    }

    /** 对给定字符串逐字符做 XOR。传 $ 之前的内容（不含前导 $）。 */
    public static int xor(String body) {
        int checksum = 0;
        for (int i = 0; i < body.length(); i++) {
            checksum ^= body.charAt(i) & 0xFF;
        }
        return checksum & 0xFF;
    }

    /** 转两位大写十六进制，不足补零，超出范围截断。 */
    public static String toHex(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    /**
     * @param sentence 完整语句，含前导 $，不含 *CS 与 \r\n
     * @return 追加 *CS 与 \r\n 的完整帧
     */
    public static String withChecksum(String sentence) {
        String body = sentence.startsWith("$") ? sentence.substring(1) : sentence;
        return sentence + "*" + toHex(xor(body)) + "\r\n";
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2
Expected: `BUILD SUCCESSFUL`，4 个用例全通过。

- [ ] **Step 5: 实现 `NmeaInput` 与 `NmeaSentenceType`**

`app/src/main/java/com/gpsbt/transfer/nmea/NmeaInput.java`：
```java
package com.gpsbt.transfer.nmea;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 与 Android 框架无关的定位快照，NMEA 生成器的唯一输入。 */
public final class NmeaInput {

    public boolean valid;
    public long utcMillis;
    public double latitude;
    public double longitude;
    public double altitudeM;
    public double speedMps;
    public double bearingDeg;
    public float accuracyM = Float.NaN;
    public int satellitesUsed;

    private final List<Sat> satellites = new ArrayList<>();

    /** 一颗可见卫星。 */
    public static final class Sat {
        public final int prn;
        public final int elevationDeg;
        public final int azimuthDeg;
        public final int snr;
        public final boolean used;

        public Sat(int prn, int elevationDeg, int azimuthDeg, int snr, boolean used) {
            this.prn = prn;
            this.elevationDeg = elevationDeg;
            this.azimuthDeg = azimuthDeg;
            this.snr = snr;
            this.used = used;
        }
    }

    public void addSat(Sat sat) {
        satellites.add(sat);
    }

    public void clearSats() {
        satellites.clear();
    }

    public List<Sat> satellites() {
        return Collections.unmodifiableList(satellites);
    }

    public int satellitesInView() {
        return satellites.size();
    }
}
```

`app/src/main/java/com/gpsbt/transfer/nmea/NmeaSentenceType.java`：
```java
package com.gpsbt.transfer.nmea;

import java.util.EnumSet;
import java.util.Set;

/** 用户可开关的 NMEA 语句类型。 */
public enum NmeaSentenceType {
    GGA, RMC, GSA, GSV, VTG;

    public static Set<NmeaSentenceType> all() {
        return EnumSet.allOf(NmeaSentenceType.class);
    }
}
```

- [ ] **Step 6: 编译并全量跑测试**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`（本任务还没写使用 `NmeaInput`/`NmeaSentenceType` 的代码，编译通过即可）。

- [ ] **Step 7: 检查点**

报告：测试通过数量、APK 是否重新产出成功。

---

### Task 3: NMEA 五类语句生成器（TDD）

**Files:**
- Create: `app/src/test/java/com/gpsbt/transfer/nmea/NmeaGeneratorTest.java`
- Create: `app/src/main/java/com/gpsbt/transfer/nmea/NmeaGenerator.java`

**Interfaces:**
- Consumes: Task 2 的 `NmeaChecksum`、`NmeaInput`、`NmeaInput.Sat`、`NmeaSentenceType`
- Produces:
  - `NmeaGenerator(float defaultHdop)`
  - `String gga(NmeaInput)`、`String rmc(NmeaInput)`、`String gsa(NmeaInput)`、`List<String> gsv(NmeaInput)`、`String vtg(NmeaInput)`
  - `String buildFrame(NmeaInput in, Set<NmeaSentenceType> enabled)`
  - `static String latitude(double)`、`static String longitude(double)`、`static String utcTime(long)`、`static String utcDate(long)`
  - `static double hdopFor(NmeaInput in, float fallback)`
  - `static final double MS_TO_KNOTS`

- [ ] **Step 1: 写失败测试 `NmeaGeneratorTest`**

```java
package com.gpsbt.transfer.nmea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class NmeaGeneratorTest {

    private static final long UTC = 1700000000000L; // 2023-11-14 22:13:20 UTC

    private final NmeaGenerator generator = new NmeaGenerator(1.0f);

    private NmeaInput sample() {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = UTC;
        in.latitude = 31.230416;
        in.longitude = 121.473701;
        in.altitudeM = 12.5;
        in.speedMps = 5.14444;
        in.bearingDeg = 87.0;
        in.accuracyM = 5.0f;
        in.satellitesUsed = 3;
        in.addSat(new NmeaInput.Sat(3, 45, 120, 42, true));
        in.addSat(new NmeaInput.Sat(14, 12, 300, 28, true));
        in.addSat(new NmeaInput.Sat(22, 70, 40, 0, false));
        in.addSat(new NmeaInput.Sat(19, 80, 200, 35, true));
        in.addSat(new NmeaInput.Sat(8, 5, 10, 0, false));
        return in;
    }

    private String payload(String sentence) {
        String s = sentence.trim();
        int star = s.lastIndexOf('*');
        return s.substring(1, star);
    }

    private String checksumOf(String sentence) {
        String s = sentence.trim();
        return s.substring(s.lastIndexOf('*') + 1);
    }

    @Test
    public void timeAndDateUseUtc() {
        assertEquals("221320.00", NmeaGenerator.utcTime(UTC));
        assertEquals("141123", NmeaGenerator.utcDate(UTC));
    }

    @Test
    public void latitudeConversion() {
        assertEquals("3113.8250,N", NmeaGenerator.latitude(31.230416));
        assertEquals("0000.0000,N", NmeaGenerator.latitude(0.0));
        assertEquals("3351.4100,S", NmeaGenerator.latitude(-33.856833));
    }

    @Test
    public void longitudeConversion() {
        assertEquals("12128.4221,E", NmeaGenerator.longitude(121.473701));
        assertEquals("00000.0000,E", NmeaGenerator.longitude(0.0));
        assertEquals("11812.6050,W", NmeaGenerator.longitude(-118.210083));
    }

    @Test
    public void hdopEstimatedFromAccuracy() {
        NmeaInput in = new NmeaInput();
        in.accuracyM = 5.0f;
        assertEquals(1.0f, NmeaGenerator.hdopFor(in, 1.0f), 0.0001f);
        in.accuracyM = 100.0f;
        assertEquals(9.9f, NmeaGenerator.hdopFor(in, 1.0f), 0.0001f);
        in.accuracyM = 1.0f;
        assertEquals(0.5f, NmeaGenerator.hdopFor(in, 1.0f), 0.0001f);
        in.accuracyM = Float.NaN;
        assertEquals(2.5f, NmeaGenerator.hdopFor(in, 2.5f), 0.0001f);
    }

    @Test
    public void ggaFields() {
        String[] f = payload(generator.gga(sample())).split(",");
        assertEquals("GPGGA", f[0]);
        assertEquals("221320.00", f[1]);
        assertEquals("3113.8250", f[2]);
        assertEquals("N", f[3]);
        assertEquals("12128.4221", f[4]);
        assertEquals("E", f[5]);
        assertEquals("1", f[6]);
        assertEquals("03", f[7]);
        assertEquals("1.0", f[8]);
        assertEquals("12.5", f[9]);
        assertEquals("M", f[10]);
        assertEquals("0.0", f[11]);
        assertEquals("M", f[12]);
    }

    @Test
    public void ggaInvalidFixReportsZeroSatellites() {
        NmeaInput in = sample();
        in.valid = false;
        in.satellitesUsed = 0;
        String[] f = payload(generator.gga(in)).split(",");
        assertEquals("0", f[6]);
        assertEquals("00", f[7]);
        assertEquals("1.0", f[8]);
    }

    @Test
    public void rmcAllFields() {
        String[] f = payload(generator.rmc(sample())).split(",");
        assertEquals("GPRMC", f[0]);
        assertEquals("221320.00", f[1]);
        assertEquals("A", f[2]);
        assertEquals("3113.8250", f[3]);
        assertEquals("N", f[4]);
        assertEquals("12128.4221", f[5]);
        assertEquals("E", f[6]);
        assertEquals("10.0", f[7]);
        assertEquals("87.0", f[8]);
        assertEquals("141123", f[9]);
        assertEquals("A", f[12]);
    }

    @Test
    public void rmcInvalidReportsVoidAndZeroSpeed() {
        NmeaInput in = sample();
        in.valid = false;
        String[] f = payload(generator.rmc(in)).split(",");
        assertEquals("V", f[2]);
        assertEquals("0.0", f[7]);
        assertEquals("0.0", f[8]);
        assertEquals("N", f[12]);
    }

    @Test
    public void gsaListsOnlySatsUsedInFix() {
        String[] f = payload(generator.gsa(sample())).split(",");
        assertEquals("GPGSA", f[0]);
        assertEquals("A", f[1]);
        assertEquals("3", f[2]);
        assertEquals("03", f[3]);
        assertEquals("14", f[4]);
        assertEquals("19", f[5]);
        assertEquals("", f[6]);
        assertEquals("", f[14]);
        assertEquals("1.2", f[15]);
        assertEquals("1.0", f[16]);
        assertEquals("0.8", f[17]);
    }

    @Test
    public void gsvPagesOfFourWhenEightSats() {
        NmeaInput in = sample();
        in.addSat(new NmeaInput.Sat(21, 30, 60, 20, false));
        in.addSat(new NmeaInput.Sat(27, 66, 180, 44, true));
        in.addSat(new NmeaInput.Sat(31, 15, 240, 12, false));
        List<String> sentences = generator.gsv(in);
        assertEquals(2, sentences.size());

        String[] page1 = payload(sentences.get(0)).split(",");
        assertEquals("GPGSV", page1[0]);
        assertEquals("2", page1[1]);
        assertEquals("1", page1[2]);
        assertEquals("8", page1[3]);
        assertEquals("03", page1[4]);
        assertEquals("45", page1[5]);
        assertEquals("120", page1[6]);
        assertEquals("42", page1[7]);

        String[] page2 = payload(sentences.get(1)).split(",");
        assertEquals("2", page2[1]);
        assertEquals("2", page2[2]);
        assertEquals("8", page2[3]);
        assertEquals("21", page2[4]);
    }

    @Test
    public void gsvBlankSnrWhenZeroAndSerializesElevationAzimuth() {
        List<String> sentences = generator.gsv(sample());
        String[] page1 = payload(sentences.get(0)).split(",");
        // 第 3 颗卫星 snr=0 → 空字段
        assertEquals("22", page1[12]);
        assertEquals("70", page1[13]);
        assertEquals("040", page1[14]);
        assertEquals("", page1[15]);
    }

    @Test
    public void gsvEmptyWhenNoSatellites() {
        NmeaInput in = sample();
        in.clearSats();
        assertTrue(generator.gsv(in).isEmpty());
    }

    @Test
    public void vtgFields() {
        String[] f = payload(generator.vtg(sample())).split(",");
        assertEquals("GPVTG", f[0]);
        assertEquals("87.0", f[1]);
        assertEquals("T", f[2]);
        assertEquals("M", f[4]);
        assertEquals("10.0", f[5]);
        assertEquals("N", f[6]);
        assertEquals("18.5", f[7]);
        assertEquals("K", f[8]);
        assertEquals("A", f[9]);
    }

    @Test
    public void buildFrameEmitsInFixedOrderWithChecksumAndCrLf() {
        String frame = generator.buildFrame(sample(), NmeaSentenceType.all());
        assertTrue(frame.endsWith("\r\n"));
        int gga = frame.indexOf("$GPGGA");
        int rmc = frame.indexOf("$GPRMC");
        int gsa = frame.indexOf("$GPGSA");
        int gsv = frame.indexOf("$GPGSV");
        int vtg = frame.indexOf("$GPVTG");
        assertTrue(gga > -1 && rmc > gga && gsa > rmc && gsv > gsa && vtg > gsv);

        for (String line : frame.split("\r\n")) {
            if (line.isEmpty()) {
                continue;
            }
            assertEquals(NmeaChecksum.toHex(NmeaChecksum.xor(payload(line))), checksumOf(line));
        }
    }

    @Test
    public void buildFrameSkipsDisabledSentences() {
        Set<NmeaSentenceType> only = EnumSet.of(NmeaSentenceType.GGA);
        String frame = generator.buildFrame(sample(), only);
        assertTrue(frame.contains("$GPGGA"));
        assertFalse(frame.contains("$GPRMC"));
        assertFalse(frame.contains("$GPGSV"));
        assertEquals(1, frame.split("\r\n").length - 1);
    }

}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --tests 'com.gpsbt.transfer.nmea.NmeaGeneratorTest' --console=plain 2>&1 | tail -15
```
Expected: 失败，找不到 `NmeaGenerator`。

- [ ] **Step 3: 实现 `NmeaGenerator`**

`app/src/main/java/com/gpsbt/transfer/nmea/NmeaGenerator.java`：
```java
package com.gpsbt.transfer.nmea;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** 由定位快照生成标准 NMEA 0183 语句。 */
public final class NmeaGenerator {

    /** 米/秒 → 节。 */
    public static final double MS_TO_KNOTS = 1.9438444924406045;

    private static final String TALKER = "GP";
    private final float defaultHdop;

    public NmeaGenerator(float defaultHdop) {
        this.defaultHdop = defaultHdop;
    }

    public String gga(NmeaInput in) {
        List<String> f = new ArrayList<>();
        f.add(utcTime(in.utcMillis));
        f.add(latitude(in.latitude));
        f.add(longitude(in.longitude));
        f.add(in.valid ? "1" : "0");
        f.add(String.format(Locale.US, "%02d", in.valid ? Math.max(1, in.satellitesUsed) : 0));
        f.add(String.format(Locale.US, "%.1f", hdopFor(in.valid ? in : null, defaultHdop)));
        f.add(String.format(Locale.US, "%.1f", in.valid ? in.altitudeM : 0d));
        f.add("M");
        f.add("0.0");
        f.add("M");
        f.add("");
        f.add("");
        return frame("GGA", f);
    }

    public String rmc(NmeaInput in) {
        List<String> f = new ArrayList<>();
        f.add(utcTime(in.utcMillis));
        f.add(in.valid ? "A" : "V");
        f.add(latitude(in.latitude));
        f.add(longitude(in.longitude));
        f.add(String.format(Locale.US, "%.1f", in.valid ? Math.max(0d, in.speedMps * MS_TO_KNOTS) : 0d));
        f.add(String.format(Locale.US, "%.1f", in.valid ? clamp(0d, 360d, in.bearingDeg) : 0d));
        f.add(utcDate(in.utcMillis));
        f.add("");
        f.add("");
        f.add(in.valid ? "A" : "N");
        return frame("RMC", f);
    }

    public String gsa(NmeaInput in) {
        List<Integer> used = new ArrayList<>();
        for (NmeaInput.Sat sat : in.satellites()) {
            if (sat.used && used.size() < 12) {
                used.add(sat.prn);
            }
        }
        if (used.isEmpty() && in.satellitesUsed > 0 && in.satellitesInView() > 0) {
            for (NmeaInput.Sat sat : in.satellites()) {
                if (used.size() < Math.min(12, in.satellitesUsed)) {
                    used.add(sat.prn);
                }
            }
        }
        double hdop = hdopFor(in.valid ? in : null, defaultHdop);
        List<String> f = new ArrayList<>();
        f.add("A");
        f.add(in.valid ? "3" : "1");
        for (int i = 0; i < 12; i++) {
            f.add(i < used.size() ? String.format(Locale.US, "%02d", used.get(i)) : "");
        }
        f.add(String.format(Locale.US, "%.1f", hdop * 1.2));
        f.add(String.format(Locale.US, "%.1f", hdop));
        f.add(String.format(Locale.US, "%.1f", hdop * 0.8));
        return frame("GSA", f);
    }

    public List<String> gsv(NmeaInput in) {
        int count = in.satellitesInView();
        if (count == 0) {
            return Collections.emptyList();
        }
        int total = (count + 3) / 4;
        List<String> out = new ArrayList<>();
        for (int page = 0; page < total; page++) {
            List<String> f = new ArrayList<>();
            f.add(String.valueOf(total));
            f.add(String.valueOf(page + 1));
            f.add(String.valueOf(count));
            for (int k = 0; k < 4; k++) {
                int index = page * 4 + k;
                if (index < count) {
                    NmeaInput.Sat sat = in.satellites().get(index);
                    f.add(String.format(Locale.US, "%02d", sat.prn));
                    f.add(String.format(Locale.US, "%02d", (int) clamp(0d, 90d, sat.elevationDeg)));
                    f.add(String.format(Locale.US, "%03d", (int) normalize360(sat.azimuthDeg)));
                    f.add(sat.snr > 0 ? String.format(Locale.US, "%02d", Math.min(99, sat.snr)) : "");
                } else {
                    f.add("");
                    f.add("");
                    f.add("");
                    f.add("");
                }
            }
            out.add(frame("GSV", f));
        }
        return out;
    }

    public String vtg(NmeaInput in) {
        double knots = in.valid ? Math.max(0d, in.speedMps * MS_TO_KNOTS) : 0d;
        double kmh = in.valid ? Math.max(0d, in.speedMps * 3.6) : 0d;
        double track = in.valid ? clamp(0d, 360d, in.bearingDeg) : 0d;
        List<String> f = new ArrayList<>();
        f.add(String.format(Locale.US, "%.1f", track));
        f.add("T");
        f.add("");
        f.add("M");
        f.add(String.format(Locale.US, "%.1f", knots));
        f.add("N");
        f.add(String.format(Locale.US, "%.1f", kmh));
        f.add("K");
        f.add("A");
        return frame("VTG", f);
    }

    /** 按固定顺序拼装一帧（各语句均带 *CS 与 \r\n）。 */
    public String buildFrame(NmeaInput in, Set<NmeaSentenceType> enabled) {
        StringBuilder sb = new StringBuilder();
        if (enabled.contains(NmeaSentenceType.GGA)) {
            sb.append(gga(in));
        }
        if (enabled.contains(NmeaSentenceType.RMC)) {
            sb.append(rmc(in));
        }
        if (enabled.contains(NmeaSentenceType.GSA)) {
            sb.append(gsa(in));
        }
        if (enabled.contains(NmeaSentenceType.GSV)) {
            for (String sentence : gsv(in)) {
                sb.append(sentence);
            }
        }
        if (enabled.contains(NmeaSentenceType.VTG)) {
            sb.append(vtg(in));
        }
        return sb.toString();
    }

    /** 纬度转 ddmm.mmmm,N/S。 */
    public static String latitude(double lat) {
        double abs = Math.abs(lat);
        int degrees = (int) abs;
        double minutes = (abs - degrees) * 60.0;
        if (minutes >= 59.99995) {
            minutes -= 60.0;
            degrees += 1;
        }
        return String.format(Locale.US, "%02d%07.4f,%s", degrees, Math.max(0d, minutes), lat >= 0 ? "N" : "S");
    }

    /** 经度转 dddmm.mmmm,E/W。 */
    public static String longitude(double lon) {
        double abs = Math.abs(lon);
        int degrees = (int) abs;
        double minutes = (abs - degrees) * 60.0;
        if (minutes >= 59.99995) {
            minutes -= 60.0;
            degrees += 1;
        }
        return String.format(Locale.US, "%03d%07.4f,%s", degrees, Math.max(0d, minutes), lon >= 0 ? "E" : "W");
    }

    /** UTC 毫秒 → HHmmss.SS。 */
    public static String utcTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("HHmmss.SS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    /** UTC 毫秒 → ddMMyy。 */
    public static String utcDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("ddMMyy", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    /** 由定位精度估算 HDOP；无精度信息时返回 fallback。 */
    public static double hdopFor(NmeaInput in, float fallback) {
        if (in == null || Float.isNaN(in.accuracyM) || in.accuracyM <= 0f) {
            return fallback;
        }
        return clamp(0.5d, 9.9d, in.accuracyM / 5.0);
    }

    private static String frame(String type, List<String> values) {
        StringBuilder sb = new StringBuilder("$").append(TALKER).append(type);
        for (String value : values) {
            sb.append(',').append(value);
        }
        return NmeaChecksum.withChecksum(sb.toString());
    }

    private static double clamp(double min, double max, double value) {
        return Math.min(max, Math.max(min, value));
    }

    private static double normalize360(double value) {
        double v = value % 360.0;
        return v < 0 ? v + 360.0 : v;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --tests 'com.gpsbt.transfer.nmea.*' --console=plain 2>&1 | tail -25
```
Expected: `BUILD SUCCESSFUL`。若某个断言失败，**先修正实现，再修正测试中明显写错的期望**，禁止为了让测试通过而删除断言。

- [ ] **Step 5: 全量验证**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: 检查点**

报告：通过的用例数、是否修正过断言（若修正需说明原因）。

---

### Task 4: 偏好设置、格式化、日志总线

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/util/Prefs.java`
- Create: `app/src/main/java/com/gpsbt/transfer/util/Formatters.java`
- Create: `app/src/main/java/com/gpsbt/transfer/state/LogBus.java`
- Create: `app/src/test/java/com/gpsbt/transfer/util/FormattersTest.java`
- Create: `app/src/test/java/com/gpsbt/transfer/state/LogBusTest.java`

**Interfaces:**
- Produces:
  - `Prefs.isReady()`、`Prefs.getRateHz()` / `setRateHz(int)`、`getServiceName()` / `setServiceName(String)`、`getUuid()` / `setUuid(String)`、`isAllowNetwork()` / `setAllowNetwork(boolean)`、`getStaleMillis()` / `setStaleMillis(long)`、`getDefaultHdop()` / `setDefaultHdop(float)`、`getSentences()` / `setSentences(Set<NmeaSentenceType>)`、`getMode()` / `setMode(ConnectionMode)`、`getLastDeviceMac()` / `setLastDeviceMac(String)`
  - `Formatters.latitude(double)`、`longitude(double)`、`speedKmh(double)`、`bearing(double)`、`altitude(double)`、`duration(long)`、`clockOfDay(long)`、`accuracy(float)`、`zero → "--"`
  - `LogBus.get()`、`log(Level, String)`、`entries()`、`clear()`、`addListener(Listener)`、`removeListener(Listener)`；`LogBus.Level.{INFO,WARN,ERROR,DATA}`；`LogBus.Entry{long time; Level level; String message}`

- [ ] **Step 1: 写失败测试 `FormattersTest`**

`app/src/test/java/com/gpsbt/transfer/util/FormattersTest.java`：
```java
package com.gpsbt.transfer.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FormattersTest {

    @Test
    public void latitudeHasHemisphereAndSymbol() {
        assertEquals("31.230416° N", Formatters.latitude(31.230416));
        assertEquals("33.856833° S", Formatters.latitude(-33.856833));
    }

    @Test
    public void longitudeHasHemisphereAndSymbol() {
        assertEquals("121.473701° E", Formatters.longitude(121.473701));
        assertEquals("118.210083° W", Formatters.longitude(-118.210083));
    }

    @Test
    public void speedConvertedToKmh() {
        assertEquals("18.5 km/h", Formatters.speedKmh(5.14444));
        assertEquals("0.0 km/h", Formatters.speedKmh(0));
        assertEquals("NaN", Formatters.speedKmh(Double.NaN));
    }

    @Test
    public void bearingAltitudeAndAccuracy() {
        assertEquals("87.0°", Formatters.bearing(87.0));
        assertEquals("12.5 m", Formatters.altitude(12.5));
        assertEquals("±3.0 m", Formatters.accuracy(3.0f));
        assertEquals("--", Formatters.accuracy(Float.NaN));
    }

    @Test
    public void durationFormatsAsClock() {
        assertEquals("00:00:00", Formatters.duration(0));
        assertEquals("00:01:23", Formatters.duration(83000));
        assertEquals("01:02:03", Formatters.duration(3723000));
        assertEquals("--", Formatters.duration(-1));
    }
}
```

- [ ] **Step 2: 写失败测试 `LogBusTest`**

`app/src/test/java/com/gpsbt/transfer/state/LogBusTest.java`：
```java
package com.gpsbt.transfer.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.state.LogBus.Entry;
import com.gpsbt.transfer.state.LogBus.Level;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LogBusTest {

    private LogBus bus;

    @Before
    public void setUp() {
        bus = LogBus.get();
        bus.clear();
    }

    @After
    public void tearDown() {
        bus.clear();
    }

    @Test
    public void listenerReceivesNewEntries() {
        final AtomicInteger calls = new AtomicInteger();
        LogBus.Listener listener = new LogBus.Listener() {
            @Override
            public void onLog(Entry entry) {
                calls.incrementAndGet();
            }
        };
        bus.addListener(listener);
        bus.log(Level.INFO, "测试");
        assertEquals(1, calls.get());
        bus.removeListener(listener);
        bus.log(Level.INFO, "第二次");
        assertEquals(1, calls.get());
    }

    @Test
    public void entriesKeepMostRecentLastAndTimestamped() {
        bus.log(Level.INFO, "第一条");
        bus.log(Level.ERROR, "第二条");
        List<Entry> entries = bus.entries();
        assertEquals(2, entries.size());
        assertEquals("第一条", entries.get(0).message);
        assertEquals(Level.ERROR, entries.get(1).level);
        assertTrue(entries.get(0).time > 0);
    }

    @Test
    public void bufferIsCapped() {
        for (int i = 0; i < LogBus.MAX_ENTRIES + 50; i++) {
            bus.log(Level.INFO, "第" + i + "条");
        }
        List<Entry> entries = bus.entries();
        assertEquals(LogBus.MAX_ENTRIES, entries.size());
        assertEquals("第50条", entries.get(0).message);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --tests 'com.gpsbt.transfer.util.FormattersTest' --tests 'com.gpsbt.transfer.state.LogBusTest' --console=plain 2>&1 | tail -15
```
Expected：失败，符号不存在。

- [ ] **Step 4: 实现 `Formatters`**

`app/src/main/java/com/gpsbt/transfer/util/Formatters.java`：
```java
package com.gpsbt.transfer.util;

import java.util.Locale;

/** 中文界面格式化，所有输出均为面向用户的可读文本。 */
public final class Formatters {

    private Formatters() {
    }

    public static String latitude(double value) {
        return String.format(Locale.US, "%.6f° %s", Math.abs(value), value >= 0 ? "N" : "S");
    }

    public static String longitude(double value) {
        return String.format(Locale.US, "%.6f° %s", Math.abs(value), value >= 0 ? "E" : "W");
    }

    public static String speedKmh(double metersPerSecond) {
        if (Double.isNaN(metersPerSecond)) {
            return "NaN";
        }
        return String.format(Locale.US, "%.1f km/h", Math.max(0d, metersPerSecond) * 3.6);
    }

    public static String bearing(double degrees) {
        if (Double.isNaN(degrees)) {
            return "--";
        }
        return String.format(Locale.US, "%.1f°", degrees);
    }

    public static String altitude(double meters) {
        if (Double.isNaN(meters)) {
            return "--";
        }
        return String.format(Locale.US, "%.1f m", meters);
    }

    public static String accuracy(float meters) {
        if (Float.isNaN(meters) || meters <= 0f) {
            return "--";
        }
        return String.format(Locale.US, "±%.1f m", meters);
    }

    /** 时长格式化为 HH:mm:ss，负数返回 --。 */
    public static String duration(long millis) {
        if (millis < 0) {
            return "--";
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    /** 时间戳当天时刻 HH:mm:ss。 */
    public static String clockOfDay(long millis) {
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        format.setTimeZone(java.util.TimeZone.getDefault());
        return format.format(new java.util.Date(millis));
    }
}
```

- [ ] **Step 5: 实现 `LogBus`**

`app/src/main/java/com/gpsbt/transfer/state/LogBus.java`：
```java
package com.gpsbt.transfer.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.ListIterator;

/** 内存日志总线：后台线程写，UI 线程读。 */
public final class LogBus {

    public enum Level { INFO, WARN, ERROR, DATA }

    public static final class Entry {
        public final long time;
        public final Level level;
        public final String message;

        Entry(long time, Level level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }
    }

    public interface Listener {
        void onLog(Entry entry);
    }

    public static final int MAX_ENTRIES = 400;

    private static volatile LogBus instance;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<Listener> listeners = new ArrayList<>();

    private LogBus() {
    }

    public static LogBus get() {
        LogBus local = instance;
        if (local == null) {
            synchronized (LogBus.class) {
                local = instance;
                if (local == null) {
                    local = new LogBus();
                    instance = local;
                }
            }
        }
        return local;
    }

    public synchronized void log(Level level, String message) {
        Entry entry = new Entry(System.currentTimeMillis(), level, message == null ? "" : message);
        if (entries.size() >= MAX_ENTRIES) {
            entries.pollFirst();
        }
        entries.addLast(entry);
        for (Listener listener : snapshotListeners()) {
            listener.onLog(entry);
        }
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public void addListener(Listener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private List<Listener> snapshotListeners() {
        synchronized (listeners) {
            return new ArrayList<>(listeners);
        }
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --tests 'com.gpsbt.transfer.util.FormattersTest' --tests 'com.gpsbt.transfer.state.LogBusTest' --console=plain 2>&1 | tail -20
```
Expected：`BUILD SUCCESSFUL`。

- [ ] **Step 7: 实现 `Prefs`（依赖 Android，不进 JVM 测试）**

`app/src/main/java/com/gpsbt/transfer/util/Prefs.java`：
```java
package com.gpsbt.transfer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 设置持久化。 */
public final class Prefs {

    public static final String DEFAULT_SERVICE_NAME = "GPS蓝牙传输";
    public static final String DEFAULT_UUID = "00001101-0000-1000-8000-00805F9B34FB";
    public static final int DEFAULT_RATE_HZ = 1;
    public static final int MAX_RATE_HZ = 10;
    public static final int MIN_RATE_HZ = 1;
    public static final long DEFAULT_STALE_MILLIS = 5000L;
    public static final float DEFAULT_HDOP = 1.0f;

    private static final String NAME = "gps_bt_prefs";
    private static final String KEY_RATE = "rate_hz";
    private static final String KEY_SERVICE_NAME = "service_name";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_ALLOW_NETWORK = "allow_network";
    private static final String KEY_STALE = "stale_millis";
    private static final String KEY_HDOP = "default_hdop";
    private static final String KEY_SENTENCES = "sentences";
    private static final String KEY_MODE = "mode";
    private static final String KEY_LAST_MAC = "last_device_mac";

    private static volatile Prefs instance;
    private final SharedPreferences prefs;

    private Prefs(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Prefs get(Context context) {
        Prefs local = instance;
        if (local == null) {
            synchronized (Prefs.class) {
                local = instance;
                if (local == null) {
                    local = new Prefs(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static boolean isReady() {
        return instance != null;
    }

    public int getRateHz() {
        return clampInt(MIN_RATE_HZ, MAX_RATE_HZ, prefs.getInt(KEY_RATE, DEFAULT_RATE_HZ));
    }

    public void setRateHz(int value) {
        prefs.edit().putInt(KEY_RATE, clampInt(MIN_RATE_HZ, MAX_RATE_HZ, value)).apply();
    }

    public String getServiceName() {
        String name = prefs.getString(KEY_SERVICE_NAME, DEFAULT_SERVICE_NAME);
        return name == null || name.trim().isEmpty() ? DEFAULT_SERVICE_NAME : name.trim();
    }

    public void setServiceName(String value) {
        prefs.edit().putString(KEY_SERVICE_NAME, value == null ? DEFAULT_SERVICE_NAME : value).apply();
    }

    public String getUuid() {
        String uuid = prefs.getString(KEY_UUID, DEFAULT_UUID);
        if (!isValidUuid(uuid)) {
            return DEFAULT_UUID;
        }
        return uuid;
    }

    public void setUuid(String value) {
        prefs.edit().putString(KEY_UUID, isValidUuid(value) ? value : DEFAULT_UUID).apply();
    }

    public boolean isAllowNetwork() {
        return prefs.getBoolean(KEY_ALLOW_NETWORK, false);
    }

    public void setAllowNetwork(boolean value) {
        prefs.edit().putBoolean(KEY_ALLOW_NETWORK, value).apply();
    }

    public long getStaleMillis() {
        long value = prefs.getLong(KEY_STALE, DEFAULT_STALE_MILLIS);
        return Math.max(1000L, Math.min(60000L, value));
    }

    public void setStaleMillis(long value) {
        prefs.edit().putLong(KEY_STALE, Math.max(1000L, Math.min(60000L, value))).apply();
    }

    public float getDefaultHdop() {
        float value = prefs.getFloat(KEY_HDOP, DEFAULT_HDOP);
        return Math.max(0.5f, Math.min(9.9f, value));
    }

    public void setDefaultHdop(float value) {
        prefs.edit().putFloat(KEY_HDOP, Math.max(0.5f, Math.min(9.9f, value))).apply();
    }

    public Set<NmeaSentenceType> getSentences() {
        Set<NmeaSentenceType> result = EnumSet.noneOf(NmeaSentenceType.class);
        for (String token : prefs.getStringSet(KEY_SENTENCES, defaultSentenceTokens())) {
            try {
                result.add(NmeaSentenceType.valueOf(token));
            } catch (IllegalArgumentException ignored) {
                // 忽略未知值
            }
        }
        return result.isEmpty() ? NmeaSentenceType.all() : result;
    }

    public void setSentences(Set<NmeaSentenceType> sentences) {
        Set<String> tokens = new HashSet<>();
        for (NmeaSentenceType type : sentences) {
            tokens.add(type.name());
        }
        prefs.edit().putStringSet(KEY_SENTENCES, tokens).apply();
    }

    public ConnectionMode getMode() {
        String name = prefs.getString(KEY_MODE, ConnectionMode.SERVER.name());
        try {
            return ConnectionMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ConnectionMode.SERVER;
        }
    }

    public void setMode(ConnectionMode mode) {
        prefs.edit().putString(KEY_MODE, (mode == null ? ConnectionMode.SERVER : mode).name()).apply();
    }

    public String getLastDeviceMac() {
        return prefs.getString(KEY_LAST_MAC, null);
    }

    public void setLastDeviceMac(String mac) {
        prefs.edit().putString(KEY_LAST_MAC, mac).apply();
    }

    private static Set<String> defaultSentenceTokens() {
        Set<String> tokens = new HashSet<>();
        for (NmeaSentenceType type : NmeaSentenceType.values()) {
            tokens.add(type.name());
        }
        return tokens;
    }

    private static boolean isValidUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int clampInt(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }
}
```

`Prefs` 引用 `com.gpsbt.transfer.bt.ConnectionMode`，该文件在 Task 8 创建；因此本任务执行时**先只创建 `Prefs.java`，不参与 `assembleDebug` 之外的编译**。为避免编译失败，Task 8 会创建 `ConnectionMode`。本任务结束时运行：

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --console=plain 2>&1 | tail -10
```
Expected：`BUILD SUCCESSFUL`（单元测试任务不会编译 `Prefs` 的 Android 依赖错误；若 `assembleDebug` 因 `ConnectionMode` 缺失失败，属预期，Task 8 补齐后恢复）。

- [ ] **Step 8: 检查点**

报告：两个测试类的通过数。

---

### Task 5: 全局状态中心（TDD）

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/state/TransmitStats.java`
- Create: `app/src/test/java/com/gpsbt/transfer/state/TransmitterStateTest.java`
- Create: `app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java`
- Create: `app/src/main/java/com/gpsbt/transfer/App.java`
- Modify: `app/src/main/AndroidManifest.xml`（`<application android:name=".App">`）

**Interfaces:**
- Consumes: `ConnectionMode`（Task 8 才创建，本任务先在 `TransmitterState` 内**内联定义** `ConnectionMode` 枚举于 `bt` 包会造成循环依赖风险，因此本任务把枚举直接放在 `TransmitterState` 内作为 `Mode`，Task 8 的 `ConnectionMode` 与它保持 `valueOf` 兼容）
  - **最终约定**：`TransmitterState.Mode { SERVER, CLIENT }`，`ConnectionMode` 在 Task 8 中定义为同名同序枚举，`Prefs` 返回 `ConnectionMode`，`TransmitterState.setMode(ConnectionMode)` 直接接收。
- Produces:
  - `TransmitterState.get()`、`setExecutor(CallbackExecutor)`、`addListener(Listener)`、`removeListener(Listener)`
  - `setPhase(Phase)`、`setBt(Bt)`、`setMode(ConnectionMode)`、`setConnectedDevice(Device)`、`setPendingDevice(Device)`、`setConfig(String serviceName, String uuid, int rateHz)`、`setFix(Fix)`、`setStats(TransmitStats)`、`setStatusText(String)`
  - getter 同名小写：`phase()`、`bt()`、`mode()`、`connectedDevice()`、`pendingDevice()`、`serviceName()`、`uuid()`、`rateHz()`、`fix()`、`stats()`、`statusText()`
  - `TransmitterState.Mode`、`TransmitterState.Bt`、`TransmitterState.Device(String name, String mac)`、`TransmitterState.Fix`、`TransmitterState.CallbackExecutor`、`TransmitterState.DirectExecutor`、`TransmitterState.MainThreadExecutor`
  - `TransmitStats.reset()`，字段 `sentences`/`bytes`/`lastFrameAt`/`rateHz`/`connectedAt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/gpsbt/transfer/state/TransmitterStateTest.java`：
```java
package com.gpsbt.transfer.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.gpsbt.transfer.state.TransmitterState.Bt;
import com.gpsbt.transfer.state.TransmitterState.Device;
import com.gpsbt.transfer.state.TransmitterState.Fix;
import com.gpsbt.transfer.state.TransmitterState.Mode;
import com.gpsbt.transfer.state.TransmitterState.Phase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TransmitterStateTest {

    private TransmitterState state;

    @Before
    public void setUp() {
        state = new TransmitterState(new TransmitterState.DirectExecutor());
    }

    @After
    public void tearDown() {
        state = null;
    }

    @Test
    public void listenerIsNotifiedForEveryMutation() {
        final AtomicInteger calls = new AtomicInteger();
        final List<TransmitterState> seen = new ArrayList<>();
        TransmitterState.Listener listener = new TransmitterState.Listener() {
            @Override
            public void onStateChanged(TransmitterState changed) {
                calls.incrementAndGet();
                seen.add(changed);
            }
        };
        state.addListener(listener);
        state.setPhase(Phase.RUNNING);
        state.setBt(Bt.WAITING);
        state.setMode(Mode.SERVER);
        state.setConfig("GPS蓝牙传输", "00001101-0000-1000-8000-00805F9B34FB", 2);
        state.setStatusText("等待连接");

        assertEquals(5, calls.get());
        assertEquals(Phase.RUNNING, state.phase());
        assertEquals(Bt.WAITING, state.bt());
        assertEquals(Mode.SERVER, state.mode());
        assertEquals(2, state.rateHz());
        assertEquals("GPS蓝牙传输", state.serviceName());
        assertEquals("等待连接", state.statusText());
        assertSame(state, seen.get(0));
    }

    @Test
    public void removedListenerStopsReceiving() {
        final AtomicInteger calls = new AtomicInteger();
        TransmitterState.Listener listener = new TransmitterState.Listener() {
            @Override
            public void onStateChanged(TransmitterState changed) {
                calls.incrementAndGet();
            }
        };
        state.addListener(listener);
        state.removeListener(listener);
        state.setPhase(Phase.RUNNING);
        assertEquals(0, calls.get());
    }

    @Test
    public void devicesAndFixAreExposed() {
        Device connected = new Device("车机", "AA:BB:CC:DD:EE:FF");
        state.setConnectedDevice(connected);
        assertEquals("车机", state.connectedDevice().name);

        Fix fix = new Fix();
        fix.valid = true;
        fix.latitude = 31.1;
        state.setFix(fix);
        assertSame(fix, state.fix());

        assertNull(state.pendingDevice());
    }

    @Test
    public void defaultStateIsIdle() {
        assertEquals(Phase.IDLE, state.phase());
        assertEquals(Bt.IDLE, state.bt());
        assertEquals(Mode.SERVER, state.mode());
        assertEquals(1, state.rateHz());
        assertNull(state.connectedDevice());
        assertNull(state.fix());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --tests 'com.gpsbt.transfer.state.TransmitterStateTest' --console=plain 2>&1 | tail -12
```
Expected：失败（类不存在）。

- [ ] **Step 3: 实现 `TransmitStats`**

`app/src/main/java/com/gpsbt/transfer/state/TransmitStats.java`：
```java
package com.gpsbt.transfer.state;

/** 发送统计快照。字段均可由后台线程写、UI 线程读。 */
public final class TransmitStats {

    public volatile long sentences;
    public volatile long frames;
    public volatile long bytes;
    public volatile long lastFrameAt;
    public volatile long startedAt;
    public volatile double rateHz;

    public void reset() {
        sentences = 0;
        frames = 0;
        bytes = 0;
        lastFrameAt = 0;
        startedAt = 0;
        rateHz = 0;
    }
}
```

- [ ] **Step 4: 实现 `TransmitterState`**

`app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java`：
```java
package com.gpsbt.transfer.state;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.bt.ConnectionMode;

import java.util.ArrayList;
import java.util.List;

/** 全局 UI 状态中心：后台写、UI 读，监听回调切到主线程。 */
public final class TransmitterState {

    public enum Phase { IDLE, RUNNING }

    /** 连接状态。注意枚举顺序与 `bt.BtState` 保持一致。 */
    public enum Bt { IDLE, WAITING, CONNECTING, CONNECTED, FAILED, DISCONNECTED }

    /** 连接方式；顺序必须与 `bt.ConnectionMode` 一致。 */
    public enum Mode { SERVER, CLIENT }

    public static final class Device {
        public final String name;
        public final String mac;

        public Device(String name, String mac) {
            this.name = name;
            this.mac = mac;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Device)) {
                return false;
            }
            Device that = (Device) other;
            return mac != null && mac.equals(that.mac);
        }

        @Override
        public int hashCode() {
            return mac == null ? 0 : mac.hashCode();
        }
    }

    /** 定位快照（UI 展示用，字段可被后台线程更新）。 */
    public static final class Fix {
        public volatile boolean valid;
        public volatile int satellitesInView;
        public volatile int satellitesUsed;
        public volatile double latitude;
        public volatile double longitude;
        public volatile double altitudeM;
        public volatile double speedMps;
        public volatile double bearingDeg;
        public volatile float accuracyM = Float.NaN;
        public volatile long fixAt;
    }

    public interface Listener {
        void onStateChanged(TransmitterState state);
    }

    public interface CallbackExecutor {
        void execute(Runnable command);
    }

    /** 同步执行，单元测初始。 */
    public static final class DirectExecutor implements CallbackExecutor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    /** 切到主线程。 */
    public static final class MainThreadExecutor implements CallbackExecutor {
        private final Handler handler;

        public MainThreadExecutor(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void execute(Runnable command) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                command.run();
            } else {
                handler.post(command);
            }
        }
    }

    private static volatile TransmitterState instance;

    private final List<Listener> listeners = new ArrayList<>();
    private volatile CallbackExecutor executor = new DirectExecutor();

    private volatile Phase phase = Phase.IDLE;
    private volatile Bt bt = Bt.IDLE;
    private volatile Mode mode = Mode.SERVER;
    private volatile Device connectedDevice;
    private volatile Device pendingDevice;
    private volatile String serviceName = "";
    private volatile String uuid = "";
    private volatile int rateHz = 1;
    private volatile String statusText = "";
    private volatile Fix fix;
    private volatile TransmitStats stats = new TransmitStats();

    private TransmitterState() {
    }

    public static TransmitterState get() {
        TransmitterState local = instance;
        if (local == null) {
            synchronized (TransmitterState.class) {
                local = instance;
                if (local == null) {
                    local = new TransmitterState();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 仅供单元测试注入。 */
    TransmitterState(CallbackExecutor injected) {
        this.executor = injected;
    }

    public void setExecutor(CallbackExecutor executor) {
        this.executor = executor == null ? new DirectExecutor() : executor;
    }

    public void addListener(Listener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public Phase phase() {
        return phase;
    }

    public Bt bt() {
        return bt;
    }

    public Mode mode() {
        return mode;
    }

    public Device connectedDevice() {
        return connectedDevice;
    }

    public Device pendingDevice() {
        return pendingDevice;
    }

    public String serviceName() {
        return serviceName;
    }

    public String uuid() {
        return uuid;
    }

    public int rateHz() {
        return rateHz;
    }

    public String statusText() {
        return statusText;
    }

    public Fix fix() {
        return fix;
    }

    public TransmitStats stats() {
        return stats;
    }

    public void setPhase(Phase value) {
        phase = value;
        notifyListeners();
    }

    public void setBt(Bt value) {
        bt = value;
        notifyListeners();
    }

    public void setMode(Mode value) {
        mode = value;
        notifyListeners();
    }

    public void setMode(ConnectionMode value) {
        setMode(value == ConnectionMode.CLIENT ? Mode.CLIENT : Mode.SERVER);
    }

    public void setConnectedDevice(Device value) {
        connectedDevice = value;
        notifyListeners();
    }

    public void setPendingDevice(Device value) {
        pendingDevice = value;
        notifyListeners();
    }

    public void setConfig(String serviceName, String uuid, int rateHz) {
        this.serviceName = serviceName == null ? "" : serviceName;
        this.uuid = uuid == null ? "" : uuid;
        this.rateHz = rateHz;
        notifyListeners();
    }

    public void setStatusText(String value) {
        statusText = value == null ? "" : value;
        notifyListeners();
    }

    public void setFix(Fix value) {
        fix = value;
        notifyListeners();
    }

    public void setStats(TransmitStats value) {
        stats = value == null ? new TransmitStats() : value;
        notifyListeners();
    }

    private void notifyListeners() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<Listener> snapshot;
                synchronized (listeners) {
                    snapshot = new ArrayList<>(listeners);
                }
                for (Listener listener : snapshot) {
                    listener.onStateChanged(TransmitterState.this);
                }
            }
        });
    }
}
```

- [ ] **Step 5: 实现 `App` 并在清单注册**

`app/src/main/java/com/gpsbt/transfer/App.java`：
```java
package com.gpsbt.transfer;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.state.TransmitterState;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        TransmitterState.get()
                .setExecutor(new TransmitterState.MainThreadExecutor(new Handler(Looper.getMainLooper())));
    }
}
```

Manifest `<application>` 增加 `android:name=".App"`。

- [ ] **Step 6: 运行测试确认通过**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --console=plain 2>&1 | tail -15
```
Expected：`BUILD SUCCESSFUL`（此步骤 `ConnectionMode` 尚不存在，`assembleDebug` 会失败，属预期；Task 8 补齐后恢复）。

- [ ] **Step 7: 检查点**

报告：用例通过数；确认 `Bt`/`Mode` 枚举顺序与后续 `BtState`/`ConnectionMode` 一致。

---

### Task 6: 发送引擎（TDD）

**Files:**
- Create: `app/src/test/java/com/gpsbt/transfer/engine/TransmitEngineTest.java`
- Create: `app/src/main/java/com/gpsbt/transfer/engine/TransmitEngine.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/nmea/NmeaInput.java`（新增 `copy()`）

**Interfaces:**
- Consumes: `NmeaGenerator`、`NmeaSentenceType`
- Produces:
  - `TransmitEngine(Sink sink, NmeaGenerator generator, Set<NmeaSentenceType> enabled, long staleMillis)`
  - `TransmitEngine.Sink#write(byte[] data, int length)`
  - `void publish(NmeaInput input, long nowMillis)`、`List<String> tick(long nowMillis)`、`long sentences()`、`long frames()`、`long bytes()`、`double rateHz(long nowMillis)`、`void resetCounters()`、`boolean hasPending()`
  - `NmeaInput.copy()`

- [ ] **Step 1: 给 `NmeaInput` 增加 `copy()`**

在 `NmeaInput.java` 末尾（类闭合前）追加：
```java
    /** 返回副本（含卫星列表），`utcMillis` 原样复制。 */
    public NmeaInput copy() {
        NmeaInput copy = new NmeaInput();
        copy.valid = valid;
        copy.utcMillis = utcMillis;
        copy.latitude = latitude;
        copy.longitude = longitude;
        copy.altitudeM = altitudeM;
        copy.speedMps = speedMps;
        copy.bearingDeg = bearingDeg;
        copy.accuracyM = accuracyM;
        copy.satellitesUsed = satellitesUsed;
        for (Sat sat : satellites) {
            copy.addSat(sat);
        }
        return copy;
    }
```

- [ ] **Step 2: 写失败测试**

`app/src/test/java/com/gpsbt/transfer/engine/TransmitEngineTest.java`：
```java
package com.gpsbt.transfer.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TransmitEngineTest {

    private final List<byte[]> written = new ArrayList<>();
    private TransmitEngine engine;

    private TransmitEngine.Sink sink = new TransmitEngine.Sink() {
        @Override
        public void write(byte[] data, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(data, 0, copy, 0, length);
            written.add(copy);
        }
    };

    private final NmeaGenerator generator = new NmeaGenerator(1.0f);

    @Before
    public void setUp() {
        written.clear();
        engine = new TransmitEngine(sink, generator, NmeaSentenceType.all(), 5000L);
    }

    private NmeaInput sample(long utcMillis) {
        NmeaInput in = new NmeaInput();
        in.valid = true;
        in.utcMillis = utcMillis;
        in.latitude = 31.230416;
        in.longitude = 121.473701;
        in.altitudeM = 12.5;
        in.speedMps = 5.14444;
        in.bearingDeg = 87.0;
        in.accuracyM = 5.0f;
        in.satellitesUsed = 1;
        in.addSat(new NmeaInput.Sat(3, 45, 120, 42, true));
        return in;
    }

    @Test
    public void tickWithoutInputWritesNothing() {
        assertTrue(engine.tick(1000L).isEmpty());
        assertTrue(written.isEmpty());
        assertFalse(engine.hasPending());
    }

    @Test
    public void tickWritesFrameOnce() {
        engine.publish(sample(1000L), 1000L);
        List<String> sentences = engine.tick(2000L);
        // GGA + RMC + GSA + GSV(1 页) + VTG = 5 条
        assertEquals(5, sentences.size());
        assertEquals(1, written.size());
        assertEquals(5, engine.sentences());
        assertTrue(engine.bytes() > 0);
        assertTrue(engine.hasPending());
    }

    @Test
    public void tickRefreshesUtcTimeFromClock() {
        engine.publish(sample(1000L), 1700000000000L);
        List<String> sentences = engine.tick(1700000001000L);
        assertTrue(sentences.get(0).startsWith("$GPGGA,221320.00,"));
    }

    @Test
    public void repeatedTicksWriteEveryCycle() {
        engine.publish(sample(1000L), 1000L);
        for (int i = 1; i <= 3; i++) {
            engine.tick(1000L + i * 1000L);
        }
        assertEquals(3, written.size());
        assertEquals(15, engine.sentences());
    }

    @Test
    public void staleInputSuppressesOutput() {
        engine.publish(sample(1000L), 1000L);
        assertTrue(engine.tick(2000L).size() > 0);
        written.clear();
        assertTrue(engine.tick(7000L).isEmpty());
        assertTrue(written.isEmpty());
        assertEquals(5, engine.sentences());
    }

    @Test
    public void onlyGgaWhenSentenceDisabled() {
        engine = new TransmitEngine(sink, generator, Collections.singleton(NmeaSentenceType.GGA), 5000L);
        engine.publish(sample(1000L), 1000L);
        assertEquals(1, engine.tick(2000L).size());
        assertEquals(1, engine.sentences());
    }

    @Test
    public void countersResetAndRateComputed() {
        engine.publish(sample(1000L), 1000L);
        engine.tick(2000L);
        // 1 帧 / 2 秒 ⇒ 0.5 帧每秒
        assertEquals(0.5, engine.rateHz(12000L), 0.01);
        assertEquals(1, engine.frames());
        engine.resetCounters();
        assertEquals(0, engine.sentences());
        assertEquals(0, engine.frames());
        assertEquals(0, engine.bytes());
        assertEquals(0.0, engine.rateHz(12000L), 0.01);
    }

    @Test
    public void sinkReceivesAsciiBytesEndingWithCrLf() {
        engine.publish(sample(1000L), 1000L);
        engine.tick(2000L);
        String text = new String(written.get(0), StandardCharsets.US_ASCII);
        assertTrue(text.endsWith("\r\n"));
        assertTrue(text.contains("$GPGGA,"));
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --tests 'com.gpsbt.transfer.engine.TransmitEngineTest' --console=plain 2>&1 | tail -12
```
Expected：失败（`TransmitEngine` 不存在）。

- [ ] **Step 4: 实现 `TransmitEngine`**

`app/src/main/java/com/gpsbt/transfer/engine/TransmitEngine.java`：
```java
package com.gpsbt.transfer.engine;

import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 定频把最新定位转成 NMEA 帧写入蓝牙流。 */
public final class TransmitEngine {

    public interface Sink {
        void write(byte[] data, int length);
    }

    private final Sink sink;
    private final NmeaGenerator generator;
    private final Set<NmeaSentenceType> enabled;
    private final long staleMillis;

    private volatile NmeaInput pending;
    private volatile long pendingAt;
    private volatile long sentences;
    private volatile long frames;
    private volatile long bytes;
    private volatile long startedAt;
    private volatile long lastFrameAt;

    public TransmitEngine(Sink sink, NmeaGenerator generator, Set<NmeaSentenceType> enabled, long staleMillis) {
        this.sink = sink;
        this.generator = generator;
        this.enabled = enabled;
        this.staleMillis = staleMillis;
    }

    /** 后台线程调用，只保存引用，不写流。 */
    public void publish(NmeaInput input, long nowMillis) {
        pending = input;
        pendingAt = nowMillis;
    }

    public boolean hasPending() {
        return pending != null;
    }

    /**
     * @return 本周期已写入的语句列表；无定位或定位过期时为空列表
     */
    public List<String> tick(long nowMillis) {
        NmeaInput input = pending;
        if (input == null || nowMillis - pendingAt > staleMillis) {
            return Collections.emptyList();
        }
        NmeaInput frameInput = input;
        if (input.utcMillis != nowMillis) {
            frameInput = input.copy();
            frameInput.utcMillis = nowMillis;
        }
        String frame = generator.buildFrame(frameInput, enabled);
        byte[] data = frame.getBytes(StandardCharsets.US_ASCII);
        if (data.length > 0) {
            sink.write(data, data.length);
        }
        if (startedAt == 0) {
            startedAt = nowMillis;
        }
        lastFrameAt = nowMillis;
        frames++;
        sentences += countSentences(frame);
        bytes += data.length;

        List<String> result = new java.util.ArrayList<>();
        for (String line : frame.split("\r\n")) {
            if (!line.isEmpty()) {
                result.add(line + "\r\n");
            }
        }
        return result;
    }

    public long sentences() {
        return sentences;
    }

    public long frames() {
        return frames;
    }

    public long bytes() {
        return bytes;
    }

    /** 累计平均发送速率（帧/秒，1 帧 = 一个发送周期内的全部语句）。 */
    public double rateHz(long nowMillis) {
        if (startedAt == 0 || nowMillis <= startedAt) {
            return 0d;
        }
        return frames * 1000.0 / (nowMillis - startedAt);
    }

    public void resetCounters() {
        sentences = 0;
        frames = 0;
        bytes = 0;
        startedAt = 0;
        lastFrameAt = 0;
    }

    private static int countSentences(String frame) {
        int count = 0;
        for (String line : frame.split("\r\n")) {
            if (!line.isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --console=plain 2>&1 | tail -20
```
Expected：`BUILD SUCCESSFUL`。

- [ ] **Step 6: 检查点**

报告：7 个用例通过情况；如有失败，修正实现而非删除断言。

---

### Task 7: 定位与卫星数据提供者

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/engine/LocationProvider.java`

**Interfaces:**
- Produces:
  - `LocationProvider(Context context)`
  - `LocationProvider.Callback#onFix(NmeaInput input)` / `#onStatus(String message)` / `#onSatelliteUpdate(int inView, int used)`
  - `void start(boolean allowNetwork, int minTimeMillis, Callback callback)`、`void stop()`
  - `static boolean hasPermission(Context context)`

- [ ] **Step 1: 实现 `LocationProvider`**

`app/src/main/java/com/gpsbt/transfer/engine/LocationProvider.java`：
```java
package com.gpsbt.transfer.engine;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.nmea.NmeaInput;

import java.util.ArrayList;
import java.util.List;

/** 封装 LocationManager 与卫星状态，输出与框架无关的 NmeaInput。 */
public final class LocationProvider {

    public interface Callback {
        void onFix(NmeaInput input);

        void onStatus(String message);

        void onSatelliteUpdate(int inView, int used);
    }

    private static final String[] GPS_AND_NETWORK = {
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
    };
    private static final String[] GPS_ONLY = { LocationManager.GPS_PROVIDER };

    private final Context context;
    private final LocationManager locationManager;
    private Callback callback;
    private boolean running;

    private final List<NmeaInput.Sat> satellites = new ArrayList<>();
    private int usedCount;

    public LocationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start(boolean allowNetwork, int minTimeMillis, Callback callback) {
        this.callback = callback;
        if (running) {
            return;
        }
        if (locationManager == null) {
            callback.onStatus("系统不支持定位服务");
            return;
        }
        if (!hasPermission(context)) {
            callback.onStatus("缺少定位权限");
            return;
        }
        String[] providers = allowNetwork ? GPS_AND_NETWORK : GPS_ONLY;
        String requested = "";
        for (String provider : providers) {
            if (locationManager.getProvider(provider) != null) {
                locationManager.requestLocationUpdates(
                        provider, Math.max(200L, minTimeMillis), 0f, listener, Looper.getMainLooper());
                requested = requested.isEmpty() ? provider : requested + " + " + provider;
            }
        }
        if (requested.isEmpty()) {
            callback.onStatus("设备没有可用的 GPS 定位提供者");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && locationManager.getProvider(LocationManager.GPS_PROVIDER) != null) {
            locationManager.registerGnssStatusCallback(gnssCallback, new android.os.Handler(Looper.getMainLooper()));
        }
        running = true;
        callback.onStatus("已启动定位：" + requested);
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (locationManager == null) {
            return;
        }
        locationManager.removeUpdates(listener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback(gnssCallback);
        }
        if (callback != null) {
            callback = null;
        }
    }

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (callback == null) {
                return;
            }
            callback.onFix(toInput(location));
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            if (callback != null) {
                callback.onStatus("定位提供者已关闭：" + provider);
            }
        }
    };

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            synchronized (satellites) {
                satellites.clear();
                usedCount = 0;
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    int snr = (int) status.getCn0DbHz(i);
                    boolean used = status.usedInFix(i);
                    if (used) {
                        usedCount++;
                    }
                    satellites.add(new NmeaInput.Sat(
                            status.getSvid(i),
                            (int) status.getElevationDegrees(i),
                            (int) status.getAzimuthDegrees(i),
                            snr,
                            used));
                }
            }
            if (callback != null) {
                callback.onSatelliteUpdate(satellites.size(), usedCount);
            }
        }
    };

    /** 把 Android Location 转成 NmeaInput。 */
    public static NmeaInput toInput(Location location) {
        NmeaInput input = new NmeaInput();
        input.utcMillis = System.currentTimeMillis();
        input.valid = true;
        input.latitude = location.getLatitude();
        input.longitude = location.getLongitude();
        input.altitudeM = location.hasAltitude() ? location.getAltitude() : 0d;
        input.speedMps = location.hasSpeed() ? location.getSpeed() : 0d;
        input.bearingDeg = location.hasBearing() ? location.getBearing() : 0d;
        input.accuracyM = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        return input;
    }

    /** 取最近一次卫星快照的副本并对定位快照补齐卫星字段。 */
    public NmeaInput withSatellites(NmeaInput input) {
        synchronized (satellites) {
            input.clearSats();
            for (NmeaInput.Sat sat : satellites) {
                input.addSat(sat);
            }
            input.satellitesUsed = usedCount;
        }
        return input;
    }

    public int satellitesInView() {
        synchronized (satellites) {
            return satellites.size();
        }
    }
}
```

- [ ] **Step 2: 编译验证（本任务无 JVM 测试，验证编译通过）**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -25
```
Expected：`ConnectionMode`/`BtState` 仍不存在 → `assembleDebug` 失败，但 `testDebugUnitTest` 必须成功。Task 8 补齐后 `assembleDebug` 恢复。

- [ ] **Step 3: 检查点**

报告：单元测试状态 + 预期中的编译失败项清单。

---

### Task 8: 蓝牙 SPP 双模式连接管理

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/bt/ConnectionMode.java`
- Create: `app/src/main/java/com/gpsbt/transfer/bt/BtState.java`
- Create: `app/src/main/java/com/gpsbt/transfer/bt/BluetoothLinkManager.java`
- Modify: `app/src/main/AndroidManifest.xml`（注册 Service，供 Task 9 使用）

**Interfaces:**
- Consumes: `bt.ConnectionMode`（Task 5 的 `TransmitterState.Mode` 与之同序）
- Produces:
  - `ConnectionMode { SERVER, CLIENT }`
  - `BtState { IDLE, WAITING, CONNECTING, CONNECTED, FAILED, DISCONNECTED }`
  - `BluetoothLinkManager(Context, BluetoothAdapter, String serviceName, UUID uuid, Listener listener)`
  - `Listener#onState(BtState, String message)` / `#onConnected(String remoteName, String remoteMac)` / `#onDisconnected(String message)`
  - `void startServer()` / `void startClient(BluetoothDevice)` / `void stop()` / `boolean isConnected()` / `void write(byte[] data)`（未连接时抛 `IOException`）
  - `static boolean isBluetoothAvailable()`、`static List<BluetoothDevice> bondedDevices()`、`static String displayName(BluetoothDevice)`

- [ ] **Step 1: 创建两个枚举**

`app/src/main/java/com/gpsbt/transfer/bt/ConnectionMode.java`：
```java
package com.gpsbt.transfer.bt;

/** 蓝牙连接发起方。顺序必须与 `state.TransmitterState.Mode` 一致。 */
public enum ConnectionMode {
    /** 手机注册 SPP 服务，等待对端连接。 */
    SERVER,
    /** 手机主动连接已配对设备。 */
    CLIENT
}
```

`app/src/main/java/com/gpsbt/transfer/bt/BtState.java`：
```java
package com.gpsbt.transfer.bt;

/** 蓝牙链路状态。顺序必须与 `state.TransmitterState.Bt` 一致。 */
public enum BtState {
    IDLE, WAITING, CONNECTING, CONNECTED, FAILED, DISCONNECTED
}
```

- [ ] **Step 2: 实现 `BluetoothLinkManager`**

`app/src/main/java/com/gpsbt/transfer/bt/BluetoothLinkManager.java`：
```java
package com.gpsbt.transfer.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 蓝牙 SPP（RFCOMM）连接管理：支持服务器等待连接与客户端主动连接。 */
public final class BluetoothLinkManager {

    /** 主线程回调。 */
    public interface Listener {
        void onState(BtState state, String message);

        void onConnected(String remoteName, String remoteMac);

        void onDisconnected(String message);
    }

    private static final UUID FALLBACK_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final BluetoothAdapter adapter;
    private final String serviceName;
    private final UUID uuid;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean stopping;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private BluetoothSocket socket;
    private OutputStream out;

    public BluetoothLinkManager(Context context, BluetoothAdapter adapter,
                                String serviceName, UUID uuid, Listener listener) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
        this.serviceName = serviceName == null || serviceName.trim().isEmpty()
                ? context.getString(com.gpsbt.transfer.R.string.app_name) : serviceName;
        this.uuid = uuid == null ? FALLBACK_UUID : uuid;
        this.listener = listener;
    }

    public static boolean isBluetoothAvailable() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    public boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    /** 已配对设备，按名称排序。 */
    public static List<BluetoothDevice> bondedDevices() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return Collections.emptyList();
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        List<BluetoothDevice> result = new ArrayList<>(bonded);
        Collections.sort(result, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice left, BluetoothDevice right) {
                return displayName(left).compareToIgnoreCase(displayName(right));
            }
        });
        return result;
    }

    public static String displayName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? device.getAddress() : name;
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && out != null;
    }

    // ---------- 服务器模式 ----------

    public void startServer() {
        if (adapter == null) {
            postState(BtState.FAILED, "设备不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            postState(BtState.FAILED, "蓝牙未开启，请在系统设置中开启");
            return;
        }
        stopping = false;
        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    // ---------- 客户端模式 ----------

    public void startClient(BluetoothDevice device) {
        if (adapter == null || device == null) {
            postState(BtState.FAILED, "设备不支持蓝牙或目标设备无效");
            return;
        }
        if (!adapter.isEnabled()) {
            postState(BtState.FAILED, "蓝牙未开启，请在系统设置中开启");
            return;
        }
        stopping = false;
        postState(BtState.CONNECTING, "正在连接 " + displayName(device));
        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    // ---------- 发送 ----------

    /** 未连接时抛 IOException；调用方负责据此停止服务。 */
    public synchronized void write(byte[] data) throws IOException {
        OutputStream stream = out;
        if (stream == null || socket == null || !socket.isConnected()) {
            throw new IOException("蓝牙未连接");
        }
        stream.write(data);
        stream.flush();
    }

    // ---------- 停止 ----------

    public synchronized void stop() {
        stopping = true;
        closeSocket();
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        postState(BtState.IDLE, "蓝牙链路已关闭");
    }

    private synchronized void closeSocket() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
            out = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
            socket = null;
        }
    }

    private synchronized void attach(BluetoothSocket accepted) {
        socket = accepted;
        try {
            out = accepted.getOutputStream();
        } catch (IOException e) {
            closeSocket();
            postState(BtState.FAILED, "获取输出流失败：" + e.getMessage());
            return;
        }
        BluetoothDevice device = accepted.getRemoteDevice();
        String name = device == null ? "未知设备" : displayName(device);
        String mac = device == null ? "" : device.getAddress();
        postState(BtState.CONNECTED, "已连接 " + name);
        listener.onConnected(name, mac);
    }

    private void postState(final BtState state, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(state, message);
            }
        });
    }

    private void postDisconnected(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(BtState.DISCONNECTED, message);
                listener.onDisconnected(message);
            }
        });
    }

    private final class AcceptThread extends Thread {
        private BluetoothServerSocket server;

        @Override
        public void run() {
            try {
                server = adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid);
            } catch (SecurityException e) {
                postState(BtState.FAILED, "缺少蓝牙连接权限");
                return;
            } catch (IOException e) {
                postState(BtState.FAILED, "创建蓝牙服务失败：" + e.getMessage());
                return;
            }
            postState(BtState.WAITING, "蓝牙服务已开启，等待设备连接");
            try {
                BluetoothSocket accepted = server.accept();
                if (stopping || accepted == null) {
                    closeQuietly(accepted);
                    return;
                }
                attach(accepted);
            } catch (IOException e) {
                if (!stopping) {
                    postState(BtState.FAILED, "监听中断：" + e.getMessage());
                }
            } finally {
                closeServer();
            }
        }

        void cancel() {
            closeServer();
        }

        private void closeServer() {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                server = null;
            }
        }

        private void closeQuietly(BluetoothSocket target) {
            if (target == null) {
                return;
            }
            try {
                target.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private final class ConnectThread extends Thread {
        private final BluetoothDevice device;
        private BluetoothSocket target;

        ConnectThread(BluetoothDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            try {
                target = device.createInsecureRfcommSocketToServiceRecord(uuid);
                target.connect();
            } catch (SecurityException e) {
                postState(BtState.FAILED, "缺少蓝牙连接权限");
                return;
            } catch (IOException e) {
                postState(BtState.FAILED, "连接失败：" + e.getMessage());
                closeQuietly();
                return;
            }
            if (stopping) {
                closeQuietly();
                return;
            }
            attach(target);
        }

        void cancel() {
            closeQuietly();
        }

        private void closeQuietly() {
            if (target != null) {
                try {
                    target.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                target = null;
            }
        }
    }
}
```

- [ ] **Step 3: 在清单注册前台服务**

在 `<application>` 内、`</application>` 之前插入：
```xml
        <service
            android:name=".service.GpsOutputService"
            android:exported="false"
            android:foregroundServiceType="location" />
```

- [ ] **Step 4: 全量编译**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -25
```
Expected：`GpsOutputService` 不存在 → 清单合并失败，但**编译 MainActivity / 蓝牙模块本身无错误**。用下面命令单独校验 Java 编译：
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew compileDebugJavaWithJavac --console=plain 2>&1 | tail -20
```
Expected：`BUILD SUCCESSFUL`（Task 9 补齐后清单合并恢复）。

- [ ] **Step 5: 检查点**

报告：`compileDebugJavaWithJavac` 结果。

---

### Task 9: 前台服务串起整条链路

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java`（新增 `resetForStop()`）
- Modify: `app/src/main/res/values/strings.xml`（追加通知文案）

**Interfaces:**
- Consumes: `Prefs`、`NmeaGenerator`、`TransmitEngine`、`LocationProvider`、`BluetoothLinkManager`、`TransmitterState`
- Produces:
  - `GpsOutputService.ACTION_START`、`ACTION_STOP`、`EXTRA_MODE`、`EXTRA_DEVICE_MAC`
  - `static void start(Context, ConnectionMode, String deviceMac)`、`static void stop(Context)`
  - `TransmitterState.resetForStop()`

- [ ] **Step 1: 给 `TransmitterState` 追加 `resetForStop()`**

在 `setStats(...)` 之后插入：
```java
    public void resetForStop() {
        phase = Phase.IDLE;
        bt = Bt.IDLE;
        connectedDevice = null;
        pendingDevice = null;
        statusText = "";
        fix = null;
        stats = new TransmitStats();
        notifyListeners();
    }
```

- [ ] **Step 2: 追加通知字符串**

在 `app/src/main/res/values/strings.xml` 的 `</resources>` 前追加：
```xml
    <string name="channel_transmit_name">GPS蓝牙传输服务</string>
    <string name="channel_transmit_desc">显示 GPS 蓝牙传输的运行状态</string>
    <string name="notif_waiting">等待设备连接…</string>
    <string name="notif_sending">正在发送 GPS 数据</string>
    <string name="notif_no_fix">无定位信号</string>
    <string name="notif_connected_to">已连接：%1$s</string>
    <string name="notif_stats">已发送 %1$d 句 / %2$s</string>
    <string name="notif_action_stop">停止传输</string>
```

- [ ] **Step 3: 实现 `GpsOutputService`**

`app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java`：
```java
package com.gpsbt.transfer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.gpsbt.transfer.App;
import com.gpsbt.transfer.MainActivity;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.bt.BluetoothLinkManager;
import com.gpsbt.transfer.bt.BtState;
import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.engine.LocationProvider;
import com.gpsbt.transfer.engine.TransmitEngine;
import com.gpsbt.transfer.nmea.NmeaGenerator;
import com.gpsbt.transfer.nmea.NmeaInput;
import com.gpsbt.transfer.nmea.NmeaSentenceType;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.state.TransmitStats;
import com.gpsbt.transfer.util.Formatters;
import com.gpsbt.transfer.util.Prefs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 前台服务：定位 → NMEA → 蓝牙串口。
 * 只能由用户在主页点击「开始传输」显式启动；使用 START_NOT_STICKY，被杀后不自动重启。
 */
public class GpsOutputService extends Service {

    public static final String ACTION_START = "com.gpsbt.transfer.action.START";
    public static final String ACTION_STOP = "com.gpsbt.transfer.action.STOP";
    public static final String EXTRA_MODE = "com.gpsbt.transfer.extra.MODE";
    public static final String EXTRA_DEVICE_MAC = "com.gpsbt.transfer.extra.DEVICE_MAC";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "gps_bt_output";

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private int currentRateHz;
    private long lastNotificationAt;

    private Prefs prefs;
    private LocationProvider locationProvider;
    private TransmitEngine engine;
    private BluetoothLinkManager link;
    private TransmitStats stats;
    private TransmitterState state;
    private volatile ConnectionMode mode = ConnectionMode.SERVER;
    private volatile String deviceMac;

    public static void start(Context context, ConnectionMode mode, String deviceMac) {
        Intent intent = new Intent(context, GpsOutputService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_MODE, (mode == null ? ConnectionMode.SERVER : mode).name());
        intent.putExtra(EXTRA_DEVICE_MAC, deviceMac);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, GpsOutputService.class);
        intent.setAction(ACTION_STOP);
        context.getApplicationContext().startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.get(this);
        state = TransmitterState.get();
        stats = new TransmitStats();
        createChannel();
        state.setStats(stats);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            LogBus.get().log(LogBus.Level.INFO, "用户停止传输");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (!ACTION_START.equals(action)) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        try {
            mode = ConnectionMode.valueOf(intent.getStringExtra(EXTRA_MODE));
        } catch (RuntimeException e) {
            mode = ConnectionMode.SERVER;
        }
        deviceMac = intent.getStringExtra(EXTRA_DEVICE_MAC);

        goForeground();

        if (!startPipeline()) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        // 关键约定：禁用"被杀后自动重启"
        return START_NOT_STICKY;
    }

    private boolean startPipeline() {
        if (!LocationProvider.hasPermission(this)) {
            state.setStatusText("缺少定位权限，无法开始传输");
            LogBus.get().log(LogBus.Level.ERROR, "缺少定位权限");
            return false;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            state.setStatusText("蓝牙未开启，无法开始传输");
            LogBus.get().log(LogBus.Level.ERROR, "蓝牙未开启");
            return false;
        }
        if (mode == ConnectionMode.CLIENT && (deviceMac == null || deviceMac.isEmpty())) {
            state.setStatusText("请选择要连接的蓝牙设备");
            LogBus.get().log(LogBus.Level.ERROR, "客户端模式缺少目标设备");
            return false;
        }

        state.setPhase(TransmitterState.Phase.RUNNING);
        state.setMode(mode);
        state.setConfig(prefs.getServiceName(), prefs.getUuid(), prefs.getRateHz());
        state.setStatusText(mode == ConnectionMode.SERVER ? "蓝牙服务启动中…" : "正在连接设备…");

        final NmeaGenerator generator =
                new NmeaGenerator(prefs.getDefaultHdop());
        final Set<NmeaSentenceType> enabled =
                prefs.getSentences().isEmpty() ? NmeaSentenceType.all() : prefs.getSentences();

        link = new BluetoothLinkManager(this, adapter, prefs.getServiceName(),
                UUID.fromString(prefs.getUuid()), bluetoothListener);
        engine = new TransmitEngine(new TransmitEngine.Sink() {
            @Override
            public void write(byte[] data, int length) {
                byte[] payload = new byte[length];
                System.arraycopy(data, 0, payload, 0, length);
                link.write(payload);
            }
        }, generator, enabled, prefs.getStaleMillis());

        locationProvider = new LocationProvider(this);
        locationProvider.start(prefs.isAllowNetwork(), 1000 / Math.max(1, prefs.getRateHz()),
                locationCallback);

        if (mode == ConnectionMode.SERVER) {
            link.startServer();
        } else {
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(deviceMac);
            } catch (IllegalArgumentException e) {
                state.setStatusText("目标设备地址无效：" + deviceMac);
                LogBus.get().log(LogBus.Level.ERROR, "目标设备地址无效：" + deviceMac);
                return false;
            }
            state.setPendingDevice(new TransmitterState.Device(
                    BluetoothLinkManager.displayName(device), deviceMac));
            link.startClient(device);
        }

        startScheduler(prefs.getRateHz());
        LogBus.get().log(LogBus.Level.INFO, "传输已启动，模式：" + (mode == ConnectionMode.SERVER ? "等待被连接" : "主动连接"));
        return true;
    }

    private void startScheduler(int rateHz) {
        stopScheduler();
        currentRateHz = Math.max(1, rateHz);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        final long period = 1000L / currentRateHz;
        future = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                tickInternal();
            }
        }, 0, period, TimeUnit.MILLISECONDS);
    }

    private void stopScheduler() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void tickInternal() {
        final TransmitEngine current = engine;
        final BluetoothLinkManager currentLink = link;
        if (current == null || currentLink == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (currentLink.isConnected()) {
            try {
                List<String> sentences = current.tick(now);
                if (!sentences.isEmpty()) {
                    // tick 已经通过 Sink 写入蓝牙流；这里只更新统计与通知
                    stats.lastFrameAt = now;
                    maybeUpdateNotification(now);
                }
            } catch (IOException e) {
                LogBus.get().log(LogBus.Level.ERROR, "发送失败：" + e.getMessage());
                state.setStatusText("蓝牙写失败，传输已中断");
                stopSelfSafely();
            }
        } else if (now - (stats.lastFrameAt == 0 ? now : stats.lastFrameAt) > 5000L) {
            // 长时间未连接不视为错误，等待用户操作或停止
        }
    }

    private final LocationProvider.Callback locationCallback = new LocationProvider.Callback() {
        @Override
        public void onFix(NmeaInput input) {
            NmeaInput full = locationProvider.withSatellites(input);
            engine.publish(full, System.currentTimeMillis());

            TransmitterState.Fix fix = new TransmitterState.Fix();
            fix.valid = full.valid;
            fix.satellitesInView = full.satellitesInView();
            fix.satellitesUsed = full.satellitesUsed;
            fix.latitude = full.latitude;
            fix.longitude = full.longitude;
            fix.altitudeM = full.altitudeM;
            fix.speedMps = full.speedMps;
            fix.bearingDeg = full.bearingDeg;
            fix.accuracyM = full.accuracyM;
            fix.fixAt = System.currentTimeMillis();
            state.setFix(fix);
        }

        @Override
        public void onStatus(String message) {
            state.setStatusText(message);
            LogBus.get().log(LogBus.Level.INFO, message);
        }

        @Override
        public void onSatelliteUpdate(int inView, int used) {
            LogBus.get().log(LogBus.Level.DATA, "卫星：可见 " + inView + "，用于定位 " + used);
        }
    };

    private final BluetoothLinkManager.Listener bluetoothListener =
            new BluetoothLinkManager.Listener() {
                @Override
                public void onState(BtState btState, String message) {
                    state.setBt(mapBt(btState));
                    state.setStatusText(message);
                    LogBus.get().log(mapLevel(btState), "蓝牙：" + message);
                    NotificationManager manager =
                            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (manager != null) {
                        manager.notify(NOTIFICATION_ID, buildNotification());
                    }
                }

                @Override
                public void onConnected(String remoteName, String remoteMac) {
                    state.setConnectedDevice(new TransmitterState.Device(remoteName, remoteMac));
                    state.setPendingDevice(null);
                    stats.startedAt = System.currentTimeMillis();
                    LogBus.get().log(LogBus.Level.INFO, "已连接 " + remoteName + "（" + remoteMac + "）");
                }

                @Override
                public void onDisconnected(String message) {
                    state.setConnectedDevice(null);
                    LogBus.get().log(LogBus.Level.WARN, "蓝牙已断开：" + message);
                    state.setStatusText("蓝牙连接已断开，传输停止");
                    stopSelfSafely();
                }
            };

    private static TransmitterState.Bt mapBt(BtState value) {
        switch (value) {
            case WAITING:
                return TransmitterState.Bt.WAITING;
            case CONNECTING:
                return TransmitterState.Bt.CONNECTING;
            case CONNECTED:
                return TransmitterState.Bt.CONNECTED;
            case FAILED:
                return TransmitterState.Bt.FAILED;
            case DISCONNECTED:
                return TransmitterState.Bt.DISCONNECTED;
            case IDLE:
            default:
                return TransmitterState.Bt.IDLE;
        }
    }

    private static LogBus.Level mapLevel(BtState value) {
        switch (value) {
            case FAILED:
                return LogBus.Level.ERROR;
            case DISCONNECTED:
                return LogBus.Level.WARN;
            default:
                return LogBus.Level.INFO;
        }
    }

    // ---------- 通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_transmit_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_transmit_desc));
        channel.setShowBadge(false);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void goForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        TransmitterState.Bt bt = state.bt();
        TransmitterState.Fix fix = state.fix();
        String title;
        if (bt == TransmitterState.Bt.CONNECTED) {
            title = getString(R.string.notif_sending);
        } else if (bt == TransmitterState.Bt.WAITING || bt == TransmitterState.Bt.CONNECTING) {
            title = getString(R.string.notif_waiting);
        } else if (fix != null && fix.valid) {
            title = getString(R.string.notif_sending);
        } else {
            title = getString(R.string.notif_no_fix);
        }
        TransmitterState.Device device = state.connectedDevice();
        StringBuilder text = new StringBuilder();
        if (device != null) {
            text.append(getString(R.string.notif_connected_to, device.name));
        } else {
            text.append(state.statusText());
        }
        text.append("　");
        if (fix != null && fix.valid) {
            text.append(Formatters.latitude(fix.latitude))
                    .append("　").append(Formatters.longitude(fix.longitude));
        }
        text.append("　").append(getString(R.string.notif_stats, engine == null ? 0 : engine.sentences(),
                Formatters.duration(stats.startedAt == 0 ? -1
                        : System.currentTimeMillis() - stats.startedAt)));

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, GpsOutputService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_satellite)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(content)
                .addAction(0, getString(R.string.notif_action_stop), stopAction)
                .build();
    }

    private void maybeUpdateNotification(long now) {
        if (now - lastNotificationAt < 2000L) {
            return;
        }
        lastNotificationAt = now;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private synchronized void stopSelfSafely() {
        if (locationProvider != null) {
            locationProvider.stop();
            locationProvider = null;
        }
        if (link != null) {
            link.stop();
            link = null;
        }
        stopScheduler();
        engine = null;
        state.resetForStop();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSelfSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
```

- [ ] **Step 4: 创建通知图标资源**

`app/src/main/res/drawable/ic_stat_satellite.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2L9.5,9.5L2,12l7.5,2.5L12,22l2.5,-7.5L22,12l-7.5,-2.5z" />
</vector>
```

- [ ] **Step 5: 编译验证**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -30
```
Expected：`BUILD SUCCESSFUL`，产出 APK。

- [ ] **Step 6: 检查点**

报告：APK 构建结果；确认清单中 `GpsOutputService` 的 `exported=false` 与 `foregroundServiceType="location"`。

---

### Task 10: 深色科技风资源与三个自定义 View

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（全量中文文案）
- Create: `app/src/main/res/values/attrs.xml`, `app/src/main/res/values/styles.xml`
- Create: `app/src/main/res/drawable/card_background.xml`, `badge_background.xml`, `mode_segment_background.xml`, `section_divider.xml`, `ripple_soft.xml`
- Create: `app/src/main/res/drawable/ic_stat_satellite.xml`（已存在则跳过）、`ic_nav_home.xml`、`ic_nav_satellite.xml`、`ic_nav_log.xml`、`ic_nav_settings.xml`、`ic_stat_location.xml`、`ic_stat_speed.xml`、`ic_stat_compass.xml`、`ic_stat_altitude.xml`、`ic_stat_send.xml`、`ic_stat_bluetooth.xml`、`ic_copy.xml`, `ic_delete.xml`, `ic_export.xml`
- Create: `app/src/main/java/com/gpsbt/transfer/ui/view/StatCardView.java`, `PulseButtonView.java`, `SatelliteRadarView.java`

**Interfaces:**
- Produces:
  - `StatCardView(Context, AttributeSet)`，方法 `setTitle(String)`、`setValue(String)`、`setUnit(String)`、`setIcon(int)`、`setAccent(int)`
  - `PulseButtonView(Context, AttributeSet)`，`enum State { IDLE, RUNNING }`，`setState(State)`、`State state()`
  - `SatelliteRadarView(Context, AttributeSet)`，`static final class Sat { int prn, elevationDeg, azimuthDeg, snr; boolean used }`，`submit(List<Sat>)`、`clear()`
  - attrs：`StatCardView`（`cardTitle`/`cardValue`/`cardUnit`/`cardIcon`）；`PulseButtonView`（`pbIdleText`/`pbRunningText`/`pbState`）

- [ ] **Step 1: 全量重写 `strings.xml`**

`app/src/main/res/values/strings.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">GPS蓝牙传输</string>

    <!-- 底部导航 -->
    <string name="nav_home">传输</string>
    <string name="nav_satellite">卫星</string>
    <string name="nav_log">日志</string>
    <string name="nav_settings">设置</string>

    <!-- 主页 -->
    <string name="home_title">GPS蓝牙传输</string>
    <string name="home_subtitle">NMEA 0183 蓝牙串口输出</string>
    <string name="home_mode_title">连接方式</string>
    <string name="home_mode_server">等待被连接</string>
    <string name="home_mode_client">主动连接</string>
    <string name="home_mode_server_hint">手机作为蓝牙串口服务端，由对方设备发起连接</string>
    <string name="home_mode_client_hint">由手机主动连接下方选中的已配对设备</string>
    <string name="home_devices_title">已配对设备</string>
    <string name="home_devices_empty">没有已配对设备，请先到「系统设置 → 蓝牙」中配对</string>
    <string name="home_devices_refresh">刷新列表</string>
    <string name="home_service_title">串口服务信息</string>
    <string name="home_service_name">服务名称</string>
    <string name="home_service_uuid">SPP UUID</string>
    <string name="home_stats_title">运行数据</string>
    <string name="home_btn_start">开始传输</string>
    <string name="home_btn_stop">停止传输</string>
    <string name="home_note">本软件不支持开机自启，每次使用需手动点击「开始传输」。</string>

    <!-- 状态文案 -->
    <string name="state_idle">未开始</string>
    <string name="state_waiting">等待设备连接</string>
    <string name="state_connecting">正在连接…</string>
    <string name="state_connected">已连接，发送中</string>
    <string name="state_failed">连接失败</string>
    <string name="state_no_fix">暂无定位</string>
    <string name="state_bluetooth_off">蓝牙未开启</string>

    <!-- 数据卡片标题 -->
    <string name="stat_fix">定位状态</string>
    <string name="stat_satellites">卫星数量</string>
    <string name="stat_latitude">纬度</string>
    <string name="stat_longitude">经度</string>
    <string name="stat_speed">对地速度</string>
    <string name="stat_bearing">航向</string>
    <string name="stat_altitude">海拔高度</string>
    <string name="stat_sentences">已发送语句</string>
    <string name="stat_rate">发送速率</string>
    <string name="stat_device">连接设备</string>

    <!-- 数值 -->
    <string name="value_no_fix">无定位</string>
    <string name="value_fixed">已定位</string>
    <string name="value_unknown">--</string>
    <string name="unit_satellites">颗</string>
    <string name="unit_rate">句/秒</string>
    <string name="unit_knots">节</string>

    <!-- 卫星页 -->
    <string name="satellite_title">卫星状态</string>
    <string name="satellite_radar_empty">等待卫星信号…</string>
    <string name="satellite_list_title">卫星明细</string>
    <string name="satellite_col_prn">编号</string>
    <string name="satellite_col_snr">信噪比</string>
    <string name="satellite_col_used">已用于定位</string>
    <string name="satellite_yes">是</string>
    <string name="satellite_no">否</string>
    <string name="satellite_empty">暂无卫星数据</string>

    <!-- 日志页 -->
    <string name="log_title">运行日志</string>
    <string name="log_copy">复制</string>
    <string name="log_clear">清空</string>
    <string name="log_export">导出</string>
    <string name="log_empty">暂无日志</string>
    <string name="log_copied">日志已复制到剪贴板</string>
    <string name="log_exported">日志已导出到：%1$s</string>
    <string name="log_export_failed">导出失败：%1$s</string>

    <!-- 设置页 -->
    <string name="settings_title">设置</string>
    <string name="settings_rate">发送频率</string>
    <string name="settings_rate_value">%1$d Hz</string>
    <string name="settings_sentences">NMEA 语句</string>
    <string name="settings_service_title">蓝牙服务</string>
    <string name="settings_location_title">定位来源</string>
    <string name="settings_allow_network">允许网络辅助定位</string>
    <string name="settings_allow_network_desc">关闭时仅使用 GPS 卫星定位，位置更精确但首次定位更慢</string>
    <string name="settings_stale">无定位判定超时</string>
    <string name="settings_stale_value">%1$d 秒</string>
    <string name="settings_hdop">HDOP 基准值</string>
    <string name="settings_hdop_value">%.1f</string>
    <string name="settings_defaults">恢复默认</string>
    <string name="settings_about_title">关于</string>
    <string name="settings_about_body">GPS蓝牙传输 %1$s\n把本机 GPS 定位以标准 NMEA 0183 格式通过蓝牙串口输出给其他设备。\n\n本软件不支持开机自启与自动运行，每次使用都需手动开启；停止传输后立即释放蓝牙与 GPS 资源。</string>

    <!-- 权限 -->
    <string name="perm_location_title">需要定位权限</string>
    <string name="perm_location_rationale">获取卫星定位数据是 GPS 输出的前提，请授予「精确位置」权限。</string>
    <string name="perm_location_denied">未获得定位权限，无法开始传输</string>
    <string name="perm_bluetooth_rationale">需要通过蓝牙发送数据，请授予「附近的设备」权限。</string>
    <string name="perm_notification_rationale">开启常驻通知，便于随时查看传输状态并一键停止。</string>
    <string name="perm_grant">去授权</string>

    <!-- 通知 -->
    <string name="channel_transmit_name">GPS蓝牙传输服务</string>
    <string name="channel_transmit_desc">显示 GPS 蓝牙传输的运行状态</string>
    <string name="notif_waiting">等待设备连接…</string>
    <string name="notif_sending">正在发送 GPS 数据</string>
    <string name="notif_no_fix">无定位信号</string>
    <string name="notif_connected_to">已连接：%1$s</string>
    <string name="notif_stats">已发送 %1$d 句 / %2$s</string>
    <string name="notif_action_stop">停止传输</string>
</resources>
```

- [ ] **Step 2: 写 `attrs.xml` 与 `styles.xml`**

`app/src/main/res/values/attrs.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <declare-styleable name="StatCardView">
        <attr name="cardTitle" format="string" />
        <attr name="cardValue" format="string" />
        <attr name="cardUnit" format="string" />
        <attr name="cardIcon" format="reference" />
    </declare-styleable>

    <declare-styleable name="PulseButtonView">
        <attr name="pbIdleText" format="string" />
        <attr name="pbRunningText" format="string" />
        <attr name="pbState" format="enum">
            <enum name="idle" value="0" />
            <enum name="running" value="1" />
        </attr>
    </declare-styleable>
</resources>
```

`app/src/main/res/values/styles.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>

    <style name="TextPrimary" parent="@android:style/TextAppearance.Material.Body1">
        <item name="android:textColor">@color/color_text_primary</item>
    </style>

    <style name="TextSecondary" parent="@android:style/TextAppearance.Material.Body2">
        <item name="android:textColor">@color/color_text_secondary</item>
    </style>

    <style name="CardStyle">
        <item name="android:background">@drawable/card_background</item>
        <item name="android:padding">16dp</item>
    </style>

    <style name="SectionTitle">
        <item name="android:textColor">@color/color_text_secondary</item>
        <item name="android:textSize">13sp</item>
        <item name="android:letterSpacing">0.08</item>
        <item name="android:layout_marginTop">8dp</item>
        <item name="android:layout_marginBottom">8dp</item>
    </style>

    <style name="ValueText">
        <item name="android:fontFamily">monospace</item>
        <item name="android:textColor">@color/color_text_primary</item>
        <item name="android:textSize">18sp</item>
        <item name="android:maxLines">1</item>
        <item name="android:ellipsize">end</item>
    </style>

    <style name="SettingsRow">
        <item name="android:textColor">@color/color_text_primary</item>
        <item name="android:textSize">15sp</item>
    </style>
</resources>
```

- [ ] **Step 3: 写 drawable**

`drawable/card_background.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/color_card" />
    <corners android:radius="20dp" />
    <stroke android:width="1dp" android:color="@color/color_card_stroke" />
</shape>
```

`drawable/badge_background.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#1E2436" />
    <corners android:radius="999dp" />
    <stroke android:width="1dp" android:color="@color/color_card_stroke" />
</shape>
```

`drawable/mode_segment_background.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_checked="true">
        <shape android:shape="rectangle">
            <solid android:color="#1F2A44" />
            <corners android:radius="14dp" />
            <stroke android:width="1dp" android:color="@color/color_primary" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#151D33" />
            <corners android:radius="14dp" />
            <stroke android:width="1dp" android:color="@color/color_card_stroke" />
        </shape>
    </item>
</selector>
```

`drawable/section_divider.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/color_card_stroke" />
    <size android:height="1dp" />
</shape>
```

`drawable/ripple_soft.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="#3300E5C7">
    <item android:id="@android:id/mask">
        <shape android:shape="rectangle">
            <solid android:color="#3300E5C7" />
            <corners android:radius="16dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#181F36" />
            <corners android:radius="16dp" />
        </shape>
    </item>
</ripple>
```

图标统一 24dp、`android:tint="?attr/colorControlNormal"`，模板如下（逐个写文件）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="PATH_DATA" />
</vector>
```
各图标 `PATH_DATA`：
- `ic_nav_home`：`M12,3l9,8h-3v9h-5v-6H11v6H6V11H3z`
- `ic_nav_satellite`：`M12,2L9.5,9.5L2,12l7.5,2.5L12,22l2.5,-7.5L22,12l-7.5,-2.5z`
- `ic_nav_log`：`M4,4h16v2H4zM4,9h16v2H4zM4,14h10v2H4zM4,19h10v1.5H4z`
- `ic_nav_settings`：`M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94s-0.02,-0.64 -0.07,-0.94l2.03,-1.58a0.49,0.49 0 0,0 0.12,-0.61l-1.92,-3.32a0.488,0.488 0 0,0 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94l-0.36,-2.54a0.484,0.484 0 0,0 -0.48,-0.41h-3.84a0.484,0.484 0 0,0 -0.48,0.41l-0.36,2.54c-0.59,0.24 -1.13,0.57 -1.62,0.94l-2.39,-0.96a0.488,0.488 0 0,0 -0.59,0.22L2.16,8.91a0.488,0.488 0 0,0 0.12,0.61l2.03,1.58c-0.05,0.3 -0.09,0.63 -0.09,0.94s0.02,0.64 0.07,0.94l-2.03,1.58a0.49,0.49 0 0,0 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.43,-0.17 0.48,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32a0.49,0.49 0 0,0 -0.12,-0.61l-2.01,-1.58zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z`
- `ic_stat_location`：`M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z`
- `ic_stat_speed`：`M20.38,8.57l-1.23,1.85a9,9 0 0,1 -0.9,1.53l-1.62,-1.02 0.75,-1.27a7,7 0 0,0 0.7,-1.19l1.23,-1.85a0.6,0.6 0 0,1 1.07,0.95zM2.62,8.57a0.6,0.6 0 0,1 1.07,-0.95l1.23,1.85c0.18,0.37 0.42,0.77 0.7,1.19l0.75,1.27 -1.62,1.02a9,9 0 0,1 -0.9,-1.53zM12,6a7,7 0 0,0 -7,7c0,1.5 1.5,4.5 3,6h8c1.5,-1.5 3,-4.5 3,-6a7,7 0 0,0 -7,-7zM12,13.5a2.5,2.5 0 1,1 0,-5 2.5,2.5 0 0,1 0,5z`
- `ic_stat_compass`：`M12,2a10,10 0 1,0 0,20 10,10 0 0,0 0,-20zM12,4a8,8 0 0,1 8,8 8,8 0 0,1 -8,8 8,8 0 0,1 -8,-8 8,8 0 0,1 8,-8zM15.5,8.5l-2,5 -5,2 2,-5z`
- `ic_stat_altitude`：`M3,20l6,-10 4,6 3,-4 5,8zM12,6l3,-3 3,3h-2v3h-2V6z`
- `ic_stat_send`：`M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z`
- `ic_stat_bluetooth`：`M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29zM13,5.83l1.88,1.88L13,9.59zM14.88,16.29L13,18.17v-3.76z`
- `ic_copy`：`M16,1H4a2,2 0 0,0 -2,2v14h2V3h12zM19,5H8a2,2 0 0,0 -2,2v14a2,2 0 0,0 2,2h11a2,2 0 0,0 2,-2V7a2,2 0 0,0 -2,-2zM19,21H8V7h11z`
- `ic_delete`：`M6,19a2,2 0 0,0 2,2h8a2,2 0 0,0 2,-2V7H6zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14z`
- `ic_export`：`M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z`

- [ ] **Step 4: 实现 `StatCardView`**

`app/src/main/java/com/gpsbt/transfer/ui/view/StatCardView.java`：
```java
package com.gpsbt.transfer.ui.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.R;

/** 数据卡片：图标 + 标题 + 等宽数值 + 单位。 */
public class StatCardView extends LinearLayout {

    private final TextView titleView = new TextView(getContext());
    private final TextView valueView = new TextView(getContext());
    private final TextView unitView = new TextView(getContext());
    private final ImageView iconView = new ImageView(getContext());
    private int accent = ContextCompat.getColor(getContext(), R.color.color_primary);

    public StatCardView(Context context) {
        super(context);
        init(null);
    }

    public StatCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.card_background);
        int padding = dp(16);
        setPadding(padding, padding, padding, padding);

        titleView.setTextSize(12);

        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dp(18), dp(18));
        iconParams.setMargins(0, 0, dp(6), 0);
        iconView.setLayoutParams(iconParams);
        iconView.setColorFilter(accent, PorterDuff.Mode.SRC_IN);

        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(iconView);
        titleRow.addView(titleView);
        addView(titleRow);

        LinearLayout valueRow = new LinearLayout(getContext());
        valueRow.setOrientation(HORIZONTAL);
        valueRow.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams valueParams =
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        valueView.setLayoutParams(valueParams);
        valueRow.addView(valueView);
        valueRow.addView(unitView);
        LinearLayout.LayoutParams valueRowParams =
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        valueRowParams.setMargins(0, dp(8), 0, 0);
        addView(valueRow, valueRowParams);

        titleView.setTextColor(ContextCompat.getColor(getContext(), R.color.color_text_secondary));
        valueView.setTextColor(ContextCompat.getColor(getContext(), R.color.color_text_primary));
        valueView.setTypeface(android.graphics.Typeface.MONOSPACE);
        valueView.setTextSize(17);
        valueView.setMaxLines(1);
        unitView.setTextColor(ContextCompat.getColor(getContext(), R.color.color_text_secondary));
        unitView.setTextSize(11);
        unitView.setPadding(dp(3), 0, 0, dp(2));

        if (attrs != null) {
            TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.StatCardView);
            setTitle(array.getString(R.styleable.StatCardView_cardTitle));
            setValue(array.getString(R.styleable.StatCardView_cardValue));
            setUnit(array.getString(R.styleable.StatCardView_cardUnit));
            setIcon(array.getResourceId(R.styleable.StatCardView_cardIcon, 0));
            array.recycle();
        }
    }

    public void setTitle(String text) {
        titleView.setText(text == null ? "" : text);
    }

    public void setValue(String text) {
        valueView.setText(text == null ? getContext().getString(R.string.value_unknown) : text);
    }

    public void setUnit(String text) {
        unitView.setText(text == null ? "" : text);
    }

    public void setIcon(@DrawableRes int resId) {
        if (resId == 0) {
            iconView.setVisibility(GONE);
            return;
        }
        iconView.setVisibility(VISIBLE);
        iconView.setImageResource(resId);
        iconView.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
    }

    public void setAccent(int color) {
        accent = color;
        titleView.setTextColor(color);
        iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
```

- [ ] **Step 5: 实现 `PulseButtonView`**

`app/src/main/java/com/gpsbt/transfer/ui/view/PulseButtonView.java`：
```java
package com.gpsbt.transfer.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.R;

/** 圆形脉冲主按钮：未启动为青色呼吸光环，传输中为橙色扩散波纹。 */
public class PulseButtonView extends View {

    public enum State { IDLE, RUNNING }

    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density = getResources().getDisplayMetrics().density;
    private final int idleColor = ContextCompat.getColor(getContext(), R.color.color_primary);
    private final int runningColor = ContextCompat.getColor(getContext(), R.color.color_warning);
    private final int textColor = ContextCompat.getColor(getContext(), R.color.color_text_primary);

    private ValueAnimator animator;
    private float phase;
    private State state = State.IDLE;
    private String idleText;
    private String runningText;

    public PulseButtonView(Context context) {
        super(context);
        init(null);
    }

    public PulseButtonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        idleText = getContext().getString(R.string.home_btn_start);
        runningText = getContext().getString(R.string.home_btn_stop);
        if (attrs != null) {
            TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.PulseButtonView);
            idleText = array.getString(R.styleable.PulseButtonView_pbIdleText);
            if (idleText == null) {
                idleText = getContext().getString(R.string.home_btn_start);
            }
            runningText = array.getString(R.styleable.PulseButtonView_pbRunningText);
            if (runningText == null) {
                runningText = getContext().getString(R.string.home_btn_stop);
            }
            state = array.getInt(R.styleable.PulseButtonView_pbState, 0) == 1 ? State.RUNNING : State.IDLE;
            array.recycle();
        }

        corePaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f * density);
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(2f * density);
        textPaint.setColor(textColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(15f * density);
        setClickable(true);
        startAnimation();
    }

    public void setState(State value) {
        state = value == null ? State.IDLE : value;
        invalidate();
    }

    public State state() {
        return state;
    }

    private void startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1800L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = animation.getAnimatedFraction();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float outer = Math.min(getWidth(), getHeight()) / 2f - 4f * density;
        float radius = outer * 0.66f;
        int color = state == State.RUNNING ? runningColor : idleColor;

        if (state == State.RUNNING) {
            for (int i = 0; i < 2; i++) {
                float progress = (phase + i * 0.5f) % 1f;
                pulsePaint.setColor(withAlpha(color, (int) (180 * (1f - progress))));
                canvas.drawCircle(centerX, centerY, radius * (1f + 0.55f * progress), pulsePaint);
            }
        } else {
            float breathe = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2);
            pulsePaint.setColor(withAlpha(color, (int) (40 + 90 * (1f - breathe))));
            canvas.drawCircle(centerX, centerY, radius * (1f + 0.10f * breathe), pulsePaint);
        }

        corePaint.setShader(new LinearGradient(
                centerX - radius, centerY - radius, centerX + radius, centerY + radius,
                withAlpha(color, 255), withAlpha(mix(color, Color.BLACK, 0.45f), 255),
                Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, radius, corePaint);
        corePaint.setShader(null);

        ringPaint.setColor(color);
        canvas.drawCircle(centerX, centerY, radius, ringPaint);

        String label = state == State.RUNNING ? runningText : idleText;
        canvas.drawText(label, centerX,
                centerY - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    private static int mix(int first, int second, float ratio) {
        return Color.rgb(
                (int) (Color.red(first) + (Color.red(second) - Color.red(first)) * ratio),
                (int) (Color.green(first) + (Color.green(second) - Color.green(first)) * ratio),
                (int) (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * ratio));
    }
}
```

- [ ] **Step 6: 实现 `SatelliteRadarView`**

`app/src/main/java/com/gpsbt/transfer/ui/view/SatelliteRadarView.java`：
```java
package com.gpsbt.transfer.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.gpsbt.transfer.R;

import java.util.ArrayList;
import java.util.List;

/** 卫星天空雷达：同心圆表示仰角，卫星按方位/仰角定位，柱高表示信噪比。 */
public class SatelliteRadarView extends View {

    public static final class Sat {
        public final int prn;
        public final int elevationDeg;
        public final int azimuthDeg;
        public final int snr;
        public final boolean used;

        public Sat(int prn, int elevationDeg, int azimuthDeg, int snr, boolean used) {
            this.prn = prn;
            this.elevationDeg = elevationDeg;
            this.azimuthDeg = azimuthDeg;
            this.snr = snr;
            this.used = used;
        }
    }

    private final List<Sat> satellites = new ArrayList<>();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint usedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unusedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density = getResources().getDisplayMetrics().density;

    public SatelliteRadarView(Context context) {
        super(context);
        init();
    }

    public SatelliteRadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_card_stroke));
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1f * density);
        crossPaint.setColor(0x33FFFFFF);
        usedPaint.setStyle(Paint.Style.FILL);
        usedPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_primary));
        unusedPaint.setStyle(Paint.Style.FILL);
        unusedPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_accent_blue));
        barPaint.setStyle(Paint.Style.STROKE);
        barPaint.setStrokeWidth(3f * density);
        barPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_primary));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(9f * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_text_secondary));
        emptyPaint.setTextSize(13f * density);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void submit(List<Sat> incoming) {
        satellites.clear();
        if (incoming != null) {
            satellites.addAll(incoming);
        }
        postInvalidate();
    }

    public void clear() {
        satellites.clear();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - 14f * density;
        if (radius <= 0) {
            return;
        }

        for (int ring = 1; ring <= 3; ring++) {
            canvas.drawCircle(centerX, centerY, radius * ring / 3f, gridPaint);
        }
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, crossPaint);
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, crossPaint);
        canvas.drawText("北", centerX, centerY - radius - 3f * density, textPaint);

        if (satellites.isEmpty()) {
            canvas.drawText(getContext().getString(R.string.satellite_radar_empty),
                    centerX, centerY + emptyPaint.getTextSize() / 2f, emptyPaint);
            return;
        }

        for (Sat sat : satellites) {
            double azimuth = Math.toRadians(sat.azimuthDeg);
            float distance = radius * (1f - Math.max(0, Math.min(90, sat.elevationDeg)) / 90f) * 0.96f;
            float x = centerX + (float) (distance * Math.sin(azimuth));
            float y = centerY - (float) (distance * Math.cos(azimuth));
            float barLength = 6f * density + (sat.snr / 50f) * 18f * density;
            barPaint.setColor(sat.used
                    ? ContextCompat.getColor(getContext(), R.color.color_primary)
                    : ContextCompat.getColor(getContext(), R.color.color_accent_blue));
            canvas.drawLine(x, y, x, y - barLength, barPaint);
            canvas.drawCircle(x, y, 4f * density, sat.used ? usedPaint : unusedPaint);
        }
    }
}
```

- [ ] **Step 7: 编译与全量测试**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -25
```
Expected：`BUILD SUCCESSFUL`（`R.styleable.*` 与所有图标资源被 View 引用，缺失会直接报错）。

- [ ] **Step 8: 检查点**

报告：构建结果；如缺少某个图标/drawable，补齐后重跑。

---

### Task 11: 主界面骨架与底部导航

**Files:**
- Create: `app/src/main/res/layout/activity_main.xml`（覆盖 Task 1 版本）
- Create: `app/src/main/res/menu/bottom_nav_menu.xml`
- Create: `app/src/main/res/layout/fragment_home.xml`、`fragment_satellite.xml`、`fragment_log.xml`、`fragment_settings.xml`
- Create: `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java`、`SatelliteFragment.java`、`LogFragment.java`、`SettingsFragment.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/MainActivity.java`（覆盖 Task 1 版本）
- Create: `app/src/main/res/layout/item_device.xml`、`item_satellite.xml`、`item_log.xml`
- Create: `app/src/main/java/com/gpsbt/transfer/ui/adapter/PairedDeviceAdapter.java`、`SatelliteAdapter.java`、`LogAdapter.java`

**Interfaces:**
- Consumes: `TransmitterState`、`PulseButtonView`、`SatelliteRadarView`、`StatCardView`
- Produces: `MainActivity`（4 个 Fragment 的宿主、权限入口 `ensurePermissions(Runnable)`）、`PairedDeviceAdapter(List<BluetoothDevice>, OnDeviceSelected)`、`SatelliteAdapter(List<SatelliteRadarView.Sat>)`、`LogAdapter(List<LogBus.Entry>)`

- [ ] **Step 1: 覆盖 `activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/app_bg">

    <FrameLayout
        android:id="@+id/fragment_container"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toTopOf="@id/bottom_nav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottom_nav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:background="@color/color_card"
        app:elevation="0dp"
        app:itemIconTint="@color/color_primary"
        app:itemTextColor="@color/color_text_secondary"
        app:labelVisibilityMode="labeled"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:menu="@menu/bottom_nav_menu" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

`res/menu/bottom_nav_menu.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/nav_home"
        android:icon="@drawable/ic_nav_home"
        android:title="@string/nav_home" />
    <item
        android:id="@+id/nav_satellite"
        android:icon="@drawable/ic_nav_satellite"
        android:title="@string/nav_satellite" />
    <item
        android:id="@+id/nav_log"
        android:icon="@drawable/ic_nav_log"
        android:title="@string/nav_log" />
    <item
        android:id="@+id/nav_settings"
        android:icon="@drawable/ic_nav_settings"
        android:title="@string/nav_settings" />
</menu>
```

- [ ] **Step 2: 覆盖 `MainActivity.java`**

`app/src/main/java/com/gpsbt/transfer/MainActivity.java`：
```java
package com.gpsbt.transfer;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.gpsbt.transfer.ui.fragment.HomeFragment;
import com.gpsbt.transfer.ui.fragment.LogFragment;
import com.gpsbt.transfer.ui.fragment.SatelliteFragment;
import com.gpsbt.transfer.ui.fragment.SettingsFragment;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private final Map<Integer, Fragment> cache = new HashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull android.view.MenuItem item) {
                switchTo(item.getItemId());
                return true;
            }
        });
        if (savedInstanceState == null) {
            switchTo(R.id.nav_home);
        } else {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (current == null) {
                switchTo(R.id.nav_home);
            }
        }
    }

    private void switchTo(int menuId) {
        Fragment fragment = cache.get(menuId);
        if (fragment == null) {
            if (menuId == R.id.nav_satellite) {
                fragment = new SatelliteFragment();
            } else if (menuId == R.id.nav_log) {
                fragment = new LogFragment();
            } else if (menuId == R.id.nav_settings) {
                fragment = new SettingsFragment();
            } else {
                fragment = new HomeFragment();
            }
            cache.put(menuId, fragment);
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    @Override
    public void onBackPressed() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav.getSelectedItemId() != R.id.nav_home) {
            nav.setSelectedItemId(R.id.nav_home);
        } else {
            super.onBackPressed();
        }
    }
}
```

- [ ] **Step 3: 写主页布局 `fragment_home.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <!-- 顶部标题与状态徽标 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    style="@style/TextAppearance.GpsBt.Title"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/home_title" />

                <TextView
                    style="@style/TextAppearance.GpsBt.Body"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/home_subtitle" />
            </LinearLayout>

            <TextView
                android:id="@+id/status_badge"
                style="@style/TextSecondary"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="@drawable/badge_background"
                android:paddingHorizontal="12dp"
                android:paddingVertical="6dp"
                android:text="@string/state_idle"
                android:textColor="@color/color_primary" />
        </LinearLayout>

        <!-- 连接方式 -->
        <TextView
            style="@style/SectionTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/home_mode_title" />

        <com.google.android.material.button.MaterialButtonToggleGroup
            android:id="@+id/mode_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:selectionRequired="true"
            app:singleSelection="true">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/mode_server"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:background="@drawable/mode_segment_background"
                android:text="@string/home_mode_server"
                android:textColor="@color/color_text_primary" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/mode_client"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:background="@drawable/mode_segment_background"
                android:text="@string/home_mode_client"
                android:textColor="@color/color_text_primary" />
        </com.google.android.material.button.MaterialButtonToggleGroup>

        <TextView
            android:id="@+id/mode_hint"
            style="@style/TextSecondary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/home_mode_server_hint"
            android:textSize="12sp" />

        <!-- 服务信息（服务器模式） -->
        <LinearLayout
            android:id="@+id/service_card"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/home_service_title" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <TextView
                    style="@style/TextSecondary"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/home_service_name" />

                <TextView
                    android:id="@+id/service_name_value"
                    style="@style/ValueText"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="13sp"
                    tools:ignore="MissingConstraints" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:orientation="horizontal">

                <TextView
                    style="@style/TextSecondary"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/home_service_uuid" />

                <TextView
                    android:id="@+id/uuid_value"
                    style="@style/ValueText"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="11sp" />
            </LinearLayout>
        </LinearLayout>

        <!-- 设备列表（客户端模式） -->
        <LinearLayout
            android:id="@+id/device_panel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp"
            android:visibility="gone">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <TextView
                    style="@style/SectionTitle"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/home_devices_title" />

                <Button
                    android:id="@+id/refresh_devices"
                    style="?attr/materialButtonOutlinedStyle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:minHeight="36dp"
                    android:text="@string/home_devices_refresh"
                    android:textSize="12sp" />
            </LinearLayout>

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/device_list"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp" />

            <TextView
                android:id="@+id/device_empty"
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="@string/home_devices_empty"
                android:textSize="12sp"
                android:visibility="gone" />
        </LinearLayout>

        <!-- 主按钮 -->
        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:layout_marginBottom="8dp">

            <com.gpsbt.transfer.ui.view.PulseButtonView
                android:id="@+id/pulse_button"
                android:layout_width="180dp"
                android:layout_height="180dp"
                android:layout_gravity="center_horizontal" />
        </FrameLayout>

        <TextView
            android:id="@+id/status_text"
            style="@style/TextSecondary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:text="@string/state_idle"
            android:textSize="13sp" />

        <!-- 运行数据 -->
        <TextView
            style="@style/SectionTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:text="@string/home_stats_title" />

        <GridLayout
            android:id="@+id/stats_grid"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:columnCount="2"
            android:rowCount="8" />

        <TextView
            style="@style/TextSecondary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/home_note"
            android:textSize="11sp" />
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

> 注意：`GridLayout` 中的 `StatCardView` 在 Task 12 用代码添加，因此此处无需在布局中声明。

- [ ] **Step 4: 写其余三个页面布局**

`fragment_satellite.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        style="@style/TextAppearance.GpsBt.Title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="12dp"
        android:text="@string/satellite_title" />

    <com.gpsbt.transfer.ui.view.SatelliteRadarView
        android:id="@+id/radar_view"
        android:layout_width="match_parent"
        android:layout_height="260dp"
        android:layout_marginBottom="12dp" />

    <TextView
        style="@style/SectionTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/satellite_list_title" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/satellite_list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <TextView
        android:id="@+id/satellite_empty"
        style="@style/TextSecondary"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/satellite_empty"
        android:visibility="gone" />
</LinearLayout>
```

`fragment_log.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        style="@style/TextAppearance.GpsBt.Title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="12dp"
        android:text="@string/log_title" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <Button
            android:id="@+id/log_copy"
            style="?attr/materialButtonOutlinedStyle"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/log_copy"
            android:textSize="12sp" />

        <Button
            android:id="@+id/log_clear"
            style="?attr/materialButtonOutlinedStyle"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:text="@string/log_clear"
            android:textSize="12sp" />

        <Button
            android:id="@+id/log_export"
            style="?attr/materialButtonOutlinedStyle"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:layout_weight="1"
            android:text="@string/log_export"
            android:textSize="12sp" />
    </LinearLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/log_list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="12dp"
        android:layout_weight="1" />

    <TextView
        android:id="@+id/log_empty"
        style="@style/TextSecondary"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:text="@string/log_empty"
        android:visibility="gone" />
</LinearLayout>
```

`fragment_settings.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            style="@style/TextAppearance.GpsBt.Title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginBottom="12dp"
            android:text="@string/settings_title" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                style="@style/SettingsRow"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/settings_rate" />

            <com.google.android.material.slider.Slider
                android:id="@+id/rate_slider"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:stepSize="1"
                android:valueFrom="1"
                android:valueTo="10"
                app:labelBehavior="gone"
                app:trackColorActive="@color/color_primary"
                app:trackColorInactive="@color/color_card_stroke"
                app:thumbColor="@color/color_primary" />

            <TextView
                android:id="@+id/rate_value"
                style="@style/ValueText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="13sp" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/settings_sentences" />

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/sw_gga"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="$GPGGA 定位信息" />

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/sw_rmc"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="$GPRMC 推荐定位" />

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/sw_gsa"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="$GPGSA 精度因子" />

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/sw_gsv"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="$GPGSV 可见卫星" />

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/sw_vtg"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="$GPVTG 速度航向" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/settings_service_title" />

            <com.google.android.material.textfield.TextInputLayout
                style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:hint="@string/home_service_name">

                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/input_service_name"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:inputType="text"
                    android:maxLines="1"
                    android:textColor="@color/color_text_primary" />
            </com.google.android.material.textfield.TextInputLayout>

            <com.google.android.material.textfield.TextInputLayout
                style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:hint="@string/home_service_uuid">

                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/input_uuid"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:inputType="text"
                    android:maxLines="1"
                    android:textColor="@color/color_text_primary" />
            </com.google.android.material.textfield.TextInputLayout>

            <Button
                android:id="@+id/btn_restore_defaults"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="@string/settings_defaults"
                android:textSize="12sp" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/settings_location_title" />

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/sw_allow_network"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/settings_allow_network" />

            <TextView
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/settings_allow_network_desc"
                android:textSize="11sp" />

            <TextView
                style="@style/SettingsRow"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="@string/settings_stale" />

            <com.google.android.material.slider.Slider
                android:id="@+id/stale_slider"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:stepSize="1"
                android:valueFrom="2"
                android:valueTo="30"
                app:labelBehavior="gone"
                app:trackColorActive="@color/color_primary"
                app:thumbColor="@color/color_primary" />

            <TextView
                android:id="@+id/stale_value"
                style="@style/ValueText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="13sp" />

            <TextView
                style="@style/SettingsRow"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="@string/settings_hdop" />

            <com.google.android.material.slider.Slider
                android:id="@+id/hdop_slider"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:stepSize="0.1"
                android:valueFrom="0.5"
                android:valueTo="9.9"
                app:labelBehavior="gone"
                app:trackColorActive="@color/color_primary"
                app:thumbColor="@color/color_primary" />

            <TextView
                android:id="@+id/hdop_value"
                style="@style/ValueText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="13sp" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/settings_about_title" />

            <TextView
                android:id="@+id/about_body"
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textSize="12sp" />
        </LinearLayout>
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

- [ ] **Step 5: 写三个列表项布局**

`item_device.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/ripple_soft"
    android:minHeight="52dp"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:paddingHorizontal="12dp"
    android:paddingVertical="8dp">

    <ImageView
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:src="@drawable/ic_stat_bluetooth"
        app:tint="@color/color_text_secondary"
        xmlns:app="http://schemas.android.com/apk/res-auto" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="10dp"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/device_name"
            style="@style/SettingsRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:maxLines="1"
            android:ellipsize="end" />

        <TextView
            android:id="@+id/device_mac"
            style="@style/TextSecondary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:textSize="11sp" />
    </LinearLayout>

    <TextView
        android:id="@+id/device_selected"
        style="@style/TextSecondary"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="✓"
        android:textColor="@color/color_primary"
        android:textSize="16sp"
        android:visibility="gone" />
</LinearLayout>
```

`item_satellite.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:minHeight="40dp"
    android:orientation="horizontal">

    <TextView
        android:id="@+id/sat_prn"
        style="@style/ValueText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="13sp" />

    <TextView
        android:id="@+id/sat_elevation"
        style="@style/ValueText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:gravity="center"
        android:textSize="13sp" />

    <TextView
        android:id="@+id/sat_azimuth"
        style="@style/ValueText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:textSize="13sp" />

    <TextView
        android:id="@+id/sat_snr"
        style="@style/ValueText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:textSize="13sp" />

    <TextView
        android:id="@+id/sat_used"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:gravity="center"
        android:textColor="@color/color_primary"
        android:textSize="13sp" />
</LinearLayout>
```

`item_log.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:paddingVertical="5dp">

    <TextView
        android:id="@+id/log_time"
        android:layout_width="62dp"
        android:layout_height="wrap_content"
        android:fontFamily="monospace"
        android:textColor="@color/color_text_secondary"
        android:textSize="11sp" />

    <TextView
        android:id="@+id/log_message"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:layout_weight="1"
        android:textColor="@color/color_text_primary"
        android:textSize="12sp" />
</LinearLayout>
```

- [ ] **Step 6: 写三个 Adapter**

`app/src/main/java/com/gpsbt/transfer/ui/adapter/PairedDeviceAdapter.java`：
```java
package com.gpsbt.transfer.ui.adapter;

import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.bt.BluetoothLinkManager;

import java.util.List;

public class PairedDeviceAdapter extends RecyclerView.Adapter<PairedDeviceAdapter.Holder> {

    public interface OnDeviceSelected {
        void onSelected(BluetoothDevice device);
    }

    private final List<BluetoothDevice> devices;
    private final OnDeviceSelected listener;
    private String selectedMac;

    public PairedDeviceAdapter(List<BluetoothDevice> devices, OnDeviceSelected listener) {
        this.devices = devices;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setSelectedMac(String mac) {
        selectedMac = mac;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final BluetoothDevice device = devices.get(position);
        String mac = device.getAddress();
        holder.name.setText(BluetoothLinkManager.displayName(device));
        holder.mac.setText(mac);
        holder.selected.setVisibility(mac.equals(selectedMac) ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSelected(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    @Override
    public long getItemId(int position) {
        return devices.get(position).getAddress().hashCode();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView mac;
        final TextView selected;

        Holder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.device_name);
            mac = itemView.findViewById(R.id.device_mac);
            selected = itemView.findViewById(R.id.device_selected);
        }
    }
}
```

`app/src/main/java/com/gpsbt/transfer/ui/adapter/SatelliteAdapter.java`：
```java
package com.gpsbt.transfer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;

import java.util.List;

public class SatelliteAdapter extends RecyclerView.Adapter<SatelliteAdapter.Holder> {

    private final List<SatelliteRadarView.Sat> satellites;

    public SatelliteAdapter(List<SatelliteRadarView.Sat> satellites) {
        this.satellites = satellites;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_satellite, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SatelliteRadarView.Sat sat = satellites.get(position);
        holder.prn.setText(String.format(java.util.Locale.US, "PRN %d", sat.prn));
        holder.elevation.setText(sat.elevationDeg + "°");
        holder.azimuth.setText(sat.azimuthDeg + "°");
        holder.snr.setText(sat.snr > 0 ? String.valueOf(sat.snr) : "--");
        holder.used.setText(sat.used
                ? holder.itemView.getContext().getString(R.string.satellite_yes)
                : holder.itemView.getContext().getString(R.string.satellite_no));
        holder.used.setTextColor(androidx.core.content.ContextCompat.getColor(
                holder.itemView.getContext(),
                sat.used ? R.color.color_primary : R.color.color_text_disabled));
    }

    @Override
    public int getItemCount() {
        return satellites.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView prn;
        final TextView elevation;
        final TextView azimuth;
        final TextView snr;
        final TextView used;

        Holder(View itemView) {
            super(itemView);
            prn = itemView.findViewById(R.id.sat_prn);
            elevation = itemView.findViewById(R.id.sat_elevation);
            azimuth = itemView.findViewById(R.id.sat_azimuth);
            snr = itemView.findViewById(R.id.sat_snr);
            used = itemView.findViewById(R.id.sat_used);
        }
    }
}
```

`app/src/main/java/com/gpsbt/transfer/ui/adapter/LogAdapter.java`：
```java
package com.gpsbt.transfer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.util.Formatters;

import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

    private final List<LogBus.Entry> entries;
    private int highlightColor = 0;

    public LogAdapter(List<LogBus.Entry> entries) {
        this.entries = entries;
        setHasStableIds(true);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        highlightColor = ContextCompat.getColor(parent.getContext(), R.color.color_error);
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LogBus.Entry entry = entries.get(position);
        holder.time.setText(Formatters.clockOfDay(entry.time));
        holder.message.setText(entry.message);
        int color = R.color.color_text_primary;
        if (entry.level == LogBus.Level.ERROR) {
            color = highlightColor;
        } else if (entry.level == LogBus.Level.WARN) {
            color = R.color.color_warning;
        } else if (entry.level == LogBus.Level.DATA) {
            color = R.color.color_text_secondary;
        }
        holder.message.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), color));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @Override
    public long getItemId(int position) {
        return entries.get(position).time * 31 + position;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView message;

        Holder(View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.log_time);
            message = itemView.findViewById(R.id.log_message);
        }
    }
}
```

- [ ] **Step 7: 写四个 Fragment 骨架**

四个 Fragment 仅做视图绑定，本任务不做数据联动（Task 12–14 补齐）。

`HomeFragment.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.ui.view.PulseButtonView;

public class HomeFragment extends Fragment {

    protected PulseButtonView pulseButton;
    protected TextView statusBadge;
    protected TextView statusText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        pulseButton = view.findViewById(R.id.pulse_button);
        statusBadge = view.findViewById(R.id.status_badge);
        statusText = view.findViewById(R.id.status_text);
    }
}
```

`SatelliteFragment.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;

import java.util.ArrayList;

public class SatelliteFragment extends Fragment {

    protected SatelliteRadarView radarView;
    protected RecyclerView listView;
    protected TextView emptyView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_satellite, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        radarView = view.findViewById(R.id.radar_view);
        listView = view.findViewById(R.id.satellite_list);
        emptyView = view.findViewById(R.id.satellite_empty);
        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        listView.setAdapter(new com.gpsbt.transfer.ui.adapter.SatelliteAdapter(new ArrayList<>()));
    }
}
```

`LogFragment.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.ui.adapter.LogAdapter;

import java.util.ArrayList;

public class LogFragment extends Fragment {

    protected RecyclerView listView;
    protected TextView emptyView;
    protected LogAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.log_list);
        emptyView = view.findViewById(R.id.log_empty);
        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new LogAdapter(new ArrayList<>());
        listView.setAdapter(adapter);
    }
}
```

`SettingsFragment.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gpsbt.transfer.R;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView about = view.findViewById(R.id.about_body);
        about.setText(getString(R.string.settings_about_body, "1.0.0"));
    }
}
```

- [ ] **Step 8: 编译验证**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -30
```
Expected：`BUILD SUCCESSFUL`。`item_device.xml` 中 `app:tint` 需要 `xmlns:app` 命名空间已经内联声明，若 AAPT 报错则把命名空间移到根节点。

- [ ] **Step 9: 检查点**

报告：构建结果；修复所有 XML/资源错误后再进入 Task 12。

---

### Task 12: 主页完整交互

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java`（覆盖 Task 11 版本）
- Create: `app/src/main/java/com/gpsbt/transfer/ui/fragment/StatsGridBuilder.java`

**Interfaces:**
- Consumes: `TransmitterState`、`Prefs`、`BluetoothLinkManager`、`GpsOutputService`、`StatCardView`
- Produces:
  - `HomeFragment.onStateChanged(TransmitterState)`（渲染全部状态）
  - `StatsGridBuilder.build(GridLayout, Map<String, StatCardView>)`

- [ ] **Step 1: 实现 `StatsGridBuilder`**

`app/src/main/java/com/gpsbt/transfer/ui/fragment/StatsGridBuilder.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.view.ViewGroup;
import android.widget.GridLayout;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.ui.view.StatCardView;

import java.util.LinkedHashMap;
import java.util.Map;

/** 在主页 GridLayout 中填充 10 张数据卡片。 */
final class StatsGridBuilder {

    static final String KEY_FIX = "fix";
    static final String KEY_SATELLITES = "satellites";
    static final String KEY_LATITUDE = "latitude";
    static final String KEY_LONGITUDE = "longitude";
    static final String KEY_SPEED = "speed";
    static final String KEY_BEARING = "bearing";
    static final String KEY_ALTITUDE = "altitude";
    static final String KEY_SENTENCES = "sentences";
    static final String KEY_RATE = "rate";
    static final String KEY_DEVICE = "device";

    private StatsGridBuilder() {
    }

    static void build(GridLayout grid, Map<String, StatCardView> target) {
        add(grid, target, KEY_FIX, R.string.stat_fix, R.drawable.ic_stat_location, R.string.value_no_fix);
        add(grid, target, KEY_SATELLITES, R.string.stat_satellites, R.drawable.ic_nav_satellite, R.string.value_unknown);
        add(grid, target, KEY_LATITUDE, R.string.stat_latitude, R.drawable.ic_stat_location, R.string.value_unknown);
        add(grid, target, KEY_LONGITUDE, R.string.stat_longitude, R.drawable.ic_stat_location, R.string.value_unknown);
        add(grid, target, KEY_SPEED, R.string.stat_speed, R.drawable.ic_stat_speed, R.string.value_unknown);
        add(grid, target, KEY_BEARING, R.string.stat_bearing, R.drawable.ic_stat_compass, R.string.value_unknown);
        add(grid, target, KEY_ALTITUDE, R.string.stat_altitude, R.drawable.ic_stat_altitude, R.string.value_unknown);
        add(grid, target, KEY_SENTENCES, R.string.stat_sentences, R.drawable.ic_stat_send, "0");
        add(grid, target, KEY_RATE, R.string.stat_rate, R.drawable.ic_stat_speed, "0");
        add(grid, target, KEY_DEVICE, R.string.stat_device, R.drawable.ic_stat_bluetooth, R.string.value_unknown);
    }

    private static void add(GridLayout grid, Map<String, StatCardView> target, String key,
                            int titleRes, int iconRes, Object value) {
        StatCardView card = new StatCardView(grid.getContext());
        card.setTitle(grid.getContext().getString(titleRes));
        card.setIcon(iconRes);
        card.setValue(value instanceof Integer
                ? grid.getContext().getString((Integer) value) : String.valueOf(value));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(6, 6, 6, 6);
        grid.addView(card, params);
        target.put(key, card);
    }
}
```

- [ ] **Step 2: 覆盖 `HomeFragment`**

`app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.bt.BluetoothLinkManager;
import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.service.GpsOutputService;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.adapter.PairedDeviceAdapter;
import com.gpsbt.transfer.ui.view.PulseButtonView;
import com.gpsbt.transfer.ui.view.StatCardView;
import com.gpsbt.transfer.util.Formatters;
import com.gpsbt.transfer.util.Prefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HomeFragment extends Fragment
        implements TransmitterState.Listener, PairedDeviceAdapter.OnDeviceSelected {

    private TransmitterState state;
    private Prefs prefs;

    private PulseButtonView pulseButton;
    private TextView statusBadge;
    private TextView statusText;
    private MaterialButtonToggleGroup modeGroup;
    private MaterialButton modeServer;
    private MaterialButton modeClient;
    private TextView modeHint;
    private View serviceCard;
    private TextView serviceNameValue;
    private TextView uuidValue;
    private View devicePanel;
    private RecyclerView deviceList;
    private TextView deviceEmpty;
    private Button refreshDevices;
    private GridLayout statsGrid;

    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final Map<String, StatCardView> cards = new LinkedHashMap<>();
    private PairedDeviceAdapter deviceAdapter;
    private String selectedMac;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = TransmitterState.get();
        prefs = Prefs.get(requireContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        pulseButton = view.findViewById(R.id.pulse_button);
        statusBadge = view.findViewById(R.id.status_badge);
        statusText = view.findViewById(R.id.status_text);
        modeGroup = view.findViewById(R.id.mode_group);
        modeServer = view.findViewById(R.id.mode_server);
        modeClient = view.findViewById(R.id.mode_client);
        modeHint = view.findViewById(R.id.mode_hint);
        serviceCard = view.findViewById(R.id.service_card);
        serviceNameValue = view.findViewById(R.id.service_name_value);
        uuidValue = view.findViewById(R.id.uuid_value);
        devicePanel = view.findViewById(R.id.device_panel);
        deviceList = view.findViewById(R.id.device_list);
        deviceEmpty = view.findViewById(R.id.device_empty);
        refreshDevices = view.findViewById(R.id.refresh_devices);
        statsGrid = view.findViewById(R.id.stats_grid);

        StatsGridBuilder.build(statsGrid, cards);

        deviceAdapter = new PairedDeviceAdapter(devices, this);
        deviceList.setLayoutManager(new LinearLayoutManager(getContext()));
        deviceList.setAdapter(deviceAdapter);

        modeGroup.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId, boolean isChecked) {
                if (!isChecked) {
                    return;
                }
                ConnectionMode mode = checkedId == R.id.mode_client
                        ? ConnectionMode.CLIENT : ConnectionMode.SERVER;
                prefs.setMode(mode);
                state.setMode(mode);
                renderPanels();
            }
        });

        refreshDevices.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadDevices();
            }
        });

        pulseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPulseClicked();
            }
        });

        selectedMac = prefs.getLastDeviceMac();
        loadDevices();
    }

    @Override
    public void onStart() {
        super.onStart();
        state.addListener(this);
        render(state);
    }

    @Override
    public void onStop() {
        state.removeListener(this);
        super.onStop();
    }

    @Override
    public void onStateChanged(TransmitterState changed) {
        if (!isAdded()) {
            return;
        }
        render(changed);
    }

    @Override
    public void onSelected(BluetoothDevice device) {
        selectedMac = device.getAddress();
        prefs.setLastDeviceMac(selectedMac);
        deviceAdapter.setSelectedMac(selectedMac);
    }

    private void onPulseClicked() {
        if (state.phase() == TransmitterState.Phase.RUNNING) {
            GpsOutputService.stop(requireContext());
            return;
        }
        ConnectionMode mode = prefs.getMode();
        if (mode == ConnectionMode.CLIENT && (selectedMac == null || selectedMac.isEmpty())) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.home_devices_title)
                    .setMessage(R.string.home_devices_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        ((MainCallbacks) requireActivity()).requestStartPermissions(new Runnable() {
            @Override
            public void run() {
                GpsOutputService.start(requireContext(), prefs.getMode(), selectedMac);
            }
        });
    }

    /** Activity 需实现的权限入口。 */
    public interface MainCallbacks {
        void requestStartPermissions(Runnable onGranted);
    }

    private void loadDevices() {
        devices.clear();
        try {
            devices.addAll(BluetoothLinkManager.bondedDevices());
        } catch (SecurityException e) {
            deviceEmpty.setText(R.string.perm_bluetooth_rationale);
            deviceEmpty.setVisibility(View.VISIBLE);
            deviceAdapter.notifyDataSetChanged();
            return;
        }
        deviceEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        deviceAdapter.setSelectedMac(selectedMac);
        deviceAdapter.notifyDataSetChanged();
    }

    private void render(TransmitterState snapshot) {
        boolean running = snapshot.phase() == TransmitterState.Phase.RUNNING;
        TransmitterState.Bt bt = snapshot.bt();

        int statusTextRes;
        int statusColor = R.color.color_text_secondary;
        if (!running) {
            statusTextRes = R.string.state_idle;
        } else if (bt == TransmitterState.Bt.CONNECTED) {
            statusTextRes = R.string.state_connected;
            statusColor = R.color.color_primary;
        } else if (bt == TransmitterState.Bt.WAITING) {
            statusTextRes = R.string.state_waiting;
            statusColor = R.color.color_accent_blue;
        } else if (bt == TransmitterState.Bt.CONNECTING) {
            statusTextRes = R.string.state_connecting;
            statusColor = R.color.color_accent_blue;
        } else if (bt == TransmitterState.Bt.FAILED) {
            statusTextRes = R.string.state_failed;
            statusColor = R.color.color_error;
        } else if (bt == TransmitterState.Bt.DISCONNECTED) {
            statusTextRes = R.string.state_failed;
            statusColor = R.color.color_warning;
        } else {
            statusTextRes = R.string.state_idle;
        }
        statusBadge.setText(statusTextRes);
        statusBadge.setTextColor(getResources().getColorStateList(statusColor, null));
        statusText.setText(snapshot.statusText().isEmpty()
                ? getString(statusTextRes) : snapshot.statusText());
        pulseButton.setState(running ? PulseButtonView.State.RUNNING : PulseButtonView.State.IDLE);

        ConnectionMode mode = snapshot.mode() == TransmitterState.Mode.CLIENT
                ? ConnectionMode.CLIENT : ConnectionMode.SERVER;
        if (mode == ConnectionMode.CLIENT) {
            modeGroup.check(R.id.mode_client);
        } else {
            modeGroup.check(R.id.mode_server);
        }
        renderPanels();
        serviceNameValue.setText(prefs.getServiceName());
        uuidValue.setText(prefs.getUuid());

        TransmitterState.Fix fix = snapshot.fix();
        StatCardView fixCard = cards.get(StatsGridBuilder.KEY_FIX);
        if (!running || fix == null) {
            fixCard.setValue(getString(R.string.value_no_fix));
            fixCard.setAccent(getResources().getColor(R.color.color_text_secondary, null));
        } else if (fix.valid) {
            fixCard.setValue(getString(R.string.value_fixed));
            fixCard.setAccent(getResources().getColor(R.color.color_primary, null));
        } else {
            fixCard.setValue(getString(R.string.value_no_fix));
            fixCard.setAccent(getResources().getColor(R.color.color_warning, null));
        }

        set(StatsGridBuilder.KEY_SATELLITES, fix == null ? getString(R.string.value_unknown)
                : fix.satellitesUsed + " / " + fix.satellitesInView + " " + getString(R.string.unit_satellites));
        set(StatsGridBuilder.KEY_LATITUDE, fix == null || !running ? getString(R.string.value_unknown)
                : Formatters.latitude(fix.latitude));
        set(StatsGridBuilder.KEY_LONGITUDE, fix == null || !running ? getString(R.string.value_unknown)
                : Formatters.longitude(fix.longitude));
        set(StatsGridBuilder.KEY_SPEED, fix == null || !running ? getString(R.string.value_unknown)
                : Formatters.speedKmh(fix.speedMps));
        set(StatsGridBuilder.KEY_BEARING, fix == null || !running ? getString(R.string.value_unknown)
                : Formatters.bearing(fix.bearingDeg));
        set(StatsGridBuilder.KEY_ALTITUDE, fix == null || !running ? getString(R.string.value_unknown)
                : Formatters.altitude(fix.altitudeM));
        set(StatsGridBuilder.KEY_SENTENCES, String.valueOf(running ? snapshot.stats().sentences : 0));
        set(StatsGridBuilder.KEY_RATE, String.format(java.util.Locale.US, "%.1f %s",
                running ? snapshot.stats().rateHz : 0d, getString(R.string.unit_rate)));
        TransmitterState.Device device = snapshot.connectedDevice();
        set(StatsGridBuilder.KEY_DEVICE, device == null ? getString(R.string.value_unknown) : device.name);
    }

    private void set(String key, String value) {
        StatCardView card = cards.get(key);
        if (card != null) {
            card.setValue(value);
        }
    }

    private void renderPanels() {
        boolean client = prefs.getMode() == ConnectionMode.CLIENT;
        serviceCard.setVisibility(client ? View.GONE : View.VISIBLE);
        devicePanel.setVisibility(client ? View.VISIBLE : View.GONE);
        modeHint.setText(client ? R.string.home_mode_client_hint : R.string.home_mode_server_hint);
        modeServer.setTextColor(getResources().getColorStateList(
                client ? R.color.color_text_secondary : R.color.color_primary, null));
        modeClient.setTextColor(getResources().getColorStateList(
                client ? R.color.color_primary : R.color.color_text_secondary, null));
    }
}
```

- [ ] **Step 3: 编译验证**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -30
```
Expected：`BUILD SUCCESSFUL`（Task 13 前 `requestStartPermissions` 尚未在 Activity 实现，编译期不报错，运行期才需要；因此编译应通过）。

- [ ] **Step 4: 检查点**

报告：构建结果。

---

### Task 13: 权限与蓝牙开启流程

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/MainActivity.java`（实现 `HomeFragment.MainCallbacks`）
- Modify: `app/src/main/AndroidManifest.xml`（`<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28"/>`，供 Task 14 日志导出使用）

**Interfaces:**
- Produces: `MainActivity.requestStartPermissions(Runnable)`：依次处理定位 → 蓝牙连接 → 通知权限 → 蓝牙开关，全部满足后回调

- [ ] **Step 1: 覆盖 `MainActivity`**

`app/src/main/java/com/gpsbt/transfer/MainActivity.java`：
```java
package com.gpsbt.transfer;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.gpsbt.transfer.bt.BluetoothLinkManager;
import com.gpsbt.transfer.ui.fragment.HomeFragment;
import com.gpsbt.transfer.ui.fragment.LogFragment;
import com.gpsbt.transfer.ui.fragment.SatelliteFragment;
import com.gpsbt.transfer.ui.fragment.SettingsFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements HomeFragment.MainCallbacks {

    private static final int REQUEST_ENABLE_BT = 0x51;

    private final Map<Integer, androidx.fragment.app.Fragment> cache = new HashMap<>();
    private Runnable pendingStart;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> enableBluetoothLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                new androidx.activity.result.ActivityResultCallback<Map<String, Boolean>>() {
                    @Override
                    public void onActivityResult(Map<String, Boolean> result) {
                        boolean denied = false;
                        for (Boolean granted : result.values()) {
                            if (granted == null || !granted) {
                                denied = true;
                            }
                        }
                        if (denied) {
                            showRationale();
                            pendingStart = null;
                            return;
                        }
                        continueStart();
                    }
                });

        enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new androidx.activity.result.ActivityResultCallback<androidx.activity.result.ActivityResult>() {
                    @Override
                    public void onActivityResult(androidx.activity.result.ActivityResult result) {
                        if (BluetoothLinkManager.isBluetoothAvailable()) {
                            continueStart();
                        } else {
                            showBluetoothOffDialog();
                        }
                    }
                });

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull android.view.MenuItem item) {
                switchTo(item.getItemId());
                return true;
            }
        });
        if (savedInstanceState == null) {
            switchTo(R.id.nav_home);
        } else if (getSupportFragmentManager().findFragmentById(R.id.fragment_container) == null) {
            switchTo(R.id.nav_home);
        }
    }

    private void switchTo(int menuId) {
        androidx.fragment.app.Fragment fragment = cache.get(menuId);
        if (fragment == null) {
            if (menuId == R.id.nav_satellite) {
                fragment = new SatelliteFragment();
            } else if (menuId == R.id.nav_log) {
                fragment = new LogFragment();
            } else if (menuId == R.id.nav_settings) {
                fragment = new SettingsFragment();
            } else {
                fragment = new HomeFragment();
            }
            cache.put(menuId, fragment);
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    @Override
    public void onBackPressed() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav.getSelectedItemId() != R.id.nav_home) {
            nav.setSelectedItemId(R.id.nav_home);
        } else {
            super.onBackPressed();
        }
    }

    // ---------- 权限入口 ----------

    @Override
    public void requestStartPermissions(Runnable onGranted) {
        pendingStart = onGranted;
        List<String> missing = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!missing.isEmpty()) {
            permissionLauncher.launch(missing.toArray(new String[0]));
            return;
        }
        ensureBluetoothEnabled();
    }

    private void continueStart() {
        ensureBluetoothEnabled();
    }

    private void ensureBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_location_title)
                    .setMessage("此设备没有蓝牙硬件，无法传输 GPS 数据。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            pendingStart = null;
            return;
        }
        if (!adapter.isEnabled()) {
            // 只弹系统授权对话框，由用户决定是否开启，绝不静默开启
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        runPending();
    }

    private void runPending() {
        Runnable action = pendingStart;
        pendingStart = null;
        if (action != null) {
            action.run();
        }
    }

    private void showBluetoothOffDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.state_bluetooth_off)
                .setMessage("请在系统设置中开启蓝牙后重试。")
                .setPositiveButton(R.string.perm_grant, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        pendingStart = null;
    }

    private void showRationale() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_location_title)
                .setMessage(R.string.perm_location_rationale)
                .setPositiveButton(R.string.perm_grant, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        requestStartPermissions(pendingStart == null ? new Runnable() {
                            @Override
                            public void run() {
                                // 空操作
                            }
                        } : pendingStart);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
```

- [ ] **Step 2: Manifest 增加外部存储权限**

在 `<application>` 前追加：
```xml
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
```

- [ ] **Step 3: 编译与测试**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -30
```
Expected：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 检查点**

报告：构建结果；确认 `REQUEST_ENABLE_BT` 常量若未使用需删除以避免 lint 警告。

---

### Task 14: 卫星页、日志页、设置页数据绑定

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java`（新增 `setSatellites(List)` / `satellites()`）
- Modify: `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java`（`onFix` 中同步卫星列表）
- Create: `app/src/main/java/com/gpsbt/transfer/util/LogExporter.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/SatelliteFragment.java`（覆盖）
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/LogFragment.java`（覆盖）
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/SettingsFragment.java`（覆盖）

**Interfaces:**
- Produces:
  - `TransmitterState.setSatellites(List<SatelliteRadarView.Sat>)`、`satellites()`
  - `LogExporter.export(Context)` → 返回文件路径字符串，失败抛 `IOException`
  - `SatelliteFragment`/`LogFragment` 订阅状态与日志；`SettingsFragment` 读写 `Prefs`

- [ ] **Step 1: 状态中心追加卫星列表**

在 `TransmitterState` 的字段区追加：
```java
    private volatile List<SatelliteRadarView.Sat> satellites = java.util.Collections.emptyList();
```
在 getter 区追加：
```java
    public List<SatelliteRadarView.Sat> satellites() {
        return satellites;
    }
```
在 setter 区追加：
```java
    public void setSatellites(List<SatelliteRadarView.Sat> value) {
        satellites = value == null ? java.util.Collections.<SatelliteRadarView.Sat>emptyList()
                : java.util.Collections.unmodifiableList(new ArrayList<>(value));
        notifyListeners();
    }
```
并新增 import：
```java
import com.gpsbt.transfer.ui.view.SatelliteRadarView;
```

- [ ] **Step 2: 服务中同步卫星**

在 `GpsOutputService.locationCallback.onFix` 的 `state.setFix(fix);` 之后追加：
```java
            List<SatelliteRadarView.Sat> converted = new java.util.ArrayList<>();
            for (NmeaInput.Sat sat : full.satellites()) {
                converted.add(new SatelliteRadarView.Sat(sat.prn, sat.elevationDeg,
                        sat.azimuthDeg, sat.snr, sat.used));
            }
            state.setSatellites(converted);
```
并新增 import `com.gpsbt.transfer.ui.view.SatelliteRadarView;`。

- [ ] **Step 3: 实现 `LogExporter`**

`app/src/main/java/com/gpsbt/transfer/util/LogExporter.java`：
```java
package com.gpsbt.transfer.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.gpsbt.transfer.state.LogBus;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 把内存日志导出为文本文件，返回可读路径。 */
public final class LogExporter {

    private LogExporter() {
    }

    public static String export(Context context) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("# GPS蓝牙传输 日志\r\n");
        for (LogBus.Entry entry : LogBus.get().entries()) {
            builder.append(Formatters.clockOfDay(entry.time))
                    .append("\t").append(entry.level.name())
                    .append("\t").append(entry.message)
                    .append("\r\n");
        }
        byte[] data = builder.toString().getBytes(StandardCharsets.UTF_8);
        String name = "GpsBt_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".log";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建下载目录文件");
            }
            OutputStream stream = context.getContentResolver().openOutputStream(uri);
            try {
                stream.write(data);
            } finally {
                close(stream);
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
        }

        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        File target = new File(directory, name);
        OutputStream stream = new FileOutputStream(target);
        try {
            stream.write(data);
        } finally {
            close(stream);
        }
        return target.getAbsolutePath();
    }

    private static void close(OutputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
```

- [ ] **Step 4: 覆盖 `SatelliteFragment`**

`app/src/main/java/com/gpsbt/transfer/ui/fragment/SatelliteFragment.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.state.TransmitterState;
import com.gpsbt.transfer.ui.adapter.SatelliteAdapter;
import com.gpsbt.transfer.ui.view.SatelliteRadarView;

import java.util.ArrayList;
import java.util.List;

public class SatelliteFragment extends Fragment implements TransmitterState.Listener {

    private TransmitterState state;
    private SatelliteRadarView radarView;
    private RecyclerView listView;
    private TextView emptyView;
    private SatelliteAdapter adapter;
    private final List<SatelliteRadarView.Sat> data = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_satellite, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = TransmitterState.get();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        radarView = view.findViewById(R.id.radar_view);
        listView = view.findViewById(R.id.satellite_list);
        emptyView = view.findViewById(R.id.satellite_empty);
        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SatelliteAdapter(data);
        listView.setAdapter(adapter);
    }

    @Override
    public void onStart() {
        super.onStart();
        state.addListener(this);
        render();
    }

    @Override
    public void onStop() {
        state.removeListener(this);
        super.onStop();
    }

    @Override
    public void onStateChanged(TransmitterState changed) {
        render();
    }

    private void render() {
        List<SatelliteRadarView.Sat> latest = state.satellites();
        data.clear();
        if (state.phase() == TransmitterState.Phase.RUNNING) {
            data.addAll(latest);
        }
        adapter.notifyDataSetChanged();
        radarView.submit(data);
        emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
```

- [ ] **Step 5: 覆盖 `LogFragment`**

`app/src/main/java/com/gpsbt/transfer/ui/fragment/LogFragment.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.ui.adapter.LogAdapter;
import com.gpsbt.transfer.util.LogExporter;

import java.util.ArrayList;
import java.util.List;

public class LogFragment extends Fragment implements LogBus.Listener {

    private RecyclerView listView;
    private TextView emptyView;
    private View copyButton;
    private View clearButton;
    private View exportButton;
    private LogAdapter adapter;
    private final List<LogBus.Entry> entries = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.log_list);
        emptyView = view.findViewById(R.id.log_empty);
        copyButton = view.findViewById(R.id.log_copy);
        clearButton = view.findViewById(R.id.log_clear);
        exportButton = view.findViewById(R.id.log_export);

        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new LogAdapter(entries);
        listView.setAdapter(adapter);

        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        });
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogBus.get().clear();
                entries.clear();
                adapter.notifyDataSetChanged();
                renderEmpty();
            }
        });
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        LogBus.get().addListener(this);
        entries.clear();
        entries.addAll(LogBus.get().entries());
        adapter.notifyDataSetChanged();
        if (!entries.isEmpty()) {
            listView.scrollToPosition(entries.size() - 1);
        }
        renderEmpty();
    }

    @Override
    public void onStop() {
        LogBus.get().removeListener(this);
        super.onStop();
    }

    @Override
    public void onLog(LogBus.Entry entry) {
        if (!isAdded()) {
            return;
        }
        entries.add(entry);
        adapter.notifyItemInserted(entries.size() - 1);
        listView.scrollToPosition(entries.size() - 1);
        renderEmpty();
    }

    private void renderEmpty() {
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void copy() {
        StringBuilder builder = new StringBuilder();
        for (LogBus.Entry entry : LogBus.get().entries()) {
            builder.append(com.gpsbt.transfer.util.Formatters.clockOfDay(entry.time))
                    .append(' ').append(entry.level.name())
                    .append(' ').append(entry.message).append('\n');
        }
        android.content.ClipboardManager manager =
                (android.content.ClipboardManager) requireContext()
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.app_name), builder.toString()));
        }
        Toast.makeText(getContext(), R.string.log_copied, Toast.LENGTH_SHORT).show();
    }

    private void export() {
        try {
            String path = LogExporter.export(requireContext());
            Toast.makeText(getContext(), getString(R.string.log_exported, path),
                    Toast.LENGTH_LONG).show();
            LogBus.get().log(LogBus.Level.INFO, "日志已导出：" + path);
        } catch (java.io.IOException e) {
            Toast.makeText(getContext(), getString(R.string.log_export_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            LogBus.get().log(LogBus.Level.ERROR, "日志导出失败：" + e.getMessage());
        }
    }
}
```

- [ ] **Step 6: 覆盖 `SettingsFragment`**

`app/src/main/java/com/gpsbt/transfer/ui/fragment/SettingsFragment.java`：
```java
package com.gpsbt.transfer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.gpsbt.transfer.R;
import com.gpsbt.transfer.nmea.NmeaSentenceType;
import com.gpsbt.transfer.util.Prefs;

import java.util.EnumSet;
import java.util.Set;

public class SettingsFragment extends Fragment {

    private Prefs prefs;

    private Slider rateSlider;
    private TextView rateValue;
    private MaterialSwitch swGga;
    private MaterialSwitch swRmc;
    private MaterialSwitch swGsa;
    private MaterialSwitch swGsv;
    private MaterialSwitch swVtg;
    private TextInputEditText inputServiceName;
    private TextInputEditText inputUuid;
    private Button restoreDefaults;
    private MaterialSwitch swAllowNetwork;
    private Slider staleSlider;
    private TextView staleValue;
    private Slider hdopSlider;
    private TextView hdopValue;
    private TextView about;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rateSlider = view.findViewById(R.id.rate_slider);
        rateValue = view.findViewById(R.id.rate_value);
        swGga = view.findViewById(R.id.sw_gga);
        swRmc = view.findViewById(R.id.sw_rmc);
        swGsa = view.findViewById(R.id.sw_gsa);
        swGsv = view.findViewById(R.id.sw_gsv);
        swVtg = view.findViewById(R.id.sw_vtg);
        inputServiceName = view.findViewById(R.id.input_service_name);
        inputUuid = view.findViewById(R.id.input_uuid);
        restoreDefaults = view.findViewById(R.id.btn_restore_defaults);
        swAllowNetwork = view.findViewById(R.id.sw_allow_network);
        staleSlider = view.findViewById(R.id.stale_slider);
        staleValue = view.findViewById(R.id.stale_value);
        hdopSlider = view.findViewById(R.id.hdop_slider);
        hdopValue = view.findViewById(R.id.hdop_value);
        about = view.findViewById(R.id.about_body);

        about.setText(getString(R.string.settings_about_body, "1.0.0"));

        rateSlider.setValue(Math.max(1, prefs.getRateHz()));
        rateValue.setText(getString(R.string.settings_rate_value, prefs.getRateHz()));
        rateSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                int hz = Math.max(1, Math.round(value));
                prefs.setRateHz(hz);
                rateValue.setText(getString(R.string.settings_rate_value, hz));
            }
        });

        Set<NmeaSentenceType> enabled = prefs.getSentences();
        swGga.setChecked(enabled.contains(NmeaSentenceType.GGA));
        swRmc.setChecked(enabled.contains(NmeaSentenceType.RMC));
        swGsa.setChecked(enabled.contains(NmeaSentenceType.GSA));
        swGsv.setChecked(enabled.contains(NmeaSentenceType.GSV));
        swVtg.setChecked(enabled.contains(NmeaSentenceType.VTG));

        MaterialSwitch.OnCheckedChangeListener sentenceListener =
                new MaterialSwitch.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(@NonNull MaterialSwitch buttonView, boolean isChecked) {
                        saveSentences();
                    }
                };
        swGga.setOnCheckedChangeListener(sentenceListener);
        swRmc.setOnCheckedChangeListener(sentenceListener);
        swGsa.setOnCheckedChangeListener(sentenceListener);
        swGsv.setOnCheckedChangeListener(sentenceListener);
        swVtg.setOnCheckedChangeListener(sentenceListener);

        inputServiceName.setText(prefs.getServiceName());
        inputUuid.setText(prefs.getUuid());
        inputServiceName.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    prefs.setServiceName(inputServiceName.getText() == null
                            ? Prefs.DEFAULT_SERVICE_NAME : inputServiceName.getText().toString());
                }
            }
        });
        inputUuid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    prefs.setUuid(inputUuid.getText() == null
                            ? Prefs.DEFAULT_UUID : inputUuid.getText().toString());
                }
            }
        });

        restoreDefaults.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setServiceName(Prefs.DEFAULT_SERVICE_NAME);
                prefs.setUuid(Prefs.DEFAULT_UUID);
                inputServiceName.setText(prefs.getServiceName());
                inputUuid.setText(prefs.getUuid());
            }
        });

        swAllowNetwork.setChecked(prefs.isAllowNetwork());
        swAllowNetwork.setOnCheckedChangeListener(new MaterialSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull com.google.android.material.materialswitch.MaterialSwitch buttonView,
                                         boolean isChecked) {
                prefs.setAllowNetwork(isChecked);
            }
        });

        staleSlider.setValue(prefs.getStaleMillis() / 1000f);
        staleValue.setText(getString(R.string.settings_stale_value,
                Math.round(prefs.getStaleMillis() / 1000f)));
        staleSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                prefs.setStaleMillis((long) (Math.round(value) * 1000L));
                staleValue.setText(getString(R.string.settings_stale_value, Math.round(value)));
            }
        });

        hdopSlider.setValue(prefs.getDefaultHdop());
        hdopValue.setText(getString(R.string.settings_hdop_value, prefs.getDefaultHdop()));
        hdopSlider.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                prefs.setDefaultHdop(value);
                hdopValue.setText(getString(R.string.settings_hdop_value, value));
            }
        });
    }

    private void saveSentences() {
        Set<NmeaSentenceType> result = EnumSet.noneOf(NmeaSentenceType.class);
        if (swGga.isChecked()) {
            result.add(NmeaSentenceType.GGA);
        }
        if (swRmc.isChecked()) {
            result.add(NmeaSentenceType.RMC);
        }
        if (swGsa.isChecked()) {
            result.add(NmeaSentenceType.GSA);
        }
        if (swGsv.isChecked()) {
            result.add(NmeaSentenceType.GSV);
        }
        if (swVtg.isChecked()) {
            result.add(NmeaSentenceType.VTG);
        }
        if (result.isEmpty()) {
            result = NmeaSentenceType.all();
        }
        prefs.setSentences(result);
    }
}
```

- [ ] **Step 7: 编译与全量测试**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -30
```
Expected：`BUILD SUCCESSFUL`。

- [ ] **Step 8: 检查点**

报告：构建结果；确认 `LogExporter` 仅保留 `export(Context)` 与 `close(OutputStream)` 两个方法。

---

### Task 15: 全量验证与静态检查

**Files:**
- Modify: 按验证结果修正所有源文件

**Interfaces:**
- Consumes: 全部已有文件

- [ ] **Step 1: 运行单元测试并输出报告**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --console=plain 2>&1 | tail -25
```
Expected：`BUILD SUCCESSFUL`，测试报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。

- [ ] **Step 2: 生成 Release 构建验证混淆与资源打包**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleRelease --console=plain 2>&1 | tail -20
```
Expected：`BUILD SUCCESSFUL`（未签名，仅验证可构建）。

- [ ] **Step 3: 运行 Lint 检查关键问题**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew lintDebug --console=plain 2>&1 | tail -40
```
Expected：无 Error 级别问题。忽略 Warning（如未使用的资源、可访问性建议）；若出现 `MissingPermission` 相关 Error，为相应调用补充 `ContextCompat.checkSelfPermission` 守卫或 `@SuppressLint` 并注释原因。

- [ ] **Step 4: 校验「不自动运行」契约**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && grep -rn "BOOT_COMPLETED\|RECEIVE_BOOT_COMPLETED" app/src/main || echo "OK: 无开机自启相关代码"
```
Expected：输出 `OK: 无开机自启相关代码`。

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && grep -n "GpsOutputService" -A 3 app/src/main/AndroidManifest.xml
```
Expected：`android:exported="false"` 与 `android:foregroundServiceType="location"` 均存在，且 `<service>` 之外无任何 `<receiver>`。

- [ ] **Step 5: 校验中文界面完整性**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && grep -rn 'android:text="[A-Za-z]' app/src/main/res/layout | grep -v '\\$GP' | head -20 && echo "---" ; ls app/src/main/res | grep -i values
```
Expected：布局中除 `$GPGGA` 这类协议名称外无英文字面量；`values` 目录下**没有** `values-en` 等其它语言目录。

- [ ] **Step 6: 修正并复核**

根据 Step 1–5 的结果修正问题，然后重跑：
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -20
```
Expected：`BUILD SUCCESSFUL`。

- [ ] **Step 7: 检查点**

报告：测试通过数、Lint Error 数、APK 路径与大小。

---

### Task 16: 交付文档与真机调试指南

**Files:**
- Create: `README.md`
- Create: `docs/USAGE.md`

**Interfaces:**
- Consumes: 最终代码与 APK

- [ ] **Step 1: 写 `README.md`**

内容必须包含（用中文，标题层级清晰）：
1. 软件简介：把手机 GPS 以 NMEA 0183 通过蓝牙 SPP 输出给其他设备
2. 功能特性列表（两种连接方式、1–10Hz、五类 NMEA 语句、前台服务、卫星星图、日志导出）
3. **重要说明**：不支持开机自启，每次使用需手动点击「开始传输」
4. 构建环境要求：JDK 21、Android SDK（platform 34、build-tools 35.0.0）、Gradle 8.12
5. 构建命令：
   ```bash
   JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
   ```
   产物：`app/build/outputs/apk/debug/app-debug.apk`
6. 权限说明表（定位、蓝牙连接、通知、外部存储 ≤28）
7. 目录结构（源码树）
8. 单元测试命令与覆盖范围

- [ ] **Step 2: 写 `docs/USAGE.md`（真机调试指南）**

内容必须包含：
1. **安装**：`adb install -r app-debug.apk`，首次运行授予定位 / 附近的设备 / 通知权限
2. **服务器模式使用步骤**：手机点「等待被连接 → 开始传输」→ 状态显示「等待设备连接」→ 在笔记本/平板上搜索并配对手机 → 对端按 SPP 连接 → 收到 NMEA 数据
3. **客户端模式使用步骤**：先在系统蓝牙中配对目标设备 → App 切到「主动连接」→ 选择设备 → 开始传输
4. **Windows 接收端验证**：
   - 控制面板 → 设备和打印机 → 手机属性 → 「服务」页确认串行端口（传出/传入）
   - `mode` 命令查看分配的 COM 口，用串口助手/PuTTY 以任意波特率打开该 COM 口
   - 期望看到形如：
     ```
     $GPGGA,012345.00,3113.8250,N,12128.4221,E,1,07,1.0,12.5,M,0.0,M,,*4B
     $GPRMC,012345.00,A,3113.8250,N,12128.4221,E,10.0,87.0,260923,,,A*7C
     ```
     （句中校验和以实际输出为准；可把语句粘到任意 NMEA 校验和工具核对）
5. **常见问题**：
   - 收不到数据：确认 UUID 为 `00001101-0000-1000-8000-00805F9B34FB`；确认对端连的是"串行端口"服务而非拨号网络
   - 状态卡在"等待设备连接"：对端需要**主动发起**连接
   - "无定位信号"：到室外等 GPS 冷启动 1–3 分钟，或确认未开启"仅限省电模式"
   - 部分车机只能被连、不能被连：改用「主动连接」模式
   - 通知被关闭后仍可传输，只是看不到状态；在通知里点「停止传输」立即停止
6. **停止与省电**：点「停止传输」或通知中的「停止传输」立即释放蓝牙与 GPS；退出界面同样会停止

- [ ] **Step 3: 复核文档准确性**

逐条核对文档中的命令、路径、默认值是否与代码一致（`Prefs.DEFAULT_*`、包名、权限表）。发现不一致先改文档或代码，再继续。

- [ ] **Step 4: 最终全量验证**

Run:
```bash
cd "工作区根目录/GPS蓝牙传输" && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest assembleDebug --console=plain 2>&1 | tail -20 && ls -la app/build/outputs/apk/debug/
```
Expected：`BUILD SUCCESSFUL` 且列出 APK。

- [ ] **Step 5: 交付清单**

确认交付物齐全：
- [ ] `app-debug.apk` 可安装包
- [ ] 完整 Android Studio 工程（`gradlew`、`settings.gradle`、`app/`）
- [ ] 单元测试（JVM，无需模拟器）
- [ ] `README.md` 与 `docs/USAGE.md`
- [ ] `docs/superpowers/specs/` 设计文档与 `docs/superpowers/plans/` 实现计划

---

## 完成标准

- `./gradlew testDebugUnitTest assembleDebug` 返回 `BUILD SUCCESSFUL`
- 所有 NMEA 与发送引擎单元测试通过
- Lint 无 Error 级问题
- 源码中不存在 `BOOT_COMPLETED` / `RECEIVE_BOOT_COMPLETED`
- 界面资源无英文字面量、无 `values-en` 等其它语言目录
- APK 可安装并在真机上按 `docs/USAGE.md` 完成接收端验证（由用户执行）







