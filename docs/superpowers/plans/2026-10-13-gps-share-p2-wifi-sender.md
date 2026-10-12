# GPS共享 特别版 — P2：WiFi 发送端 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让手机在热点上开 TCP 服务，把 NMEA 广播给**多个**接收端，并用 UDP 信标让接收端免手输 IP 自动发现；界面上可在蓝牙 / WiFi 两种传输方式间切换。

**Architecture:** 新增 `net` 包：`NetLinkManager` 实现 P1 引入的 `Link` 接口（TCP 服务端，多对端），内部用可单测的 `PeerRegistry` 管理客户端集合；`DiscoveryBeacon` + `DiscoveryBroadcaster` 负责 UDP 服务发现；`HotspotAddresses` 负责找出热点网卡地址。`GpsOutputService` 按 `Prefs` 里的传输方式构造蓝牙或 WiFi 链路——它对两者一视同仁，只认 `Link`。

**Tech Stack:** Java 17、Android Gradle Plugin 8.7.3、Gradle 8.12、JUnit 4、Material Components 3（XML 布局）

---

## 本计划的范围

四阶段中的第二阶段（P1 已完成）：

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 骨架 + 传输层抽象（`link/Link` + `Peer`，蓝牙实现之） | 完成，分支 `p1-link-abstraction` |
| **P2** | **WiFi 发送端（多接收端 + 服务发现）** | **本文档** |
| P3 | 接收端（NMEA 解析 + 模拟位置注入） | 待写 |
| P4 | 打磨（文档、截图） | 待写 |

**P2 的完成判据**：手机开热点后，PC 上用 TCP 工具连 `192.168.x.1:10110` 能持续收到 NMEA 帧；**两台接收端可同时收**；接收端能通过 UDP 信标自动发现手机。

**分支**：从 `p1-link-abstraction` 开出 `p2-wifi-sender`。

---

## 必须兑现的 P1 接口约束

P1 执行期间记下的四条约束（见 `2026-10-12-gps-share-p1-link-abstraction.md` 的「P1 执行中记下的」一节），本阶段逐条落实：

| # | 约束 | 本计划落点 |
|---|---|---|
| 1 | `write()` 抛 `IOException` 只能表示「整条通道不可用」，不能表示「某一个对端死了」 | `NetLinkManager.write()` **永不抛**；单个客户端写失败由 `PeerRegistry.broadcast()` 静默剔除 → Task 3、Task 4 |
| 2 | `stop()` 必须幂等，重复调用不得再次触发回调 | `NetLinkManager` 用 `started` 标志短路 → Task 4 |
| 3 | 多对端下回调与 `write()` 可能来自不同线程 | `PeerRegistry` 用 `CopyOnWriteArrayList` → Task 3 |
| 4 | `Listener` 回调统一在主线程投递 | `NetLinkManager` 所有回调经 `Handler(mainLooper).post` → Task 4 |

> 约束 4 只对**新实现**生效。蓝牙实现保持 v1.4.6 原样（`onPeerJoined` 仍由 accept 线程直接调用），P1 的 Javadoc 已如实说明。

---

## 前置条件

- 工程根目录：`工作区根目录\GPS共享`
- 所有命令在该目录下执行；Gradle 统一带 `JAVA_HOME`：

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew <任务>
```

- `local.properties` 必须含 `build.dir=C\:/build`（已配好，别动）
- **基线状态**：P1 完成后 55 个单测全过、`assembleDebug` 正常

---

## 文件结构

| 动作 | 路径 | 职责 |
|---|---|---|
| Create | `app/src/main/java/com/gpsbt/transfer/link/Transport.java` | 传输方式枚举：蓝牙 / WiFi |
| Create | `app/src/main/java/com/gpsbt/transfer/net/PeerRegistry.java` | 多对端注册表：广播、失效剔除（纯逻辑，可单测） |
| Create | `app/src/main/java/com/gpsbt/transfer/net/NetLinkManager.java` | TCP 服务端，实现 `Link` |
| Create | `app/src/main/java/com/gpsbt/transfer/net/DiscoveryBeacon.java` | 发现信标的编解码（纯逻辑，可单测） |
| Create | `app/src/main/java/com/gpsbt/transfer/net/DiscoveryBroadcaster.java` | 周期性 UDP 广播信标 |
| Create | `app/src/main/java/com/gpsbt/transfer/net/HotspotAddresses.java` | 本机 IPv4 枚举与热点网卡识别 |
| Create | `app/src/test/java/com/gpsbt/transfer/net/PeerRegistryTest.java` | — |
| Create | `app/src/test/java/com/gpsbt/transfer/net/DiscoveryBeaconTest.java` | — |
| Create | `app/src/test/java/com/gpsbt/transfer/net/HotspotAddressesTest.java` | — |
| Create | `app/src/main/res/layout/item_peer.xml` | 在线接收端列表项 |
| Create | `app/src/main/java/com/gpsbt/transfer/ui/adapter/PeerAdapter.java` | 在线接收端列表适配器 |
| Modify | `app/src/main/java/com/gpsbt/transfer/util/Prefs.java` | 传输方式、WiFi 端口、服务发现开关与端口 |
| Modify | `app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java` | 多对端列表 + 传输方式 |
| Modify | `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java` | 按传输方式构造链路、多对端回调、发现广播生命周期 |
| Modify | `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java` | 传输方式切换、WiFi 服务卡片、在线接收端列表 |
| Modify | `app/src/main/java/com/gpsbt/transfer/ui/fragment/SettingsFragment.java` | WiFi 端口、服务发现开关 |
| Modify | `app/src/main/res/layout/fragment_home.xml` | 新增视图 |
| Modify | `app/src/main/res/layout/fragment_settings.xml` | 新增设置项 |
| Modify | `app/src/main/res/values/strings.xml` | 新增文案 |
| Modify | `app/src/main/AndroidManifest.xml` | 加 `INTERNET` 权限 |
| Modify | `app/src/test/java/com/gpsbt/transfer/state/TransmitterStateTest.java` | 适配多对端 |

---

## Task 1: `link/Transport` 枚举与 WiFi 设置持久化

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/link/Transport.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/util/Prefs.java`

- [ ] **Step 1: 创建 `Transport` 枚举**

创建 `app/src/main/java/com/gpsbt/transfer/link/Transport.java`：

```java
package com.gpsbt.transfer.link;

/** 发送端的传输方式。顺序必须与 `state.TransmitterState.Transport` 一致。 */
public enum Transport {
    /** 蓝牙 SPP（单对端，服务端 / 客户端两种模式）。 */
    BLUETOOTH,
    /** WiFi / TCP 服务端（多对端，只在热点上监听）。 */
    WIFI
}
```

- [ ] **Step 2: `Prefs` 增加 WiFi 相关设置**

在 `app/src/main/java/com/gpsbt/transfer/util/Prefs.java` 中：

**2a.** 顶部 import 区，把

```java
import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.nmea.NmeaSentenceType;
```

改为

```java
import com.gpsbt.transfer.bt.ConnectionMode;
import com.gpsbt.transfer.link.Transport;
import com.gpsbt.transfer.nmea.NmeaSentenceType;
```

**2b.** 常量区，在 `DEFAULT_AUTO_START` 之后插入：

```java
    /** WiFi 通道默认端口：NMEA over TCP 的行业惯例端口。 */
    public static final int DEFAULT_WIFI_PORT = 10110;
    /** 服务发现默认端口。 */
    public static final int DEFAULT_DISCOVERY_PORT = 10111;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;
```

**2c.** 键名区，在 `KEY_AUTO_START` 之后插入：

```java
    private static final String KEY_TRANSPORT = "transport";
    private static final String KEY_WIFI_PORT = "wifi_port";
    private static final String KEY_DISCOVERY_ENABLED = "discovery_enabled";
    private static final String KEY_DISCOVERY_PORT = "discovery_port";
```

**2d.** 在 `setAutoStartOnBoot` 方法之后、`defaultSentenceTokens` 之前插入：

```java
    public Transport getTransport() {
        String name = prefs.getString(KEY_TRANSPORT, Transport.BLUETOOTH.name());
        try {
            return Transport.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Transport.BLUETOOTH;
        }
    }

    public void setTransport(Transport value) {
        prefs.edit().putString(KEY_TRANSPORT,
                (value == null ? Transport.BLUETOOTH : value).name()).apply();
    }

    public int getWifiPort() {
        return clampInt(MIN_PORT, MAX_PORT,
                prefs.getInt(KEY_WIFI_PORT, DEFAULT_WIFI_PORT));
    }

    public void setWifiPort(int value) {
        prefs.edit().putInt(KEY_WIFI_PORT, clampInt(MIN_PORT, MAX_PORT, value)).apply();
    }

    public boolean isDiscoveryEnabled() {
        return prefs.getBoolean(KEY_DISCOVERY_ENABLED, true);
    }

    public void setDiscoveryEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_DISCOVERY_ENABLED, value).apply();
    }

    public int getDiscoveryPort() {
        return clampInt(MIN_PORT, MAX_PORT,
                prefs.getInt(KEY_DISCOVERY_PORT, DEFAULT_DISCOVERY_PORT));
    }

    public void setDiscoveryPort(int value) {
        prefs.edit().putInt(KEY_DISCOVERY_PORT, clampInt(MIN_PORT, MAX_PORT, value)).apply();
    }
```

