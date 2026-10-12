package com.gpsbt.transfer.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.gpsbt.transfer.state.LogBus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 把内存日志导出为文本文件，返回可读路径。 */
public final class LogExporter {

    private LogExporter() {
    }

    public static String export(Context context) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("# GPS蓝牙传输 日志\r\n");
        for (LogBus.Entry entry : LogBus.get().entries()) {
            builder.append(Formatters.clockOfDay(entry.time))
                    .append("\t").append(entry.level.name())
                    .append("\t").append(entry.message)
                    .append("\r\n");
        }
        byte[] data = builder.toString().getBytes(StandardCharsets.UTF_8);
        String name = "GpsBt_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".log";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建下载目录文件");
            }
            OutputStream stream = context.getContentResolver().openOutputStream(uri);
            try {
                stream.write(data);
            } finally {
                close(stream);
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
        }

        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        File target = new File(directory, name);
        OutputStream stream = new FileOutputStream(target);
        try {
            stream.write(data);
        } finally {
            close(stream);
        }
        return target.getAbsolutePath();
    }

    private static void close(OutputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
