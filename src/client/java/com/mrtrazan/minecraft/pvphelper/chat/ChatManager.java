package com.mrtrazan.minecraft.pvphelper.chat;

import com.mrtrazan.minecraft.pvphelper.ai.ActionPermissionManager;
import com.mrtrazan.minecraft.pvphelper.ai.ConversationManager;
import com.mrtrazan.minecraft.pvphelper.ai.OpenAIClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ChatManager — handles user ↔ AI conversation flow.
 *
 * URL enrichment: any http(s) URLs found in a user message are fetched
 * asynchronously and their text content is appended to the message before
 * it is sent to the API. This is done transparently in both
 * {@link #sendUserMessage} and {@link #sendUserMessageFromCommand}.
 *
 * Action scanning: every AI response is passed through
 * {@link ActionPermissionManager#scanAndProposeActions} so that any
 * [ACTION: ...] tags the model emits are surfaced as permission-gated actions
 * with clickable [ACCEPT] / [DECLINE] buttons in the HUD chat.
 */
public class ChatManager {

    private static final List<String> messages = new ArrayList<>();
    private static final String SYSTEM_PROMPT =
        "You are a helpful Minecraft assistant. Keep replies short and actionable. "
        + "Gemini AI handles PvP combat and block actions; OpenAI handles inventory organisation "
        + "and resource planning. If you want to propose an in-game action, output it as "
        + "[ACTION: RUN_COMMAND <cmd>], [ACTION: PLACE_BLOCK <x> <y> <z> <block>], "
        + "[ACTION: BREAK_BLOCK <x> <y> <z>], [ACTION: SPAWN_BOT], or [ACTION: REMOVE_BOT].";

    // ── Message log ───────────────────────────────────────────────────────────

    public static List<String> getMessages() {
        return new ArrayList<>(messages);
    }

    public static void clearMessages() {
        messages.clear();
    }

    public static void addLocalMessage(String author, String content) {
        String line = author + ": " + content;
        messages.add(line);
        if (messages.size() > 200) messages.remove(0);
    }

    // ── Core send methods ─────────────────────────────────────────────────────

    /**
     * Send a user message from the in-game chat overlay (GUI mode).
     * Responses are stored in the local message list.
     */
    public static void sendUserMessage(String content) {
        if (content == null || content.isBlank()) return;
        addLocalMessage("You", content);
        ConversationManager.addUserMessage(content);

        boolean isGemini = content.trim().toLowerCase().startsWith("gemini ");
        String cleanContent = content;
        if (isGemini) {
            cleanContent = content.trim().substring(7).trim();
        } else if (content.trim().toLowerCase().startsWith("openai ")) {
            cleanContent = content.trim().substring(7).trim();
        }

        String model = isGemini ? "gemini-2.0-flash" : "gpt-4o-mini";
        String systemPrompt = isGemini ?
            "You are Gemini, a Minecraft PvP and physical block action assistant. Keep replies short and actionable. Propose in-game actions using [ACTION: ...] tags: [ACTION: RUN_COMMAND <cmd>], [ACTION: PLACE_BLOCK <x> <y> <z> <block>], [ACTION: BREAK_BLOCK <x> <y> <z>], [ACTION: SPAWN_BOT], [ACTION: REMOVE_BOT]." :
            "You are ChatGPT/OpenAI, a Minecraft inventory manager and resource planner assistant. Keep replies short and actionable. Propose in-game actions using [ACTION: ...] tags: [ACTION: RUN_COMMAND <cmd>], [ACTION: PLACE_BLOCK <x> <y> <z> <block>], [ACTION: BREAK_BLOCK <x> <y> <z>], [ACTION: SPAWN_BOT], [ACTION: REMOVE_BOT].";

        com.mrtrazan.minecraft.pvphelper.config.ModConfig cfg =
            com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance();

        String apiKey = isGemini ? cfg.geminiApiKey : cfg.openAiApiKey;
        String apiUrl = isGemini ? cfg.geminiApiUrl : cfg.openAiApiUrl;

        // Enrich with URL content, then call API
        OpenAIClient.enrichMessageWithUrls(cleanContent).thenCompose(enriched ->
            OpenAIClient.requestChatCompletionWithHistory(
                model, systemPrompt, ConversationManager.recent(), enriched, apiKey, apiUrl, isGemini)
        ).thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                ConversationManager.addAssistantMessage(response.trim());
                addLocalMessage("Assistant", response.trim());
                // Scan for proposed actions
                MinecraftClient client = MinecraftClient.getInstance();
                ActionPermissionManager.scanAndProposeActions(client, response);
            } else {
                addLocalMessage("Assistant", "(no response)");
            }
        });
    }

    /**
     * Send a user message triggered by a /cai command.
     * Responses are printed directly into the HUD chat.
     *
     * @param content     The message text to send.
     * @param printToHUD  If true, also print the You/Assistant lines to HUD chat;
     *                    pass false if the caller already printed the "You:" line.
     */
    public static void sendUserMessageFromCommand(String content, boolean printToHUD) {
        if (content == null || content.isBlank()) return;

        MinecraftClient client = MinecraftClient.getInstance();

        if (printToHUD && client.player != null) {
            client.player.sendMessage(
                Text.literal("You: " + content).formatted(Formatting.GRAY), false);
        }

        ConversationManager.addUserMessage(content);

        boolean isGemini = content.trim().toLowerCase().startsWith("gemini ");
        String cleanContent = content;
        if (isGemini) {
            cleanContent = content.trim().substring(7).trim();
        } else if (content.trim().toLowerCase().startsWith("openai ")) {
            cleanContent = content.trim().substring(7).trim();
        }

        String model = isGemini ? "gemini-2.0-flash" : "gpt-4o-mini";
        String systemPrompt = isGemini ?
            "You are Gemini, a Minecraft PvP and physical block action assistant. Keep replies short and actionable. Propose in-game actions using [ACTION: ...] tags: [ACTION: RUN_COMMAND <cmd>], [ACTION: PLACE_BLOCK <x> <y> <z> <block>], [ACTION: BREAK_BLOCK <x> <y> <z>], [ACTION: SPAWN_BOT], [ACTION: REMOVE_BOT]." :
            "You are ChatGPT/OpenAI, a Minecraft inventory manager and resource planner assistant. Keep replies short and actionable. Propose in-game actions using [ACTION: ...] tags: [ACTION: RUN_COMMAND <cmd>], [ACTION: PLACE_BLOCK <x> <y> <z> <block>], [ACTION: BREAK_BLOCK <x> <y> <z>], [ACTION: SPAWN_BOT], [ACTION: REMOVE_BOT].";

        com.mrtrazan.minecraft.pvphelper.config.ModConfig cfg =
            com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance();

        String apiKey = isGemini ? cfg.geminiApiKey : cfg.openAiApiKey;
        String apiUrl = isGemini ? cfg.geminiApiUrl : cfg.openAiApiUrl;

        // Enrich with URL content asynchronously, then call API
        OpenAIClient.enrichMessageWithUrls(cleanContent).thenCompose(enriched ->
            OpenAIClient.requestChatCompletionWithHistory(
                model, systemPrompt, ConversationManager.recent(), enriched, apiKey, apiUrl, isGemini)
        ).thenAccept(response -> {
            if (response != null && !response.isBlank()) {
                ConversationManager.addAssistantMessage(response.trim());
                addLocalMessage("Assistant", response.trim());
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal("Assistant: " + response.trim()).formatted(Formatting.GREEN), false);
                }
                // Scan AI response for embedded [ACTION: ...] tags
                ActionPermissionManager.scanAndProposeActions(client, response);
            } else {
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal("Assistant: (no response)").formatted(Formatting.RED), false);
                }
            }
        });
    }

    /**
     * Convenience overload — always prints to HUD.
     */
    public static void sendUserMessageFromCommand(String content) {
        sendUserMessageFromCommand(content, true);
    }

    // ── Context-gathering commands ────────────────────────────────────────────

    /**
     * Gather the player's current status (health, armor, food, position, hotbar)
     * and send it to the AI, printing the response to HUD chat.
     */
    public static void askStatus() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        StringBuilder ctx = new StringBuilder();
        ctx.append("Evaluate my current status:\n");
        ctx.append(String.format("Health: %.1f/20, Armor: %d, Food: %d/20\n",
            client.player.getHealth(),
            client.player.getArmor(),
            client.player.getHungerManager().getFoodLevel()));
        ctx.append("Position: ").append(client.player.getBlockPos().toShortString()).append("\n");
        ctx.append("Hotbar items:\n");
        for (int i = 0; i < 9; i++) {
            var s = client.player.getInventory().getStack(i);
            if (!s.isEmpty()) {
                ctx.append(String.format("  [%d] %s x%d\n",
                    i, s.getItem().getName().getString(), s.getCount()));
            }
        }
        // Include PvP hit analysis summary
        ctx.append("\n").append(com.mrtrazan.minecraft.pvphelper.ai.GeminiPvPEngine.getCombatHistorySummary());

        sendUserMessageFromCommand(ctx.toString(), false);
    }

    /**
     * Gather details of the block or entity the player is currently looking at
     * and send them to the AI, printing the response to HUD chat.
     */
    public static void askLook() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        var target = client.crosshairTarget;
        if (target == null) {
            sendUserMessageFromCommand("Look context: I am looking at the sky or empty air.", false);
            return;
        }

        if (target.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && target instanceof net.minecraft.util.hit.BlockHitResult blockHit) {
            var blockPos   = blockHit.getBlockPos();
            var blockState = client.world.getBlockState(blockPos);
            String name    = blockState.getBlock().getName().getString();
            sendUserMessageFromCommand(
                "Analyze block: looking at " + name + " at " + blockPos.toShortString()
                + ". Provide combat/survival tips.", false);

        } else if (target.getType() == net.minecraft.util.hit.HitResult.Type.ENTITY
                && target instanceof net.minecraft.util.hit.EntityHitResult entityHit) {
            var entity = entityHit.getEntity();
            sendUserMessageFromCommand(
                "Analyze entity: looking at " + entity.getName().getString()
                + " at distance " + String.format("%.2f", client.player.distanceTo(entity))
                + " blocks. Provide PvP combat facts.", false);

        } else {
            sendUserMessageFromCommand(
                "Look context: looking at " + target.getType(), false);
        }
    }

    // ── Status string ─────────────────────────────────────────────────────────

    public static String getStatus() {
        var cfg = com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance();
        return "Dual AI: Gemini=" + (cfg.enableGemini  ? "ON" : "OFF")
             + ", ChatGPT="       + (cfg.enableChatGPT ? "ON" : "OFF");
    }
}
