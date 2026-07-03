package com.mrtrazan.minecraft.codexassistant;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ClothConfigProvider {
    public static Screen createScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Text.of("Codex Assistant"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // Basic example entries; real entries are added elsewhere (ModConfig persistence).
        builder.getOrCreateCategory(Text.of("General"));

        return builder.build();
    }
}