- [ ] **Step 3: 编译**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/link/Transport.java app/src/main/java/com/gpsbt/transfer/util/Prefs.java
git commit -m "feat: 新增 Transport 枚举与 WiFi 设置项"
```

---

## Task 2: `TransmitterState` 支持多对端与传输方式

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java`
- Modify: `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java`
- Modify: `app/src/test/java/com/gpsbt/transfer/state/TransmitterStateTest.java`

**关键取舍**：`connectedDevice()` 保留为**派生 getter**（返回第一个对端），这样 `HomeFragment` 本任务不用动、仍能编译；界面改造留到 Task 7。`setConnectedDevice()` 删除，由 `addPeer` / `removePeer` / `setPeers` 取代。

- [ ] **Step 1: 先改测试，让它失败**

在 `app/src/test/java/com/gpsbt/transfer/state/TransmitterStateTest.java` 中：

**1a.** import 区加上：

```java
import com.gpsbt.transfer.link.Peer;
```

**1b.** 把测试方法 `devicesAndFixAreExposed` 整个替换为：

```java
    @Test
    public void peersAreAddedRemovedAndDerivedAsConnectedDevice() {
        Peer car = new Peer("车机", "192.168.43.2:10110", 1000L);
        Peer tablet = new Peer("平板", "192.168.43.3:10110", 2000L);
        state.addPeer(car);
        state.addPeer(tablet);

        assertEquals(2, state.peers().size());
        // connectedDevice 是派生值：单对端时等于那一个，多对端时取第一个
        assertEquals("车机", state.connectedDevice().name);

        state.removePeer(car);
        assertEquals(1, state.peers().size());
        assertEquals("平板", state.connectedDevice().name);

        state.removePeer(tablet);
        assertTrue(state.peers().isEmpty());
        assertNull(state.connectedDevice());
    }

    @Test
    public void transportIsExposedAndResetsToBluetooth() {
        state.setTransport(TransmitterState.Transport.WIFI);
        assertEquals(TransmitterState.Transport.WIFI, state.transport());

        state.resetForStop();
        assertEquals(TransmitterState.Transport.BLUETOOTH, state.transport());
    }

    @Test
    public void peersAreExposed() {
        Fix fix = new Fix();
        fix.valid = true;
        fix.latitude = 31.1;
        state.setFix(fix);
        assertSame(fix, state.fix());

        assertNull(state.pendingDevice());
        assertTrue(state.peers().isEmpty());
    }
```

**1c.** import 区把 `assertTrue` 加进来：

```java
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
```

**1d.** 把 `defaultStateIsIdle` 里的

```java
        assertNull(state.connectedDevice());
```

改为

```java
        assertTrue(state.peers().isEmpty());
        assertNull(state.connectedDevice());
        assertEquals(TransmitterState.Transport.BLUETOOTH, state.transport());
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.state.TransmitterStateTest"
```

预期：编译失败，报找不到 `addPeer` / `peers` / `Transport` 等符号。

- [ ] **Step 3: 改 `TransmitterState`**

在 `app/src/main/java/com/gpsbt/transfer/state/TransmitterState.java` 中：

**3a.** import 区加上：

```java
import com.gpsbt.transfer.link.Peer;
```

**3b.** 枚举区，在 `Mode` 之后插入：

```java
    /** 传输方式。顺序与 `link.Transport` 一致。 */
    public enum Transport { BLUETOOTH, WIFI }
```

**3c.** 字段区，把

```java
    private volatile Device connectedDevice;
```

改为

```java
    private volatile List<Peer> peers = Collections.emptyList();
```

并在其后（`pendingDevice` 之后）加上：

```java
    private volatile Transport transport = Transport.BLUETOOTH;
```

**3d.** 把 getter `connectedDevice()` 替换为：

```java
    public List<Peer> peers() {
        return peers;
    }

    /**
     * 派生值：第一个对端。蓝牙恒为单对端，因此等价于「已连接的设备」。
     * 多对端场景请改用 {@link #peers()}。
     */
    public Device connectedDevice() {
        List<Peer> snapshot = peers;
        if (snapshot.isEmpty()) {
            return null;
        }
        Peer first = snapshot.get(0);
        return new Device(first.name, first.address);
    }

    public Transport transport() {
        return transport;
    }
```

**3e.** 把 setter `setConnectedDevice(Device value)` 替换为：

```java
    public void setPeers(List<Peer> value) {
        peers = value == null
                ? Collections.<Peer>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(value));
        notifyListeners();
    }

    /** 追加一个对端；已存在（地址相同）则忽略。 */
    public void addPeer(Peer peer) {
        if (peer == null) {
            return;
        }
        List<Peer> next = new ArrayList<>(peers);
        if (!next.contains(peer)) {
            next.add(peer);
        }
        setPeers(next);
    }

    /** 移除一个对端；不存在则不做任何通知。 */
    public void removePeer(Peer peer) {
        if (peer == null) {
            return;
        }
        List<Peer> next = new ArrayList<>(peers);
        if (next.remove(peer)) {
            setPeers(next);
        }
    }

    public void setTransport(Transport value) {
        transport = value == null ? Transport.BLUETOOTH : value;
        notifyListeners();
    }
```

**3f.** `resetForStop()` 里把

```java
        connectedDevice = null;
```

改为

```java
        peers = Collections.emptyList();
        transport = Transport.BLUETOOTH;
```

- [ ] **Step 4: 改 `GpsOutputService` 的对端写入**

在 `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java` 中，把 `linkListener` 里两处 `setConnectedDevice` 调用替换：

**4a.** `onPeerJoined` 里把

```java
            state.setConnectedDevice(new TransmitterState.Device(peer.name, peer.address));
```

改为

```java
            state.addPeer(peer);
```

**4b.** `onPeerLeft` 里两处 `state.setConnectedDevice(null);` 都改为 `state.removePeer(peer);`（SERVER 分支和 CLIENT 分支各一处）。

**4c.** `tickInternal` 的写失败分支里把

```java
                state.setConnectedDevice(null);
```

改为

```java
                state.setPeers(null);
```

> 写失败意味着这条链路整体不可用（服务器模式下 accept 循环会重新接受连接），因此清空全部对端而不是逐个移除。

**4d.** 确认文件里已无 `setConnectedDevice` 残留：

```bash
grep -rn "setConnectedDevice" app/src/ || echo "(无残留)"
```

- [ ] **Step 5: 跑全部单测**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，**57 个用例**（55 + Task 2 净增 2：新增 3 个、替换掉 1 个）全过。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat: TransmitterState 支持多对端与传输方式

connectedDevice 改为派生 getter（返回首个对端），setConnectedDevice
由 addPeer/removePeer/setPeers 取代。界面改造留到 Task 7。"
```

---

## Task 3: `net/PeerRegistry` 多对端注册表

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/net/PeerRegistry.java`
- Test: `app/src/test/java/com/gpsbt/transfer/net/PeerRegistryTest.java`

这是本阶段最核心的可单测逻辑，直接兑现 P1 约束 #1（单个对端写失败不得影响其余）与 #3（跨线程）。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/gpsbt/transfer/net/PeerRegistryTest.java`：

```java
package com.gpsbt.transfer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PeerRegistryTest {

    /** 记录写入内容的假客户端；可设成写入即失败。 */
    private static final class FakeClient implements PeerRegistry.Client {
        final String address;
        final List<String> written = new ArrayList<>();
        boolean failOnWrite;
        boolean closed;

        FakeClient(String address) {
            this.address = address;
        }

        @Override
        public String address() {
            return address;
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (failOnWrite) {
                throw new IOException("写失败");
            }
            written.add(new String(data));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    public void addRemoveAndSnapshot() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient a = new FakeClient("192.168.43.2:10110");
        FakeClient b = new FakeClient("192.168.43.3:10110");

        assertTrue(registry.isEmpty());
        registry.add(a);
        registry.add(b);
        assertEquals(2, registry.size());
        assertFalse(registry.isEmpty());

        assertTrue(registry.remove(a));
        assertEquals(1, registry.size());
        assertFalse(registry.remove(a));
    }

    @Test
    public void broadcastReachesEveryClient() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient a = new FakeClient("192.168.43.2:10110");
        FakeClient b = new FakeClient("192.168.43.3:10110");
        registry.add(a);
        registry.add(b);

        registry.broadcast("$GPGGA,1,2,3\r\n".getBytes());

        assertEquals(1, a.written.size());
        assertEquals(1, b.written.size());
        assertEquals("$GPGGA,1,2,3\r\n", a.written.get(0));
    }

    @Test
    public void failingClientIsDroppedWithoutAffectingOthers() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient dead = new FakeClient("192.168.43.2:10110");
        FakeClient alive = new FakeClient("192.168.43.3:10110");
        dead.failOnWrite = true;
        registry.add(dead);
        registry.add(alive);

        // 关键契约：广播不抛异常，失败的对端被静默剔除
        registry.broadcast("x".getBytes());

        assertEquals(1, registry.size());
        assertTrue(dead.closed);
        assertEquals(1, alive.written.size());
    }

    @Test
    public void closeAllClosesEveryClientAndEmpties() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient a = new FakeClient("192.168.43.2:10110");
        FakeClient b = new FakeClient("192.168.43.3:10110");
        registry.add(a);
        registry.add(b);

        registry.closeAll();

        assertTrue(a.closed);
        assertTrue(b.closed);
        assertTrue(registry.isEmpty());
    }

    @Test
    public void snapshotIsACopy() {
        PeerRegistry registry = new PeerRegistry();
        FakeClient a = new FakeClient("192.168.43.2:10110");
        registry.add(a);

        List<PeerRegistry.Client> snapshot = registry.snapshot();
        registry.closeAll();

        assertEquals(1, snapshot.size());
        assertSame(a, snapshot.get(0));
        assertTrue(registry.isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.net.PeerRegistryTest"
```

