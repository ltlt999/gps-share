package com.gpsbt.transfer;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.gpsbt.transfer.state.TransmitterState;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        TransmitterState.get()
                .setExecutor(new TransmitterState.MainThreadExecutor(new Handler(Looper.getMainLooper())));
    }
}
