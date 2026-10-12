# GPS蓝牙传输 — 设计文档

- 日期：2026-09-23
- 作者：设计评审稿（待用户确认）
- 应用名（中文界面显示）：GPS蓝牙传输
- 包名：`com.gpsbt.transfer`
- 平台：Android 7.0（minSdk 24）～ 编译目标 SDK 34
- 技术栈：**Java + XML 布局**，Material Components 3（XML 主题），Gradle 8.12 + AGP 8.7.3，Java 17 源码兼容

---

## 1. 目标与范围

### 1.1 核心用途

把**本机 GPS 定位数据**，按标准 **NMEA 0183** 格式，通过**蓝牙串口（SPP / RFCOMM）**实时发送给其他设备
（平板、笔记本、车机、工控机等）。接收端把本机当作一台普通蓝牙 GPS 外设使用。

### 1.2 范围内功能

| 编号 | 功能 | 说明 |
|---|---|---|
| F1 | 蓝牙「等待被连接」服务器模式 | 手机注册 SPP 服务，等待对端发起连接 |
| F2 | 蓝牙「主动连接」客户端模式 | 从**已配对设备列表**中选择目标，主动建立 RFCOMM 连接 |
| F3 | 标准 NMEA 输出 | `$GPGGA` `$GPRMC` `$GPGSA` `$GPGSV` `$GPVTG`，含 XOR 校验和，`\r\n` 结尾 |
| F4 | 发送频率可调 | 1–10 Hz，滑块调节，默认 1 Hz |
| F5 | 前台服务后台传输 | 切后台 / 锁屏仍持续发送，常驻通知显示状态，通知内可一键停止 |
| F6 | 实时状态界面 | GPS 固定状态、卫星数、经纬度、速度、航向、海拔、连接设备、已发语句数、实际速率 |
| F7 | 卫星星图 | 天空雷达（极坐标方位/仰角 + 信噪比柱）+ 卫星明细列表 |
| F8 | 连接日志 | 实时日志列表，支持复制、清空、导出到系统「下载」目录 |
| F9 | 设置 | 频率、NMEA 语句开关、服务名、SPP UUID、定位来源、HDOP、发送策略 |

### 1.3 范围外（明确不做，YAGNI）

- 开机自启、定时唤醒、任何形式的自动启动（见第 6 节）
- 网络 / TCP / WiFi 转发
- 把外部蓝牙 GPS 注入系统模拟定位（Mock Location）
- 账号、云同步、上报
- 未配对设备的主动扫描（因此**不申请 `BLUETOOTH_SCAN` 权限**）

---

## 2. 架构

### 2.1 分层

```
┌─────────────────────────── ui (Activity/Fragment/自定义View)
│           ↓ TransmitterState（观察者，主线程回调）
├─────────────────────────── service/GpsOutputService（前台服务）
│           ↓ owns
├── engine/TransmitEngine    ← 节流：把定位更新转成定频 NMEA 帧
│      ├── nmea/NmeaGenerator（纯 Java，无 Android 依赖）
│      └── bt/BluetoothLinkManager（SPP 双模式连接）
└── sys/（LocationManager / BluetoothAdapter，仅在这一层接触）
```

- UI 层**不直接持有 Service**，全部通过 `TransmitterState` 读写状态；Fragment 在 `onStart/onStop` 注册/注销观察者，杜绝生命周期泄漏。
- `NmeaGenerator` 与 `NmeaChecksum` 为**纯 Java 无 Android 依赖**，可在 JVM 上直接单测。
- `TransmitEngine` 负责节流与写流，不触碰 UI 与生命周期。
- `BluetoothLinkManager` 只负责"连接 + 写字节 + 断线回调"，不解析业务。

