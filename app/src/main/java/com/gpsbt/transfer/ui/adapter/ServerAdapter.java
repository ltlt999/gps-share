package com.gpsbt.transfer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.net.ServerTable;

import java.util.ArrayList;
import java.util.List;

/** 发现的发送端列表；点一下即连。 */
public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ServerViewHolder> {

    /** 选中回调。 */
    public interface OnServerSelected {
        void onSelected(ServerTable.Server server);
    }

    private final List<ServerTable.Server> servers = new ArrayList<>();
    private final OnServerSelected listener;

    public ServerAdapter(OnServerSelected listener) {
        this.listener = listener;
    }

    public void submit(List<ServerTable.Server> value) {
        servers.clear();
        if (value != null) {
            servers.addAll(value);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ServerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server, parent, false);
        return new ServerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ServerViewHolder holder, int position) {
        final ServerTable.Server server = servers.get(position);
        holder.name.setText(server.name);
        holder.address.setText(server.key());
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSelected(server);
            }
        });
    }

    @Override
    public int getItemCount() {
        return servers.size();
    }

    static final class ServerViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView address;

        ServerViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.server_name);
            address = itemView.findViewById(R.id.server_address);
        }
    }
}
