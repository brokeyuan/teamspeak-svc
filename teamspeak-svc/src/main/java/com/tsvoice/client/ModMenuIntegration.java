package com.tsvoice.client;

/**
 * ModMenu 集成（可选依赖）：模组列表 → 点开 teamspeak-svc 条目 → 右上角齿轮按钮进入配置。
 * 未安装 ModMenu 时此类不加载；`/tsvoice gui` 命令恒可用。
 */
public class ModMenuIntegration implements com.terraformersmc.modmenu.api.ModMenuApi {

    @Override
    public com.terraformersmc.modmenu.api.ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigScreen screen = new ConfigScreen();
            screen.setParent(parent);
            return screen;
        };
    }
}
