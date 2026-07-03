package com.mrtrazan.minecraft.codexassistant.ai;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Dual AI Coordinator
 *  - Gemini  : PvP combat decisions + block actions (mining, building defence, block placement)
 *  - ChatGPT : Inventory organisation + resource planning
 *
 * Hit detection uses the player's {@code hurtTime} field so that we catch every hit
 * reliably, regardless of minor floating-point noise in the health value.
 *
 * MOSHPIT MODE: when 3+ enemies are within 12 blocks, a MoshpitCache snapshot is taken
 * and the coordinator enters round-robin targeting — cycling through all nearby enemies
 * every MOSHPIT_ROTATE_TICKS ticks so the AI fights everyone simultaneously.
 */
public class DualAICoordinator {

    private static long lastPvPCheck       = 0;
    private static long lastInventoryCheck = 0;
    private static final long INVENTORY_CHECK_INTERVAL = 2000;

    public static volatile String nextPlannedAction = "NONE";
    public static volatile String nextPlannedReason = "";
    public static PlayerEntity activeTarget = null;
    private static boolean wasAiMoving = false;

    // ── Moshpit round-robin ───────────────────────────────────────────────────

    /** How many ticks between target rotations in moshpit mode. */
    private static final int MOSHPIT_ROTATE_TICKS = 8;
    private static int moshpitRotateTick = 0;
    private static int moshpitTargetIndex = 0;

    /** Live list of nearby enemy players used for round-robin. */
    private static final List<PlayerEntity> moshpitTargets = new ArrayList<>();

    // ── Hit tracking via hurtTime ─────────────────────────────────────────────

    private static int  lastHurtTime = 0;
    private static float lastHealth  = -1f;

    // ── Public entry point ────────────────────────────────────────────────────

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (com.mrtrazan.minecraft.codexassistant.config.ModConfig.getInstance().aiDisabled) {
            if (wasAiMoving) {
                client.options.leftKey.setPressed(false);
                client.options.rightKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                client.options.backKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                wasAiMoving = false;
            }
            return;
        }

        // Tick client-side Copper Golem bot
        CopperBotManager.tick(client);

        detectAndRecordHit(client);

        // Update moshpit snapshot every tick (cheap — just counts enemies)
        MoshpitCache.trySnapshot(client);

        // Tick PvP and combat controls on every single tick
        managePvP(client);
        handleCombatMovementAndAim(client);

