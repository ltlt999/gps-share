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
