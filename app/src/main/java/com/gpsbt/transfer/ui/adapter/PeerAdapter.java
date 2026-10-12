package com.gpsbt.transfer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.transfer.R;
import com.gpsbt.transfer.link.Peer;
import com.gpsbt.transfer.util.Formatters;

import java.util.ArrayList;
import java.util.List;

/** 在线接收端列表：显示地址与连接时长。 */
public class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.PeerViewHolder> {

    private final List<Peer> peers = new ArrayList<>();

    public void submit(List<Peer> value) {
        peers.clear();
        if (value != null) {
            peers.addAll(value);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PeerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer, parent, false);
        return new PeerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PeerViewHolder holder, int position) {
        Peer peer = peers.get(position);
        holder.address.setText(peer.address);
        long elapsed = peer.connectedAt <= 0
                ? -1L : System.currentTimeMillis() - peer.connectedAt;
        holder.duration.setText(holder.itemView.getContext()
                .getString(R.string.notif_stats_duration, Formatters.duration(elapsed)));
    }

    @Override
    public int getItemCount() {
        return peers.size();
    }

    static final class PeerViewHolder extends RecyclerView.ViewHolder {
        final TextView address;
        final TextView duration;

        PeerViewHolder(@NonNull View itemView) {
            super(itemView);
            address = itemView.findViewById(R.id.peer_address);
            duration = itemView.findViewById(R.id.peer_duration);
        }
    }
}
