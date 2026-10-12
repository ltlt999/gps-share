package com.gpsbt.transfer.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
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