### 2.2 模块清单

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 单 Activity，底部导航容器，权限请求入口 |
| `ui/fragment/HomeFragment.java` | 主页：模式切换、脉冲开始按钮、设备选择、数据卡片 |
| `ui/fragment/SatelliteFragment.java` | 卫星雷达 + 明细列表 |
| `ui/fragment/LogFragment.java` | 日志页 |
| `ui/fragment/SettingsFragment.java` | 设置页 |
| `service/GpsOutputService.java` | 前台服务：定位注册、持有引擎、通知 |
| `engine/TransmitEngine.java` | 定频发送循环、无定位降级判断 |
| `engine/LocationProvider.java` | 封装 LocationManager（GPS / 网络）、GnssStatus 回调 |
| `nmea/NmeaGenerator.java` | 由 Location + 卫星信息生成标准语句 |
| `nmea/NmeaChecksum.java` | `$` 与 `*` 之间字符 XOR → 两位大写十六进制 |
| `nmea/NmeaSentenceType.java` | 语句类型枚举与开关 |
| `bt/BluetoothLinkManager.java` | SPP 服务器 / 客户端连接、写流、断线回调 |
| `bt/ConnectionMode.java` | `SERVER` / `CLIENT` |
| `state/TransmitterState.java` | 线程安全状态单例 + 观察者（主线程回调） |
| `state/TransmitStats.java` | 已发语句数、字节数、实际速率等统计快照 |
| `ui/view/PulseButtonView.java` | 圆形脉冲主按钮（开始/停止） |
| `ui/view/SatelliteRadarView.java` | 卫星天空雷达 |
| `ui/view/StatCardView.java` | 可复用数据卡片（标题 + 等宽数值 + 副标题） |
| `ui/adapter/PairedDeviceAdapter.java` | 已配对设备列表 |
| `ui/adapter/SatelliteAdapter.java` | 卫星列表 |
| `ui/adapter/LogAdapter.java` | 日志列表 |
| `util/Prefs.java` | SharedPreferences 读写封装 |
| `util/Formatters.java` | 坐标 / 速度 / 时间中文格式化 |

---

## 3. NMEA 输出规格

### 3.1 帧格式

```
$<talker><TYPE>,<字段...>*<CS>\r\n
```

- talker 固定 `GP`（可设置扩展点，本版固定）
- `CS` = `$` 之后、`*` 之前所有字符（含逗号）的 XOR，输出**两位大写十六进制**，不足补零
- 每条语句以 `\r\n` 结尾；一次发送把本周期启用的语句按固定顺序拼接后一次性 `write + flush`

### 3.2 语句定义（发送顺序固定）

| 语句 | 字段 |
|---|---|
| `$GPGGA` | UTC 时间、纬度(ddmm.mmmm)、N/S、经度(dddmm.mmmm)、E/W、定位质量(1=单点/0=无效)、使用卫星数、HDOP、天线海拔(M)、M、大地水准面差距(M)、M、(差分龄期空)、(差分站号空) |
| `$GPRMC` | UTC 时间、状态(A/V)、纬度、N/S、经度、E/W、对地速度(节)、地面航向(度)、UTC 日期(ddMMyy)、磁偏角(空)、磁偏方向(空)、模式指示(A) |
| `$GPGSA` | 模式(A)、定位类型(3=3D)、12个使用中的卫星 PRN（不足留空）、PDOP、HDOP、VDOP |
| `$GPGSV` | 总语句数、本句序号、可见卫星总数、随后最多 4 组「PRN,仰角(2位),方位(3位),信噪比(2位)」，不足补空 |
| `$GPVTG` | 真航向(度),T、磁航向(度),M、速度(节),N、速度(km/h),K、模式(A) |

换算规则：
- 度 → 度分：纬度 `%02d%07.4f`，经度 `%03d%07.4f`；南纬/西经输出负半球字符（`S`/`W`）
- m/s → 节：`×1.9438445`
- 日期取 UTC，格式 `ddMMyy`
- 无卫星数据时 `$GPGSV` 不发；卫星不足 12 颗时 `$GPGSA` 对应位留空
- HDOP 由定位精度估算：`clamp(accuracy/5.0, 0.5, 9.9)`，无精度信息时取设置值（默认 1.0）
- PDOP = HDOP × 1.2、VDOP = HDOP × 0.8（估算值）

### 3.3 无定位/失效策略

- 最新定位时间戳超过 **5 秒**（可设置）视为失效：暂停发送，UI 显示"无定位等待中"，通知同步显示该状态。
- 收到有效定位后自动恢复；节流周期仍按设定频率，用「当前 UTC 时间 + 最近一次坐标」重建帧。

### 3.4 串口参数

- 协议：RFCOMM / SPP，UUID 默认 `00001101-0000-1000-8000-00805F9B34FB`（可设置）
- 服务名默认 `GPS蓝牙传输`
- 写缓冲：`BufferedOutputStream` + 每周期 `flush`；单条语句最大 200 字节上限保护

---

## 4. 蓝牙连接

### 4.1 服务器模式（等待被连接）

1. 申请 `BLUETOOTH_CONNECT`，确认蓝牙已开启（未开启则弹系统授权对话框，由用户决定，**不静默开启**）。
2. `BluetoothAdapter.listenUsingRfcommWithServiceRecord(name, uuid)`（不安全通道，兼容车机/老设备）。
3. 后台线程循环 `accept()`；同一时刻只服务一个客户端。
4. 断开后：若用户未停止，间隔 2 秒重新监听（属于"已开启传输后的重连"，非自动启动）。
5. `accept()` 阻塞期间用户点停止 → 关闭 ServerSocket 打断阻塞。

