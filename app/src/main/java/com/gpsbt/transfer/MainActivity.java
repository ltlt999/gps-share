package com.gpsbt.transfer;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.gpsbt.transfer.bt.BluetoothLinkManager;
import com.gpsbt.transfer.ui.fragment.HomeFragment;
import com.gpsbt.transfer.ui.fragment.LogFragment;
import com.gpsbt.transfer.ui.fragment.SatelliteFragment;
import com.gpsbt.transfer.ui.fragment.SettingsFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements HomeFragment.HomeCallbacks {

    private final Map<Integer, androidx.fragment.app.Fragment> cache =
            new HashMap<Integer, androidx.fragment.app.Fragment>();

    private Runnable pendingStart;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> enableBluetoothLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 车机兜底：部分系统不投递开机广播，而是直接拉起界面；此时由这里补一次自动开始传输
        com.gpsbt.transfer.boot.BootAutoStart.tryStart(this);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                new ActivityResultCallback<Map<String, Boolean>>() {
                    @Override
                    public void onActivityResult(Map<String, Boolean> result) {
                        boolean denied = false;
                        for (Boolean granted : result.values()) {
                            if (granted == null || !granted) {
                                denied = true;
                            }
                        }
                        if (denied) {
                            showRationale();
                            return;
                        }
                        ensureBluetoothEnabled();
                    }
                });

        enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<androidx.activity.result.ActivityResult>() {
                    @Override
                    public void onActivityResult(androidx.activity.result.ActivityResult result) {
                        if (BluetoothLinkManager.isBluetoothAvailable()) {
                            runPending();
                        } else {
                            showBluetoothOffDialog();
                        }
                    }
                });

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull android.view.MenuItem item) {
                switchTo(item.getItemId());
                return true;
            }
        });

        if (savedInstanceState == null) {
            switchTo(R.id.nav_home);
        } else if (getSupportFragmentManager().findFragmentById(R.id.fragment_container) == null) {
            switchTo(R.id.nav_home);
        }
    }

    private void switchTo(int menuId) {
        androidx.fragment.app.Fragment fragment = cache.get(menuId);
        if (fragment == null) {
            if (menuId == R.id.nav_satellite) {
                fragment = new SatelliteFragment();
            } else if (menuId == R.id.nav_log) {
                fragment = new LogFragment();
            } else if (menuId == R.id.nav_settings) {
                fragment = new SettingsFragment();
            } else {
                fragment = new HomeFragment();
            }
            cache.put(menuId, fragment);
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    @Override
    public void onBackPressed() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav.getSelectedItemId() != R.id.nav_home) {
            nav.setSelectedItemId(R.id.nav_home);
        } else {
            super.onBackPressed();
        }
    }

    // ---------- 权限入口 ----------

    @Override
    public void requestStartPermissions(Runnable onGranted) {
        pendingStart = onGranted;

        List<String> missing = new ArrayList<String>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (missing.isEmpty()) {
            ensureBluetoothEnabled();
        } else {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void ensureBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_location_title)
                    .setMessage("此设备没有蓝牙硬件，无法传输 GPS 数据。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            pendingStart = null;
            return;
        }
        if (!adapter.isEnabled()) {
            // 只弹系统授权对话框，由用户决定是否开启，绝不静默开启
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        runPending();
    }

    private void runPending() {
        Runnable action = pendingStart;
        pendingStart = null;
        if (action != null) {
            action.run();
        }
    }

    private void showBluetoothOffDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.state_bluetooth_off)
                .setMessage("请在系统设置中开启蓝牙后重试。")
                .setPositiveButton(R.string.perm_grant, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        pendingStart = null;
    }

    private void showRationale() {
        final Runnable retry = pendingStart;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_location_title)
                .setMessage(R.string.perm_location_rationale)
                .setPositiveButton(R.string.perm_grant, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestStartPermissions(retry == null ? new Runnable() {
                            @Override
                            public void run() {
                                // 无操作
                            }
                        } : retry);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        pendingStart = null;
    }
}
