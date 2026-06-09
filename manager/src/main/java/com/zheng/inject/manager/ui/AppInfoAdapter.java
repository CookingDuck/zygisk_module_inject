package com.zheng.inject.manager.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.zheng.inject.manager.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AppInfoAdapter extends RecyclerView.Adapter<AppInfoAdapter.ViewHolder> {

    public enum Filter { ALL, USER, SYSTEM }

    public static class Item {
        public final ApplicationInfo info;
        public final String label;
        public final boolean isSystem;

        public Item(ApplicationInfo info, String label, boolean isSystem) {
            this.info = info;
            this.label = label;
            this.isSystem = isSystem;
        }
    }

    private final List<Item> source;
    private final List<Item> visible = new ArrayList<>();
    private final PackageManager pm;
    private final OnItemClickListener listener;

    private String query = "";
    private Filter filter = Filter.USER;

    public interface OnItemClickListener {
        void onItemClick(ApplicationInfo appInfo);
    }

    public AppInfoAdapter(Context context, List<Item> items, OnItemClickListener listener) {
        this.source = items;
        this.pm = context.getPackageManager();
        this.listener = listener;
    }

    public void setQuery(String q) {
        this.query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        rebuild();
    }

    public void setFilter(Filter f) {
        this.filter = f == null ? Filter.USER : f;
        rebuild();
    }

    public Filter getFilter() {
        return filter;
    }

    public void rebuild() {
        visible.clear();
        for (Item it : source) {
            if (filter == Filter.USER && it.isSystem) continue;
            if (filter == Filter.SYSTEM && !it.isSystem) continue;
            if (!query.isEmpty()) {
                String label = it.label == null ? "" : it.label.toLowerCase(Locale.ROOT);
                String pkg = it.info.packageName == null ? "" : it.info.packageName.toLowerCase(Locale.ROOT);
                if (!label.contains(query) && !pkg.contains(query)) continue;
            }
            visible.add(it);
        }
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return visible.isEmpty();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_info, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Item item = visible.get(position);
        holder.appName.setText(item.label);
        holder.pkgName.setText(item.info.packageName);
        holder.appIcon.setImageDrawable(pm.getApplicationIcon(item.info));
        holder.badgeSystem.setVisibility(item.isSystem ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(v -> listener.onItemClick(item.info));
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView appIcon;
        final TextView appName, pkgName, badgeSystem;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.appIcon);
            appName = itemView.findViewById(R.id.appName);
            pkgName = itemView.findViewById(R.id.pkgName);
            badgeSystem = itemView.findViewById(R.id.badgeSystem);
        }
    }
}
