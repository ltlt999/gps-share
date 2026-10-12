# GPS蓝牙传输

把 Android 手机的 GPS 定位，按**标准 NMEA 0183** 格式，通过**蓝牙串口（SPP / RFCOMM）**实时输出给其他设备。接收端（笔记本、平板、车机、工控机）把本机当成一台普通蓝牙 GPS 外设即可使用。

界面全中文，深色科技风。**默认不自动运行**；开启「设置 → 开机自动开始传输」后，重启手机将自动开始传输。

---

## 界面截图

主界面——脉冲式「开始传输」按钮 + 连接方式切换，首屏即可见：

![传输主页](docs/screenshots/1-home.png)

串口服务信息与实时运行数据卡片（定位状态、卫星数、经纬度、速度、航向、海拔、已发语句、速率、连接设备）：

![运行数据](docs/screenshots/2-stats.png)

卫星页——天空雷达按方位/仰角定位卫星，柱高表示信噪比：

![卫星星图](docs/screenshots/3-satellite.png)

日志页——实时记录连接与发送过程，可复制、清空或导出到系统「下载」目录：

![运行日志](docs/screenshots/4-log.png)

设置页——发送频率、NMEA 语句开关、蓝牙服务名与 UUID、定位来源、开机自启：

![设置](docs/screenshots/5-settings.png)

---

## 功能特性

| 功能 | 说明 |
|---|---|
| 两种蓝牙连接方式 | 「等待被连接」（手机做 SPP 服务端）与「主动连接」（手机连已配对设备），一键切换 |
| 标准 NMEA 输出 | `$GPGGA` / `$GPRMC` / `$GPGSA` / `$GPGSV` / `$GPVTG` / `$GPZDA`，含 XOR 校验和，`\r\n` 结尾 |
| 按星座分组发送 | GSV 按卫星系统分组（`$GP`/`$BD`/`$GL`/`$GA`/`$GI` talker），接收端可准确区分 GPS/北斗/GLONASS/Galileo |
| 发送频率可调 | 1–10 Hz，滑块实时调节 |
| 无定位保活 | 无定位时每秒发一帧 `$GPZDA` 时间帧，链路始终有数据流动，断链 1 秒内暴露 |
| GPS 优先 / 网络兜底 | 「允许网络定位」开启时：GPS 正常只用 GPS；连续 10 秒无 GPS 定位自动切 WiFi/基站兜底，恢复后自动切回 |
| 断线自动恢复 | 服务器模式断线后回到监听等待重连（读取线程检测对端断开）；客户端模式 5 秒后自动重连 |
| 重复启动防护 | 重复的启动请求会先清理旧流水线，避免两个监听服务并存导致「两端已连接但收不到数据」 |
| 后台持续传输 | 前台服务 + 常驻通知，切后台/锁屏仍发送，通知内可一键停止 |
| 实时运行数据 | 定位状态、卫星数、经纬度、速度、航向、海拔、已发语句、速率、连接设备 |
| 卫星星图 | 天空雷达（方位/仰角/信噪比）+ 卫星明细列表 |
| 运行日志 | 实时日志，支持复制、清空、导出；按「普通 / 卫星 / 数据 / 全部」分类筛选 |
| 最低兼容 Android 7.0 | minSdk 24，覆盖绝大多数在用设备 |
| 开机自启（可选，默认关） | 开启后每次重启自动按上次的连接方式与设备开始传输 |
| 链路保活（无定位时） | 无 GPS 定位时每秒发一帧 `$GPZDA` 时间帧，保持链路有数据流动；死链 1 秒内暴露 |
| 断线自动重连 | 连接断开 / 写入失败后 5 秒自动重新监听或重连，服务不再因断链退出 |

## 技术栈

- 纯 **Java + XML 布局**（无 Kotlin、无 Compose）
- Material Components 3（XML 主题）、ConstraintLayout、RecyclerView
- `minSdk 24` / `targetSdk 34` / `compileSdk 34`
- Gradle 8.12 + Android Gradle Plugin 8.7.3，Java 17 源码兼容
- JUnit 4 单元测试（纯 JVM，无需模拟器）

## 重要说明：不自动运行

### 默认不自动运行，开关打开后开机自启

**默认状态下没有任何后台自动行为**：

1. `AndroidManifest.xml` 虽声明了 `BootReceiver`，但开机自启开关**默认关闭**，关闭时接收器什么都不做。
2. 传输服务（`GpsOutputService`）`android:exported="false"`，除了系统 `BOOT_COMPLETED` 与用户点击按钮外，没有其它入口。
3. 服务使用 `START_NOT_STICKY`，被系统杀掉后**不会**自动重启。
4. 点「停止传输」或通知里的「停止传输」会立即停止服务、断开蓝牙、释放 GPS 监听；退出界面同样停止。
5. 蓝牙未开启时只弹系统授权对话框，由用户决定是否开启，不会静默开启。

**开启「设置 → 开机自动开始传输」后**，每次重启会按上次的连接方式与设备自动开始传输。约束如下：

