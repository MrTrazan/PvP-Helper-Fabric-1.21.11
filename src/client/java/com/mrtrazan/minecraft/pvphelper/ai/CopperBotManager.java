package com.mrtrazan.minecraft.pvphelper.ai;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Hand;

/**
 * Manages a client-side "Copper Golem" dummy player entity.
 *
 * The bot is a visual-only OtherClientPlayerEntity; block changes are executed
 * via /setblock commands so they work correctly in both single-player (LAN) and
 * multiplayer (requires cheats/permissions).
 *
 * Movement is interpolated toward a target position each tick.
 *
 * Bug fixes vs original:
 *  - Entity re-add no longer spams a tight loop; uses a boolean flag instead.
 *  - spawnBot defers entity add to the next client execute() call to avoid
 *    world-load ordering issues.
 *  - Arm swing only on even ticks during an action, not every tick.
 */
public class CopperBotManager {

    private static OtherClientPlayerEntity botInstance = null;
    private static Vec3d targetPos = null;
    private static int ticksRemainingForAction = 0;

    /** Flag: set true when we need to re-add the bot entity on the next tick. */
    private static boolean botNeedsReAdd = false;

    public static synchronized void spawnBot(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        // Remove existing bot if present
        if (botInstance != null) {
            try {
                client.world.removeEntity(botInstance.getId(), net.minecraft.entity.Entity.RemovalReason.DISCARDED);
            } catch (Exception ignored) {}
            botInstance = null;
        }

        GameProfile profile = new GameProfile(UUID.randomUUID(), "CopperGolem");
        botInstance = new OtherClientPlayerEntity(client.world, profile);
        botInstance.setPosition(
            client.player.getX() + 2,
            client.player.getY(),
            client.player.getZ() + 2
        );

        // Defer the addEntity call to avoid world-loading race conditions
        client.execute(() -> {
            if (botInstance != null && client.world != null) {
                try {
                    client.world.addEntity(botInstance);
                } catch (Exception e) {
                    botNeedsReAdd = true;
                }
            }
        });

        if (client.player != null) {
            client.player.sendMessage(
                net.minecraft.text.Text.literal("[Copper Golem Bot] Spawned client-side!")
                    .formatted(net.minecraft.util.Formatting.GREEN), false);
        }
    }

    public static synchronized void removeBot(MinecraftClient client) {
        if (botInstance != null && client.world != null) {
            try {
                client.world.removeEntity(botInstance.getId(), net.minecraft.entity.Entity.RemovalReason.DISCARDED);
            } catch (Exception ignored) {}
            botInstance = null;
            botNeedsReAdd = false;
            if (client.player != null) {
                client.player.sendMessage(
                    net.minecraft.text.Text.literal("[Copper Golem Bot] Removed.")
                        .formatted(net.minecraft.util.Formatting.RED), false);
            }
        }
    }

    public static synchronized void moveBot(BlockPos pos) {
        targetPos = new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    public static synchronized void botPlace(MinecraftClient client, BlockPos pos, String blockType) {
        if (botInstance == null) return;
        targetPos = new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        ticksRemainingForAction = 10;

        if (client.player != null) {
            try {
                client.player.networkHandler.sendChatCommand(
                    "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + blockType);
            } catch (Exception ignored) {}
            botInstance.swingHand(Hand.MAIN_HAND);
        }
    }

    public static synchronized void botBreak(MinecraftClient client, BlockPos pos) {
        if (botInstance == null) return;
        targetPos = new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        ticksRemainingForAction = 10;

        if (client.player != null) {
            try {
                client.player.networkHandler.sendChatCommand(
                    "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " air");
            } catch (Exception ignored) {}
            botInstance.swingHand(Hand.MAIN_HAND);
        }
    }

    public static synchronized void tick(MinecraftClient client) {
        if (botInstance == null || client.world == null || client.player == null) return;

        // Handle deferred re-add (flag-based, not tight-loop)
        if (botNeedsReAdd) {
            botNeedsReAdd = false;
            try {
                client.world.addEntity(botInstance);
            } catch (Exception ignored) {}
            return; // skip rest of tick so entity is settled
        }

        // If entity fell out of the world tracker, schedule a single re-add
        if (client.world.getEntityById(botInstance.getId()) == null) {
            botNeedsReAdd = true;
            return;
        }

        // --- FOLLOWING LIKE A DOG BEHAVIOR ---
        if (ticksRemainingForAction <= 0) {
            double distToPlayer = botInstance.distanceTo(client.player);
            if (distToPlayer > 16.0) {
                // Teleport to player with a small offset
                double angle = client.world.getRandom().nextDouble() * Math.PI * 2;
                botInstance.setPosition(
                    client.player.getX() + Math.cos(angle) * 1.5,
                    client.player.getY(),
                    client.player.getZ() + Math.sin(angle) * 1.5
                );
                targetPos = null;
            } else if (distToPlayer > 2.5) {
                targetPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
            } else if (distToPlayer < 1.8) {
                targetPos = null;
            }
        }

        // Interpolate movement toward target position
        if (targetPos != null) {
            Vec3d botPos = new Vec3d(botInstance.getX(), botInstance.getY(), botInstance.getZ());
            Vec3d dir    = targetPos.subtract(botPos);
            double dist  = dir.length();
            if (dist > 0.1) {
                Vec3d step   = dir.normalize().multiply(Math.min(0.15, dist));
                Vec3d newPos = botPos.add(step);
                botInstance.setPosition(newPos.x, newPos.y, newPos.z);

                double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
                botInstance.setYaw((float) yaw);
                botInstance.setHeadYaw((float) yaw);
            } else {
                targetPos = null;
            }
        }

        // Swing arm only on even ticks during action (not every tick — prevents spam)
        if (ticksRemainingForAction > 0) {
            ticksRemainingForAction--;
            if (ticksRemainingForAction % 2 == 0) {
                botInstance.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    public static boolean isBotActive() {
        return botInstance != null;
    }
}
