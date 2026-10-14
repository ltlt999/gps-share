package com.gpsbt.transfer.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import com.gpsbt.transfer.state.LogBus;

import java.net.Inet4Address;

/**
 * 从系统里问出「接收端所在 WiFi 网络的网关地址」。
 *
 * 为什么需要它：接收端记下的发送端地址可能失效——热点重启后网段会变
 * （192.168.1.x ↔ 192.168.2.x 之类），只认死地址就会永远连不上。
 * 而接收端是连在发送端热点上的客户端，**它的默认网关就是发送端本身**，
 * 这个地址永远是当前有效的。
 *
 * ⚠️ 必须声明 {@code ACCESS_NETWORK_STATE}：少了这条，
 * {@code getActiveNetwork()} / {@code getLinkProperties()} 会抛
 * SecurityException，整个兜底逻辑静默失效。
 */
public final class NetworkTargets {

    private NetworkTargets() {
    }

    /** 接收端所在 WiFi 网络的 IPv4 网关地址；取不到返回空串。 */
    public static String defaultGateway(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getApplicationContext()
                            .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return "";
            }
            // **优先取 WiFi 网络的网关**，不能用 getActiveNetwork()：
            // 后者返回的是「上网用的」网络，平板插着 SIM 卡时那是移动网络，
            // 网关是运营商的，跟发送端毫无关系。
            String wifi = gatewayOf(manager, wifiNetwork(manager));
            if (!wifi.isEmpty()) {
                return wifi;
            }
            // 没有 WiFi 时（比如接收端用网线）退回到当前活动网络
            return gatewayOf(manager, manager.getActiveNetwork());
        } catch (Exception e) {
            // 以前这里是 catch (Exception ignored)，把「缺 ACCESS_NETWORK_STATE」
            // 这种硬错误也吞了，兜底静默失效很久没人发现。现在记一笔。
            LogBus.get().log(LogBus.Level.WARN, "取网关地址失败：" + e);
        }
        return "";
    }

    private static Network wifiNetwork(ConnectivityManager manager) {
        Network[] networks = manager.getAllNetworks();
        if (networks == null) {
            return null;
        }
        for (Network network : networks) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network;
            }
        }
        return null;
    }

    private static String gatewayOf(ConnectivityManager manager, Network network) {
        if (network == null) {
            return "";
        }
        LinkProperties properties = manager.getLinkProperties(network);
        if (properties == null) {
            return "";
        }
        for (RouteInfo route : properties.getRoutes()) {
            if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                return route.getGateway().getHostAddress();
            }
        }
        return "";
    }
}
