package com.mrtrazan.minecraft.codexassistant.ai;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * ChatGPT handles inventory management, block placement/breaking, and resource gathering
 */
public class ChatGPTInventoryEngine {

    private static long lastAnalysisRequest = 0;
    private static final long ANALYSIS_REQUEST_INTERVAL_MS = 10000;
    private static final AtomicBoolean analysisInFlight = new AtomicBoolean(false);
    private static final AtomicBoolean apiKeyMissingLogged = new AtomicBoolean(false);

    public static String optimizeInventory(MinecraftClient client) {
        if (client.player == null) return "NONE";
        return optimizeInventory(client, buildInventoryContext(client));
    }

    public static String optimizeInventory(MinecraftClient client, String fullContext) {
        if (client.player == null) return "NONE";

        String localResult = "LOCAL_OPTIMIZED";

        if (com.mrtrazan.minecraft.codexassistant.config.ModConfig.getInstance().enableChatGPT && OpenAIClient.hasApiKey(false) && shouldRequestAnalysis()) {
            String systemPrompt = "You are ChatGPT, a Minecraft inventory and block management assistant. " +
                "You have FULL ACCESS to player inventory, health, armor, hunger, and all items. " +
                "Analyze everything and recommend optimal inventory organization and resource priorities.";
            String userPrompt = String.format(
                "FULL INVENTORY ANALYSIS:\n%s\n\nProvide specific recommendations for optimization, item priority, and resource gathering.",
                fullContext
            );

            ConversationManager.addUserMessage(userPrompt);
            OpenAIClient.requestChatCompletionWithHistory("gpt-4o-mini", systemPrompt, ConversationManager.recent(), userPrompt)
                .thenAccept(response -> {
                    analysisInFlight.set(false);
                    if (response != null && !response.isBlank()) {
                        ConversationManager.addAssistantMessage(response.trim());
                        // Scan for [ACTION: ...] tags proposed by ChatGPT
                        ActionPermissionManager.scanAndProposeActions(client, response);
                    }
                })
                .exceptionally(ex -> {
                    analysisInFlight.set(false);
                    ex.printStackTrace();
                    return null;
                });
        } else if (!OpenAIClient.hasApiKey(false)) {
            if (apiKeyMissingLogged.compareAndSet(false, true)) {
                System.out.println("[ChatGPT Inventory] OPENAI_API_KEY not set, using local inventory management.");
            }
        } else {
            apiKeyMissingLogged.set(false);
        }

        return localResult;
    }

