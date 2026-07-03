package com.mrtrazan.minecraft.pvphelper;

import com.mrtrazan.minecraft.pvphelper.config.ModConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import com.mrtrazan.minecraft.pvphelper.ai.OpenAIClient;
import java.util.concurrent.CompletableFuture;

public class ModConfigScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget apiKeyField;
    private TextFieldWidget apiUrlField;
    private TextFieldWidget geminiKeyField;
    private TextFieldWidget geminiUrlField;
    private ButtonWidget geminiToggle;
    private ButtonWidget chatgptToggle;
    private ButtonWidget debugToggle;
    private ButtonWidget autoAcceptToggle;
    private Text statusText;
    private int statusTicks;

    protected ModConfigScreen(Screen parent) {
        super(Text.literal("PvP Helper Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int mid = this.width / 2;
        int y = 50;

        // OpenAI API Key
        apiKeyField = new TextFieldWidget(this.textRenderer, mid - 150, y, 300, 20, Text.literal("API Key"));
        apiKeyField.setMaxLength(512);
        apiKeyField.setText(ModConfig.getInstance().openAiApiKey != null ? ModConfig.getInstance().openAiApiKey : "");
        addDrawableChild(apiKeyField);
        addSelectableChild(apiKeyField);

        apiUrlField = new TextFieldWidget(this.textRenderer, mid - 150, y + 28, 300, 20, Text.literal("API URL"));
        apiUrlField.setMaxLength(512);
        apiUrlField.setText(ModConfig.getInstance().openAiApiUrl != null ? ModConfig.getInstance().openAiApiUrl : "");
        addDrawableChild(apiUrlField);
        addSelectableChild(apiUrlField);

        geminiKeyField = new TextFieldWidget(this.textRenderer, mid - 150, y + 56, 300, 20, Text.literal("Gemini API Key"));
        geminiKeyField.setMaxLength(512);
        geminiKeyField.setText(ModConfig.getInstance().geminiApiKey != null ? ModConfig.getInstance().geminiApiKey : "");
        addDrawableChild(geminiKeyField);
        addSelectableChild(geminiKeyField);

        geminiUrlField = new TextFieldWidget(this.textRenderer, mid - 150, y + 84, 300, 20, Text.literal("Gemini API URL"));
        geminiUrlField.setMaxLength(512);
        geminiUrlField.setText(ModConfig.getInstance().geminiApiUrl != null ? ModConfig.getInstance().geminiApiUrl : "");
        addDrawableChild(geminiUrlField);
        addSelectableChild(geminiUrlField);

        // Enable toggles - positioned below text fields
        geminiToggle = ButtonWidget.builder(Text.literal("Enable Gemini: " + (ModConfig.getInstance().enableGemini ? "ON" : "OFF")), btn -> {
            ModConfig.getInstance().enableGemini = !ModConfig.getInstance().enableGemini;
            btn.setMessage(Text.literal("Enable Gemini: " + (ModConfig.getInstance().enableGemini ? "ON" : "OFF")));
        }).dimensions(mid - 150, y + 115, 140, 20).build();
        addDrawableChild(geminiToggle);
        
        chatgptToggle = ButtonWidget.builder(Text.literal("Enable ChatGPT: " + (ModConfig.getInstance().enableChatGPT ? "ON" : "OFF")), btn -> {
            ModConfig.getInstance().enableChatGPT = !ModConfig.getInstance().enableChatGPT;
            btn.setMessage(Text.literal("Enable ChatGPT: " + (ModConfig.getInstance().enableChatGPT ? "ON" : "OFF")));
        }).dimensions(mid + 10, y + 115, 140, 20).build();
        addDrawableChild(chatgptToggle);

        debugToggle = ButtonWidget.builder(Text.literal("Debug Overlay: " + (ModConfig.getInstance().enableDebugOverlay ? "ON" : "OFF")), btn -> {
            ModConfig.getInstance().enableDebugOverlay = !ModConfig.getInstance().enableDebugOverlay;
            btn.setMessage(Text.literal("Debug Overlay: " + (ModConfig.getInstance().enableDebugOverlay ? "ON" : "OFF")));
        }).dimensions(mid - 150, y + 140, 140, 20).build();
        addDrawableChild(debugToggle);

        autoAcceptToggle = ButtonWidget.builder(Text.literal("Auto Accept: " + (ModConfig.getInstance().autoAcceptActions ? "ON" : "OFF")), btn -> {
            ModConfig.getInstance().autoAcceptActions = !ModConfig.getInstance().autoAcceptActions;
            btn.setMessage(Text.literal("Auto Accept: " + (ModConfig.getInstance().autoAcceptActions ? "ON" : "OFF")));
        }).dimensions(mid + 10, y + 140, 140, 20).build();
        addDrawableChild(autoAcceptToggle);

        // Save and Cancel buttons
        addDrawableChild(ButtonWidget.builder(Text.literal("Test API Keys"), button -> {
            String openAiKey = apiKeyField.getText();
            String openAiUrl = apiUrlField.getText();
            String geminiKey = geminiKeyField.getText();
            String geminiUrl = geminiUrlField.getText();

            CompletableFuture<Boolean> openAiFuture = OpenAIClient.testApiKey(openAiKey, openAiUrl, false);
            CompletableFuture<Boolean> geminiFuture = OpenAIClient.testApiKey(geminiKey, geminiUrl, true);

            CompletableFuture.allOf(openAiFuture, geminiFuture).thenRun(() -> {
                boolean openAiValid = openAiFuture.join();
                boolean geminiValid = geminiFuture.join();
                this.client.execute(() -> {
                    String message = "OpenAI: " + (openAiValid ? "OK" : "FAIL") + ", Gemini: " + (geminiValid ? "OK" : "FAIL");
                    if (!openAiValid && openAiKey.isBlank()) {
                        message += " (OpenAI key missing)";
                    }
                    if (!geminiValid && geminiKey.isBlank()) {
                        message += " (Gemini key missing)";
                    }
                    this.statusText = Text.literal(message);
                    this.statusTicks = 140;
                    System.out.println("[PvP Helper] " + message);
                });
            });
        }).dimensions(mid - 150, y + 170, 140, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> {
            ModConfig.getInstance().openAiApiKey = apiKeyField.getText();
            ModConfig.getInstance().openAiApiUrl = apiUrlField.getText();
            ModConfig.getInstance().geminiApiKey = geminiKeyField.getText();
            ModConfig.getInstance().geminiApiUrl = geminiUrlField.getText();
            // toggles already updated by their buttons; just persist
            ModConfig.save();
            this.client.setScreen(parent);
        }).dimensions(mid - 72, y + 200, 70, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> {
            this.client.setScreen(parent);
        }).dimensions(mid + 2, y + 200, 98, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int mid = this.width / 2;
        int y = 50;
        context.fill(mid - 160, y - 8, mid + 160, y + 220, 0xCC000000);

        context.drawText(this.textRenderer, Text.literal("PvP Helper Settings"), mid - 70, 20, 0xFFFFFF, false);
        context.drawText(this.textRenderer, Text.literal("OpenAI API Key  (platform.openai.com → API keys):"), mid - 150, y - 12, 0xAAAAAA, false);
        context.drawText(this.textRenderer, Text.literal("OpenAI API URL  (leave blank for default):"), mid - 150, y + 16, 0xAAAAAA, false);
        context.drawText(this.textRenderer, Text.literal("Gemini API Key  (aistudio.google.com → Get API key):"), mid - 150, y + 44, 0xAAAAAA, false);
        context.drawText(this.textRenderer, Text.literal("Gemini API URL  (leave blank for default):"), mid - 150, y + 72, 0xAAAAAA, false);
        context.drawText(this.textRenderer, Text.literal("Settings:"), mid - 150, y + 105, 0xAAAAAA, false);
        context.drawText(this.textRenderer, Text.literal("Click Save to apply. Keys are stored locally."), mid - 150, y + 190, 0x888888, false);

        if (this.apiKeyField.getText().isEmpty()) {
            context.drawText(this.textRenderer, Text.literal("sk-proj-..."), this.apiKeyField.getX() + 4, this.apiKeyField.getY() + 6, 0x666666, false);
        }
        if (this.apiUrlField.getText().isEmpty()) {
            context.drawText(this.textRenderer, Text.literal("https://api.openai.com/v1/chat/completions"), this.apiUrlField.getX() + 4, this.apiUrlField.getY() + 6, 0x555555, false);
        }
        if (this.geminiKeyField.getText().isEmpty()) {
            context.drawText(this.textRenderer, Text.literal("AIza..."), this.geminiKeyField.getX() + 4, this.geminiKeyField.getY() + 6, 0x666666, false);
        }
        if (this.geminiUrlField.getText().isEmpty()) {
            context.drawText(this.textRenderer, Text.literal("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"), this.geminiUrlField.getX() + 4, this.geminiUrlField.getY() + 6, 0x555555, false);
        }

        if (this.statusText != null && this.statusTicks > 0) {
            context.drawText(this.textRenderer, this.statusText, mid - 150, y + 225, 0xFFFFFF, false);
            this.statusTicks--;
        }

        super.render(context, mouseX, mouseY, delta);
    }
}
