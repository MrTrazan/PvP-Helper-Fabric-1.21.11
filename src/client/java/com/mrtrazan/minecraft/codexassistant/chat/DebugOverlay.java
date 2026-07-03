package com.mrtrazan.minecraft.codexassistant.chat;

import com.mrtrazan.minecraft.codexassistant.ai.DualAICoordinator;
import com.mrtrazan.minecraft.codexassistant.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public class DebugOverlay {

    public static void render(DrawContext context) {
        if (!ModConfig.getInstance().enableDebugOverlay) return;
        MinecraftClient client = MinecraftClient.getInstance();
        String action = DualAICoordinator.nextPlannedAction != null ? DualAICoordinator.nextPlannedAction : "NONE";
        String reason = DualAICoordinator.nextPlannedReason != null ? DualAICoordinator.nextPlannedReason : "";

        int x = 10;
        int y = 10;
        context.drawText(client.textRenderer, Text.literal("Next AI action: " + action), x, y, 0xFF5555, false);
        context.drawText(client.textRenderer, Text.literal("Reason: " + reason), x, y + 12, 0xAAAAAA, false);
    }
}
