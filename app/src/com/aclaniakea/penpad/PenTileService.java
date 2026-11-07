package com.aclaniakea.penpad;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 下拉通知栏的快捷磁贴：点一下开关全屏批注。
 * 应用图标被隐藏后，这是启动批注的唯一入口（笔键被系统吞了，用不了）。
 */
public class PenTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!MainActivity.canOverlay(this)) {
            // 没权限就打开应用让它引导授权（应用已隐藏，这里直接跳设置页）
            try {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) { }
            return;
        }
        if (Services.isRunning) {
            FloatService.stop(this);
            Services.isRunning = false;
        } else {
            FloatService.start(this);
            Services.isRunning = true;
        }
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = Services.isRunning;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(on ? "批注：开" : "批注：关");
        t.updateTile();
    }
}