预期：编译失败，报找不到 `PeerRegistry`。

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/gpsbt/transfer/net/PeerRegistry.java`：

```java
package com.gpsbt.transfer.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多对端注册表：维护一组已接入的 TCP 客户端，负责广播与失效剔除。
 *
 * 纯逻辑、无 Android 依赖，可在 JVM 上直接单测。核心契约：
 * 单个客户端写失败**不得**影响其余客户端，也不得抛给调用方——
 * 否则发送循环会把「一个接收端掉线」误判成「整条链路故障」而停掉服务。
 */
public final class PeerRegistry {

    /** 一个已接入的客户端。 */
    public interface Client {

        /** 通道内唯一标识，形如 "192.168.43.2:10110"。 */
        String address();

        /** 写一帧数据；失败时抛 IOException。 */
        void write(byte[] data) throws IOException;

        /** 关闭底层连接。必须幂等。 */
        void close();
    }

    private final List<Client> clients = new CopyOnWriteArrayList<>();

    public void add(Client client) {
        if (client != null) {
            clients.add(client);
        }
    }

    public boolean remove(Client client) {
        return client != null && clients.remove(client);
    }

    public int size() {
        return clients.size();
    }

    public boolean isEmpty() {
        return clients.isEmpty();
    }

    /** 当前客户端的快照副本，供界面展示。 */
    public List<Client> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(clients));
    }

    /**
     * 广播给所有客户端。**永不抛异常**：写失败的客户端被静默摘除并关闭，
     * 其余客户端照常收到数据。
     */
    public void broadcast(byte[] data) {
        for (Client client : clients) {
            try {
                client.write(data);
            } catch (IOException e) {
                // 这个对端死了，摘掉即可——不是整条链路的故障
                clients.remove(client);
                client.close();
            }
        }
    }

    /** 关闭并清空全部客户端。 */
    public void closeAll() {
        for (Client client : clients) {
            client.close();
        }
        clients.clear();
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.net.PeerRegistryTest"
```

预期：`BUILD SUCCESSFUL`，5 个用例通过。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/net/PeerRegistry.java app/src/test/java/com/gpsbt/transfer/net/PeerRegistryTest.java
git commit -m "feat: 新增 net/PeerRegistry 多对端注册表

单个客户端写失败静默剔除、不影响其余，兑现 P1 记下的接口约束 #1。"
```

---

## Task 4: `net/NetLinkManager` TCP 服务端

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/net/NetLinkManager.java`

Socket 层不做单测（与原版一致），靠真机 / PC 验证。本任务兑现约束 #2（`stop()` 幂等）与 #4（回调统一主线程）。

- [ ] **Step 1: 写实现**

创建 `app/src/main/java/com/gpsbt/transfer/net/NetLinkManager.java`：

```java
package com.gpsbt.transfer.net;

import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.link.Link;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.state.LogBus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * WiFi / TCP 服务端通道：实现 {@link Link}，多对端。
 *
 * 与蓝牙实现的差异（均为有意为之）：
 * - {@code write()} **永不抛 IOException**——单个接收端写失败由注册表静默剔除，
 *   发送循环不应因此停掉服务；
 * - {@code stop()} 幂等，重复调用不再触发回调；
 * - 所有 Listener 回调统一经 Handler 投递到主线程。
 */
public final class NetLinkManager implements Link {

    private static final int READ_BUFFER = 256;

    private final int port;
    private final Link.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PeerRegistry registry = new PeerRegistry();

    private volatile boolean stopping;
    private volatile boolean started;
    private ServerSocket server;
    private Thread acceptThread;

    public NetLinkManager(int port, Link.Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        started = true;
        stopping = false;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "net-accept");
        acceptThread.start();
    }

    /** 幂等：重复调用不做事，也不再触发回调。 */
    @Override
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        stopping = true;
        closeServer();
        registry.closeAll();
        postState(Link.State.IDLE, "WiFi 服务已关闭");
    }

    /**
     * 广播给所有接收端。**永不抛异常**——单个客户端失败由注册表处理。
     * 接口声明了 throws IOException，但本实现不需要；保留声明是为了满足接口契约。
     */
    @Override
    public void write(byte[] data) {
        registry.broadcast(data);
    }

    @Override
    public boolean isConnected() {
        return !registry.isEmpty();
    }

    /** 断开所有接收端，继续监听等待重连。 */
    @Override
    public void resetConnection() {
        registry.closeAll();
    }

    /** 当前监听端口。 */
    public int port() {
        return port;
    }

    // ---------- accept ----------

    private void acceptLoop() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            server = socket;
        } catch (IOException e) {
            postState(Link.State.FAILED, "WiFi 服务启动失败：" + e.getMessage());
            return;
        }
        postState(Link.State.WAITING, "WiFi 服务已开启，等待接收端连接（端口 " + port + "）");
        while (!stopping) {
            Socket accepted;
            try {
                accepted = server.accept();
            } catch (IOException e) {
                if (!stopping) {
                    postState(Link.State.FAILED, "WiFi 监听中断：" + e.getMessage());
                }
                break;
            }
            if (stopping || accepted == null) {
                closeQuietly(accepted);
                break;
            }
            attach(accepted);
        }
        closeServer();
    }

    private void attach(Socket socket) {
        if (stopping) {
            closeQuietly(socket);
            return;
        }
        String address = socket.getInetAddress() == null
                ? "未知地址" : socket.getInetAddress().getHostAddress();
        String label = address + ":" + socket.getPort();
        TcpClient client = new TcpClient(socket, label);
        if (!client.open()) {
            return;
        }
        registry.add(client);
        final Peer peer = new Peer(label, label, System.currentTimeMillis());
        main.post(new Runnable() {
            @Override
            public void run() {
                if (stopping) {
                    return;
                }
                postStateInternal(Link.State.CONNECTED, "接收端已连接：" + peer.address);
                listener.onPeerJoined(peer);
            }
        });
        LogBus.get().log(LogBus.Level.INFO, "WiFi 接收端接入：" + label);
    }

    private void closeServer() {
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }

    // ---------- 回调 ----------

    private void postState(final Link.State state, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                postStateInternal(state, message);
            }
        });
    }

    private void postStateInternal(Link.State state, String message) {
        listener.onState(state, message);
    }

    /** 一个 TCP 接收端。读线程检测到 EOF / 异常即判定对端断开。 */
    private final class TcpClient implements PeerRegistry.Client {

        private final Socket socket;
        private final String address;
        private OutputStream out;
        private InputStream in;
        private Thread reader;
        private volatile boolean alive = true;

        TcpClient(Socket socket, String address) {
            this.socket = socket;
            this.address = address;
        }

        @Override
        public String address() {
            return address;
        }

        boolean open() {
            try {
                out = socket.getOutputStream();
                in = socket.getInputStream();
            } catch (IOException e) {
                LogBus.get().log(LogBus.Level.WARN, "WiFi 取流失败：" + e.getMessage());
                close();
                return false;
            }
            reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "net-read-" + address);
            reader.start();
            return true;
        }

        @Override
        public void write(byte[] data) throws IOException {
            OutputStream stream = out;
            if (stream == null || !alive) {
                throw new IOException("接收端已断开");
            }
            stream.write(data);
            stream.flush();
        }

        @Override
        public void close() {
            if (!alive) {
                return;
            }
            alive = false;
            closeQuietly(socket);
        }

        private void readLoop() {
            byte[] buffer = new byte[READ_BUFFER];
            while (alive && !stopping) {
                int length;
                try {
                    length = in.read(buffer);
                } catch (IOException e) {
                    break;
                }
                if (length < 0) {
                    break;
                }
                // 接收端不发数据，收到即忽略
            }
            if (!alive) {
                return;
            }
            alive = false;
            registry.remove(this);
            closeQuietly(socket);
            LogBus.get().log(LogBus.Level.WARN, "WiFi 接收端断开：" + address);
            final Peer peer = new Peer(address, address, 0L);
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onPeerLeft(peer, "接收端已断开");
                }
            });
        }
    }
}
```

- [ ] **Step 2: 编译**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 跑全部单测**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，**62 个用例**（57 + Task 3 的 PeerRegistryTest 5 个）全过。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/gpsbt/transfer/net/NetLinkManager.java
git commit -m "feat: 新增 net/NetLinkManager TCP 服务端通道

实现 Link，多对端。write() 永不抛、stop() 幂等、回调统一主线程，
兑现 P1 记下的接口约束 #2 与 #4。"
```