| 系统版本 | 行为 |
|---|---|
| Android 7.0 – 11 | 完全自动，无需任何操作 |
| Android 12 及以上 | 系统**禁止后台启动的前台服务读取定位**。因此开机后服务会先就绪，若 20 秒内拿不到定位，会推送一条「需要手动开始传输」通知，点其中的「开始传输」即刻恢复（此时为前台操作，不受限制） |

开机时若前置条件不满足，不会静默失败，而是推送「开机自动传输未启动」通知并说明原因：未授予定位权限 / 未授予蓝牙连接权限 / 设备没有蓝牙硬件 / 蓝牙未开启 / 未选择目标设备。其中「蓝牙未开启」会先等待蓝牙就绪（最长 60 秒）再决定。

> 国产 ROM（小米、OPPO、华为等）可能额外拦截开机自启，需在系统设置中允许本软件「自启动」并关闭电池优化。

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17 或更高（Android Studio 自带 JBR 21 即可） |
| Android SDK Platform | android-34 |
| Android Build Tools | 35.0.0 |
| Gradle | 8.12（仓库内含 `gradlew`，无需单独安装） |

在 `local.properties` 中指定本机 SDK 路径：

```properties
sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
```

## 构建

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装到已连接的真机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 运行单元测试

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest
```

覆盖范围（40 个用例）：

- `NmeaChecksumTest`：XOR 校验和算法（含两个标准样例 `*47` / `*6A`）、两位十六进制补零、`\r\n` 结尾
- `NmeaGeneratorTest`：五类语句字段拼装、南纬/西经半球、度分转换边界、HDOP 估算、GSV 分页、语句开关
- `TransmitEngineTest`：定频写入、时间取自当前时钟、无定位/过期降级、计数器与速率
- `TransmitterStateTest`：观察者通知、注销后不再通知、默认状态
- `LogBusTest`：监听回调、缓冲区上限、时间戳
- `FormattersTest`：中文数值格式化、时长

测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`

> Windows 提示：若把工程放在**含中文字符的目录**下运行单元测试，可能遇到 `ClassNotFoundException`（AGP 在 Windows 上对非 ASCII 路径的已知问题）。此时把工程移到纯英文路径再跑测试即可，普通 APK 构建不受影响。

## 权限说明

| 权限 | 适用版本 | 用途 |
|---|---|---|
| `ACCESS_FINE_LOCATION` | 全部 | 获取卫星定位（必需） |
| `ACCESS_COARSE_LOCATION` | 全部 | 开启「网络辅助定位」时使用 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | ≤ Android 11 | 旧版蓝牙 |
| `BLUETOOTH_CONNECT` | ≥ Android 12 | 读取已配对设备名、建立 RFCOMM 连接 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION` | 全部 / ≥ 14 | 后台持续传输 |
| `POST_NOTIFICATIONS` | ≥ Android 13 | 常驻通知（拒绝时仍可传输，只是看不到状态） |
| `WRITE_EXTERNAL_STORAGE` | ≤ Android 9 | 导出日志到「下载」目录 |
| `RECEIVE_BOOT_COMPLETED` | 全部 | 开机自启（开关默认关闭） |

未申请：`BLUETOOTH_SCAN`、`ACCESS_BACKGROUND_LOCATION`、`WAKE_LOCK`。

## 源码结构

```
app/src/main/java/com/gpsbt/transfer/
├── MainActivity.java              单 Activity + 底部导航 + 权限入口
├── App.java                       Application，装配主线程回调
├── service/GpsOutputService.java  前台服务主控
├── engine/TransmitEngine.java     定频发送与统计（纯逻辑，可单测）
├── engine/LocationProvider.java   LocationManager / GnssStatus 封装
├── nmea/                          NMEA 生成与校验（无 Android 依赖）
│   ├── NmeaGenerator.java
│   ├── NmeaChecksum.java
│   ├── NmeaInput.java
│   └── NmeaSentenceType.java
├── bt/                            蓝牙 SPP 双模式连接
│   ├── BluetoothLinkManager.java
│   ├── ConnectionMode.java
│   └── BtState.java
├── state/                         TransmitterState 状态中心 / TransmitStats / LogBus
├── ui/fragment/                   Home / Satellite / Log / Settings
├── ui/view/                       PulseButtonView / SatelliteRadarView / StatCardView
├── ui/adapter/                    设备 / 卫星 / 日志列表
└── util/                          Prefs / Formatters / LogExporter
```

## 真机调试

详见 [docs/USAGE.md](docs/USAGE.md)：安装、两种模式的操作步骤、Windows 接收端如何开串口看数据、常见问题排查。

## 设计文档与实现计划

- 设计：[docs/superpowers/specs/2026-09-23-gps-bluetooth-transmitter-design.md](docs/superpowers/specs/2026-09-23-gps-bluetooth-transmitter-design.md)
- 实现计划：[docs/superpowers/plans/2026-09-23-gps-bluetooth-transmitter.md](docs/superpowers/plans/2026-09-23-gps-bluetooth-transmitter.md)
