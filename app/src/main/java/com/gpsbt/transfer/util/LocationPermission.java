package com.gpsbt.transfer.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * 位置权限的精细状态。
 *
 * Android 12 起有「精确位置 / 大致位置」两级：选了**大致位置**的
 * {@code ACCESS_COARSE_LOCATION} 会被授予，而 {@code ACCESS_FINE_LOCATION}
 * 不会——`checkSelfPermission` 对两者分别返回。
 *
 * 对本应用来说这不是小差别：GPS 共享要的是卫星级的位置，大致位置是基站 /
 * 网络推算的，几百米误差，而且**应用看起来一切正常**——用户完全察觉不到
 * 自己拿的是粗位置。所以要把这个状态显式呈现出来，并给出升级入口。
 */
public final class LocationPermission {

    /** 位置权限的精细状态。 */
    public enum State {
        /** 精确位置：GPS 级，共享出来的数据可用。 */
        PRECISE,
        /** 只有大致位置：基站/网络级，几百米误差，**不能用来共享 GPS**。 */
        APPROXIMATE,
        /** 完全没授权。 */
        DENIED
    }

    private LocationPermission() {
    }

    public static State state(Context context) {
        boolean fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
        if (fine) {
            return State.PRECISE;
        }
        return granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                ? State.APPROXIMATE : State.DENIED;
    }

    public static boolean isPrecise(Context context) {
        return state(context) == State.PRECISE;
    }

    /**
     * 是否拿得到能用的定位。
     *
     * 大致位置**不算**——宁可没有，也不要给用户几百米误差却毫无提示。
     */
    public static boolean isUsable(Context context) {
        return isPrecise(context);
    }

    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 升级到精确位置时应当请求的权限。 */
    public static String[] preciseRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 只请求 FINE：系统会弹「升级到精确位置」的提示
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION};
    }
}
