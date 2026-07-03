package com.mrtrazan.minecraft.codexassistant;

import net.fabricmc.api.ModInitializer;

public class CodexAssistant implements ModInitializer {

    public static final String MOD_ID = "codex_assistant";
    public static final String MOD_NAME = "Codex Assistant";

    @Override
    public void onInitialize() {
        System.out.println("[" + MOD_NAME + "] Initializing with Dual AI System");
        System.out.println("[" + MOD_NAME + "] Gemini (PvP) + ChatGPT (Inventory/Blocks)");
        System.out.println("[" + MOD_NAME + "] Server-side initialization complete. Client AI will start when player enters world.");
    }
}