---

## Task 5: 服务发现与热点地址

**Files:**
- Create: `app/src/main/java/com/gpsbt/transfer/net/DiscoveryBeacon.java`
- Create: `app/src/main/java/com/gpsbt/transfer/net/DiscoveryBroadcaster.java`
- Create: `app/src/main/java/com/gpsbt/transfer/net/HotspotAddresses.java`
- Test: `app/src/test/java/com/gpsbt/transfer/net/DiscoveryBeaconTest.java`
- Test: `app/src/test/java/com/gpsbt/transfer/net/HotspotAddressesTest.java`

- [ ] **Step 1: 写 `DiscoveryBeacon` 的失败测试**

创建 `app/src/test/java/com/gpsbt/transfer/net/DiscoveryBeaconTest.java`：

```java
package com.gpsbt.transfer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.Charset;

public class DiscoveryBeaconTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void encodeDecodeRoundTrip() {
        byte[] data = DiscoveryBeacon.encode("车机的手机", 10110);

        DiscoveryBeacon.Info info = DiscoveryBeacon.decode(data, data.length);

        assertNotNull(info);
        assertEquals("车机的手机", info.name);
        assertEquals(10110, info.tcpPort);
    }

    @Test
    public void nameWithSeparatorIsEscaped() {
        byte[] data = DiscoveryBeacon.encode("a|b", 10110);

        DiscoveryBeacon.Info info = DiscoveryBeacon.decode(data, data.length);

        assertNotNull(info);
        assertEquals("a|b", info.name);
    }

    @Test
    public void emptyNameFallsBackToDefault() {
        byte[] data = DiscoveryBeacon.encode("", 10110);

        DiscoveryBeacon.Info info = DiscoveryBeacon.decode(data, data.length);

        assertNotNull(info);
        assertEquals(DiscoveryBeacon.DEFAULT_NAME, info.name);
    }

    @Test
    public void wrongMagicIsRejected() {
        byte[] data = "OTHER/1|phone|10110\n".getBytes(UTF8);

        assertNull(DiscoveryBeacon.decode(data, data.length));
    }

    @Test
    public void malformedPayloadIsRejected() {
        assertNull(DiscoveryBeacon.decode("GPSSHARE/1|phone\n".getBytes(UTF8), 13));
        assertNull(DiscoveryBeacon.decode("GPSSHARE/1|phone|abc\n".getBytes(UTF8), 21));
        assertNull(DiscoveryBeacon.decode("GPSSHARE/1|phone|0\n".getBytes(UTF8), 19));
        assertNull(DiscoveryBeacon.decode("GPSSHARE/1|phone|70000\n".getBytes(UTF8), 23));
    }

    @Test
    public void decodeHonoursLengthArgument() {
        byte[] data = DiscoveryBeacon.encode("phone", 10110);
        byte[] padded = new byte[data.length + 8];
        System.arraycopy(data, 0, padded, 0, data.length);

        // 只解析前 data.length 字节，尾部垃圾不应影响结果
        DiscoveryBeacon.Info info = DiscoveryBeacon.decode(padded, data.length);

        assertNotNull(info);
        assertEquals("phone", info.name);
        assertEquals(10110, info.tcpPort);
    }

    @Test
    public void garbageBytesAreRejected() {
        byte[] data = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01};

        assertNull(DiscoveryBeacon.decode(data, data.length));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.net.DiscoveryBeaconTest"
```

预期：编译失败，报找不到 `DiscoveryBeacon`。

- [ ] **Step 3: 写 `DiscoveryBeacon` 实现**

创建 `app/src/main/java/com/gpsbt/transfer/net/DiscoveryBeacon.java`：

```java
package com.gpsbt.transfer.net;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;

/**
 * 服务发现信标的编解码。纯逻辑、无 Android 依赖，可单测。
 *
 * 报文格式（单行，`\n` 结尾）：
 * <pre>GPSSHARE/1|&lt;URL编码的设备名&gt;|&lt;TCP端口&gt;</pre>
 * 设备名用 URL 编码，因此可以安全地包含 `|`、空格与中文。
 */
public final class DiscoveryBeacon {

    /** 协议魔数，带版本号便于以后演进。 */
    public static final String MAGIC = "GPSSHARE/1";

    /** 设备名为空时的兜底名称。 */
    public static final String DEFAULT_NAME = "GPS共享";

    private static final char SEPARATOR = '|';
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 解析结果。 */
    public static final class Info {
        public final String name;
        public final int tcpPort;

        public Info(String name, int tcpPort) {
            this.name = name;
            this.tcpPort = tcpPort;
        }
    }

    private DiscoveryBeacon() {
    }

    /** 编码成一行字节。设备名为空时用 {@link #DEFAULT_NAME}。 */
    public static byte[] encode(String name, int tcpPort) {
        String safeName = name == null || name.trim().isEmpty() ? DEFAULT_NAME : name.trim();
        StringBuilder builder = new StringBuilder();
        builder.append(MAGIC).append(SEPARATOR)
                .append(encodeName(safeName)).append(SEPARATOR)
                .append(tcpPort).append('\n');
        return builder.toString().getBytes(UTF8);
    }

    /**
     * 解码。任何不合规的输入都返回 null——发现端口上会收到各种无关广播，
     * 不能因为一条垃圾报文就抛异常。
     */
    public static Info decode(byte[] data, int length) {
        if (data == null || length <= 0) {
            return null;
        }
        String text = new String(data, 0, Math.min(length, data.length), UTF8).trim();
        String[] parts = split(text);
        if (parts == null) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        String name = decodeName(parts[1]);
        if (name == null || name.trim().isEmpty()) {
            name = DEFAULT_NAME;
        }
        return new Info(name, port);
    }

    /** 拆成 [魔数, 名称, 端口]；格式不符返回 null。 */
    private static String[] split(String text) {
        String[] raw = new String[3];
        int index = 0;
        int start = 0;
        for (int i = 0; i < text.length() && index < 3; i++) {
            if (text.charAt(i) == SEPARATOR) {
                raw[index++] = text.substring(start, i);
                start = i + 1;
            }
        }
        if (index < 2) {
            return null;
        }
        // 最后一段一直取到行尾
        raw[2] = text.substring(start);
        if (!MAGIC.equals(raw[0])) {
            return null;
        }
        if (raw[1] == null || raw[2] == null) {
            return null;
        }
        return raw;
    }

    private static String encodeName(String name) {
        try {
            return URLEncoder.encode(name, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return name;
        }
    }

    private static String decodeName(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }
}
```

> 注意：`URLEncoder.encode` 会把空格编成 `+`，`URLDecoder.decode` 会把 `+` 还原成空格，两者对称，因此往返一致。

- [ ] **Step 4: 跑测试，确认通过**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.net.DiscoveryBeaconTest"
```

预期：`BUILD SUCCESSFUL`，7 个用例通过。

- [ ] **Step 5: 写 `HotspotAddresses` 的失败测试**

创建 `app/src/test/java/com/gpsbt/transfer/net/HotspotAddressesTest.java`：

```java
package com.gpsbt.transfer.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HotspotAddressesTest {

    private static HotspotAddresses.Candidate candidate(String ifName, String ip) {
        return new HotspotAddresses.Candidate(ifName, ip);
    }

    @Test
    public void emptyListYieldsNull() {
        assertNull(HotspotAddresses.pickLikelyHotspot(Collections.<HotspotAddresses.Candidate>emptyList()));
        assertNull(HotspotAddresses.pickLikelyHotspot(null));
    }

    @Test
    public void accessPointInterfaceWins() {
        List<HotspotAddresses.Candidate> candidates = new ArrayList<>(Arrays.asList(
                candidate("wlan0", "192.168.1.23"),
                candidate("ap0", "192.168.43.1")));

        assertEquals("192.168.43.1", HotspotAddresses.pickLikelyHotspot(candidates).ip);
    }

    @Test
    public void softApAndSwlanNamesAreRecognised() {
        assertEquals("10.0.0.1", HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("wlan0", "192.168.1.23"),
                candidate("softap0", "10.0.0.1"))).ip);
        assertEquals("10.0.0.2", HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("swlan0", "10.0.0.2"))).ip);
    }

    @Test
    public void gatewayShapedAddressBeatsPlainWifi() {
        assertEquals("192.168.43.1", HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("wlan0", "192.168.1.23"),
                candidate("wlan1", "192.168.43.1"))).ip);
    }

    @Test
    public void fallsBackToFirstCandidateWhenNothingMatches() {
        HotspotAddresses.Candidate picked = HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("wlan0", "192.168.1.23"),
                candidate("rmnet0", "10.1.2.3")));

        assertEquals("192.168.1.23", picked.ip);
    }

    @Test
    public void loopbackIsNeverPicked() {
        HotspotAddresses.Candidate picked = HotspotAddresses.pickLikelyHotspot(Arrays.asList(
                candidate("lo", "127.0.0.1"),
                candidate("wlan0", "192.168.1.23")));

        assertEquals("192.168.1.23", picked.ip);
    }
}
```

- [ ] **Step 6: 跑测试，确认失败**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.net.HotspotAddressesTest"
```

