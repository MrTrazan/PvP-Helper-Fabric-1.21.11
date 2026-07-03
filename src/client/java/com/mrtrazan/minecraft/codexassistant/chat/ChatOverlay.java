package com.mrtrazan.minecraft.codexassistant.chat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

public class ChatOverlay {

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        var msgs = ChatManager.getMessages();
        int x = 10;
        int y = 30;
        int limit = Math.min(msgs.size(), 6);
        for (int i = Math.max(0, msgs.size() - limit); i < msgs.size(); i++) {
            context.drawText(client.textRenderer, Text.literal(msgs.get(i)), x, y, 0xDDDDDD, false);
            y += 12;
        }
        // small status
        context.drawText(client.textRenderer, Text.literal(ChatManager.getStatus()), 10, y + 6, 0xAAAAAA, false);
    }
}
