package com.mrtrazan.minecraft.codexassistant;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;

/**
 * ModMenu integration to open the mod's settings screen from ModMenu.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (parent) -> new ModConfigScreen((Screen) parent);
    }
}
