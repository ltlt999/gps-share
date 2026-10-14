package com.gpsbt.transfer.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;

import java.net.Inet4Address;

/**
 * 从系统里问出「当前默认网络的网关地址」。
 *
 * 为什么需要它：接收端记下的发送端地址可能失效——热点重启后网段会变
 * （192.168.43.x ↔ 192.168.3.x 之类），只认死地址就会永远连不上。
 * 而接收端是连在发送端热点上的客户端，**它的默认网关就是发送端本身**，
 * 这个地址永远是当前有效的。
 */
public final class NetworkTargets {

    private NetworkTargets() {
    }

    /** 当前默认网络的 IPv4 网关地址；取不到返回空串。 */
    public static String defaultGateway(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getApplicationContext()
                            .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return "";
            }
            Network network = manager.getActiveNetwork();
            if (network == null) {
                return "";
            }
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) {
                return "";
            }
            for (RouteInfo route : properties.getRoutes()) {
                if (route.isDefaultRoute()
                        && route.getGateway() instanceof Inet4Address) {
                    return route.getGateway().getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // 权限、系统版本差异、网络切换竞态……任何异常都退化为「没有备选地址」
        }
        return "";
    }
}