    public static void manageInventory(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;

        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            
            if ((stack.getItem().getName().getString().contains("Dirt") ||
                 stack.getItem().getName().getString().contains("Cobblestone")) &&
                stack.getCount() > 32) {
                
                int slotId = (i < 9) ? (i + 36) : i;
                try {
                    client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId,
                        slotId,
                        1, // drop entire stack
                        net.minecraft.screen.slot.SlotActionType.THROW,
                        client.player
                    );
                } catch (Exception ignored) {}
            }
        }

        reorganizeHotbar(client);
    }

    private static void reorganizeHotbar(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;

        // Target mapping for hotbar slots 0 to 4
        String[] priority = {
            "Sword", "Pickaxe", "Axe", "Food", "Block"
        };

        for (int targetSlot = 0; targetSlot < priority.length; targetSlot++) {
            String keyword = priority[targetSlot];
            ItemStack currentStack = client.player.getInventory().getStack(targetSlot);
            if (!currentStack.isEmpty() && VersionCompat.getItemName(currentStack).contains(keyword)) {
                continue; // already correct
            }

            // Look for matching item in rest of inventory (slots and hotbar)
            int foundSlot = -1;
            for (int i = 0; i < client.player.getInventory().size(); i++) {
                if (i == targetSlot) continue;
                ItemStack stack = client.player.getInventory().getStack(i);
                if (!stack.isEmpty()) {
                    String itemName = VersionCompat.getItemName(stack);
                    if (itemName.contains(keyword)) {
                        foundSlot = i;
                        break;
                    }
                }
            }

            // Swap if found
            if (foundSlot >= 0) {
                int screenSlot = (foundSlot < 9) ? (foundSlot + 36) : foundSlot;
                try {
                    client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId,
                        screenSlot,
                        targetSlot, // hotbar slot to swap with
                        net.minecraft.screen.slot.SlotActionType.SWAP,
                        client.player
                    );
                } catch (Exception ignored) {}
            }
        }
    }

    public static void mineBlock(MinecraftClient client, BlockPos blockPos) {
        if (client.interactionManager == null) return;

        client.interactionManager.attackBlock(blockPos, Direction.UP);
    }

    public static BlockPos findNearestBlock(MinecraftClient client, Block targetBlock, int radius) {
        if (client.player == null) return null;

        BlockPos playerPos = client.player.getBlockPos();
        double closest = Double.MAX_VALUE;
        BlockPos closestBlock = null;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);
                    if (client.world.getBlockState(checkPos).getBlock() == targetBlock) {
                        double dist = client.player.squaredDistanceTo(
                            checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5
                        );
                        if (dist < closest) {
                            closest = dist;
                            closestBlock = checkPos;
                        }
                    }
                }
            }
        }

        return closestBlock;
    }

    public static void placeBlock(MinecraftClient client, BlockPos blockPos, int hotbarSlot) {
        if (client.player == null || client.interactionManager == null) return;

        // Correctly hold the block in main hand by updating selectedSlot
        client.player.getInventory().selectedSlot = hotbarSlot;

        BlockPos adjacentPos = blockPos.up();
        client.interactionManager.interactBlock(
            client.player,
            net.minecraft.util.Hand.MAIN_HAND,
            new BlockHitResult(
                new Vec3d(adjacentPos.getX() + 0.5, adjacentPos.getY(), adjacentPos.getZ() + 0.5),
                Direction.UP,
                adjacentPos,
                false
            )
        );
    }

    public static void buildDefensiveStructure(MinecraftClient client, BlockPos centerPos) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                BlockPos placePos = centerPos.add(x, 0, z);
                placeBlock(client, placePos, 0);
            }
        }
    }

    public static String requestResourceAnalysis(MinecraftClient client) {
        if (client.player == null) return "NONE";
        return requestResourceAnalysis(client, buildInventoryContext(client));
    }

    public static String requestResourceAnalysis(MinecraftClient client, String fullContext) {
        if (client.player == null) return "NONE";

        String localResult = "RESOURCE_ANALYSIS_REQUESTED";

        if (com.mrtrazan.minecraft.codexassistant.config.ModConfig.getInstance().enableChatGPT && OpenAIClient.hasApiKey(false) && shouldRequestAnalysis()) {
            String systemPrompt = "You are ChatGPT, a Minecraft resource analysis and survival strategy assistant. " +
                "You have FULL ACCESS to all inventory, health, armor, hunger, and biome data. " +
                "Provide strategic recommendations for resource gathering, defense, crafting, and survival priorities.";
            String userPrompt = String.format(
                "FULL GAME CONTEXT FOR ANALYSIS:\n%s\n\nProvide strategic recommendations for resource gathering, survival priorities, and crafting needs.",
                fullContext
            );

            ConversationManager.addUserMessage(userPrompt);
            OpenAIClient.requestChatCompletionWithHistory("gpt-4o-mini", systemPrompt, ConversationManager.recent(), userPrompt)
                .thenAccept(response -> {
                    analysisInFlight.set(false);
                    if (response != null && !response.isBlank()) {
                        ConversationManager.addAssistantMessage(response.trim());
                        client.execute(() -> System.out.println("[ChatGPT Inventory] Resource analysis: " + response.trim()));
                        // Scan for [ACTION: ...] tags proposed by ChatGPT
                        ActionPermissionManager.scanAndProposeActions(client, response);
                    }
                })
                .exceptionally(ex -> {
                    analysisInFlight.set(false);
                    ex.printStackTrace();
                    return null;
                });
        } else if (!OpenAIClient.hasApiKey(false)) {
            if (apiKeyMissingLogged.compareAndSet(false, true)) {
                System.out.println("[ChatGPT Inventory] OPENAI_API_KEY not set, skipping API resource analysis.");
            }
        } else {
            apiKeyMissingLogged.set(false);
        }

        return localResult;
    }

    private static String buildInventoryContext(MinecraftClient client) {
        if (client.player == null) return "{}";

        StringBuilder context = new StringBuilder("{");
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                if (context.length() > 1) context.append(", ");
                context.append(String.format("\"%s\": %d", 
                    stack.getItem().getName().getString(), 
                    stack.getCount()
                ));
            }
        }
        context.append("}");
        return context.toString();
    }

    private static boolean shouldRequestAnalysis() {
        long now = System.currentTimeMillis();
        if (now - lastAnalysisRequest < ANALYSIS_REQUEST_INTERVAL_MS) {
            return false;
        }
        if (analysisInFlight.compareAndSet(false, true)) {
            lastAnalysisRequest = now;
            return true;
        }
        return false;
    }

    private static void requestChatAnalysis(MinecraftClient client, String systemPrompt, String userPrompt, String logPrefix) {
        lastAnalysisRequest = System.currentTimeMillis();

        OpenAIClient.requestChatCompletion("gpt-4o-mini", systemPrompt, userPrompt)
            .thenAccept(response -> {
                analysisInFlight.set(false);
                if (response == null || response.isBlank()) return;
                client.execute(() -> System.out.println(logPrefix + ": " + response.trim()));
            });
    }
}
