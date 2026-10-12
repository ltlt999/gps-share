package com.gpsbt.transfer.nmea;

import java.util.Locale;

/** NMEA 0183 校验和：$ 与 * 之间所有字符的 XOR，输出两位大写十六进制。 */
public final class NmeaChecksum {

    private NmeaChecksum() {
    }

    /** 对给定字符串逐字符做 XOR。传 $ 之前的内容（不含前导 $）。 */
    public static int xor(String body) {
        int checksum = 0;
        for (int i = 0; i < body.length(); i++) {
            checksum ^= body.charAt(i) & 0xFF;
        }
        return checksum & 0xFF;
    }

    /** 转两位大写十六进制，不足补零，超出范围截断。 */
    public static String toHex(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    /**
     * @param sentence 完整语句，含前导 $，不含 *CS 与 \r\n
     * @return 追加 *CS 与 \r\n 的完整帧
     */
    public static String withChecksum(String sentence) {
        String body = sentence.startsWith("$") ? sentence.substring(1) : sentence;
        return sentence + "*" + toHex(xor(body)) + "\r\n";
    }
}