预期：编译失败，报找不到 `HotspotAddresses`。

- [ ] **Step 7: 写 `HotspotAddresses` 实现**

创建 `app/src/main/java/com/gpsbt/transfer/net/HotspotAddresses.java`：

```java
package com.gpsbt.transfer.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 本机 IPv4 地址枚举与热点网卡识别。
 *
 * 应用**无法自己打开带流量共享的热点**（`startLocalOnlyHotspot()` 建的是
 * 不共享流量的本地网络），只能由用户在系统设置里手动打开。因此这里做的是
 * 「热点已经开着，找出它的地址告诉用户」。
 */
public final class HotspotAddresses {

    /** 一个候选地址。 */
    public static final class Candidate {
        public final String ifName;
        public final String ip;

        public Candidate(String ifName, String ip) {
            this.ifName = ifName == null ? "" : ifName;
            this.ip = ip == null ? "" : ip;
        }
    }

    private HotspotAddresses() {
    }

    /** 枚举本机所有非回环 IPv4 地址。任何异常都退化为空列表。 */
    public static List<Candidate> localIpv4() {
        List<Candidate> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return result;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        result.add(new Candidate(nic.getName(), address.getHostAddress()));
                    }
                }
            }
        } catch (SocketException ignored) {
            // 枚举失败就当作没有可用地址
        }
        return result;
    }

    /**
     * 从候选里挑出最可能是热点网卡的那个。纯逻辑，便于单测。
     *
     * 优先级：网卡名像热点（ap* / softap* / swlan* / wlan1）→ 地址形如
     * 192.168.x.1 的网关 → 任意非回环地址。都没有则返回 null。
     */
    public static Candidate pickLikelyHotspot(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<Candidate> usable = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!isLoopback(candidate.ip)) {
                usable.add(candidate);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        for (Candidate candidate : usable) {
            if (looksLikeAccessPoint(candidate.ifName)) {
                return candidate;
            }
        }
        for (Candidate candidate : usable) {
            if (looksLikeGateway(candidate.ip)) {
                return candidate;
            }
        }
        return usable.get(0);
    }

    private static boolean looksLikeAccessPoint(String ifName) {
        String name = ifName.toLowerCase(Locale.US);
        return name.startsWith("ap")
                || name.startsWith("softap")
                || name.startsWith("swlan")
                || name.equals("wlan1");
    }

    private static boolean looksLikeGateway(String ip) {
        return ip.startsWith("192.168.") && ip.endsWith(".1");
    }

    private static boolean isLoopback(String ip) {
        return ip.startsWith("127.");
    }
}
```

- [ ] **Step 8: 跑测试，确认通过**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "com.gpsbt.transfer.net.HotspotAddressesTest"
```

预期：`BUILD SUCCESSFUL`，6 个用例通过。

- [ ] **Step 9: 写 `DiscoveryBroadcaster`**

创建 `app/src/main/java/com/gpsbt/transfer/net/DiscoveryBroadcaster.java`：

```java
package com.gpsbt.transfer.net;

import com.gpsbt.transfer.state.LogBus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 周期性向本机各网卡的广播地址发送 UDP 信标，供接收端自动发现。
 *
 * 只发 255.255.255.255 是不够的：默认路由可能把广播带到移动数据网卡上。
 * 因此按网卡逐个算广播地址发送，确保信标从热点网卡出去。
 */
public final class DiscoveryBroadcaster {

    private static final long INTERVAL_SECONDS = 1L;

    private final String name;
    private final int tcpPort;
    private final int udpPort;

    private ScheduledExecutorService scheduler;

    public DiscoveryBroadcaster(String name, int tcpPort, int udpPort) {
        this.name = name;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
    }

    public void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                sendOnce();
            }
        }, 0L, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 幂等：重复调用不做事。 */
    public void stop() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private void sendOnce() {
        byte[] payload = DiscoveryBeacon.encode(name, tcpPort);
        List<InetAddress> targets = broadcastTargets();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            for (InetAddress target : targets) {
                try {
                    socket.send(new DatagramPacket(payload, payload.length, target, udpPort));
                } catch (IOException ignored) {
                    // 某个网卡发不出去不影响其它网卡
                }
            }
        } catch (IOException e) {
            LogBus.get().log(LogBus.Level.WARN, "服务发现广播失败：" + e.getMessage());
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private List<InetAddress> broadcastTargets() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface nic = interfaces.nextElement();
                    if (!nic.isUp() || nic.isLoopback()) {
                        continue;
                    }
                    for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                        InetAddress broadcast = address.getBroadcast();
                        if (broadcast != null) {
                            targets.add(broadcast);
                        }
                    }
                }
            }
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
            // 枚举失败时至少还有兜底广播地址
        }
        return targets;
    }
}
```

- [ ] **Step 10: 编译并跑全部单测**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，**75 个用例**（62 + DiscoveryBeacon 7 + HotspotAddresses 6）全过。

- [ ] **Step 11: 提交**

```bash
git add -A
git commit -m "feat: 新增服务发现与热点地址识别

DiscoveryBeacon（信标编解码，纯逻辑）、DiscoveryBroadcaster（按网卡
广播地址逐个发送）、HotspotAddresses（IPv4 枚举 + 热点网卡识别）。"
```

---

## Task 6: `GpsOutputService` 接线与权限

**Files:**
- Modify: `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 加 `INTERNET` 权限**

在 `app/src/main/AndroidManifest.xml` 的「定位」权限块之前插入：

```xml
    <!-- WiFi 传输：TCP 服务端与 UDP 服务发现（普通权限，无运行时弹窗） -->
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: `GpsOutputService` 改为按传输方式构造链路**

在 `app/src/main/java/com/gpsbt/transfer/service/GpsOutputService.java` 中：

**2a.** import 区加上：

```java
import com.gpsbt.transfer.link.Transport;
import com.gpsbt.transfer.net.DiscoveryBroadcaster;
import com.gpsbt.transfer.net.HotspotAddresses;
import com.gpsbt.transfer.net.NetLinkManager;
```

**2b.** 字段区加上：

```java
    private DiscoveryBroadcaster discovery;
```

**2c.** `prerequisiteFailure` 整个替换为：

```java
    public static String prerequisiteFailure(Context context) {
        if (!LocationProvider.hasPermission(context)) {
            return context.getString(R.string.boot_reason_location);
        }
        Prefs prefs = Prefs.get(context);
        // WiFi 通道不依赖蓝牙硬件与配对，跳过蓝牙相关的前置检查
        if (prefs.getTransport() != Transport.BLUETOOTH) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return context.getString(R.string.boot_reason_bluetooth_permission);
        }
        if (BluetoothAdapter.getDefaultAdapter() == null) {
            return context.getString(R.string.boot_reason_no_bluetooth);
        }
        if (prefs.getMode() == ConnectionMode.CLIENT) {
            String mac = prefs.getLastDeviceMac();
            if (mac == null || mac.isEmpty()) {
                return context.getString(R.string.boot_reason_no_device);
            }
        }
        return null;
    }
```

**2d.** `startPipeline` 整个替换为：

```java
    private boolean startPipeline() {
        if (!LocationProvider.hasPermission(this)) {
            failStart(getString(R.string.boot_reason_location));
            return false;
        }
        if (prefs.getTransport() == Transport.WIFI) {
            return startTransmission(null);
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            failStart(getString(R.string.boot_reason_no_bluetooth));
            return false;
        }
        if (mode == ConnectionMode.CLIENT && (deviceMac == null || deviceMac.isEmpty())) {
            failStart(getString(R.string.boot_reason_no_device));
            return false;
        }

        if (!adapter.isEnabled()) {
            if (!fromBoot) {
                failStart(getString(R.string.boot_reason_bluetooth_off));
                return false;
            }
            // 开机自启：蓝牙栈可能尚未就绪，等待而非直接放弃
            state.setPhase(TransmitterState.Phase.RUNNING);
            state.setMode(mode);
            state.setConfig(prefs.getServiceName(), prefs.getUuid(), prefs.getRateHz());
            state.setStatusText("等待蓝牙开启…");
            LogBus.get().log(LogBus.Level.INFO, "等待蓝牙开启…");
            waitForBluetooth(adapter);
            return true;
        }
        return startTransmission(adapter);
    }
