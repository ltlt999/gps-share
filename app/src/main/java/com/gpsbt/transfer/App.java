package com.gpsbt.transfer;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.rx.MockLocationInjector;
import com.gpsbt.transfer.state.TransmitterState;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        TransmitterState.get()
                .setExecutor(new TransmitterState.MainThreadExecutor(new Handler(Looper.getMainLooper())));
        // 让系统「模拟位置应用」列表里能选到本应用——单纯装 APK 不会登记，
        // 必须先调过一次 addTestProvider。
        MockLocationInjector.ensureRegistered(this);
    }
}
