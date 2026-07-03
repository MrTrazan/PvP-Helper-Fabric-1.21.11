package com.mrtrazan.minecraft.pvphelper;

import net.fabricmc.api.ModInitializer;

public class PvPHelper implements ModInitializer {

    public static final String MOD_ID = "pvp_helper";
    public static final String MOD_NAME = "PvP Helper";

    @Override
    public void onInitialize() {
        System.out.println("[" + MOD_NAME + "] Initializing with Dual AI System");
        System.out.println("[" + MOD_NAME + "] Gemini (PvP) + ChatGPT (Inventory/Blocks)");
        System.out.println("[" + MOD_NAME + "] Server-side initialization complete. Client AI will start when player enters world.");
    }
}
