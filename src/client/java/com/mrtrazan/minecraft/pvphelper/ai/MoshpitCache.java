package com.mrtrazan.minecraft.pvphelper.ai;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * MoshpitCache — activated when the player is surrounded by 3+ enemies within 12 blocks.
 *
 * On moshpit detection it snapshots:
 *  - Player stats (health, armor, food, XP level)
 *  - Per-enemy threat data (name, distance, health, armor, threat score)
 *  - Weapon loadout (sword/axe/mace/bow/crystal/elytra)
 *  - Derived PvP "skill rating" from the GeminiPvPEngine hit-history ring buffer
 *
 * The snapshot is valid for CACHE_TTL_MS milliseconds.  Callers should always
 * check {@link #isMoshpit()} and optionally {@link #isValid()} before using data.
 */
public class MoshpitCache {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Minimum number of nearby enemies to trigger moshpit mode. */
    public static final int MOSHPIT_THRESHOLD = 3;

    /** Radius in blocks considered "moshpit range". */
    public static final double MOSHPIT_RADIUS = 12.0;

    /** How long (ms) the snapshot stays valid before a re-snap is required. */
    private static final long CACHE_TTL_MS = 5_000;

    // ── Singleton snapshot ────────────────────────────────────────────────────

    private static volatile MoshpitCache instance = null;
    private static long snapshotTime = 0;

    // ── Snapshot fields ───────────────────────────────────────────────────────

    public final float playerHealth;
    public final float playerMaxHealth;
    public final int   playerArmor;
    public final int   playerFood;
    public final int   playerLevel;

    public final boolean hasSword;
    public final boolean hasAxe;
    public final boolean hasMace;
    public final boolean hasBow;
    public final boolean hasCrossbow;
    public final boolean hasEndCrystal;
    public final boolean hasElytra;
    public final boolean hasTotem;
    public final String  bestWeapon;

    public final int     enemyCount;
    public final List<EnemyInfo> enemies;

    /** 0-100 skill rating based on damage dealt vs received from the hit-history buffer. */
    public final int skillRating;

    // ── Inner record ─────────────────────────────────────────────────────────

    public static class EnemyInfo {
        public final String name;
        public final double distance;
        public final float  health;
        public final int    armor;
        public final int    threatScore; // 0-100, higher = more dangerous

        EnemyInfo(String name, double distance, float health, int armor) {
            this.name     = name;
            this.distance = distance;
            this.health   = health;
            this.armor    = armor;
            // Threat score: high health + heavy armor + close = danger
            int score = 0;
            score += (int) (health / 20f * 40);  // up to 40 pts for health
            score += (int) (armor  / 20f * 30);  // up to 30 pts for armor
            score += (int) (Math.max(0, (MOSHPIT_RADIUS - distance) / MOSHPIT_RADIUS) * 30); // up to 30 pts for proximity
            this.threatScore = Math.min(100, score);
        }

        @Override
        public String toString() {
            return String.format("%s(dist=%.1f,hp=%.1f,arm=%d,threat=%d)",
                name, distance, health, armor, threatScore);
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    private MoshpitCache(MinecraftClient client, List<EnemyInfo> enemies) {
        this.enemies = enemies;
        this.enemyCount = enemies.size();

        // Player stats
        playerHealth    = client.player != null ? client.player.getHealth()    : 20f;
        playerMaxHealth = client.player != null ? client.player.getMaxHealth() : 20f;
        playerArmor     = client.player != null ? client.player.getArmor()     : 0;
        playerFood      = client.player != null ? client.player.getHungerManager().getFoodLevel() : 20;
        playerLevel     = client.player != null ? VersionCompat.getPlayerLevel(client.player) : 0;

        // Weapon scan
        boolean sw = false, ax = false, mc = false, bw = false, cb = false, ec = false, el = false, tot = false;
        String best = "FIST";

        if (client.player != null) {
            for (int i = 0; i < client.player.getInventory().size(); i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                String n = VersionCompat.getItemName(stack).toLowerCase();
                if (n.contains("sword"))       { sw  = true; if (best.equals("FIST") || best.equals("AXE")) best = "SWORD"; }
                if (n.contains("axe"))         { ax  = true; if (best.equals("FIST")) best = "AXE"; }
                if (n.contains("mace"))        { mc  = true; best = "MACE"; }
                if (n.contains("bow") && !n.contains("cross")) { bw = true; }
                if (n.contains("crossbow"))    { cb  = true; }
                if (n.contains("end_crystal") || n.contains("end crystal")) { ec = true; best = "CRYSTAL"; }
                if (n.contains("elytra"))      { el  = true; }
                if (n.contains("totem"))       { tot = true; }
            }
        }

        hasSword     = sw;
        hasAxe       = ax;
        hasMace      = mc;
        hasBow       = bw;
        hasCrossbow  = cb;
        hasEndCrystal = ec;
        hasElytra    = el;
        hasTotem     = tot;
        bestWeapon   = best;

        // Skill rating: derive from GeminiPvPEngine history
        // If we've been winning (avg damage received is low, hit count is low), rating goes up
        String history = GeminiPvPEngine.getCombatHistorySummary();
        int rating = 50; // baseline
        if (history.contains("No hits recorded")) {
            rating = 70; // untouched = winning
        } else {
            // Parse avg damage from summary
            try {
                int idx = history.indexOf("Avg damage per hit");
                if (idx >= 0) {
                    String sub = history.substring(idx);
                    String[] parts = sub.split("\\s+");
                    // "Avg damage per hit  : X.X HP"
                    for (int i = 0; i < parts.length; i++) {
                        if (parts[i].equals(":") && i + 1 < parts.length) {
                            float avgDmg = Float.parseFloat(parts[i + 1]);
                            // Low avg damage = we're tanking hits well → higher skill
                            rating = (int) Math.max(10, Math.min(90, 90 - avgDmg * 10));
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        // Penalise if very low health
        if (playerHealth < 6f) rating = Math.max(5, rating - 30);
        skillRating = rating;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if the current snapshot represents a moshpit situation.
     * (≥ MOSHPIT_THRESHOLD enemies within MOSHPIT_RADIUS blocks)
     */
    public static boolean isMoshpit() {
        MoshpitCache snap = instance;
        return snap != null && snap.enemyCount >= MOSHPIT_THRESHOLD && isValid();
    }

    public static boolean isValid() {
        return (System.currentTimeMillis() - snapshotTime) < CACHE_TTL_MS;
    }

    /**
     * Try to snapshot current game state.  Should be called each combat tick.
     * Returns true if a moshpit was detected (≥ threshold enemies nearby).
     */
    public static boolean trySnapshot(MinecraftClient client) {
        if (client.player == null || client.world == null) return false;

        List<EnemyInfo> nearby = new ArrayList<>();
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || p.isCreative() || p.isSpectator()) continue;
            double dist = client.player.distanceTo(p);
            if (dist <= MOSHPIT_RADIUS) {
                nearby.add(new EnemyInfo(
                    p.getName().getString(), dist, p.getHealth(), p.getArmor()
                ));
            }
        }

        if (nearby.size() >= MOSHPIT_THRESHOLD) {
            // Sort by threat score descending so the AI prioritises the most dangerous
            nearby.sort((a, b) -> b.threatScore - a.threatScore);
            instance = new MoshpitCache(client, nearby);
            snapshotTime = System.currentTimeMillis();
            return true;
        }

        // If we drop below threshold, invalidate cache so normal 1v1 logic resumes
        if (instance != null && nearby.size() < MOSHPIT_THRESHOLD) {
            instance = null;
        }
        return false;
    }

    /** Get the current snapshot (may be null or stale). */
    public static MoshpitCache get() {
        return instance;
    }

    /**
     * Produce a rich multi-line context string for injection into the AI prompt.
     * Includes all player stats, weapon loadout, skill rating, and per-enemy threat data.
     */
    public String getSnapshotContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MOSHPIT DETECTED — GROUP FIGHT MODE ===\n");
        sb.append(String.format("Enemy count in range : %d enemies within %.0f blocks\n", enemyCount, MOSHPIT_RADIUS));
        sb.append(String.format("Player Health        : %.1f/%.1f\n", playerHealth, playerMaxHealth));
        sb.append(String.format("Player Armor         : %d\n", playerArmor));
        sb.append(String.format("Player Food          : %d/20\n", playerFood));
        sb.append(String.format("Player XP Level      : %d\n", playerLevel));
        sb.append(String.format("PvP Skill Rating     : %d/100\n", skillRating));
        sb.append(String.format("Best Weapon          : %s\n", bestWeapon));
        sb.append(String.format("Has Totem            : %s\n", hasTotem));
        sb.append(String.format("Has Elytra           : %s\n", hasElytra));
        sb.append(String.format("Has End Crystal      : %s\n", hasEndCrystal));
        sb.append("Enemy Threat Table (sorted by danger):\n");
        for (int i = 0; i < enemies.size(); i++) {
            EnemyInfo e = enemies.get(i);
            sb.append(String.format("  #%d %s\n", i + 1, e));
        }
        sb.append("INSTRUCTION: You are in GROUP FIGHT (moshpit) mode. ");
        sb.append("Eliminate the highest-threat enemy first, then rotate to next. ");
        sb.append("Use best available weapon. If health < 6, USE_TOTEM immediately. ");
        sb.append("If End Crystals available and target is nearby, use CRYSTAL_ATTACK. ");
        sb.append("If Mace available and you can jump, use MACE_SLAM. ");
        sb.append("Output only the action keyword.\n");
        return sb.toString();
    }

    /** Returns the highest-threat enemy from the cache, or null. */
    public EnemyInfo getTopThreat() {
        return enemies.isEmpty() ? null : enemies.get(0);
    }

    /** Invalidate the cache manually (e.g. on world leave). */
    public static void invalidate() {
        instance = null;
        snapshotTime = 0;
    }
}
