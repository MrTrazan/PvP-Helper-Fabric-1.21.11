package com.mrtrazan.minecraft.codexassistant.chat;

import com.mrtrazan.minecraft.codexassistant.ai.OpenAIClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

public class ChatScreen extends Screen {

    private TextFieldWidget input;
    private final MinecraftClient client = MinecraftClient.getInstance();
    private int scrollOffset = 0;

    public ChatScreen() {
        super(Text.literal("Codex Assistant Chat"));
    }

    protected void init() {
        int y = this.height - 30;

        input = new TextFieldWidget(this.textRenderer, 10, y, this.width - 330, 20, Text.literal("Chat input"));
        input.setMaxLength(256);
        addDrawableChild(input);
        addSelectableChild(input);
        this.setInitialFocus(input);

        addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), btn -> {
            ChatManager.clearMessages();
            scrollOffset = 0;
        }).dimensions(this.width - 320, y, 70, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Up"), btn -> {
            scrollOffset = Math.min(scrollOffset + 1, Math.max(0, ChatManager.getMessages().size() - getVisibleLines()));
        }).dimensions(this.width - 240, y, 50, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Down"), btn -> {
            scrollOffset = Math.max(scrollOffset - 1, 0);
        }).dimensions(this.width - 180, y, 50, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Send"), btn -> {
            String txt = input.getText();
            if (!txt.isBlank()) {
                ChatManager.sendUserMessage(txt);
                input.setText("");
            }
        }).dimensions(this.width - 110, y, 100, 20).build());
    }

    private int getVisibleLines() {
        int top = 40;
        int bottom = this.height - 60;
        return Math.max(3, (bottom - top) / 12);
    }

    public boolean isPauseScreen() { return false; }

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int left = 10;
        int right = this.width - 10;
        int top = 10;
        int bottom = this.height - 50;

        context.fill(left - 2, top - 2, right + 2, bottom + 2, 0xCC000000);
        context.fill(left, top, right, bottom, 0x88000000);

        context.drawText(this.textRenderer, Text.literal("Codex Assistant Chat"), left + 5, top + 5, 0xFFFFFF, false);
        
        List<String> msgs = ChatManager.getMessages();
        int visibleLines = getVisibleLines();
        
        // Show big warning if no API key configured
        if (!OpenAIClient.hasApiKey()) {
            context.drawText(this.textRenderer, Text.literal("*** WARNING: NO API KEY CONFIGURED ***"), left + 5, top + 18, 0xFF0000, false);
            context.drawText(this.textRenderer, Text.literal("HELLO"), left + 5, top + 32, 0xFF5555, false);
            context.drawText(this.textRenderer, Text.literal("U DIDN'T ADD API PLZ ADD IT"), left + 5, top + 44, 0xFF5555, false);
            context.drawText(this.textRenderer, Text.literal("WHY?"), left + 5, top + 56, 0xFFAA00, false);
            context.drawText(this.textRenderer, Text.literal("U DIDN'T ADD OK? ADD NOW"), left + 5, top + 68, 0xFF5555, false);
            context.drawText(this.textRenderer, Text.literal("Go to Mod Menu -> Codex Assistant Settings -> Add OpenAI API Key"), left + 5, top + 82, 0x00FFFF, false);
            
            int startIndex = Math.max(0, msgs.size() - visibleLines - scrollOffset);
            int endIndex = Math.max(0, msgs.size() - scrollOffset);
            int y = top + 100;

            for (int i = startIndex; i < endIndex; i++) {
                context.drawText(this.textRenderer, Text.literal(msgs.get(i)), left + 5, y, 0xFFFFFF, false);
                y += 12;
            }
        } else {
            context.drawText(this.textRenderer, Text.literal("API Mode: OpenAI"), left + 5, top + 18, 0x00FF00, false);

            int startIndex = Math.max(0, msgs.size() - visibleLines - scrollOffset);
            int endIndex = Math.max(0, msgs.size() - scrollOffset);
            int y = top + 34;

            for (int i = startIndex; i < endIndex; i++) {
                context.drawText(this.textRenderer, Text.literal(msgs.get(i)), left + 5, y, 0xFFFFFF, false);
                y += 12;
            }
        }

        if (msgs.size() > visibleLines) {
            context.drawText(this.textRenderer, Text.literal("Scroll: " + (scrollOffset + 1) + "/" + (msgs.size() - visibleLines + 1)), right - 80, top + 5, 0xAAAAAA, false);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput keyInput) {
        int keyCode = keyInput.key();
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            String txt = input.getText();
            if (!txt.isBlank()) {
                ChatManager.sendUserMessage(txt);
                input.setText("");
            }
            return true;
        }
        return super.keyPressed(keyInput);
    }

    public void tick() {
        super.tick();
    }
}
