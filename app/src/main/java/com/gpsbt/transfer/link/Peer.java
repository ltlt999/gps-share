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
