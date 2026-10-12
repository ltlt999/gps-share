# GPS共享 特别版 — P1：骨架 + 传输层抽象 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在独立仓库里把传输层抽象成 `Link` 接口，让蓝牙实现它、发送服务只依赖它，并完成特别版的应用标识调整——产出与 v1.4.6 **行为完全等价**的可验证起点。

**Architecture:** 新增 `link` 包（`Link` 接口 + `Peer` 值对象）；`BluetoothLinkManager` 改为实现 `Link`（内部逻辑不变，只是补上接口与 `Peer` 包装）；`GpsOutputService` 的链路字段类型从具体类改为接口。传输层之上的一切（`TransmitEngine` / `NmeaGenerator` / `LocationProvider`）**一行都不改**。

**Tech Stack:** Java 17、Android Gradle Plugin 8.7.3、Gradle 8.12、JUnit 4、Material Components 3（XML 布局）

---

## 本计划的范围

整个特别版分四个阶段，**每个阶段都是一份独立计划、一个可装机运行的完整版本**：

| 阶段 | 内容 | 计划文档 |
|---|---|---|
| **P1** | 骨架 + 传输层抽象 | **本文档** |
| P2 | WiFi 发送端（多接收端 + 服务发现） | 待写 |
| P3 | 接收端（NMEA 解析 + 模拟位置注入） | 待写 |
| P4 | 打磨（文档、截图、开机自启适配） | 待写 |

P2 / P3 / P4 的计划在各自阶段开始时再写——P2 涉及大量 UI 改动、P3 涉及模拟位置的 API 细节，届时结合当时的真实代码来写，比现在凭空推测可靠得多。

**P1 的完成判据（唯一验收标准）：蓝牙功能与 v1.4.6 完全等价。** 本阶段**不引入任何新功能**，纯粹是让后面的 WiFi / 接收端能挂进来。

---

## 前置条件

- 工程根目录：`工作区根目录\GPS共享`
- 所有命令都在该目录下执行
- `local.properties` 必须含 `build.dir=C\:/build`（已配好）。**这是单测能跑起来的前提**：工程路径含中文，JVM 用系统 ANSI 代码页解码 classpath 会让测试类加载不到。构建产物因此落在 `构建目录\`，源码仍在原地。
- Gradle 命令统一带 `JAVA_HOME`：

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew <任务>
```

**基线状态（本计划开始前已确认）：** `clean` 后 `testDebugUnitTest` **51 个用例全过**，`assembleDebug` 正常产出 APK。

---

## 文件结构

| 动作 | 路径 | 职责 |
|---|---|---|
| Create | `app/src/main/java/com/gpsbt/transfer/link/Peer.java` | 一个已连接对端的值对象（名称、地址、接入时刻） |
| Create | `app/src/main/java/com/gpsbt/transfer/link/Link.java` | 传输通道接口：状态、监听器、start/stop/write/isConnected/resetConnection |
| Create | `app/src/test/java/com/gpsbt/transfer/link/PeerTest.java` | `Peer` 的相等性契约 |
| Modify | `app/src/main/java/com/gpsbt/transfer/bt/BluetoothLinkManager.java` | 实现 `Link`；构造时决定服务端/客户端模式；维护当前 `Peer` |
| Delete | `app/src/main/java/com/gpsbt/transfer/bt/BtState.java` | 状态枚举移入 `Link.State` |
| Modify | `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java` | 链路字段改为 `Link`；监听器改为 `Link.Listener` |
| Modify | `app/build.gradle` | `applicationId` / `versionCode` / `versionName` |
| Modify | `app/src/main/res/values/strings.xml` | 应用显示名 |

**刻意不动的东西**（P1 要保持 diff 最小、行为不变）：

- `state/TransmitterState.java` 的 `Bt` 枚举与 `Device` 类 —— UI 面向的状态模型本阶段不改。多对端列表是 P2 的事，届时再动这里。本阶段由 `GpsOutputService` 把 `Peer` 映射成 `TransmitterState.Device`（蓝牙是单对端，映射是忠实的）。
- `ui/` 下所有文件 —— 界面完全不碰。
- `nmea/`、`engine/`、`util/` —— 传输层之上的一切。

