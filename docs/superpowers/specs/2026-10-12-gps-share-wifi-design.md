# GPS共享（特别版） — 设计文档

- 日期：2026-10-12
- 作者：设计评审稿（待用户确认）
- 应用名（中文界面显示）：GPS共享
- 包名（Java 包 / namespace）：`com.gpsbt.transfer`（沿用，见 §2.2）
- 安装标识 applicationId：`com.gpsshare`
- 版本：versionCode 1 / versionName 1.0.0
- 平台：Android 7.0（minSdk 24）～ 编译目标 SDK 34
- 技术栈：**Java + XML 布局**，Material Components 3（XML 主题），Gradle 8.12 + AGP 8.7.3，Java 17 源码兼容

> 本工程是 `GPS蓝牙传输` v1.4.6 的**独立特别版**，位于独立仓库，原工程不改动。

---

## 1. 目标与范围

### 1.1 核心用途

在原版「手机 GPS 经蓝牙串口共享给其他设备」的基础上，**新增 WiFi(TCP) 共享通道**，并**新增接收端角色**：让没有 GPS 的平板 / 车机连上手机热点后，直接把手机的位置当成自己的位置使用。

与蓝牙方案的根因差异：**「蓝牙串口 + NMEA」是行业事实标准**，接收端零安装即可识别为一台外接蓝牙 GPS；而 **WiFi 没有对应标准**，所以接收端必须有一个程序去读 TCP 上的 NMEA 并注入系统定位。本特别版把这个程序一并做进同一个 APK。

### 1.2 范围内功能

| 编号 | 功能 | 说明 |
|---|---|---|
| F1 | 角色切换 | 同一 APK 两个角色：「共享 GPS（发送）」与「接收 GPS」 |
| F2 | 传输方式切换 | 发送角色下可选 **蓝牙** 或 **WiFi(TCP)** |
| F3 | 蓝牙通道（沿用原版） | 服务器 / 客户端双模式，行为与 v1.4.6 一致 |
| F4 | WiFi 通道（新增） | 手机在热点上开 TCP 服务，**只做服务端**，接收端主动来连 |
| F5 | 多接收端 | WiFi 通道支持多个接收端同时接收，一份 GPS 喂多台设备 |
| F6 | 服务发现 | 发送端 UDP 广播信标，接收端自动列出可用服务器，免手输 IP |
| F7 | 接收端 | 连 TCP、解析 NMEA、通过「模拟位置」注入系统定位，导航 App 直接可用 |
| F8 | 标准 NMEA 输出 / 解析 | 沿用原版 `$GPGGA/$GPRMC/$GPGSA/$GPGSV/$GPVTG/$GPZDA`，含 XOR 校验和 |
| F9 | 复用原版全部既有能力 | 发送频率、语句开关、无定位保活、断线重连、前台服务、实时状态、卫星星图、日志、开机自启（**开机自启只对发送角色生效**，接收角色不自动启动） |

### 1.3 范围外（明确不做，YAGNI）

- WiFi 通道的**客户端模式**（手机主动连别人的 IP）——本版只做服务端
- 应用**自己打开带流量共享的热点**（Android 不允许，见 §7）
- 账号、云同步、上报
- iOS / 桌面端接收程序（接收端只做 Android）
- 接收端注入卫星星图（Android 平台限制，见 §5.4）

---

## 2. 仓库与版本形态

### 2.1 目录

```
工作区根目录\
├── GPS蓝牙传输\        ← 原工程，本版不改动任何一个字节
└── GPS共享\            ← 本特别版，独立 git 仓库
```

- 新仓库由 `GPS蓝牙传输` 的 v1.4.6 完整复制而来，含自带的 `gradlew`、`settings.gradle`、`.gitignore`。
- 首个提交为**基线提交**（已存在）：`chore: 基线——复制 GPS蓝牙传输 v1.4.6 作为「GPS共享」特别版起点`。
- `local.properties`、`keystore.properties`、`release.keystore` 已一并复制（均在本机、被 gitignore，不入库）。

