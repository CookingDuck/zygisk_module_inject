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
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.zheng.inject.manager.R;
import com.zheng.inject.manager.model.InjectConfig;
import java.util.List;

public class AppConfigAdapter extends RecyclerView.Adapter<AppConfigAdapter.ViewHolder> {
    private final List<InjectConfig> configs;
    private final PackageManager pm;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(InjectConfig config);
        void onToggleChange(InjectConfig config, boolean isChecked);
    }

    public AppConfigAdapter(Context context, List<InjectConfig> configs, OnItemClickListener listener) {
        this.configs = configs;
        this.pm = context.getPackageManager();
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_config, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InjectConfig config = configs.get(position);
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(config.packageName, 0);
            holder.appName.setText(pm.getApplicationLabel(appInfo));
            holder.appIcon.setImageDrawable(pm.getApplicationIcon(appInfo));
        } catch (PackageManager.NameNotFoundException e) {
            holder.appName.setText(config.packageName);
            holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        
        holder.pkgName.setText(config.packageName);
        holder.switchLoad.setChecked(config.loadSo);
        holder.soInfo.setText(config.soName + " (" + config.injectModel + ")");

        holder.itemView.setOnClickListener(v -> listener.onItemClick(config));
        holder.switchLoad.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.loadSo = isChecked;
            listener.onToggleChange(config, isChecked);
        });
    }

    @Override
    public int getItemCount() {
        return configs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView appName, pkgName, soInfo;
        SwitchCompat switchLoad;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.appIcon);
            appName = itemView.findViewById(R.id.appName);
            pkgName = itemView.findViewById(R.id.pkgName);
            soInfo = itemView.findViewById(R.id.soInfo);
            switchLoad = itemView.findViewById(R.id.switchLoad);
        }
    }
}
