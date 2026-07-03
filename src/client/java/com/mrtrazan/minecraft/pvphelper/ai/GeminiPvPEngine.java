package com.mrtrazan.minecraft.pvphelper.ai;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Gemini handles all PvP-related decisions and combat actions.
 * Uses gemini-2.0-flash via Google's OpenAI-compatible endpoint.
 * Dynamic polling: fires the next request immediately once the previous one finishes.
 *
 * Supported action keywords:
 *   ATTACK        — auto-select best melee weapon (sword > mace > axe > fist) and strike
 *   STRAFE_LEFT   — strafe movement (handled in DualAICoordinator tick)
 *   STRAFE_RIGHT  — strafe movement (handled in DualAICoordinator tick)
 *   ROD_COMBO     — fish rod knock-back then attack
 *   USE_TOTEM     — move totem to off-hand
 *   CRYSTAL_ATTACK— place end crystal at target's feet and detonate
 *   MACE_SLAM     — select mace, jump, and slam
 *   ELYTRA_BOOST  — equip elytra + fire rocket for repositioning
 *   NONE          — no-op
 */
public class GeminiPvPEngine {

    private static long lastApiRequest = 0;
    /** Minimum guard interval (ms) to avoid firing if the reply comes back suspiciously fast. */
    private static final long MIN_GUARD_MS = 100;
    private static final AtomicBoolean requestInFlight = new AtomicBoolean(false);

    // ── Combat-history ring-buffer ────────────────────────────────────────────

    private static final int MAX_HISTORY = 10;

    private static final double[] hitDistances  = new double[MAX_HISTORY];
    private static final long[]   hitIntervals  = new long[MAX_HISTORY];   // ms between consecutive hits
    private static final float[]  hitDamages    = new float[MAX_HISTORY];  // HP lost per hit
    private static final double[] hitAngles     = new double[MAX_HISTORY]; // relative horizontal angle (deg)
    private static int historyHead = 0;
    private static int historySize = 0;
    private static long lastHitTimestamp = 0;

    /**
     * Record a PvP hit for later injection into the combat prompt.
     *
     * @param distance   Distance from player to attacker at time of hit.
     * @param damage     Health points lost.
     * @param relAngle   Relative horizontal angle to attacker (0° = facing, 90° = right flank, 180° = from behind).
     */
    public static synchronized void recordHit(double distance, float damage, double relAngle) {
        long now = System.currentTimeMillis();
        long interval = lastHitTimestamp == 0 ? 0 : (now - lastHitTimestamp);
        lastHitTimestamp = now;

        hitDistances[historyHead] = distance;
        hitIntervals[historyHead] = interval;
        hitDamages[historyHead]   = damage;
        hitAngles[historyHead]    = relAngle;

        historyHead = (historyHead + 1) % MAX_HISTORY;
        if (historySize < MAX_HISTORY) historySize++;
    }

