package com.gpsbt.transfer.ui.adapter;

import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.bt.BluetoothLinkManager;

import java.util.List;

public class PairedDeviceAdapter extends RecyclerView.Adapter<PairedDeviceAdapter.Holder> {

    public interface OnDeviceSelected {
        void onSelected(BluetoothDevice device);
    }

    private final List<BluetoothDevice> devices;
    private final OnDeviceSelected listener;
    private String selectedMac;

    public PairedDeviceAdapter(List<BluetoothDevice> devices, OnDeviceSelected listener) {
        this.devices = devices;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setSelectedMac(String mac) {
        selectedMac = mac;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final BluetoothDevice device = devices.get(position);
        String mac = device.getAddress();
        holder.name.setText(BluetoothLinkManager.displayName(device));
        holder.mac.setText(mac);
        holder.selected.setVisibility(mac.equals(selectedMac) ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSelected(device);
            }
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    @Override
    public long getItemId(int position) {
        return devices.get(position).getAddress().hashCode();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView mac;
        final TextView selected;

        Holder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.device_name);
            mac = itemView.findViewById(R.id.device_mac);
            selected = itemView.findViewById(R.id.device_selected);
        }
    }
}