        long now = System.currentTimeMillis();
        if (now - lastInventoryCheck >= INVENTORY_CHECK_INTERVAL) {
            lastInventoryCheck = now;
            manageInventoryAndBlocks(client);
        }
    }

    // ── Hit detection ─────────────────────────────────────────────────────────

    /**
     * Uses {@code hurtTime} to detect when the player is hit.
     */
    private static void detectAndRecordHit(MinecraftClient client) {
        if (client.player == null) return;

        int   hurtTime = client.player.hurtTime;
        float health   = client.player.getHealth();

        boolean newHit = (hurtTime >= 9 && lastHurtTime < 9);

        if (newHit && lastHealth > 0f) {
            float damage = lastHealth - health;
            if (damage < 0f) damage = 0f;

            PlayerEntity attacker    = null;
            double       nearestDist = 8.0;
            for (PlayerEntity p : client.world.getPlayers()) {
                if (p == client.player || p.isCreative()) continue;
                double d = client.player.distanceTo(p);
                if (d < nearestDist) {
                    nearestDist = d;
                    attacker    = p;
                }
            }

            double relAngle = 0;
            if (attacker != null) {
                double dx = attacker.getX() - client.player.getX();
                double dz = attacker.getZ() - client.player.getZ();
                double playerYaw = Math.toRadians(client.player.getYaw());
                double atkAngle  = Math.toDegrees(Math.atan2(-dx, dz));
                relAngle = Math.abs((atkAngle - Math.toDegrees(playerYaw) + 360) % 360);
                if (relAngle > 180) relAngle = 360 - relAngle;
            }

            GeminiPvPEngine.recordHit(nearestDist, damage, relAngle);
        }

        lastHurtTime = hurtTime;
        lastHealth   = health;
    }

    // ── PvP management (Gemini) ───────────────────────────────────────────────

    private static void managePvP(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Gather all nearby enemies
        moshpitTargets.clear();
        StringBuilder allEnemies  = new StringBuilder();
        double        closestDist = 12.0;
        PlayerEntity  nearestEnemy = null;

        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || p.isCreative() || p.isSpectator()) continue;
            double dist = client.player.distanceTo(p);
            allEnemies.append(String.format(
                "Enemy: %s, Distance: %.1f, Health: %.1f, Armor: %d  ",
                p.getName().getString(), dist, p.getHealth(), p.getArmor()));
            if (dist <= 16.0) {
                moshpitTargets.add(p);
            }
            if (dist < closestDist) {
                closestDist  = dist;
                nearestEnemy = p;
            }
        }

        // ── MOSHPIT MODE ──────────────────────────────────────────────────────
        if (MoshpitCache.isMoshpit() && moshpitTargets.size() >= MoshpitCache.MOSHPIT_THRESHOLD) {
            // Sort: highest-threat enemies first (matching MoshpitCache order)
            moshpitTargets.sort((a, b) -> {
                // Use simple threat heuristic: lower health + more armor + closer = higher threat
                double scoreA = a.getHealth() * 0.4 + a.getArmor() * 0.3
                    + Math.max(0, MoshpitCache.MOSHPIT_RADIUS - client.player.distanceTo(a)) * 0.3;
                double scoreB = b.getHealth() * 0.4 + b.getArmor() * 0.3
                    + Math.max(0, MoshpitCache.MOSHPIT_RADIUS - client.player.distanceTo(b)) * 0.3;
                return Double.compare(scoreB, scoreA);
            });

            // Rotate target every MOSHPIT_ROTATE_TICKS ticks
            moshpitRotateTick++;
            if (moshpitRotateTick >= MOSHPIT_ROTATE_TICKS) {
                moshpitRotateTick = 0;
                moshpitTargetIndex = (moshpitTargetIndex + 1) % moshpitTargets.size();
            }

            // Guard: clamp index
            if (moshpitTargetIndex >= moshpitTargets.size()) moshpitTargetIndex = 0;

            PlayerEntity moshTarget = moshpitTargets.get(moshpitTargetIndex);
            // Skip dead / removed targets
            if (moshTarget.isRemoved() || !moshTarget.isAlive()) {
                moshpitTargetIndex = 0;
                moshTarget = moshpitTargets.get(0);
            }

            activeTarget = moshTarget;

            MoshpitCache snap = MoshpitCache.get();
            String fullContext = buildFullCombatContext(client, moshTarget, allEnemies.toString())
                + (snap != null ? "\n" + snap.getSnapshotContext() : "");

            String geminiDecision = GeminiPvPEngine.getPvPAction(client, moshTarget, fullContext);
            if (geminiDecision != null) {
                nextPlannedAction = geminiDecision;
            }
            nextPlannedReason = "MOSHPIT: targeting " + moshTarget.getName().getString()
                + " (" + moshpitTargetIndex + 1 + "/" + moshpitTargets.size() + ")";

            // Attack target under crosshair if it is one of the moshpit targets
            if (client.targetedEntity instanceof PlayerEntity enemy && moshpitTargets.contains(enemy)) {
                if (client.player.distanceTo(enemy) < 2.9
                        && client.player.getAttackCooldownProgress(0.5F) >= 1.0F) {
                    GeminiPvPEngine.executeAttack(client, enemy);
                }
            }
        } else {
            // ── NORMAL 1v1 MODE ───────────────────────────────────────────────
            moshpitRotateTick  = 0;
            moshpitTargetIndex = 0;
            activeTarget = nearestEnemy;

            if (nearestEnemy != null) {
                String fullContext  = buildFullCombatContext(client, nearestEnemy, allEnemies.toString());
                String geminiDecision = GeminiPvPEngine.getPvPAction(client, nearestEnemy, fullContext);
                if (geminiDecision != null) {
                    nextPlannedAction = geminiDecision;
                }
                nextPlannedReason = fullContext;
            } else {
                nextPlannedAction = "NONE";
            }
        }
    }

    private static void handleCombatMovementAndAim(MinecraftClient client) {
        if (client.player == null) return;
        if (activeTarget == null || activeTarget.isRemoved() || !activeTarget.isAlive()
                || client.player.distanceTo(activeTarget) > 16.0f) {
            activeTarget = null;
            if (wasAiMoving) {
                client.options.leftKey.setPressed(false);
                client.options.rightKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                client.options.backKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                wasAiMoving = false;
            }
            return;
        }

        // 1. Smooth look rotation (interpolated)
        net.minecraft.util.math.Vec3d targetEyePos = activeTarget.getEyePos();
        net.minecraft.util.math.Vec3d playerEyePos = client.player.getEyePos();
        double dx   = targetEyePos.x - playerEyePos.x;
        double dy   = targetEyePos.y - playerEyePos.y;
        double dz   = targetEyePos.z - playerEyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        float currentYaw   = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float yawDiff   = targetYaw - currentYaw;
        while (yawDiff < -180.0F) yawDiff += 360.0F;
        while (yawDiff >= 180.0F) yawDiff -= 360.0F;

        float pitchDiff = targetPitch - currentPitch;

        // Anti-cheat safety: make the aiming speed non-linear (slow down as we align)
        // and add a small random drift/noise to mimic natural human movement.
        float speedFactor = 0.25F;
        float minSpeed = 0.2F;
        
        float targetYawSpeed = Math.abs(yawDiff) * speedFactor;
        if (targetYawSpeed < minSpeed) targetYawSpeed = minSpeed;
        float maxYawSpeed = Math.min(15.0F, targetYawSpeed);
        
        float targetPitchSpeed = Math.abs(pitchDiff) * speedFactor;
        if (targetPitchSpeed < minSpeed) targetPitchSpeed = minSpeed;
        float maxPitchSpeed = Math.min(10.0F, targetPitchSpeed);
        
        float noise = (client.world.getRandom().nextFloat() - 0.5F) * 0.3F;

        // Use VersionCompat.clamp instead of Java-21-only Math.clamp
        float deltaYaw   = VersionCompat.clamp(yawDiff + noise,   -maxYawSpeed,   maxYawSpeed);
        float deltaPitch = VersionCompat.clamp(pitchDiff + noise, -maxPitchSpeed, maxPitchSpeed);

        client.player.setYaw(currentYaw + deltaYaw);
        client.player.setPitch(currentPitch + deltaPitch);

        // 2. Keyboard simulation
        boolean strafeLeft  = "STRAFE_LEFT".equals(nextPlannedAction);
        boolean strafeRight = "STRAFE_RIGHT".equals(nextPlannedAction);
        boolean moveForward = "ATTACK".equals(nextPlannedAction)
            || "CRYSTAL_ATTACK".equals(nextPlannedAction)
            || "MACE_SLAM".equals(nextPlannedAction);

        double distToTarget = client.player.distanceTo(activeTarget);
        if (distToTarget > 2.8 && !"ELYTRA_BOOST".equals(nextPlannedAction)) {
            moveForward = true;
        }

        client.options.leftKey.setPressed(strafeLeft);
        client.options.rightKey.setPressed(strafeRight);
        client.options.forwardKey.setPressed(moveForward);

        // Jump logic: mace slam needs a jump; otherwise random small hops for unpredictability
        if ("MACE_SLAM".equals(nextPlannedAction) && client.player.isOnGround()) {
            client.options.jumpKey.setPressed(true);
        } else if (moveForward && client.player.isOnGround() && client.world.getRandom().nextFloat() < 0.15f) {
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
        }

        wasAiMoving = true;

        // 3. Attack / Combat action execution
        if (nextPlannedAction != null) {
            executeGeminiDecision(client, activeTarget, nextPlannedAction);
        }
    }

    private static String buildFullCombatContext(MinecraftClient client, PlayerEntity target, String allEnemies) {
        if (client.player == null) return "NO_CONTEXT";

        StringBuilder ctx = new StringBuilder();
        ctx.append("=== FULL PvP CONTEXT ===\n");
        ctx.append(String.format("Player Health  : %.1f/%.1f\n",  client.player.getHealth(), client.player.getMaxHealth()));
        ctx.append(String.format("Player Armor   : %d\n",       client.player.getArmor()));
        ctx.append(String.format("Player Food    : %d\n",       client.player.getHungerManager().getFoodLevel()));
        ctx.append(String.format("Player XP Lvl  : %d\n",       VersionCompat.getPlayerLevel(client.player)));
        ctx.append(String.format("Main Target    : %s\n",       target.getName().getString()));
        ctx.append(String.format("Target Distance: %.1f blocks\n", client.player.distanceTo(target)));
        ctx.append(String.format("Target Health  : %.1f/20\n",  target.getHealth()));
        ctx.append(String.format("Target Armor   : %d\n",       target.getArmor()));

        // Extended weapon/item scan
        boolean hasTotem = false, hasRod = false, hasSword = false;
        boolean hasMace = false, hasAxe = false, hasBow = false;
        boolean hasCrossbow = false, hasEndCrystal = false, hasElytra = false;
        int crystalCount = 0;
        int totemCount = 0;

        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String name = VersionCompat.getItemName(stack).toLowerCase();
            int count = stack.getCount();
            if (name.contains("totem"))                          { hasTotem = true;      totemCount += count; }
            if (name.contains("rod"))                            { hasRod   = true; }
            if (name.contains("sword"))                          { hasSword = true; }
            if (name.contains("mace"))                           { hasMace  = true; }
            if (name.contains("axe"))                            { hasAxe   = true; }
            if (name.contains("bow") && !name.contains("cross")) { hasBow   = true; }
            if (name.contains("crossbow"))                       { hasCrossbow = true; }
            if (name.contains("end_crystal") || name.contains("end crystal")) { hasEndCrystal = true; crystalCount += count; }
            if (name.contains("elytra"))                         { hasElytra = true; }
        }

        // Check off-hand elytra
        if (VersionCompat.getItemName(client.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST)).toLowerCase().contains("elytra")) {
            hasElytra = true;
        }

        ctx.append(String.format("Has Totem      : %s (x%d)\n",       hasTotem, totemCount));
        ctx.append(String.format("Has Rod        : %s\n",              hasRod));
        ctx.append(String.format("Has Sword      : %s\n",              hasSword));
        ctx.append(String.format("Has Mace       : %s\n",              hasMace));
        ctx.append(String.format("Has Axe        : %s\n",              hasAxe));
        ctx.append(String.format("Has Bow        : %s\n",              hasBow));
        ctx.append(String.format("Has Crossbow   : %s\n",              hasCrossbow));
        ctx.append(String.format("Has End Crystal: %s (x%d)\n",        hasEndCrystal, crystalCount));
        ctx.append(String.format("Has Elytra     : %s\n",              hasElytra));
        ctx.append("\nAll Nearby Enemies: ").append(allEnemies).append("\n");
        ctx.append(String.format("Moshpit Active : %s (%d enemies)\n",
            MoshpitCache.isMoshpit(), moshpitTargets.size()));

        return ctx.toString();
    }

    private static void executeGeminiDecision(MinecraftClient client, PlayerEntity target, String decision) {
        if (decision == null) return;
        switch (decision.toUpperCase()) {
            case "ATTACK"         -> GeminiPvPEngine.executeAttack(client, target);
            case "ROD_COMBO"      -> GeminiPvPEngine.executeRodCombo(client, target);
            case "CRYSTAL_ATTACK" -> GeminiPvPEngine.executeUseCrystal(client, target);
            case "MACE_SLAM"      -> GeminiPvPEngine.executeMaceSlam(client, target);
            case "ELYTRA_BOOST"   -> GeminiPvPEngine.executeElytraBoost(client);
            case "USE_TOTEM"      -> { if (GeminiPvPEngine.shouldUseTotem(client)) GeminiPvPEngine.deployTotem(client); }
            default               -> { /* STRAFE and NONE handled via tick */ }
        }
    }

    // ── Inventory & block management (ChatGPT / OpenAI) ─────────────────────

    private static void manageInventoryAndBlocks(MinecraftClient client) {
        if (client.player == null) return;

        String fullInventoryContext = buildFullInventoryContext(client);

        // === ChatGPT role: inventory cleaning, resource planning ===
        ChatGPTInventoryEngine.optimizeInventory(client, fullInventoryContext);
        ChatGPTInventoryEngine.manageInventory(client);
        ChatGPTInventoryEngine.requestResourceAnalysis(client, fullInventoryContext);

        // === Gemini role: block actions (mining/building) ===
        if (shouldGatherResources(client)) {
            gatherResources(client);
        }
        if (needsDefense(client)) {
            nextPlannedAction = "BUILD_DEFENSE";
            nextPlannedReason = "Defensive structure at player pos";
            ActionPermissionManager.proposeAction(new ActionPermissionManager.AIAction() {
                @Override public String getDescription() { return "Build defensive structure at current position"; }
                @Override public void execute(MinecraftClient c) {
                    ChatGPTInventoryEngine.buildDefensiveStructure(c, c.player.getBlockPos());
                }
            });
        }
    }

    private static String buildFullInventoryContext(MinecraftClient client) {
        if (client.player == null) return "NO_INVENTORY";

        StringBuilder ctx = new StringBuilder();
        ctx.append("=== FULL INVENTORY CONTEXT ===\n");
        ctx.append(String.format("Player Health: %.1f/20\n", client.player.getHealth()));
        ctx.append(String.format("Player Armor : %d\n",       client.player.getArmor()));
        ctx.append(String.format("Hunger       : %d/20\n",    client.player.getHungerManager().getFoodLevel()));
        ctx.append(String.format("XP Level     : %d\n",       VersionCompat.getPlayerLevel(client.player)));
        ctx.append("Inventory Items:\n");

        int totalSlots = client.player.getInventory().size();
        for (int i = 0; i < totalSlots; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String name      = VersionCompat.getItemName(stack);
                int    count     = stack.getCount();
                int    durability = stack.isDamageable() ? (stack.getMaxDamage() - stack.getDamage()) : -1;
                if (durability > 0) {
                    ctx.append(String.format("  [%d] %s x%d (Dur: %d/%d)\n", i, name, count, durability, stack.getMaxDamage()));
                } else {
                    ctx.append(String.format("  [%d] %s x%d\n", i, name, count));
                }
            }
        }
        ctx.append("\nPlayer Position: ").append(client.player.getBlockPos()).append("\n");
        try {
            ctx.append("Current Biome: ").append(client.world.getBiome(client.player.getBlockPos()).value()).append("\n");
        } catch (Exception ignored) {}
        return ctx.toString();
    }

    // ── Gemini block-action helpers ───────────────────────────────────────────

    private static boolean shouldGatherResources(MinecraftClient client) {
        if (client.player == null) return false;
        int dirtCount = 0, foodCount = 0;
        for (int i = 0; i < 36; i++) {
            String name  = VersionCompat.getItemName(client.player.getInventory().getStack(i));
            int    count = client.player.getInventory().getStack(i).getCount();
            if (name.contains("Dirt") || name.contains("Stone")) dirtCount += count;
            if (name.contains("Food") || name.contains("Apple")) foodCount += count;
        }
        return dirtCount < 16 || foodCount < 8;
    }

    private static void gatherResources(MinecraftClient client) {
        var dirtBlock = ChatGPTInventoryEngine.findNearestBlock(client, net.minecraft.block.Blocks.DIRT, 16);
        if (dirtBlock != null && client.player.squaredDistanceTo(
                dirtBlock.getX() + 0.5, dirtBlock.getY() + 0.5, dirtBlock.getZ() + 0.5) < 64) {
            nextPlannedAction = "MINE";
            nextPlannedReason = "Nearest dirt at " + dirtBlock.toShortString();
            ChatGPTInventoryEngine.mineBlock(client, dirtBlock);
        }
    }

    private static boolean needsDefense(MinecraftClient client) {
        if (client.player == null || client.world == null) return false;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p != client.player && !p.isCreative() && client.player.distanceTo(p) < 16) {
                return true;
            }
        }
        return false;
    }

    // ── ChatGPT AI response scan ──────────────────────────────────────────────

    public static void handleChatGPTResponse(MinecraftClient client, String response) {
        if (response == null || response.isBlank()) return;
        ActionPermissionManager.scanAndProposeActions(client, response);
    }

    public static void handleGeminiResponse(MinecraftClient client, String response) {
        if (response == null || response.isBlank()) return;
        ActionPermissionManager.scanAndProposeActions(client, response);
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public static String getStatus() {
        String moshpitStatus = MoshpitCache.isMoshpit()
            ? " | MOSHPIT(" + moshpitTargets.size() + " enemies)"
            : "";
        return "Dual AI Active - Gemini (PvP + Blocks) + ChatGPT (Inventory + Resources)" + moshpitStatus;
    }

    public static String getPvpAnalysisSummary() {
        return GeminiPvPEngine.getCombatHistorySummary();
    }
}