### 4.2 客户端模式（主动连接）

1. `adapter.getBondedDevices()` 列出已配对设备（展示名称 + MAC）。
2. 用户选择目标 → `device.createRfcommSocketToServiceRecord(uuid)`，后台线程 `connect()`（阻塞 10–12 秒超时）。
3. 失败 → 回调 UI 提示，保持停止态，不无限重试。
4. 传输中断开：提示用户，不自动重连（避免后台偷偷重连）。

### 4.3 断线与错误

所有 IO 异常统一转为 `BtEvent`（断开 / 失败 / 已连接 / 等待中），写入状态与日志；服务仅在「用户主动停止」或「启动过程失败」时退出。

---

## 5. 界面设计（全中文，深色科技风）

### 5.1 视觉规范

| 项 | 值 |
|---|---|
| 背景 | 纵向渐变 `#0A0E1A` → `#0D1526` |
| 卡片 | `#141C31`，圆角 20dp，1dp 描边 `#223050` |
| 主色（青） | `#00E5C7` |
| 辅色（蓝） | `#3D8BFF` |
| 警示/强调（橙） | `#FF8A3D` |
| 错误（红） | `#FF5C6C` |
| 主文字 | `#E6EDF7`；次文字 `#8A97B0`；禁用 `#4A5878` |
| 数值字体 | `monospace`（坐标、速度、校验等所有数字） |
| 主题 | `Theme.GpsBt` 继承 `Theme.Material3.Dark.NoActionBar` |

语言：`values/strings.xml` 全部中文，不提供其它语言资源（用户要求全中文）。

### 5.2 页面

**主页 `fragment_home`**
- 顶栏：应用名 + 状态徽标（未开始 / 等待连接 / 已连接-发送中 / 无定位）
- 分段按钮：「等待被连接」「主动连接」
- 中央 `PulseButtonView`（140dp）：未启动为青色渐变圆环 + 呼吸光环动画；传输中转为橙色并显示扩散波纹与当前速率
- 主动模式：已配对设备卡片列表（名称/MAC、点击选中、空态提示"请先在系统蓝牙中配对设备"）
- 服务器模式：显示服务名与 UUID 的文本信息卡（提示对端按 SPP 连接）
- 数据卡片网格（2 列）：固定状态、可见卫星、纬度、经度、速度、航向、海拔、已发语句、速率、连接设备
- 底部提示条：说明"本软件不支持开机自启，需手动开启"

**卫星页 `fragment_satellite`**：`SatelliteRadarView` 天空雷达（同心圈 = 30°/60°/90° 仰角，卫星按方位/仰角定位，柱高 = 信噪比，已用于定位的实心青色、未使用半透明蓝色）+ 列表（PRN / 仰角 / 方位 / 信噪比 / 是否用于定位）。

**日志页 `fragment_log`**：时间 + 级别 + 内容列表，按钮：复制到剪贴板、清空、导出到 `Download/GpsBt_yyyyMMdd_HHmmss.log`。

**设置页 `fragment_settings`**
- 发送频率滑块 1–10 Hz
- NMEA 语句开关（5 个，默认全开；GSV 无数据自动禁用）
- 服务名输入、SPP UUID 输入（含"恢复默认"）
- 定位来源：仅 GPS（默认）/ GPS + 网络辅助
- 无定位判定超时（默认 5 秒）
- HDOP 默认值（默认 1.0）
- 关于：版本号 + 一条固定说明「本软件不支持开机自启与自动运行，每次使用需手动点击开始传输」

### 5.3 自定义 View

| View | 行为 |
|---|---|
| `PulseButtonView` | 中心圆 + 呼吸光环（`ValueAnimator` 缩放/透明度，1.8s 循环）+ 传输中的扩散波纹，点击回调 |
| `SatelliteRadarView` | `onDraw` 绘制网格与卫星；无数据时显示"等待卫星信号" |
| `StatCardView` | attrs：`cardTitle`/`cardValue`/`cardUnit`/`cardIcon`；数值用 monospace |

---

## 6. 「不自动运行」的实现保证

1. `AndroidManifest.xml` **不含** `BOOT_COMPLETED` 相关 `<receiver>`，**不申请** `RECEIVE_BOOT_COMPLETED`。
2. `GpsOutputService` 仅由主页按钮 `startForegroundService()` 显式启动；`android:exported="false"`，无外部入口。
3. 设置页提供说明文字而非自启开关。
4. 「停止传输」：停服务 → 关蓝牙 socket → 注销定位监听 → 清状态。
5. 蓝牙未开启时只弹系统授权对话框，由用户决定。
6. 服务退出后无自重启逻辑；`START_STICKY` 不适用（用 `START_NOT_STICKY`）。

