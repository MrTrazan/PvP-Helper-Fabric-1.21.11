package com.mrtrazan.minecraft.codexassistant;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;


public class ModMenuProvider implements ModMenuApi {

    @Override
    public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
        return parent -> new com.mrtrazan.minecraft.codexassistant.ModConfigScreen(parent);
    }
}