---

## Task 1: `link/Peer` 值对象

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/link/Peer.java`
- Test: `app/src/test/java/com/gpsbt/transfer/link/PeerTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/gpsbt/transfer/link/PeerTest.java`：

```java
package com.gpsbt.transfer.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class PeerTest {

    @Test
    public void peersWithSameAddressAreEqual() {
        Peer first = new Peer("车机", "192.168.43.2:10110", 1000L);
        Peer second = new Peer("平板", "192.168.43.2:10110", 2000L);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void peersWithDifferentAddressAreNotEqual() {
        Peer first = new Peer("车机", "192.168.43.2:10110", 1000L);
        Peer second = new Peer("车机", "192.168.43.3:10110", 1000L);

        assertNotEquals(first, second);
    }

    @Test
    public void nullFieldsAreNormalizedToEmpty() {
        Peer peer = new Peer(null, null, 0L);

        assertEquals("", peer.name);
        assertEquals("", peer.address);
        assertEquals(new Peer(null, null, 0L), peer);
    }

    @Test
    public void peerIsNotEqualToNullOrOtherTypes() {
        Peer peer = new Peer("车机", "AA:BB:CC:DD:EE:FF", 1L);

        assertEquals(peer, peer);
        assertFalse(peer.equals(null));
        assertFalse(peer.equals("不是 Peer"));
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.link.PeerTest"
```

预期：编译失败，报 `cannot find symbol: class Peer`（`Peer.java` 还不存在）。

- [ ] **Step 3: 写最小实现**

创建 `app/src/main/java/com/gpsbt/transfer/link/Peer.java`：

```java
package com.gpsbt.transfer.link;

/**
 * 一个已连接的对端：蓝牙通道下是一台配对设备，WiFi 通道下是一个 TCP 客户端。
 *
 * 相等性只按 {@link #address} 判定——地址是通道内唯一标识对端的东西，
 * 因此同一个对端重连后（名称或接入时刻变了）仍能被认为是同一个。
 */
public final class Peer {

    /** 展示名。对端未上报名称时为空串。 */
    public final String name;

    /** 通道内唯一标识：蓝牙为 MAC，WiFi 为 "IP:端口"。 */
    public final String address;

    /** 接入时刻（毫秒时间戳），用于展示连接时长。 */
    public final long connectedAt;

    public Peer(String name, String address, long connectedAt) {
        this.name = name == null ? "" : name;
        this.address = address == null ? "" : address;
        this.connectedAt = connectedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Peer)) {
            return false;
        }
        return address.equals(((Peer) other).address);
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }

    @Override
    public String toString() {
        return name.isEmpty() ? address : name + "(" + address + ")";
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.link.PeerTest"
```

预期：`BUILD SUCCESSFUL`，4 个用例通过。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/link/Peer.java app/src/test/java/com/gpsbt/transfer/link/PeerTest.java
git commit -m "feat: 新增 link/Peer 值对象"
```

---

## Task 2: `link/Link` 传输通道接口

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/link/Link.java`

本任务只新增一个接口，没有任何调用方，因此**不需要测试**——纯接口没有可测行为。验证方式是编译通过。

- [ ] **Step 1: 创建接口**

创建 `app/src/main/java/com/gpsbt/transfer/link/Link.java`：

```java
package com.gpsbt.transfer.link;

import java.io.IOException;

/**
 * 一条把 NMEA 字节流送出去的传输通道。
 *
 * 蓝牙（SPP，单对端）与 WiFi（TCP 服务端，多对端）都实现本接口，
 * 发送侧（TransmitEngine / GpsOutputService）只依赖接口，不关心底层是什么。
 * 回调由实现方决定在哪个线程触发（蓝牙实现切到主线程）。
 */
public interface Link {

    /** 通道状态。顺序必须与 `state.TransmitterState.Bt` 一致。 */
    enum State { IDLE, WAITING, CONNECTING, CONNECTED, FAILED, DISCONNECTED }

    interface Listener {

        void onState(State state, String message);

        /** 一个接收端接入。 */
        void onPeerJoined(Peer peer);

        /** 一个接收端离开；reason 为人类可读的原因。 */
        void onPeerLeft(Peer peer, String reason);
    }

    /** 启动通道：监听（服务端）或发起连接（客户端）。 */
    void start();

    /** 停止通道并释放资源。可重复调用。 */
    void stop();

    /** 把字节广播给当前所有接收端；未就绪时抛 IOException。 */
    void write(byte[] data) throws IOException;

    /** 是否至少有一个接收端在线。 */
    boolean isConnected();

    /** 断开当前所有接收端，通道本身继续监听 / 可重连。 */
    void resetConnection();
}
```

- [ ] **Step 2: 编译验证**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/link/Link.java
git commit -m "feat: 新增 link/Link 传输通道接口"
```

---

## Task 3: 蓝牙实现 `Link`，发送服务改用 `Link`

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/bt/BluetoothLinkManager.java`
- Delete: `app/src/main/java/com/gpsbt/transfer/bt/BtState.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java`

**这个任务必须一次落地**：接口、实现、调用方三者互相引用，任何中间状态都编译不过。所以它是一笔提交，而不是三笔。

**这一笔之后蓝牙行为必须与 v1.4.6 完全一致**——没有任何逻辑变化，只有类型和回调签名的替换。

- [ ] **Step 1: 改造 `BluetoothLinkManager`**

打开 `app/src/main/java/com/gpsbt/transfer/bt/BluetoothLinkManager.java`，做以下改动。

**1a. 加 import**（放在 `import android.os.Looper;` 之后）：

```java
import com.gpsbt.transfer.link.Link;
import com.gpsbt.transfer.link.Peer;
```

**1b. 类声明与类注释**（原来是 `public final class BluetoothLinkManager {`）：

```java
/** 蓝牙 SPP（RFCOMM）通道：实现 {@link Link}，单对端。服务端/客户端模式在构造时确定。 */
public final class BluetoothLinkManager implements Link {
```

**1c. 删掉类内自带的 `Listener` 接口**，即删掉这一段：

```java
    /** 主线程回调。 */
    public interface Listener {
        void onState(BtState state, String message);

        void onConnected(String remoteName, String remoteMac);

        void onDisconnected(String message);
    }
```

**1d. 字段区**：把 `private final Listener listener;` 换成下面三行，并新增 `currentPeer`：

```java
    private final ConnectionMode mode;
    private final BluetoothDevice target;
    private final Link.Listener listener;
    private volatile Peer currentPeer;
```

**1e. 构造函数**整个替换为：

```java
    public BluetoothLinkManager(Context context, BluetoothAdapter adapter,
                                String serviceName, UUID uuid,
                                ConnectionMode mode, BluetoothDevice target,
                                Link.Listener listener) {
        String fallback = context.getString(R.string.app_name);
        this.serviceName = serviceName == null || serviceName.trim().isEmpty()
                ? fallback : serviceName;
        this.adapter = adapter;
        this.uuid = uuid == null ? FALLBACK_UUID : uuid;
        this.mode = mode == null ? ConnectionMode.SERVER : mode;
        this.target = target;
        this.listener = listener;
    }
```

**1f. 新增 `start()`，并把两个启动方法改为私有。** 在 `isConnected()` 方法之前插入：

```java
    @Override
    public void start() {
        if (mode == ConnectionMode.CLIENT) {
            startClient(target);
        } else {
            startServer();
        }
    }
```

然后把 `public void startServer() {` 改为 `private void startServer() {`，把 `public void startClient(BluetoothDevice device) {` 改为 `private void startClient(BluetoothDevice device) {`。

**1g. 给接口方法补 `@Override`**：`isConnected()`、`write(...)`、`stop()`、`resetConnection()` 各加一行 `@Override`（`resetConnection()` 原有注释保留）。

**1h. 全文替换状态枚举**：把文件里所有 `BtState.` 替换为 `Link.State.`，把方法签名 `private void postState(final BtState state, final String message)` 改为：

```java
    private void postState(final Link.State state, final String message) {
```

> 替换后 `postState` 内部的 `listener.onState(state, message);` 不用动——参数类型已经变成 `Link.State`。

**1i. `attach()` 里改为构造并记录 `Peer`**。把这段：

```java
        BluetoothDevice device = accepted.getRemoteDevice();
        String name = device == null ? "未知设备" : displayName(device);
        String mac = device == null ? "" : device.getAddress();
        postState(BtState.CONNECTED, "已连接 " + name);
        listener.onConnected(name, mac);
```

改为：

```java
        BluetoothDevice device = accepted.getRemoteDevice();
        String name = device == null ? "未知设备" : displayName(device);
        String mac = device == null ? "" : device.getAddress();
        Peer peer = new Peer(name, mac, System.currentTimeMillis());
        currentPeer = peer;
        postState(Link.State.CONNECTED, "已连接 " + name);
        listener.onPeerJoined(peer);
```

**1j. `ReadThread.postDisconnected` 改为回调 `onPeerLeft`**。把：

```java
                    if (running && !stopping) {
                        listener.onDisconnected(message);
                    }
```

改为：

```java
                    if (running && !stopping) {
                        listener.onPeerLeft(currentPeer, message);
                    }
```

**1k. `stop()` 里清空 `currentPeer`**。把：

```java
    public synchronized void stop() {
        stopping = true;
        closeSocket();
```

改为：

```java
    @Override
    public synchronized void stop() {
        stopping = true;
        closeSocket();
        currentPeer = null;
```

- [ ] **Step 2: 删除 `BtState.java`**

```bash
git rm app/src/main/java/com/gpsbt/transfer/bt/BtState.java
```

- [ ] **Step 3: 改造 `GpsOutputService`**

打开 `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java`，做以下改动。

**3a. import 区**：删掉这一行：

```java
import com.gpsbt.transfer.bt.BtState;
```

在 `import com.gpsbt.transfer.engine.TransmitEngine;` 之后加上：

```java
import com.gpsbt.transfer.link.Link;
import com.gpsbt.transfer.link.Peer;
```

**3b. 字段类型**：把 `private BluetoothLinkManager link;` 改为：

```java
    private Link link;
```

**3c. `connectLink` 整个替换为**：

```java
    /** 只建立（或重建）蓝牙链路，不重置定位与调度——断线重连时复用。 */
    private boolean connectLink(BluetoothAdapter adapter) {
        BluetoothDevice device = null;
        if (mode == ConnectionMode.CLIENT) {
            try {
                device = adapter.getRemoteDevice(deviceMac);
            } catch (IllegalArgumentException e) {
                state.setStatusText("目标设备地址无效：" + deviceMac);
                LogBus.get().log(LogBus.Level.ERROR, "目标设备地址无效：" + deviceMac);
                return false;
            }
            state.setPendingDevice(new TransmitterState.Device(
                    BluetoothLinkManager.displayName(device), deviceMac));
        }
        link = new BluetoothLinkManager(this, adapter, prefs.getServiceName(),
                UUID.fromString(prefs.getUuid()), mode, device, linkListener);
        if (mode == ConnectionMode.SERVER) {
            state.setStatusText("蓝牙服务启动中…");
        }
        link.start();
        return true;
    }
```

**3d. `tickInternal` 里的一行**：把

```java
        final BluetoothLinkManager currentLink = link;
```

改为：

```java
        final Link currentLink = link;
```

**3e. 监听器整个替换**：把从 `private final BluetoothLinkManager.Listener bluetoothListener =` 开始到该匿名类结束（即 `mapBt` 方法之前）的整段，替换为：

```java
    private final Link.Listener linkListener = new Link.Listener() {
        @Override
        public void onState(Link.State linkState, String message) {
            state.setBt(mapBt(linkState));
            state.setStatusText(message);
            LogBus.get().log(mapLevel(linkState), "蓝牙：" + message);
            if (linkState == Link.State.FAILED) {
                // 监听建立失败属于致命错误（权限/蓝牙不可用），停止服务
                stopSelfSafely();
                return;
            }
            refreshNotification();
        }

        @Override
        public void onPeerJoined(Peer peer) {
            state.setConnectedDevice(new TransmitterState.Device(peer.name, peer.address));
            state.setPendingDevice(null);
            stats.startedAt = System.currentTimeMillis();
            LogBus.get().log(LogBus.Level.INFO,
                    "已连接 " + peer.name + "（" + peer.address + "）");
            refreshNotification();
        }

        @Override
        public void onPeerLeft(Peer peer, String reason) {
            LogBus.get().log(LogBus.Level.WARN, "蓝牙已断开：" + reason);
            if (mode == ConnectionMode.SERVER) {
                // 服务器模式：清理死连接，accept 循环继续等待下一次连接
                Link currentLink = link;
                if (currentLink != null) {
                    currentLink.resetConnection();
                }
                state.setConnectedDevice(null);
                state.setBt(TransmitterState.Bt.WAITING);
                state.setStatusText("蓝牙已断开，等待接收端重新连接");
                refreshNotification();
            } else {
                state.setConnectedDevice(null);
                state.setStatusText("蓝牙连接已断开，尝试重新连接");
                scheduleRelink();
            }
        }
    };
```

**3f. 两个映射方法的参数类型**：把

```java
    private static TransmitterState.Bt mapBt(BtState value) {
```

改为：

```java
    private static TransmitterState.Bt mapBt(Link.State value) {
```

把

```java
    private static LogBus.Level mapLevel(BtState value) {
```

改为：

```java
    private static LogBus.Level mapLevel(Link.State value) {
```

> 这两个方法体内的 `case WAITING:` 等分支**不用改**——`switch` 的作用对象类型变了，常量名没变。

- [ ] **Step 4: 编译**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

若有 `cannot find symbol` 报错，逐一检查是否还有残留的 `BtState` 引用：

```bash
grep -rn "BtState" app/src --include=*.java
```

预期：**无输出**。

- [ ] **Step 5: 跑全部单测**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，**55 个用例**（原 51 + Task 1 的 4 个）全过。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "refactor: 传输层抽象为 Link 接口，蓝牙实现之

BluetoothLinkManager 实现 Link，构造时确定服务端/客户端模式，
内部逻辑不变；GpsOutputService 只依赖 Link。状态枚举 BtState
移入 Link.State，回调 onConnected/onDisconnected 合并为
onPeerJoined/onPeerLeft。行为与 v1.4.6 完全一致。"
```

---

## Task 4: 应用标识改为特别版

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 改 `app/build.gradle`**

在 `defaultConfig` 块里，把这三行：

```groovy
        applicationId 'com.gpsbt.transfer'
        minSdk 24
        targetSdk 34
        versionCode 11
        versionName '1.4.6'
```

改为：

```groovy
        applicationId 'com.gpsshare'
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName '1.0.0'
```

> `namespace 'com.gpsbt.transfer'` **保持不变**——它决定 `R` 类与 Java 包名，改了要动 30 个源文件。`applicationId` 才是安装身份，两个版本靠它并存。

- [ ] **Step 2: 找出并替换所有面向用户的应用名**

先看有哪些：

```bash
grep -n "GPS蓝牙传输" app/src/main/res/values/strings.xml
```

预期看到 **4** 行，全部要改：

| 行 | 资源名 | 用途 |
|---|---|---|
| 3 | `app_name` | 桌面图标名 |
| 12 | `home_title` | 主页标题 |
| 113 | `settings_about_body` | 关于页首行（应用名 + 版本号） |
| 123 | `channel_transmit_name` | 通知渠道名（系统设置里可见） |

把这 4 行的 `GPS蓝牙传输` 改为 `GPS共享`：

```xml
    <string name="app_name">GPS共享</string>
```

```xml
    <string name="home_title">GPS共享</string>
```

```xml
    <string name="settings_about_body">GPS共享 %1$s\n把本机 GPS 定位以标准 NMEA 0183 格式通过蓝牙串口输出给其他设备。…</string>
```

```xml
    <string name="channel_transmit_name">GPS共享服务</string>
```

改完再跑一次 grep（**必须同时查带空格的写法**——`channel_transmit_desc` 里写的是「GPS 蓝牙传输」，只查不带空格的会漏掉），预期**无输出**：

```bash
grep -rn "GPS蓝牙传输\|GPS 蓝牙传输" app/src/ || echo "(无残留)"
```

> 其余含「蓝牙」的文案**不要改**——蓝牙仍是支持的传输方式（如「通过蓝牙串口输出」这句在 P1 阶段仍然准确，P2 加入 WiFi 后再更新）。

- [ ] **Step 3: 构建并确认 APK 身份正确**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```

预期：`BUILD SUCCESSFUL`，产物在 `构建目录\app\outputs\apk\debug\app-debug.apk`。

用 aapt 核对包名与版本（`aapt` 在 Android SDK 的 `build-tools/35.0.0/` 下）：

```bash
"/c/Users/<用户名>/AppData/Local/Android/Sdk/build-tools/35.0.0/aapt.exe" dump badging 构建目录/app/outputs/apk/debug/app-debug.apk | head -3
```

预期输出含 `package: name='com.gpsshare'` 与 `versionName='1.0.0'`，且 `application-label:'GPS共享'`。

- [ ] **Step 4: 提交**

```bash
git add app/build.gradle app/src/main/res/values/strings.xml
git commit -m "chore: 特别版应用标识（com.gpsshare / GPS共享 / 1.0.0）"
```

---

## Task 5: P1 验收

**Files:** 无改动，纯验证。

- [ ] **Step 1: 全量干净构建 + 单测**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew clean testDebugUnitTest assembleDebug
```

预期：`BUILD SUCCESSFUL`，55 个用例全过，APK 产出。

- [ ] **Step 2: 安装到真机**

```bash
adb install -r 构建目录/app/outputs/apk/debug/app-debug.apk
```

预期：安装成功，桌面出现「GPS共享」图标，**原版「GPS蓝牙传输」若已安装则不被覆盖**（这是 `applicationId` 分离的目的）。

- [ ] **Step 3: 蓝牙回归检查表**

逐项确认与 v1.4.6 行为一致：

| # | 检查项 | 预期 |
|---|---|---|
| 1 | 选「等待被连接」→ 点「开始传输」 | 状态变「等待设备连接」，通知出现 |
| 2 | 对端（PC 串口工具）连上手机的 SPP 服务 | 状态变「已连接，发送中」，主页出现坐标 |
| 3 | 对端收到 NMEA | 每秒若干帧 `$GPGGA`/`$GPRMC`/…，格式与 v1.4.6 一致 |
| 4 | 拔掉/关闭对端连接 | 状态回到「等待接收端重新连接」，日志有「蓝牙已断开」 |
| 5 | 对端重新连上 | 恢复正常发送，无需重启服务 |
| 6 | 切到「主动连接」→ 选已配对设备 → 开始 | 能主动连上，状态与数据正常 |
| 7 | 点「停止传输」 | 服务停止，通知消失，状态复位 |
| 8 | 设置页改发送频率到 5 Hz | 对端收帧速率随之变化 |
| 9 | 日志页 | 连接/断开/发送都有记录，分类筛选正常 |

任何一项不符，回到 Task 3 检查对应改动。

- [ ] **Step 4: 记录验收结果**

把回归检查结果写进本计划文档末尾的「验收记录」一节并提交：

```bash
git add docs/superpowers/plans/2026-10-12-gps-share-p1-link-abstraction.md
git commit -m "docs: P1 蓝牙回归验收记录"
```

---

## 验收记录

> 执行完成后填写：日期、真机型号、Android 版本、逐项结果、发现的问题。

（待填）

---

## 执行偏离记录

计划执行过程中对本文档正文做出的修正，逐条记下原因，便于日后追溯。

| 位置 | 计划原文 | 实际做法 | 原因 |
|---|---|---|---|
| Task 2 | `Link.State` 写成 `…CONNECTED, DISCONNECTED, FAILED` | `…CONNECTED, FAILED, DISCONNECTED` | 与仓库既有 `bt.BtState` / `state.TransmitterState.Bt` 的顺序对齐；原计划是笔误 |
| Task 4 Step 2 | 只列了 2 处旧应用名 | 实为 4 处，后又补出 2 处 Java 常量与 1 处带空格写法，共 7 处 | 原计划靠肉眼列举，漏了 `settings_about_body`、`channel_transmit_name`、`Prefs.DEFAULT_SERVICE_NAME`、`LogExporter` 日志头、`channel_transmit_desc`（写法带空格） |
| Task 3 Step 1e | 构造器保留 `Context context`，服务名兜底用 `R.string.app_name` | 去掉 `Context` 参数，兜底改用 `Prefs.DEFAULT_SERVICE_NAME` | 代码审查发现服务名有两个事实源：应用改名时 SDP 记录会与设置页显示的默认值分叉 |
| Task 4 设计文档 | 「服务名取自 `R.string.app_name`」 | 更正为「取自 `Prefs.DEFAULT_SERVICE_NAME`」 | 原文对机制的描述有误 |

**连带更新**：`GpsOutputService.connectLink` 的构造调用相应去掉了 `this` 参数。

---

## 自检

**规格覆盖**（对照设计文档 §3.1、§2.2、§11 P1）：

| 设计要求 | 落在哪个任务 |
|---|---|
| `link/Link` 接口 + `Peer` | Task 1、Task 2 |
| `BluetoothLinkManager implements Link` | Task 3 |
| `GpsOutputService` 只依赖 `Link` | Task 3 |
| `applicationId` / 应用名 / 版本号 | Task 4 |
| 判据：蓝牙行为与 v1.4.6 等价 | Task 5 |
| `TransmitEngine` / `NmeaGenerator` / `LocationProvider` 不改 | 全计划未触碰这三个文件 |

**类型一致性**：`Link.State`（六个枚举值与 `TransmitterState.Bt` 顺序一致）、`Link.Listener`（`onState` / `onPeerJoined` / `onPeerLeft`）、`Peer(name, address, connectedAt)`、`Link` 的五个方法（`start` / `stop` / `write` / `isConnected` / `resetConnection`）在 Task 2 定义一次，Task 3 全部按此实现与调用，无第二套命名。

**刻意留到 P2 的**：`TransmitterState` 的 `Device` → 多对端列表（P2 需要展示多个接收端时才改）；`bt/ConnectionMode` 的归属（P2 引入传输方式维度后再定）。

**P1 执行中记下的、必须在 P2 兑现的接口约束**（来自 Task 2 的代码质量审查）：

1. **`write()` 抛 `IOException` 只能表示「整条通道不可用」，不能表示「某一个对端死了」。** 蓝牙是单对端，两者等价；但 WiFi 是多对端，某个 TCP 客户端写失败必须**静默摘除该客户端、继续发给其余对端**，绝不能抛出去——否则 `GpsOutputService` 的 catch 分支会误判为链路整体故障而停掉服务。
2. **`stop()` 必须幂等，且重复调用不得再次触发回调。** 现有蓝牙实现的 `stop()` 每次都会 post 一次 `IDLE` 状态；重复调用会让监听方收到多余的「链路已关闭」事件。
3. **多对端场景下回调与 `write()` 可能来自不同线程**，实现方需自行保证可见性。
4. **线程约定应作为硬性要求写进 P2 计划**：`Listener` 回调统一在主线程投递（蓝牙实现已如此），否则 `GpsOutputService` 里每个回调点都要重新审一遍线程安全。