### 2.2 包名与安装标识

| 项 | 值 | 理由 |
|---|---|---|
| Java 包 / `namespace` | `com.gpsbt.transfer`（**不变**） | 避免 30 个源文件的大规模机械改名，让 diff 只反映真实改动 |
| `applicationId` | `com.gpsshare` | 与原版**并存安装**互不覆盖，便于同机对照测试 |
| 应用显示名 | `GPS共享` | — |
| `versionCode` / `versionName` | 1 / `1.0.0` | 特别版重新起版本号 |

> `namespace` 与 `applicationId` 分离是 Android 官方支持的用法：前者决定 `R` 类与清单里的包名，后者决定安装身份。

连带影响：蓝牙 SPP 的默认服务名取自 `R.string.app_name`，因此会从 `GPS蓝牙传输` 变为 `GPS共享`。使用方可在设置页改回原值以兼容既有接收端。

### 2.3 已知代价

`nmea/`、`engine/LocationProvider`、`ui/view` 等公共代码会有**两份**，在原工程与本特别版之间分叉。今后在这两处修 bug 需手动同步两边。这是选择「完全独立仓库」换取「原版零风险」的必然代价，已确认接受。

---

## 3. 架构

### 3.1 传输层抽象（本版核心改动）

现状：`GpsOutputService` 直接 `new BluetoothLinkManager(...)`。本版引入统一接口，发送服务只依赖接口：

```java
package com.gpsbt.transfer.link;

/** 一条把 NMEA 字节流送出去的传输通道。 */
public interface Link {
    /** 通道状态。取值与现有 BtState 一致，改名以示通用。 */
    enum State { IDLE, WAITING, CONNECTING, CONNECTED, DISCONNECTED, FAILED }

    interface Listener {
        void onState(State state, String message);
        void onPeerJoined(Peer peer);     // 一个接收端接入
        void onPeerLeft(Peer peer);       // 一个接收端离开
    }

    void start();
    void stop();
    /** 未就绪时抛 IOException；调用方据此触发重连/清理。 */
    void write(byte[] data) throws IOException;
    /** 是否至少有一个接收端在线。 */
    boolean isConnected();
}

/** 一个已连接的对端（蓝牙设备或 TCP 客户端）。 */
public final class Peer {
    public final String name;      // 蓝牙设备名 / 接收端 IP
    public final String address;   // MAC / IP:端口
    public final long connectedAt;
}
```

- `bt/BluetoothLinkManager implements Link`：**逻辑不变**，只是补上接口与 `Peer` 包装；单对端，`onPeerJoined/Left` 各触发一次。
- `net/NetLinkManager implements Link`：新增，WiFi 服务端，多对端。
- `GpsOutputService` 只持有 `Link`，按传输方式构造蓝牙或 WiFi 实现。

这样 `TransmitEngine`、`NmeaGenerator`、`LocationProvider` **一行都不用改**——它们本就不关心底层是蓝牙还是 TCP。

### 3.2 分层

```
┌─────────────────────────── ui (Activity/Fragment/自定义View)
│           ↓ TransmitterState（观察者，主线程回调）
├─────────────────────────── service/GpsOutputService（发送，前台服务）
│           ↓ owns
├── engine/TransmitEngine    ← 节流：把定位更新转成定频 NMEA 帧
│      ├── nmea/NmeaGenerator（纯 Java，无 Android 依赖）
│      └── link/Link  ← 传输层抽象
│            ├── bt/BluetoothLinkManager（SPP，单对端）
│            └── net/NetLinkManager（TCP 服务端，多对端）+ net/PeerRegistry
│
├─────────────────────────── service/ReceiveService（接收，前台服务）
│           ↓
└── rx/NmeaClient → rx/NmeaParser → rx/MockLocationInjector
```

- 发送与接收是**两条独立的流水线**，同一时刻只有一个角色在跑。
- `NmeaParser` 为**纯 Java 无 Android 依赖**，可在 JVM 上直接单测（与 `NmeaGenerator` 对称）。
- UI 层依旧不直接持有 Service，全部通过 `TransmitterState` 读写状态。

