package com.mrtrazan.minecraft.codexassistant.ai;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages AI-proposed actions that require explicit player approval before execution.
 *
 * When an AI response contains [ACTION: ...] tags, this class registers them in a pending
 * map and displays clickable [ACCEPT] / [DECLINE] buttons in the HUD chat.
 * On acceptance, the action is executed on the main client thread.
 *
 * Compatible with Minecraft 1.21.1 (Yarn 1.21.11+build.4).
 */
public class ActionPermissionManager {

    public interface AIAction {
        String getDescription();
        void execute(MinecraftClient client);
    }

    private static final Map<String, AIAction> pendingActions = new HashMap<>();
    private static int idCounter = 1;

    @SuppressWarnings("deprecation")
    public static synchronized String proposeAction(AIAction action) {
        String id = String.valueOf(idCounter++);
        pendingActions.put(id, action);

        MinecraftClient client = MinecraftClient.getInstance();
        boolean autoAccept = com.mrtrazan.minecraft.codexassistant.config.ModConfig.getInstance().autoAcceptActions;

        if (autoAccept) {
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("[Codex AI] Auto-executing Action: " + action.getDescription())
                        .formatted(Formatting.GREEN), false);
            }
            client.execute(() -> {
                action.execute(client);
                synchronized (ActionPermissionManager.class) {
                    pendingActions.remove(id);
                }
            });
        } else if (client.player != null) {
            MutableText header   = Text.literal("[Codex AI] Proposing Action: ").formatted(Formatting.GOLD);
            MutableText descText = Text.literal(action.getDescription()).formatted(Formatting.YELLOW);

            MutableText acceptText = Text.literal(" [ACCEPT] ").setStyle(Style.EMPTY
                .withColor(Formatting.GREEN)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/cai accept " + id))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to approve this action"))));

            MutableText declineText = Text.literal(" [DECLINE] ").setStyle(Style.EMPTY
                .withColor(Formatting.RED)
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/cai decline " + id))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to reject this action"))));

            client.player.sendMessage(header.append(descText).append(acceptText).append(declineText), false);
        }

        return id;
    }

    public static synchronized boolean acceptAction(String id) {
        AIAction action = pendingActions.remove(id);
        if (action != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("[Codex AI] Action approved and executing: " + action.getDescription())
                        .formatted(Formatting.GREEN), false);
            }
            client.execute(() -> action.execute(client));
            return true;
        }
        return false;
    }

    public static synchronized boolean declineAction(String id) {
        AIAction action = pendingActions.remove(id);
        if (action != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("[Codex AI] Action rejected: " + action.getDescription())
                        .formatted(Formatting.RED), false);
            }
            return true;
        }
        return false;
    }

    /**
     * Scan an AI response string for [ACTION: TYPE ARGS] patterns and register
     * each one as a pending permission-gated action with HUD buttons.
     */
    public static void scanAndProposeActions(MinecraftClient client, String text) {
        if (text == null || text.isBlank()) return;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[ACTION:\\s*(\\w+)\\s*(.*?)\\]");
        java.util.regex.Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String type = matcher.group(1).toUpperCase();
            String args = matcher.group(2).trim();

            switch (type) {
                case "RUN_COMMAND" -> {
                    final String cmd = args;
                    proposeAction(new AIAction() {
                        @Override public String getDescription() { return "Run command: \"" + cmd + "\""; }
                        @Override public void execute(MinecraftClient c) {
                            if (c.player != null) {
                                String command = cmd.startsWith("/") ? cmd.substring(1) : cmd;
                                c.player.networkHandler.sendChatCommand(command);
                            }
                        }
                    });
                }
                case "PLACE_BLOCK" -> {
                    try {
                        String[] parts   = args.split("\\s+");
                        int x            = Integer.parseInt(parts[0]);
                        int y            = Integer.parseInt(parts[1]);
                        int z            = Integer.parseInt(parts[2]);
                        String blockType = parts.length > 3 ? parts[3] : "stone";
                        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
                        proposeAction(new AIAction() {
                            @Override public String getDescription() { return "Place " + blockType + " at " + pos.toShortString(); }
                            @Override public void execute(MinecraftClient c) {
                                if (CopperBotManager.isBotActive()) {
                                    CopperBotManager.botPlace(c, pos, blockType);
                                } else {
                                    c.player.networkHandler.sendChatCommand(
                                        "setblock " + x + " " + y + " " + z + " " + blockType);
                                }
                            }
                        });
                    } catch (Exception e) {
                        System.out.println("[Codex AI] Failed to parse PLACE_BLOCK action: " + args);
                    }
                }
                case "BREAK_BLOCK" -> {
                    try {
                        String[] parts = args.split("\\s+");
                        int x          = Integer.parseInt(parts[0]);
                        int y          = Integer.parseInt(parts[1]);
                        int z          = Integer.parseInt(parts[2]);
                        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
                        proposeAction(new AIAction() {
                            @Override public String getDescription() { return "Break block at " + pos.toShortString(); }
                            @Override public void execute(MinecraftClient c) {
                                if (CopperBotManager.isBotActive()) {
                                    CopperBotManager.botBreak(c, pos);
                                } else {
                                    c.player.networkHandler.sendChatCommand(
                                        "setblock " + x + " " + y + " " + z + " air");
                                }
                            }
                        });
                    } catch (Exception e) {
                        System.out.println("[Codex AI] Failed to parse BREAK_BLOCK action: " + args);
                    }
                }
                case "SPAWN_BOT" -> proposeAction(new AIAction() {
                    @Override public String getDescription() { return "Spawn Copper Golem Bot"; }
                    @Override public void execute(MinecraftClient c) { CopperBotManager.spawnBot(c); }
                });
                case "REMOVE_BOT" -> proposeAction(new AIAction() {
                    @Override public String getDescription() { return "Remove Copper Golem Bot"; }
                    @Override public void execute(MinecraftClient c) { CopperBotManager.removeBot(c); }
                });
                default -> System.out.println("[Codex AI] Unknown action type from AI: " + type);
            }
        }
    }
}