```

**2e.** `startTransmission` 的签名与链路启动部分：把

```java
    private boolean startTransmission(BluetoothAdapter adapter) {
```

改为

```java
    private boolean startTransmission(BluetoothAdapter adapter) {
        boolean wifi = prefs.getTransport() == Transport.WIFI;
```

并把

```java
        state.setStatusText(mode == ConnectionMode.SERVER ? "蓝牙服务启动中…" : "正在连接设备…");
```

改为

```java
        state.setStatusText(wifi
                ? "WiFi 服务启动中…"
                : (mode == ConnectionMode.SERVER ? "蓝牙服务启动中…" : "正在连接设备…"));
        state.setTransport(wifi ? TransmitterState.Transport.WIFI
                : TransmitterState.Transport.BLUETOOTH);
```

**2f.** `stopExistingPipeline` 里加上停止发现广播：把

```java
    private synchronized void stopExistingPipeline() {
        stopRelink();
        if (link != null) {
```

改为

```java
    private synchronized void stopExistingPipeline() {
        stopRelink();
        stopDiscovery();
        if (link != null) {
```

**2g.** `connectLink` 整个替换为：

```java
    /** 只建立（或重建）链路，不重置定位与调度——断线重连时复用。 */
    private boolean connectLink(BluetoothAdapter adapter) {
        if (prefs.getTransport() == Transport.WIFI) {
            link = new NetLinkManager(prefs.getWifiPort(), linkListener);
            link.start();
            startDiscovery();
            return true;
        }
        BluetoothDevice device = null;
        if (mode == ConnectionMode.CLIENT) {
            if (adapter == null) {
                state.setStatusText("蓝牙不可用");
                return false;
            }
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
        link = new BluetoothLinkManager(adapter, prefs.getServiceName(),
                UUID.fromString(prefs.getUuid()), mode, device, linkListener);
        if (mode == ConnectionMode.SERVER) {
            state.setStatusText("蓝牙服务启动中…");
        }
        link.start();
        return true;
    }

    /** 开启服务发现广播；设置里关掉时不发。 */
    private void startDiscovery() {
        if (!prefs.isDiscoveryEnabled()) {
            return;
        }
        discovery = new DiscoveryBroadcaster(prefs.getServiceName(),
                prefs.getWifiPort(), prefs.getDiscoveryPort());
        discovery.start();
    }

    private void stopDiscovery() {
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
    }
```

**2h.** `scheduleRelink` 里，重连时对 WiFi 也要能重建。把

```java
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    state.setStatusText("蓝牙未开启，等待重新开启…");
                    LogBus.get().log(LogBus.Level.WARN, "蓝牙未开启，继续等待");
                    scheduleRelink();
                    return;
                }
                if (!connectLink(adapter)) {
                    stopSelfSafely();
                }
```

改为

```java
                if (prefs.getTransport() == Transport.WIFI) {
                    if (!connectLink(null)) {
                        stopSelfSafely();
                    }
                    return;
                }
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    state.setStatusText("蓝牙未开启，等待重新开启…");
                    LogBus.get().log(LogBus.Level.WARN, "蓝牙未开启，继续等待");
                    scheduleRelink();
                    return;
                }
                if (!connectLink(adapter)) {
                    stopSelfSafely();
                }
```

**2i.** `stopSelfSafely` 里加上停止发现广播：把

```java
    private synchronized void stopSelfSafely() {
        running = false;
        stopRelink();
```

改为

```java
    private synchronized void stopSelfSafely() {
        running = false;
        stopRelink();
        stopDiscovery();
```

**2j.** `onPeerLeft` 的服务器分支文案对 WiFi 也要合理。把

```java
                state.setStatusText("蓝牙已断开，等待接收端重新连接");
```

改为

```java
                state.setStatusText(prefs.getTransport() == Transport.WIFI
                        ? "接收端已断开，继续等待连接" : "蓝牙已断开，等待接收端重新连接");
```

**2k.** 日志里写死「蓝牙」的地方改为按传输方式取词。在 `linkListener.onState` 里把

```java
            LogBus.get().log(mapLevel(linkState), "蓝牙：" + message);
```

改为

```java
            LogBus.get().log(mapLevel(linkState), linkLabel() + "：" + message);
```

并在 `mapLevel` 方法之前插入：

```java
    /** 日志前缀：当前传输方式的短名。 */
    private String linkLabel() {
        return prefs.getTransport() == Transport.WIFI ? "WiFi" : "蓝牙";
    }
```

同样，`onPeerLeft` 里把

```java
            LogBus.get().log(LogBus.Level.WARN, "蓝牙已断开：" + reason);
```

改为

```java
            LogBus.get().log(LogBus.Level.WARN, linkLabel() + "已断开：" + reason);
```

**2l.** `onPeerJoined` 里把

```java
            LogBus.get().log(LogBus.Level.INFO,
                    "已连接 " + peer.name + "（" + peer.address + "）");
```

改为

```java
            LogBus.get().log(LogBus.Level.INFO,
                    linkLabel() + "已连接 " + peer.name + "（" + peer.address + "）");
```

**2m.** `tickInternal` 里的「蓝牙未就绪」提示改为通用：把

```java
                LogBus.get().log(LogBus.Level.WARN, "蓝牙未就绪，暂停发送（等待接收端连接）");
```

改为

```java
                LogBus.get().log(LogBus.Level.WARN,
                        linkLabel() + "未就绪，暂停发送（等待接收端连接）");
```

**2n.** `buildNotification` 里的通知文案：把

```java
        if (bt == TransmitterState.Bt.CONNECTED) {
            title = getString(R.string.notif_sending);
        } else if (bt == TransmitterState.Bt.WAITING || bt == TransmitterState.Bt.CONNECTING) {
            title = getString(R.string.notif_waiting);
```

保持不变——通知文案不区分传输方式，`notif_waiting`（「等待设备连接…」）对两种方式都成立。

- [ ] **Step 3: 编译**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugJavaWithJavac
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 跑全部单测并构建 APK**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

预期：`BUILD SUCCESSFUL`，**75 个用例**全过，APK 产出。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat: GpsOutputService 按传输方式构造链路

WiFi 时跳过蓝牙前置检查、启动发现广播；日志与状态文案按传输方式
取词。新增 INTERNET 权限。"
```

---

## Task 7: 主页界面 —— 传输方式切换与在线接收端列表

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/layout/item_peer.xml`
- Create: `app/src/main/java/com/gpsbt/transfer/ui/adapter/PeerAdapter.java`
- Modify: `app/src/main/res/layout/fragment_home.xml`
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java`

- [ ] **Step 1: 加文案**

在 `app/src/main/res/values/strings.xml` 的「主页」块末尾（`home_note` 之后）插入：

```xml
    <string name="home_transport_title">传输方式</string>
    <string name="home_transport_bluetooth">蓝牙</string>
    <string name="home_transport_wifi">WiFi</string>
    <string name="home_wifi_title">热点服务信息</string>
    <string name="home_wifi_address">热点地址</string>
    <string name="home_wifi_port">端口</string>
    <string name="home_wifi_peers">在线接收端</string>
    <string name="home_wifi_peers_empty">暂无接收端连接</string>
    <string name="home_wifi_no_hotspot">未检测到热点。请先在「系统设置 → 个人热点」中开启热点，接收端连上该热点后即可使用。</string>
    <string name="home_wifi_hint">接收端连上本机热点后，用 TCP 连接上方地址与端口即可接收；也可由接收端自动发现。</string>
```

并在「数据卡片标题」块里把

```xml
    <string name="stat_device">连接设备</string>
```

改为

```xml
    <string name="stat_device">连接设备</string>
    <string name="value_peer_count">%1$d 台</string>
    <string name="notif_stats_duration">已连接 %1$s</string>
```

> `notif_stats_duration` 供 Task 7 的接收端列表显示连接时长用。

- [ ] **Step 2: 建列表项布局**

创建 `app/src/main/res/layout/item_peer.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_vertical"
    android:minHeight="48dp"
    android:orientation="horizontal"
    android:paddingHorizontal="4dp"
    android:paddingVertical="6dp">

    <ImageView
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:src="@drawable/ic_nav_satellite"
        app:tint="@color/color_accent_blue" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="10dp"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/peer_address"
            style="@style/SettingsRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:ellipsize="end"
            android:fontFamily="monospace"
            android:maxLines="1"
            android:textSize="13sp" />

        <TextView
            android:id="@+id/peer_duration"
            style="@style/TextSecondary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="11sp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 3: 建适配器**

创建 `app/src/main/java/com/gpsbt/transfer/ui/adapter/PeerAdapter.java`：

```java
package com.gpsbt.transfer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.util.Formatters;

import java.util.ArrayList;
import java.util.List;

/** 在线接收端列表：显示地址与连接时长。 */
public class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.PeerViewHolder> {

    private final List<Peer> peers = new ArrayList<>();

    public void submit(List<Peer> value) {
        peers.clear();
        if (value != null) {
            peers.addAll(value);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer, parent, false);
        return new PeerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
        Peer peer = peers.get(position);
        holder.address.setText(peer.address);
        long elapsed = peer.connectedAt <= 0
                ? -1L : System.currentTimeMillis() - peer.connectedAt;
        holder.duration.setText(holder.itemView.getContext()
                .getString(R.string.notif_stats_duration, Formatters.duration(elapsed)));
    }

    @Override
    public int getItemCount() {
        return peers.size();
    }

    static final class PeerViewHolder extends RecyclerView.ViewHolder {
        final TextView address;
        final TextView duration;

        PeerViewHolder(@NonNull View itemView) {
            super(itemView);
            address = itemView.findViewById(R.id.peer_address);
            duration = itemView.findViewById(R.id.peer_duration);
        }
    }
}
```

> 连接时长文案 `notif_stats_duration` 已在 Step 1 加好。

- [ ] **Step 4: 改主页布局**

在 `app/src/main/res/layout/fragment_home.xml` 中：

**4a.** 在「连接方式」那个 `SectionTitle` 之前，插入传输方式区块：

```xml
        <!-- 传输方式 -->
        <TextView
            style="@style/SectionTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/home_transport_title" />

        <com.google.android.material.button.MaterialButtonToggleGroup
            android:id="@+id/transport_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:selectionRequired="true"
            app:singleSelection="true">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/transport_bluetooth"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/home_transport_bluetooth"
                android:textColor="@color/mode_button_text"
                app:backgroundTint="@color/mode_button_bg"
                app:cornerRadius="14dp"
                app:strokeColor="@color/mode_button_stroke"
                app:strokeWidth="1dp" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/transport_wifi"
                style="?attr/materialButtonOutlinedStyle"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/home_transport_wifi"
                android:textColor="@color/mode_button_text"
                app:backgroundTint="@color/mode_button_bg"
                app:cornerRadius="14dp"
                app:strokeColor="@color/mode_button_stroke"
                app:strokeWidth="1dp" />
        </com.google.android.material.button.MaterialButtonToggleGroup>

```

**4b.** 给「连接方式」那个 `SectionTitle` 加上 id，便于整体显隐。把

```xml
        <TextView
            style="@style/SectionTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/home_mode_title" />
```

改为

```xml
        <TextView
            android:id="@+id/mode_title"
            style="@style/SectionTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/home_mode_title" />
```

**4c.** 在 `device_panel` 那个 `LinearLayout` 的结束标签之后、`<!-- 运行数据 -->` 之前，插入 WiFi 区块：

```xml
        <!-- 热点服务信息（WiFi 方式） -->
        <LinearLayout
            android:id="@+id/wifi_card"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp"
            android:visibility="gone">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/home_wifi_title" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <TextView
                    style="@style/TextSecondary"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/home_wifi_address" />

                <TextView
                    android:id="@+id/wifi_address_value"
                    style="@style/ValueText"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:fontFamily="monospace"
                    android:textSize="13sp" />
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
                    android:text="@string/home_wifi_port" />

                <TextView
                    android:id="@+id/wifi_port_value"
                    style="@style/ValueText"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:fontFamily="monospace"
                    android:textSize="13sp" />
            </LinearLayout>

            <TextView
                android:id="@+id/wifi_hint"
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="@string/home_wifi_hint"
                android:textSize="11sp" />
        </LinearLayout>

        <!-- 在线接收端（WiFi 方式） -->
        <LinearLayout
            android:id="@+id/peer_panel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="16dp"
            android:visibility="gone">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/home_wifi_peers" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/peer_list"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp" />

            <TextView
                android:id="@+id/peer_empty"
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="@string/home_wifi_peers_empty"
                android:textSize="12sp" />
        </LinearLayout>

```

- [ ] **Step 5: 改 `HomeFragment`**

在 `app/src/main/java/com/gpsbt/transfer/ui/fragment/HomeFragment.java` 中：

**5a.** import 区加上：

```java
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.link.Transport;
import com.gpsbt.transfer.net.HotspotAddresses;
import com.gpsbt.transfer.ui.adapter.PeerAdapter;
```

**5b.** 字段区加上：

```java
    private MaterialButtonToggleGroup transportGroup;
    private TextView modeTitle;
    private View wifiCard;
    private TextView wifiAddressValue;
    private TextView wifiPortValue;
    private TextView wifiHint;
    private View peerPanel;
    private RecyclerView peerList;
    private TextView peerEmpty;
    private PeerAdapter peerAdapter;
```

**5c.** `onViewCreated` 里 `modeGroup = view.findViewById(R.id.mode_group);` 之前插入：

```java
        transportGroup = view.findViewById(R.id.transport_group);
        modeTitle = view.findViewById(R.id.mode_title);
        wifiCard = view.findViewById(R.id.wifi_card);
        wifiAddressValue = view.findViewById(R.id.wifi_address_value);
        wifiPortValue = view.findViewById(R.id.wifi_port_value);
        wifiHint = view.findViewById(R.id.wifi_hint);
        peerPanel = view.findViewById(R.id.peer_panel);
        peerList = view.findViewById(R.id.peer_list);
        peerEmpty = view.findViewById(R.id.peer_empty);
```

**5d.** `deviceList.setAdapter(deviceAdapter);` 之后插入：

```java
        peerAdapter = new PeerAdapter();
        peerList.setLayoutManager(new LinearLayoutManager(getContext()));
        peerList.setAdapter(peerAdapter);
```

**5e.** `modeGroup.addOnButtonCheckedListener(...)` 之前插入传输方式的监听：

```java
        transportGroup.addOnButtonCheckedListener(
                new MaterialButtonToggleGroup.OnButtonCheckedListener() {
                    @Override
                    public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                                boolean isChecked) {
                        if (!isChecked) {
                            return;
                        }
                        Transport transport = checkedId == R.id.transport_wifi
                                ? Transport.WIFI : Transport.BLUETOOTH;
                        prefs.setTransport(transport);
                        state.setTransport(transport == Transport.WIFI
                                ? TransmitterState.Transport.WIFI
                                : TransmitterState.Transport.BLUETOOTH);
                        renderPanels();
                    }
                });
```

**5f.** `onPulseClicked` 里，把客户端模式的目标设备校验限制在蓝牙：

```java
        if (prefs.getTransport() == Transport.BLUETOOTH
                && prefs.getMode() == ConnectionMode.CLIENT
                && (selectedMac == null || selectedMac.isEmpty())) {
```

**5g.** `render` 里，在 `modeGroup.check(...)` 之前插入：

```java
        Transport transport = snapshot.transport() == TransmitterState.Transport.WIFI
                ? Transport.WIFI : Transport.BLUETOOTH;
        transportGroup.check(transport == Transport.WIFI
                ? R.id.transport_wifi : R.id.transport_bluetooth);
```

**5h.** `render` 里把连接设备卡片的赋值改为支持多对端：

```java
        TransmitterState.Device device = snapshot.connectedDevice();
        set(StatsGridBuilder.KEY_DEVICE, device == null ? unknown : device.name);
```

改为

```java
        List<Peer> peers = snapshot.peers();
        if (peers.isEmpty()) {
            set(StatsGridBuilder.KEY_DEVICE, unknown);
        } else if (peers.size() == 1) {
            set(StatsGridBuilder.KEY_DEVICE, peers.get(0).name);
        } else {
            set(StatsGridBuilder.KEY_DEVICE,
                    getString(R.string.value_peer_count, peers.size()));
        }
        peerAdapter.submit(peers);
        peerEmpty.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
```

**5i.** `renderPanels` 整个替换为：

```java
    private void renderPanels() {
        boolean wifi = prefs.getTransport() == Transport.WIFI;
        boolean client = prefs.getMode() == ConnectionMode.CLIENT;

        transportGroup.setVisibility(View.VISIBLE);
        modeTitle.setVisibility(wifi ? View.GONE : View.VISIBLE);
        modeGroup.setVisibility(wifi ? View.GONE : View.VISIBLE);
        modeHint.setVisibility(wifi ? View.GONE : View.VISIBLE);

        serviceCard.setVisibility(!wifi && !client ? View.VISIBLE : View.GONE);
        devicePanel.setVisibility(!wifi && client ? View.VISIBLE : View.GONE);

        wifiCard.setVisibility(wifi ? View.VISIBLE : View.GONE);
        peerPanel.setVisibility(wifi ? View.VISIBLE : View.GONE);

        if (wifi) {
            renderWifiCard();
        } else {
            modeHint.setText(client
                    ? R.string.home_mode_client_hint : R.string.home_mode_server_hint);
        }
    }

    /** 刷新热点地址与端口；检测不到热点时给出引导。 */
    private void renderWifiCard() {
        wifiPortValue.setText(String.valueOf(prefs.getWifiPort()));
        HotspotAddresses.Candidate candidate =
                HotspotAddresses.pickLikelyHotspot(HotspotAddresses.localIpv4());
        if (candidate == null) {
            wifiAddressValue.setText(R.string.value_unknown);
            wifiHint.setText(R.string.home_wifi_no_hotspot);
        } else {
            wifiAddressValue.setText(candidate.ip);
            wifiHint.setText(R.string.home_wifi_hint);
        }
    }
```

**5j.** `onPulseClicked` 的启动调用改为不需要传蓝牙参数时也能工作——现有实现传的是 `prefs.getMode(), selectedMac`，对 WiFi 无影响，保持不动。

- [ ] **Step 6: 编译并构建**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

预期：`BUILD SUCCESSFUL`，**75 个用例**全过，APK 产出。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat: 主页支持传输方式切换与在线接收端列表"
```

---

## Task 8: 设置页 —— WiFi 端口与服务发现

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/fragment_settings.xml`
- Modify: `app/src/main/java/com/gpsbt/transfer/ui/fragment/SettingsFragment.java`

- [ ] **Step 1: 加文案**

在 `app/src/main/res/values/strings.xml` 的「设置页」块里，`settings_service_title` 之前插入：

```xml
    <string name="settings_wifi_title">WiFi 传输</string>
    <string name="settings_wifi_port">TCP 端口</string>
    <string name="settings_wifi_port_desc">接收端连本机热点后，用这个端口接收数据。默认 10110 是 NMEA over TCP 的惯例端口。</string>
    <string name="settings_discovery">允许接收端自动发现</string>
    <string name="settings_discovery_desc">开启后本机每秒广播一次 UDP 信标，接收端无需手动输入 IP。</string>
    <string name="settings_discovery_port">发现端口</string>
```

- [ ] **Step 2: 加设置卡片**

在 `app/src/main/res/layout/fragment_settings.xml` 里，`settings_service_title` 那个卡片的结束 `</LinearLayout>` 之后插入：

```xml
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
                android:text="@string/settings_wifi_title" />

            <com.google.android.material.textfield.TextInputLayout
                style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:hint="@string/settings_wifi_port">

                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/input_wifi_port"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:inputType="number"
                    android:maxLines="1"
                    android:textColor="@color/color_text_primary" />
            </com.google.android.material.textfield.TextInputLayout>

            <TextView
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/settings_wifi_port_desc"
                android:textSize="11sp" />

            <com.google.android.material.materialswitch.MaterialSwitch
                android:id="@+id/sw_discovery"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="@string/settings_discovery" />

            <TextView
                style="@style/TextSecondary"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/settings_discovery_desc"
                android:textSize="11sp" />

            <com.google.android.material.textfield.TextInputLayout
                style="@style/Widget.Material3.TextInputLayout.OutlinedBox"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:hint="@string/settings_discovery_port">

                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/input_discovery_port"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:inputType="number"
                    android:maxLines="1"
                    android:textColor="@color/color_text_primary" />
            </com.google.android.material.textfield.TextInputLayout>
        </LinearLayout>
```

- [ ] **Step 3: 接线 `SettingsFragment`**

在 `app/src/main/java/com/gpsbt/transfer/ui/fragment/SettingsFragment.java` 中：

**3a.** 字段区加上：

```java
    private TextInputEditText inputWifiPort;
    private MaterialSwitch swDiscovery;
    private TextInputEditText inputDiscoveryPort;
```

**3b.** `onViewCreated` 里 `about = view.findViewById(R.id.about_body);` 之前插入：

```java
        inputWifiPort = view.findViewById(R.id.input_wifi_port);
        swDiscovery = view.findViewById(R.id.sw_discovery);
        inputDiscoveryPort = view.findViewById(R.id.input_discovery_port);
```

**3c.** 在 `hdopSlider` 的监听之后（`onViewCreated` 末尾）插入：

```java
        inputWifiPort.setText(String.valueOf(prefs.getWifiPort()));
        inputWifiPort.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    prefs.setWifiPort(portOf(inputWifiPort, Prefs.DEFAULT_WIFI_PORT));
                    inputWifiPort.setText(String.valueOf(prefs.getWifiPort()));
                }
            }
        });

        swDiscovery.setChecked(prefs.isDiscoveryEnabled());
        swDiscovery.setOnCheckedChangeListener(new MaterialSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull android.widget.CompoundButton buttonView,
                                         boolean isChecked) {
                prefs.setDiscoveryEnabled(isChecked);
            }
        });

        inputDiscoveryPort.setText(String.valueOf(prefs.getDiscoveryPort()));
        inputDiscoveryPort.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    prefs.setDiscoveryPort(
                            portOf(inputDiscoveryPort, Prefs.DEFAULT_DISCOVERY_PORT));
                    inputDiscoveryPort.setText(String.valueOf(prefs.getDiscoveryPort()));
                }
            }
        });