---

## 4. 发送端：WiFi 服务端

### 4.1 `net/NetLinkManager`

- `ServerSocket` 绑定 `0.0.0.0:<port>`，`setReuseAddress(true)`，默认端口 **10110**（NMEA over TCP 的行业惯例端口，可配置）。
- 独立 accept 线程；每接受一个 socket，创建一个 `PeerRegistry.Client`（socket + 输出流 + 读线程）。
- `write(byte[])` **广播**给所有客户端；某个客户端写失败即从注册表摘除，其余照常发送，不中断整体。
- 读线程沿用原版思路：读到 `EOF` / 异常即判定该接收端断开，立刻摘除并回调 `onPeerLeft`。
- `isConnected()` = 注册表非空。

### 4.2 `net/PeerRegistry`

多对端注册表，是本版新增的**可单测逻辑**（见 §10）：

- 维护 `Client` 集合（写操作加锁 / `CopyOnWriteArrayList`）。
- `broadcast(byte[])`：逐客户端写；捕获 `IOException` 即摘除该客户端，不抛出、不影响其它客户端。
- `add/remove/snapshot`：供 UI 展示在线接收端列表。

### 4.3 保活

无定位时仍每秒发一帧 `$GPZDA`（原版 `TransmitEngine.tickKeepalive` 已实现，无需改动）。在 WiFi 场景下这还额外起到**焐热链路**的作用，减少省电策略导致的连接休眠。

---

## 5. 接收端

### 5.1 `rx/NmeaClient`

- 连接发送端的 `IP:端口`；连不上 / 断开后**每 5 秒自动重连**（沿用原版客户端的重连节奏）。
- 读线程按行切分 NMEA 语句，逐条交给 `NmeaParser`。
- 链路健康判断与发送端一致：`EOF` / 异常即视为断开。

### 5.2 `rx/NmeaParser`

纯 Java，解析以下语句还原成定位数据（复用现有 `nmea/NmeaInput` 作为数据载体）：

| 语句 | 取出的字段 |
|---|---|
| `$--GGA` | 时间、纬度、经度、定位质量、使用卫星数、HDOP、海拔 |
| `$--RMC` | 有效性、纬度、经度、速度、航向、日期 |
| `$--GSA` | 定位模式、PDOP/HDOP/VDOP、使用中的 PRN |
| `$--GSV` | 可见卫星（PRN、仰角、方位、信噪比），按星座分组 |

- 语句以 `\r\n` 分帧，`*` 后两位为 XOR 校验和；解析时**校验和错误的行丢弃并记日志**（不中断）。
- 度分（`ddmm.mmmm`）→ 十进制度，半球字母决定正负，与 `NmeaGenerator` 的转换互为逆运算。

### 5.3 `rx/MockLocationInjector`

通过系统「模拟位置」通道把解析结果注入 `LocationManager.GPS_PROVIDER`：

1. 启动时 `addTestProvider(GPS_PROVIDER, …)` + `setTestProviderEnabled(GPS_PROVIDER, true)`；
2. 每次拿到有效定位，构造 `Location` 并 `setTestProviderLocation(GPS_PROVIDER, location)`；
3. 停止时 `setTestProviderEnabled(false)` + `removeTestProvider(GPS_PROVIDER)`。

实现注意点：

- `Location` 必须调用 `setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())`，否则 `setTestProviderLocation` 抛 `IllegalArgumentException`。
- 注入字段：纬度、经度、海拔、速度、航向、精度、时间戳。
- **前提**：接收端设备须在 **开发者选项 → 选择模拟位置信息应用** 中选中本应用，否则调用抛 `SecurityException`。应用启动时检测并给出明确指引（见 §5.5）。
- Android 12+ 对 test provider 有额外校验，具体 API 细节在实现时按目标版本核实。

### 5.4 已知限制：注入不了卫星星图

Android 的 mock provider **不支持 `GnssStatus`**，只能注入单点定位。因此：

