package com.mrtrazan.minecraft.pvphelper.ai;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * VersionCompat — safe wrappers around APIs that differ between
 * Minecraft 1.20.x (Java 17) and 1.21.x (Java 21).
 *
 * Always use these instead of direct Java-21-only calls so the mod
 * can be loaded on any supported Minecraft version.
 */
public final class VersionCompat {

    private VersionCompat() {}

    // ── Math helpers ──────────────────────────────────────────────────────────

    /**
     * Equivalent to {@code Math.clamp(value, min, max)} (Java 21+).
     * Falls back to {@code Math.max(min, Math.min(max, value))} on Java 17.
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ── Player helpers ────────────────────────────────────────────────────────

    /**
     * Returns the player's current XP level safely across MC versions.
     */
    public static int getPlayerLevel(PlayerEntity player) {
        if (player == null) return 0;
        try {
            return player.experienceLevel;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ── Item helpers ──────────────────────────────────────────────────────────

    /**
     * Returns the display name of an ItemStack, falling back gracefully.
     */
    public static String getItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        try {
            return stack.getItem().getName().getString();
        } catch (Throwable t) {
            try {
                return stack.getItem().getTranslationKey();
            } catch (Throwable t2) {
                return stack.getItem().getClass().getSimpleName();
            }
        }
    }

    /**
     * Checks if an item name contains any of the given keywords (case-insensitive).
     */
    public static boolean itemNameContains(ItemStack stack, String... keywords) {
        String name = getItemName(stack).toLowerCase();
        for (String kw : keywords) {
            if (name.contains(kw.toLowerCase())) return true;
        }
        return false;
    }
}
