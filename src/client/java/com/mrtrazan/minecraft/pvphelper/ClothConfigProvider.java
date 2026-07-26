package com.mrtrazan.minecraft.pvphelper;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ClothConfigProvider {
    public static Screen createScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Text.of("PvP Helper"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        builder.getOrCreateCategory(Text.of("General"));

        return builder.build();
    }
}