- 导航 App 里**定位本身正常**，但**卫星数可能显示为 0 / 星图空白**。
- 本应用自己的「卫星」页在接收角色下仍能正常显示——数据来自解析的 `GSV` 语句，不依赖系统 mock provider。

此限制属 Android 平台能力边界，会写入 README 与使用文档。

### 5.5 模拟位置未授权的引导

接收角色启动前检测本应用是否为当前模拟位置应用。未授权时**不静默失败**，而是在界面上给出分步指引：

> 接收端需要「模拟位置」授权：设置 → 关于手机 → 连点「版本号」7 次开启开发者选项 → 开发者选项 → 选择模拟位置信息应用 → 选「GPS共享」。

若目标设备进不去开发者选项，则此路不通——这是整条 WiFi 接收链路的前提，见 §12 风险。

---

## 6. 服务发现（免手输 IP）

热点网关地址各 ROM 不一致（192.168.43.1 / 192.168.1.1 / …），让用户在车机上手输 IP 很痛苦，因此做一层自动发现。

- **发送端**：独立线程每秒广播一个 UDP 信标到 **10111** 端口。
  - 报文格式：`GPSSHARE/1|<URL编码的设备名>|<TCP端口>`（单行，`\n` 结尾）。
  - 广播目标：枚举本机各网卡的 IPv4 **广播地址**（由地址 + 前缀长度算出），逐个发送；同时补发 `255.255.255.255` 作为兜底。这样可确保信标从热点网卡出去，而不是被默认路由带到移动数据网卡。
- **接收端**：`DatagramSocket` 绑定 10111 监听，解析信标维护一份服务器列表；**3 秒未见即过期移除**，保证列表实时反映在线的发送端。
- 手动输入 `IP:端口` 作为兜底，始终保留。

---

## 7. 热点地址获取

**应用无法自己打开「能共享流量的热点」**：`WifiManager.startLocalOnlyHotspot()`（API 26+）虽然应用可调，但它只建本地局域网、**不共享流量**；真正带网络共享的热点只能由用户在系统设置手动打开（`ConnectivityManager.startTethering()` 需系统签名权限）。

因此流程是：

1. 界面上明确引导用户去系统设置打开「个人热点」；
2. 应用枚举 `NetworkInterface` 找出 IPv4 地址，把疑似热点网卡（`ap0` / `swlan0` / `wlan1` / `softap0` 等名称，或 `192.168.x.1` 形态的地址）**高亮显示**；
3. 枚举不到时给出兜底提示，引导用户去热点设置页查看网关地址。

---

## 8. 界面

### 8.1 角色切换

主页顶部加**角色分段控件**：「共享 GPS」/「接收 GPS」。底部导航（主页 / 卫星 / 日志 / 设置）不变，卫星页与日志页两个角色共用。

### 8.2 共享角色

- 传输方式分段控件：**蓝牙 / WiFi**。
- 选蓝牙：界面与 v1.4.6 一致（连接方式「等待被连接 / 主动连接」+ 已配对设备列表 + 串口服务信息卡片）。
- 选 WiFi：
  - 「串口服务信息」卡片换成**「热点服务信息」卡片**：显示热点 IP（高亮）、端口、广播开关。
  - 加一块**在线接收端列表**：每个接收端一行（IP、连接时长），实时增减。
  - 未开热点时给出引导提示。

### 8.3 接收角色

- 连接页：**发现到的服务器列表**（点一下即连）+ **手动输入 IP:端口** + 连接状态 + 模拟位置就绪横幅。
- 卫星页：显示从 `GSV` 解析出的卫星（正常可用，见 §5.4）。
- 日志页：记录连接、解析、注入过程，沿用原版分类筛选。

### 8.4 设置

在 v1.4.6 设置项基础上新增：

- WiFi 端口（默认 10110）
- 服务发现广播开关（默认开）+ 广播端口（默认 10111）
- 接收端记住上次连接的服务器

---

## 9. 权限