---

## 7. 权限

| 权限 | 适用版本 | 用途 |
|---|---|---|
| `ACCESS_FINE_LOCATION` | 全部 | GPS 定位（必需） |
| `ACCESS_COARSE_LOCATION` | 全部 | 网络辅助定位时使用 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | ≤ API 30 | 旧版蓝牙 |
| `BLUETOOTH_CONNECT` | ≥ API 31 | 读取已配对设备名、建立 RFCOMM 连接 |
| `FOREGROUND_SERVICE` | 全部 | 前台服务 |
| `FOREGROUND_SERVICE_LOCATION` | ≥ API 34 | 定位类前台服务 |
| `POST_NOTIFICATIONS` | ≥ API 33 | 常驻通知（用户拒绝时服务仍可运行，仅无通知） |

显式**不申请**：`BLUETOOTH_SCAN`、`RECEIVE_BOOT_COMPLETED`、`ACCESS_BACKGROUND_LOCATION`、`WAKE_LOCK`。

权限流程：进入主页时按需申请定位权限 → 点「开始」时按需申请蓝牙连接权限（Android 12+）与通知权限。

---

## 8. 测试与验证

### 8.1 单元测试（本地可执行）

`app/src/test/java/com/gpsbt/transfer/nmea/NmeaGeneratorTest.java`：
- GGA/RMC/GSA/GSV/VTG 字段拼装正确性
- 校验和 XOR 与两位十六进制补零
- 南纬 / 西经半球字符
- 度分转换边界（含整数度、接近 60 分进位）
- m/s → 节换算精度
- 0 卫星 / 无精度信息降级行为
- GSV 分页（>4 颗、>8 颗）
- 语句开关关闭时不生成对应语句

### 8.2 构建验证

```bash
./gradlew testDebugUnitTest assembleDebug
```

产出 `app/build/outputs/apk/debug/app-debug.apk`。

### 8.3 真机验证（用户侧）

附《接收端调试指南》：
- Windows：设备配对后建立"传入"串口虚拟端口，用 `PuTTY` / `串口助手` 波特率任意（RFCOMM 无波特率概念）查看 NMEA 原始帧
- 用校验和比对工具验证每条语句
- 常见问题：设备需先配对、UUID 需一致、部分车机只接受蓝牙拨号/串口特定服务

---

## 9. 工程结构

```
GPS蓝牙传输/
├── settings.gradle / build.gradle / gradle.properties / gradlew(.bat)
├── local.properties            # 本机 SDK 路径，不入库
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/gpsbt/transfer/...（见 2.2）
        │   └── res/
        │       ├── layout/（activity_main、fragment_home、fragment_satellite、
        │       │           fragment_log、fragment_settings、item_device、
        │       │           item_satellite、item_log、view_stat_card）
        │       ├── drawable/（渐变、圆角、ripple、选择器、通知图标）
        │       ├── values/（colors、themes、styles、dimens、strings、attrs）
        │       ├── mipmap-anydpi-v26/（自适应启动图标）+ drawable 前景
        │       └── xml/（备份规则、文件路径）
        └── test/java/com/gpsbt/transfer/nmea/NmeaGeneratorTest.java
```

---

## 10. 构建配置

| 项 | 值 | 理由 |
|---|---|---|
| Gradle | 8.12 | 本地已有发行包，无需下载 |
| AGP | 8.7.3 | 本地缓存命中，与 compileSdk 34 兼容 |
| compileSdk / targetSdk | 34 | 本机装有 android-34 平台与 build-tools 35.0.0，最稳 |
| minSdk | 24 | 覆盖 Android 7.0+ |
| buildTools | 35.0.0（AGP 默认） | 已安装 |
| Java | 17 源码/目标 | AGP 8.7 + JBR 21 运行 |
| 依赖 | appcompat 1.7.0、material 1.12.0、constraintlayout 2.1.4、recyclerview 1.3.2、core 1.13.1、fragment 1.8.5、junit 4.13.2 | 全部与 compileSdk 34 兼容，避免 1.15.x/core 类需要 SDK 35 |
| 语言 | 纯 Java（无 Kotlin 插件） | 用户选择 Java + XML |

---

## 11. 交付物

1. 完整 Android Studio 工程（含 wrapper，可直接导入/打开）
2. 编译通过的 `app-debug.apk`
3. 单元测试（全部通过）
4. 中文 `README.md`：功能说明、使用步骤、接收端调试指南、常见问题
5. 本设计文档
