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
import java.util.List;

public class AppInfoAdapter extends RecyclerView.Adapter<AppInfoAdapter.ViewHolder> {
    private final List<ApplicationInfo> apps;
    private final PackageManager pm;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(ApplicationInfo appInfo);
    }

    public AppInfoAdapter(Context context, List<ApplicationInfo> apps, OnItemClickListener listener) {
        this.apps = apps;
        this.pm = context.getPackageManager();
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_info, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ApplicationInfo info = apps.get(position);
        holder.appName.setText(pm.getApplicationLabel(info));
        holder.pkgName.setText(info.packageName);
        holder.appIcon.setImageDrawable(pm.getApplicationIcon(info));
        holder.itemView.setOnClickListener(v -> listener.onItemClick(info));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView appName, pkgName;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.appIcon);
            appName = itemView.findViewById(R.id.appName);
            pkgName = itemView.findViewById(R.id.pkgName);
        }
    }
}
