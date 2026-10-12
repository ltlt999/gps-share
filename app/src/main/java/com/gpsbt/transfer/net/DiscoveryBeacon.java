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