    /** Build a human-readable summary of the recorded hit history for inclusion in the AI prompt. */
    public static synchronized String getCombatHistorySummary() {
        if (historySize == 0) {
            return "No hits recorded yet — attacker pattern unknown.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== ATTACKER PATTERN ANALYSIS ===\n");
        sb.append(String.format("Total recorded hits: %d\n", historySize));

        double sumDist = 0, sumDmg = 0, sumAngle = 0;
        long sumInterval = 0;
        int intervalCount = 0;

        // Iterate from oldest to newest
        int start = historySize < MAX_HISTORY ? 0 : historyHead;
        for (int i = 0; i < historySize; i++) {
            int idx = (start + i) % MAX_HISTORY;
            sumDist     += hitDistances[idx];
            sumDmg      += hitDamages[idx];
            sumAngle    += hitAngles[idx];
            if (hitIntervals[idx] > 0) {
                sumInterval += hitIntervals[idx];
                intervalCount++;
            }
        }

        double avgDist     = sumDist  / historySize;
        double avgDmg      = sumDmg   / historySize;
        double avgAngle    = sumAngle / historySize;
        double avgInterval = intervalCount == 0 ? 0 : (double) sumInterval / intervalCount;

        sb.append(String.format("Avg attack distance : %.2f blocks\n", avgDist));
        sb.append(String.format("Avg damage per hit  : %.1f HP\n",     avgDmg));
        sb.append(String.format("Avg combo interval  : %.0f ms\n",     avgInterval));
        sb.append(String.format("Avg attack angle    : %.1f deg (0=frontal,90=flank,180=behind)\n", avgAngle));

        // Classify attacker style
        String style;
        if (avgDist < 2.5) {
            style = "CLOSE-RANGE BRAWLER";
        } else if (avgDist < 4.5) {
            style = "MID-RANGE SWORD FIGHTER";
        } else {
            style = "LONG-RANGE / PROJECTILE";
        }
        if (avgInterval > 0 && avgInterval < 500) {
            style += " with RAPID combos";
        } else if (avgInterval > 1200) {
            style += " with SLOW deliberate strikes";
        }
        if (avgAngle > 120) {
            style += " preferring FLANKING / BACK-ATTACKS";
        }
        sb.append("Classified style    : ").append(style).append("\n");

        // Last 3 hits detail
        sb.append("Recent hits (newest last):\n");
        int detailCount = Math.min(3, historySize);
        int detailStart = historySize < MAX_HISTORY
            ? historySize - detailCount
            : (historyHead - detailCount + MAX_HISTORY) % MAX_HISTORY;
        for (int i = 0; i < detailCount; i++) {
            int idx = (detailStart + i) % MAX_HISTORY;
            sb.append(String.format("  Hit %d: dist=%.2f, dmg=%.1f, angle=%.1f, interval=%dms\n",
                i + 1, hitDistances[idx], hitDamages[idx], hitAngles[idx], hitIntervals[idx]));
        }

        return sb.toString();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static String getPvPAction(MinecraftClient client, PlayerEntity target, String context) {
        if (client.player == null || target == null) return "NONE";

        if (!com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance().enableGemini) return "NONE";

        if (com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance().geminiApiKey != null
            && !com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance().geminiApiKey.isBlank()) {
            scheduleApiDecision(client, target, context);
            return null; // async; result handled in callback
        }

        // Fallback when no API key is configured
        double distance = client.player.distanceTo(target);
        float playerHealth = client.player.getHealth();
        if (playerHealth < 6.0f) return "USE_TOTEM";
        if (distance < 3.0)      return "ATTACK";
        return "STRAFE_RIGHT";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void scheduleApiDecision(MinecraftClient client, PlayerEntity target, String context) {
        if (target == null || client.player == null) return;

        // Dynamic polling: only skip if a request is still in-flight.
        if (requestInFlight.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastApiRequest < MIN_GUARD_MS) return;

        lastApiRequest = now;
        requestInFlight.set(true);

        String combatHistory  = getCombatHistorySummary();
        boolean inMoshpit     = MoshpitCache.isMoshpit();
        MoshpitCache snap     = MoshpitCache.get();
        String moshpitContext = (inMoshpit && snap != null) ? snap.getSnapshotContext() : "";

        String systemPrompt = "You are Gemini, a Minecraft PvP combat AI with FULL ACCESS to all player stats, "
            + "combat data, inventory, and tactical information. You have complete situational awareness AND detailed "
            + "learning of the attacker's fighting style based on hit history. "
            + "Respond with exactly ONE action keyword from this list:\n"
            + "  ATTACK        — melee strike with best weapon (sword/mace/axe)\n"
            + "  STRAFE_LEFT   — dodge left\n"
            + "  STRAFE_RIGHT  — dodge right\n"
            + "  ROD_COMBO     — fish-rod knockback then attack\n"
            + "  USE_TOTEM     — deploy totem of undying (use when health critical)\n"
            + "  CRYSTAL_ATTACK— place and detonate end crystal (use if crystals available)\n"
            + "  MACE_SLAM     — jump + slam with mace (use if mace available)\n"
            + "  ELYTRA_BOOST  — use elytra + rocket to reposition (use if elytra available)\n"
            + "  NONE          — wait\n"
            + "Base your decision on health, distance, armor, weapons, moshpit state, and attacker pattern. "
            + "Output ONLY the action keyword, nothing else.";

        String userPrompt = String.format(
            "FULL COMBAT ANALYSIS:\n%s\n\n%s\n%s\nMake an aggressive, tactical decision. Output only the action keyword.",
            context,
            combatHistory,
            moshpitContext
        );

        com.mrtrazan.minecraft.pvphelper.config.ModConfig cfg =
            com.mrtrazan.minecraft.pvphelper.config.ModConfig.getInstance();

        OpenAIClient.requestChatCompletionWithHistory(
                "gemini-2.0-flash",
                systemPrompt,
                ConversationManager.recent(),
                userPrompt,
                cfg.geminiApiKey,
                cfg.geminiApiUrl,
                true)
            .thenAccept(response -> {
                requestInFlight.set(false);
                if (response == null || response.isBlank()) return;
                String rawDecision = response.trim().toUpperCase();
                // Strip any punctuation / extra text — keep only the keyword
                // Must use a new effectively-final variable for use inside the lambda below
                final String decision = rawDecision.split("[^A-Z_]")[0].trim();
                ConversationManager.addAssistantMessage(decision);
                client.execute(() -> {
                    com.mrtrazan.minecraft.pvphelper.ai.DualAICoordinator.nextPlannedAction = decision;
                    executeGeminiDecision(client, target, decision);
                });
            })
            .exceptionally(ex -> {
                requestInFlight.set(false);
                ex.printStackTrace();
                return null;
            });
    }

    // ── Combat actions ────────────────────────────────────────────────────────

    /**
     * Select the best available weapon in the hotbar and attack the target.
     * Priority: sword > mace > axe > any tool > fist
     */
    public static void executeAttack(MinecraftClient client, PlayerEntity target) {
        if (client.player == null || target == null) return;

        int bestSlot    = -1;
        int bestPriority = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String name = VersionCompat.getItemName(stack).toLowerCase();
            int priority = -1;
            if (name.contains("sword"))    priority = 100;
            else if (name.contains("mace"))    priority = 90;
            else if (name.contains("axe"))     priority = 80;
            else if (name.contains("pickaxe")) priority = 40;
            else if (name.contains("shovel"))  priority = 30;

            if (priority > bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            client.player.getInventory().selectedSlot = bestSlot;
        }

        if (client.player.getAttackCooldownProgress(0.5F) >= 1.0F) {
            if (client.targetedEntity == target && client.player.distanceTo(target) < 2.9) {
                client.interactionManager.attackEntity(client.player, target);
            }
            client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
    }

    public static void executeStrafe(MinecraftClient client, PlayerEntity target, String direction) {
        // Handled via keyboard simulation in DualAICoordinator
    }

    public static void executeRodCombo(MinecraftClient client, PlayerEntity target) {
        if (client.player == null || target == null) return;
        for (int i = 0; i < 9; i++) {
            String name = VersionCompat.getItemName(client.player.getInventory().getStack(i)).toLowerCase();
            if (name.contains("rod")) {
                client.player.getInventory().selectedSlot = i;
                client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
                break;
            }
        }
    }

    /**
     * Place an End Crystal from the hotbar at the target's feet, then detonate it.
     * (Detonation via attacking the crystal entity is handled separately in DualAICoordinator.)
     */
    public static void executeUseCrystal(MinecraftClient client, PlayerEntity target) {
        if (client.player == null || target == null) return;

        // Find end crystal in hotbar
        for (int i = 0; i < 9; i++) {
            String name = VersionCompat.getItemName(client.player.getInventory().getStack(i)).toLowerCase();
            if (name.contains("end_crystal") || name.contains("end crystal")) {
                client.player.getInventory().selectedSlot = i;

                // Place crystal at target's feet
                BlockPos targetFeet = target.getBlockPos();
                try {
                    net.minecraft.util.hit.BlockHitResult hitResult = new net.minecraft.util.hit.BlockHitResult(
                        new Vec3d(targetFeet.getX() + 0.5, targetFeet.getY(), targetFeet.getZ() + 0.5),
                        net.minecraft.util.math.Direction.UP,
                        targetFeet,
                        false
                    );
                    client.interactionManager.interactBlock(
                        client.player,
                        net.minecraft.util.Hand.MAIN_HAND,
                        hitResult
                    );
                } catch (Exception e) {
                    // Safe fallback — just swing
                    client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                }
                break;
            }
        }
    }

    /**
     * Mace slam: select mace from hotbar, jump, then attack.
     */
    public static void executeMaceSlam(MinecraftClient client, PlayerEntity target) {
        if (client.player == null || target == null) return;

        for (int i = 0; i < 9; i++) {
            String name = VersionCompat.getItemName(client.player.getInventory().getStack(i)).toLowerCase();
            if (name.contains("mace")) {
                client.player.getInventory().selectedSlot = i;
                // Jump to build fall height for mace smash bonus
                if (client.player.isOnGround()) {
                    client.options.jumpKey.setPressed(true);
                }
                // Attack while in air
                if (!client.player.isOnGround() && client.player.getAttackCooldownProgress(0.5F) >= 0.8F) {
                    client.interactionManager.attackEntity(client.player, target);
                    client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                }
                break;
            }
        }
    }

    /**
     * Equip elytra from inventory to chest slot and fire a firework rocket for boost.
     */
    public static void executeElytraBoost(MinecraftClient client) {
        if (client.player == null) return;

        // Look for elytra in inventory and try to equip it to chest slot (slot 6 in player inv = chest)
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            String name = VersionCompat.getItemName(client.player.getInventory().getStack(i)).toLowerCase();
            if (name.contains("elytra")) {
                // Swap elytra into chest armor slot via click
                try {
                    // Chest slot index = 6 in the combined screen handler
                    client.interactionManager.clickSlot(
                        client.player.currentScreenHandler.syncId,
                        i, 0,
                        net.minecraft.screen.slot.SlotActionType.PICKUP,
                        client.player
                    );
                } catch (Exception ignored) {}
                break;
            }
        }

        // Fire a firework rocket from hotbar if in elytra
        for (int i = 0; i < 9; i++) {
            String name = VersionCompat.getItemName(client.player.getInventory().getStack(i)).toLowerCase();
            if (name.contains("firework") || name.contains("rocket")) {
                client.player.getInventory().selectedSlot = i;
                client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
                break;
            }
        }
    }

    private static void executeGeminiDecision(MinecraftClient client, PlayerEntity target, String decision) {
        if (decision == null || decision.isEmpty()) return;
        switch (decision) {
            case "ATTACK"         -> executeAttack(client, target);
            case "ROD_COMBO"      -> executeRodCombo(client, target);
            case "CRYSTAL_ATTACK" -> executeUseCrystal(client, target);
            case "MACE_SLAM"      -> executeMaceSlam(client, target);
            case "ELYTRA_BOOST"   -> executeElytraBoost(client);
            case "USE_TOTEM"      -> { if (shouldUseTotem(client)) deployTotem(client); }
            default               -> { /* NONE or STRAFE handled in tick */ }
        }
    }

    public static boolean shouldUseTotem(MinecraftClient client) {
        return client.player != null && client.player.getHealth() < 6.0f;
    }

    public static void deployTotem(MinecraftClient client) {
        if (client.player == null) return;
        if (VersionCompat.getItemName(client.player.getOffHandStack()).toLowerCase().contains("totem")) {
            client.player.swingHand(net.minecraft.util.Hand.OFF_HAND);
            return;
        }
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (VersionCompat.getItemName(stack).toLowerCase().contains("totem")) {
                if (i < 9) { // hotbar
                    client.player.getInventory().selectedSlot = i;
                    try {
                        client.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket(
                            net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                            net.minecraft.util.math.BlockPos.ORIGIN,
                            net.minecraft.util.math.Direction.DOWN
                        ));
                    } catch (Exception ignored) {}
                } else { // main inventory
                    try {
                        client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            i,
                            8,
                            net.minecraft.screen.slot.SlotActionType.SWAP,
                            client.player
                        );
                        client.player.getInventory().selectedSlot = 8;
                        client.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket(
                            net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                            net.minecraft.util.math.BlockPos.ORIGIN,
                            net.minecraft.util.math.Direction.DOWN
                        ));
                    } catch (Exception ignored) {}
                }
                client.player.swingHand(net.minecraft.util.Hand.OFF_HAND);
                break;
            }
        }
    }
}