```

**3d.** 在 `textOf` 方法之后插入端口解析辅助：

```java
    /** 解析端口输入；非法或越界时回落到默认值（Prefs 会再做一次钳制）。 */
    private static int portOf(TextInputEditText field, int fallback) {
        if (field.getText() == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            return value < Prefs.MIN_PORT || value > Prefs.MAX_PORT ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
```

- [ ] **Step 4: 编译并构建**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

预期：`BUILD SUCCESSFUL`，**75 个用例**全过，APK 产出。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat: 设置页新增 WiFi 端口与服务发现开关"
```

---

## Task 9: P2 验收

**Files:** 无改动，纯验证。

- [ ] **Step 1: 全量干净构建**

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

预期：`BUILD SUCCESSFUL`，**75 个用例**全过，debug 与 release APK 都产出。

- [ ] **Step 2: 装到手机**

```bash
adb install -r 构建目录/app/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: 在 PC 上验证 TCP 收流**

**3a.** 手机开「个人热点」，PC 连上该热点。

**3b.** 手机上打开「GPS共享」→ 传输方式选 **WiFi** → 记录「热点服务信息」卡片显示的热点地址与端口（应形如 `192.168.43.1` 与 `10110`）。

**3c.** PC 上用 netcat 连上去看数据：

```bash
nc 192.168.43.1 10110
```

（Windows 无 `nc` 时可用 PuTTY 选 Raw 连接，或任意 TCP 调试工具。）

预期：每秒出现若干行 `$GPGGA…` / `$GPRMC…`，格式与蓝牙方式一致；无定位时也有每秒一帧 `$GPZDA` 保活。

**3d.** 手机上点「开始传输」，确认「在线接收端」列表出现 `192.168.x.x:<临时端口>` 一行；PC 端断开后，该行在 1 秒内消失。

- [ ] **Step 4: 验证多接收端**

同时用两台设备（或 PC 上开两个 TCP 客户端）连同一个 `IP:端口`，确认：

- 「在线接收端」列表出现 **两行**
- 两台都能收到相同的数据
- 「运行数据 → 连接设备」卡片显示「2 台」

- [ ] **Step 5: 验证服务发现**

在 PC 上监听发现端口：

```bash
nc -ul 10111
```

预期：每秒收到一行 `GPSSHARE/1|<设备名>|10110`（设备名为 URL 编码，中文会显示成 `%E2%80%A6` 形式）。

- [ ] **Step 6: 验证蓝牙未被破坏**

传输方式切回 **蓝牙**，重复 P1 验收里的蓝牙回归检查表（等待被连接 / 主动连接 / NMEA 内容 / 断线恢复）。任一不符即回到 Task 6 排查。

- [ ] **Step 7: 记录验收结果**

把结果写进本计划文档的「验收记录」一节并提交。

---

## 验收记录

> 执行完成后填写：日期、真机型号、Android 版本、逐项结果、发现的问题。

（待填）

---

## 自检

**规格覆盖**（对照设计文档 §4、§6、§7、§8.2、§8.4、§9、§11 P2）：

| 设计要求 | 落点 |
|---|---|
| TCP 服务端，默认端口 10110，可配 | Task 1（Prefs）、Task 4（NetLinkManager）、Task 8（设置） |
| 多接收端广播，单个失败不影响其余 | Task 3（PeerRegistry）、Task 4 |
| 无定位时 `$GPZDA` 保活 | 沿用 P1 的 `TransmitEngine.tickKeepalive`，本阶段未改 |
| UDP 服务发现，默认 10111，可关 | Task 5（Beacon/Broadcaster）、Task 1、Task 8 |
| 按网卡广播地址逐个发送 | Task 5（`DiscoveryBroadcaster.broadcastTargets`） |
| 热点地址枚举与高亮 | Task 5（HotspotAddresses）、Task 7（renderWifiCard） |
| 传输方式切换 UI | Task 7 |
| 在线接收端列表 | Task 2（状态）、Task 7（UI） |
| `INTERNET` 权限 | Task 6 |
| 应用不能自己开共享流量的热点 | 不实现该能力，改为引导用户手动开（`home_wifi_no_hotspot`） |
| WiFi 只做服务端 | `NetLinkManager` 只有 `start()` 监听，无客户端模式 |

**类型一致性**：`Transport`（`link` 包，`BLUETOOTH`/`WIFI`）与 `TransmitterState.Transport` 顺序一致；`PeerRegistry.Client` 的 `address()`/`write()`/`close()` 在 Task 3 定义、Task 4 实现；`DiscoveryBeacon.encode/decode` 与 `Info` 在 Task 5 定义并使用；`HotspotAddresses.Candidate`/`localIpv4`/`pickLikelyHotspot` 在 Task 5 定义、Task 7 调用；`Prefs` 的 `DEFAULT_WIFI_PORT`/`DEFAULT_DISCOVERY_PORT`/`MIN_PORT`/`MAX_PORT` 在 Task 1 定义，Task 8 使用。

**测试计数**：基线 55 → Task 2 后 57（净增 2）→ Task 3 后 62（+5）→ Task 5 后 75（+13）。各任务的预期数字以此为准。
