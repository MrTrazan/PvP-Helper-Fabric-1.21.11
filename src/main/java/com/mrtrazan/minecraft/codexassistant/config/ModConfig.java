package com.mrtrazan.minecraft.codexassistant.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {

    private static final Gson GSON = new Gson();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("codex_assistant_config.json");
    private static final ModConfig INSTANCE = new ModConfig();

    public String openAiApiKey = "";
    public String openAiApiUrl = "";
    public String geminiApiKey = "";
    public String geminiApiUrl = "";
    public boolean enableGemini = true;
    public boolean enableChatGPT = true;
    public boolean enableDebugOverlay = false;
    public boolean aiDisabled = false;
    public boolean autoAcceptActions = true;

    private ModConfig() {
    }

    public static ModConfig getInstance() {
        return INSTANCE;
    }

    public static void load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.exists(CONFIG_PATH)) {
                try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    INSTANCE.openAiApiKey = json.has("openAiApiKey") ? json.get("openAiApiKey").getAsString() : "";
                    INSTANCE.openAiApiUrl = json.has("openAiApiUrl") ? json.get("openAiApiUrl").getAsString() : "";
                    INSTANCE.geminiApiKey = json.has("geminiApiKey") ? json.get("geminiApiKey").getAsString() : "";
                    INSTANCE.geminiApiUrl = json.has("geminiApiUrl") ? json.get("geminiApiUrl").getAsString() : "";
                    INSTANCE.enableGemini = json.has("enableGemini") ? json.get("enableGemini").getAsBoolean() : true;
                    INSTANCE.enableChatGPT = json.has("enableChatGPT") ? json.get("enableChatGPT").getAsBoolean() : true;
                    INSTANCE.enableDebugOverlay = json.has("enableDebugOverlay") ? json.get("enableDebugOverlay").getAsBoolean() : false;
                    INSTANCE.aiDisabled = json.has("aiDisabled") ? json.get("aiDisabled").getAsBoolean() : false;
                    INSTANCE.autoAcceptActions = json.has("autoAcceptActions") ? json.get("autoAcceptActions").getAsBoolean() : true;
                }
            } else {
                save();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("openAiApiKey", INSTANCE.openAiApiKey != null ? INSTANCE.openAiApiKey : "");
            json.addProperty("openAiApiUrl", INSTANCE.openAiApiUrl != null ? INSTANCE.openAiApiUrl : "");
            json.addProperty("geminiApiKey", INSTANCE.geminiApiKey != null ? INSTANCE.geminiApiKey : "");
            json.addProperty("geminiApiUrl", INSTANCE.geminiApiUrl != null ? INSTANCE.geminiApiUrl : "");
            json.addProperty("enableGemini", INSTANCE.enableGemini);
            json.addProperty("enableChatGPT", INSTANCE.enableChatGPT);
            json.addProperty("enableDebugOverlay", INSTANCE.enableDebugOverlay);
            json.addProperty("aiDisabled", INSTANCE.aiDisabled);
            json.addProperty("autoAcceptActions", INSTANCE.autoAcceptActions);
            Files.writeString(CONFIG_PATH, GSON.toJson(json));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