| 权限 | 适用 | 用途 |
|---|---|---|
| `INTERNET` | 全部 | TCP 服务端 / 客户端、UDP 广播（普通权限，无运行时弹窗） |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 沿用 | 发送端取 GPS；接收端 mock provider 亦需 |
| `BLUETOOTH*` 系列 | 沿用 | 蓝牙通道 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | 沿用 | 发送端后台传输 |
| `POST_NOTIFICATIONS` | 沿用 | 常驻通知 |

**接收端不声明 `ACCESS_MOCK_LOCATION`**——那是系统签名权限，声明无用；API 23+ 的标准做法是走开发者选项授权（见 §5.5）。

**接收端前台服务类型**：接收端不读本机 GPS，候选 `FOREGROUND_SERVICE_DATA_SYNC`；Android 14/15 对该类型有时长限制，具体类型与权限在实现时按目标 SDK 核实，必要时改用 `specialUse`。

---

## 10. 测试

沿用原版**纯 JVM 单测**思路（无需模拟器），在现有 40 个用例之上新增：

| 测试 | 覆盖 |
|---|---|
| `NmeaParserTest` | **往返测试**：用 `NmeaGenerator` 生成语句 → `NmeaParser` 解析 → 比对经纬度/速度/航向/海拔。生成器与解析器互为逆运算，一个测试同时锁住两边。另含校验和错误丢弃、半球正负、缺字段容错 |
| `PeerRegistryTest` | 多客户端广播、写失败剔除该客户端而不影响其余、快照内容 |
| `DiscoveryBeaconTest` | 信标编码 / 解码往返、非法报文丢弃、URL 编码设备名 |

Socket 层（蓝牙 / TCP 实际连接）不做单测，与原版一致，靠真机验证。

---

## 11. 分阶段实施

每个阶段结束都是一个**能装机运行的可用版本**。

| 阶段 | 内容 | 完成判据 |
|---|---|---|
| **P1 骨架 + 传输层抽象** | 新仓库就绪；引入 `Link` / `Peer`；`BluetoothLinkManager` 实现接口；`GpsOutputService` 改用接口；包名/应用名/版本号调整 | 可构建；单测全绿；蓝牙功能与 v1.4.6 **完全等价** |
| **P2 WiFi 发送端** | `NetLinkManager` + `PeerRegistry` + 服务发现广播 + 热点 IP 探测 + 传输方式切换 UI + 在线接收端列表 | 手机开热点，PC 上用 TCP 工具能收到 NMEA；两台接收端可同时收 |
| **P3 接收端** | `NmeaClient` + `NmeaParser` + `MockLocationInjector` + 服务器发现 UI + 模拟位置引导 | 车机/平板连上后，系统定位被接管，导航 App 正常定位 |
| **P4 打磨** | 日志分类、统计、开机自启适配（发送角色）、README / USAGE 文档、截图 | 文档齐全，可交付 |

---

## 12. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 车机 / 平板进不去开发者选项 | 接收端不可用 | **这是整条链路的前提**，建议在 P3 动手前先在目标设备上验证；进不去则 WiFi 接收方案对这类设备不成立 |
| 模拟位置无卫星星图 | 导航 App 卫星数显示 0 | 文档说明，属 Android 平台限制 |
| 部分 ROM 枚举不到热点网卡 | 显示不出热点地址 | 兜底提示 + 手填 IP；服务发现可绕过地址问题 |
| UDP 广播被热点隔离 / 被路由到移动数据 | 自动发现失效 | 按网卡广播地址逐个发送；保留手动输入兜底 |
| 热点空闲自动关闭 / 省电休眠 | 连接掉线 | 每秒保活帧焐热链路；沿用 5 秒自动重连 |
| 两个仓库代码分叉 | 修 bug 要改两处 | 已接受；公共逻辑改动时手动同步 |

---

## 13. 与原版的关系

- 原工程 `GPS蓝牙传输` **不改动任何一个字节**，继续独立演进 / 出货。
- 本特别版以 v1.4.6 为基线，独立演进。
- 两版可**同机并存安装**（`applicationId` 不同），便于对照测试。
