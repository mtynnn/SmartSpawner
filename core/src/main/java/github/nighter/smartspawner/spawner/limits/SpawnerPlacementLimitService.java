package github.nighter.smartspawner.spawner.limits;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SpawnerPlacementLimitService {
    private static final UUID UNKNOWN_OWNER_UUID = new UUID(0L, 0L);

    private final SmartSpawner plugin;

    private final Map<ChunkKey, AtomicInteger> chunkCounts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> playerCounts = new ConcurrentHashMap<>();
    private final Map<String, UUID> spawnerOwners = new ConcurrentHashMap<>();

    private volatile boolean chunkLimitEnabled;
    private volatile int chunkLimitMax;
    private volatile boolean playerLimitEnabled;
    private volatile int playerLimitMax;
    private volatile String bypassPermission;
    private volatile LegacyMode legacyMode;
    private volatile boolean unknownOwnerBucket;

    public SpawnerPlacementLimitService(SmartSpawner plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        this.chunkLimitEnabled = plugin.getConfig().getBoolean("placement_limits.chunk_limit.enabled", false);
        this.chunkLimitMax = Math.max(1, plugin.getConfig().getInt("placement_limits.chunk_limit.max_per_chunk", 64));
        this.playerLimitEnabled = plugin.getConfig().getBoolean("placement_limits.player_limit.enabled", false);
        this.playerLimitMax = Math.max(1, plugin.getConfig().getInt("placement_limits.player_limit.max_per_player", 512));
        this.bypassPermission = plugin.getConfig().getString("placement_limits.permissions.bypass", "smartspawner.limit.bypass");
        this.unknownOwnerBucket = plugin.getConfig().getBoolean("placement_limits.legacy_handling.unknown_owner_bucket", false);
        this.legacyMode = LegacyMode.fromConfig(plugin.getConfig().getString("placement_limits.legacy_handling.mode", "GRANDFATHER"));
    }

    public void rebuildIndexesFromSpawnerManager() {
        chunkCounts.clear();
        playerCounts.clear();
        spawnerOwners.clear();

        if (legacyMode == LegacyMode.OFF) {
            return;
        }

        for (SpawnerData spawner : plugin.getSpawnerManager().getAllSpawners()) {
            if (spawner == null || spawner.getSpawnerLocation() == null) {
                continue;
            }

            String spawnerId = spawner.getSpawnerId();
            UUID owner = resolveOwnerUuid(spawner.getLastInteractedPlayer());
            if (spawnerId != null && owner != null) {
                spawnerOwners.put(spawnerId, owner);
            }

            incrementChunk(spawner.getSpawnerLocation());
            incrementPlayer(owner);
        }
    }

    public LimitCheckResult canPlace(Player player, Location location) {
        if (!isEnforcementActive()) {
            return LimitCheckResult.allow();
        }
        if (hasBypass(player)) {
            return LimitCheckResult.allow();
        }

        int currentChunkCount = getChunkCount(location);
        if (chunkLimitEnabled && currentChunkCount >= chunkLimitMax) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("max", String.valueOf(chunkLimitMax));
            placeholders.put("current", String.valueOf(currentChunkCount));
            placeholders.put("chunk", location.getChunk().getX() + "," + location.getChunk().getZ());
            return LimitCheckResult.deny("spawner_limit_chunk_reached", placeholders);
        }

        int currentPlayerCount = getPlayerCount(player.getUniqueId());
        if (playerLimitEnabled && currentPlayerCount >= playerLimitMax) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("max", String.valueOf(playerLimitMax));
            placeholders.put("current", String.valueOf(currentPlayerCount));
            placeholders.put("player", player.getName());
            return LimitCheckResult.deny("spawner_limit_player_reached", placeholders);
        }

        return LimitCheckResult.allow();
    }

    /**
     * V1 compatibility hook:
     * - PHYSICAL_BLOCKS mode does not increase counts on hand stacking.
     * - STRICT legacy mode may still block stack attempts when entity is already at/over limits.
     */
    public LimitCheckResult canIncreaseByPlayer(Player player, Location location) {
        if (!isEnforcementActive()) {
            return LimitCheckResult.allow();
        }
        if (hasBypass(player)) {
            return LimitCheckResult.allow();
        }
        if (legacyMode != LegacyMode.STRICT) {
            return LimitCheckResult.allow();
        }

        int currentChunkCount = getChunkCount(location);
        if (chunkLimitEnabled && currentChunkCount >= chunkLimitMax) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("max", String.valueOf(chunkLimitMax));
            placeholders.put("current", String.valueOf(currentChunkCount));
            placeholders.put("chunk", location.getChunk().getX() + "," + location.getChunk().getZ());
            return LimitCheckResult.deny("spawner_limit_legacy_exceeded", placeholders);
        }

        int currentPlayerCount = getPlayerCount(player.getUniqueId());
        if (playerLimitEnabled && currentPlayerCount >= playerLimitMax) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("max", String.valueOf(playerLimitMax));
            placeholders.put("current", String.valueOf(currentPlayerCount));
            placeholders.put("player", player.getName());
            return LimitCheckResult.deny("spawner_limit_legacy_exceeded", placeholders);
        }

        return LimitCheckResult.allow();
    }

    public void onSpawnerCreated(String spawnerId, Location location, String ownerName) {
        if (location == null || spawnerId == null) {
            return;
        }

        UUID owner = resolveOwnerUuid(ownerName);
        spawnerOwners.put(spawnerId, owner);
        incrementChunk(location);
        incrementPlayer(owner);
    }

    public void onSpawnerRemoved(String spawnerId, Location location) {
        if (location == null || spawnerId == null) {
            return;
        }

        decrementChunk(location);
        UUID owner = spawnerOwners.remove(spawnerId);
        decrementPlayer(owner);
    }

    public void onOwnerChangedIfNeeded(String spawnerId, String oldOwnerName, String newOwnerName) {
        if (spawnerId == null || legacyMode == LegacyMode.OFF) {
            return;
        }

        UUID oldOwner = resolveOwnerUuid(oldOwnerName);
        UUID newOwner = resolveOwnerUuid(newOwnerName);

        if (oldOwner == null && newOwner == null) {
            return;
        }
        if (oldOwner != null && oldOwner.equals(newOwner)) {
            return;
        }

        decrementPlayer(oldOwner);
        incrementPlayer(newOwner);
        spawnerOwners.put(spawnerId, newOwner);
    }

    private boolean isEnforcementActive() {
        return chunkLimitEnabled || playerLimitEnabled;
    }

    private boolean hasBypass(Player player) {
        return bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission);
    }

    private int getChunkCount(Location location) {
        AtomicInteger count = chunkCounts.get(ChunkKey.fromLocation(location));
        return count != null ? count.get() : 0;
    }

    private int getPlayerCount(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        AtomicInteger count = playerCounts.get(playerId);
        return count != null ? count.get() : 0;
    }

    private void incrementChunk(Location location) {
        chunkCounts.computeIfAbsent(ChunkKey.fromLocation(location), ignored -> new AtomicInteger()).incrementAndGet();
    }

    private void decrementChunk(Location location) {
        chunkCounts.computeIfPresent(ChunkKey.fromLocation(location), (ignored, count) -> {
            if (count.decrementAndGet() <= 0) {
                return null;
            }
            return count;
        });
    }

    private void incrementPlayer(UUID owner) {
        if (owner == null) {
            return;
        }
        playerCounts.computeIfAbsent(owner, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private void decrementPlayer(UUID owner) {
        if (owner == null) {
            return;
        }
        playerCounts.computeIfPresent(owner, (ignored, count) -> {
            if (count.decrementAndGet() <= 0) {
                return null;
            }
            return count;
        });
    }

    private UUID resolveOwnerUuid(String ownerName) {
        if (ownerName == null || ownerName.isBlank()) {
            return unknownOwnerBucket ? UNKNOWN_OWNER_UUID : null;
        }

        try {
            Player online = Bukkit.getPlayerExact(ownerName);
            if (online != null) {
                return online.getUniqueId();
            }

            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(ownerName);
            if (cached != null && cached.getUniqueId() != null) {
                return cached.getUniqueId();
            }
        } catch (Throwable ignored) {
            // Keep this lookup best-effort for compatibility.
        }

        return unknownOwnerBucket ? UNKNOWN_OWNER_UUID : null;
    }

    private record ChunkKey(String worldName, int chunkX, int chunkZ) {
        private static ChunkKey fromLocation(Location location) {
            return new ChunkKey(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    private enum LegacyMode {
        GRANDFATHER,
        STRICT,
        OFF;

        private static LegacyMode fromConfig(String mode) {
            if (mode == null || mode.isBlank()) {
                return GRANDFATHER;
            }
            try {
                return LegacyMode.valueOf(mode.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return GRANDFATHER;
            }
        }
    }

    public static final class LimitCheckResult {
        private final boolean allowed;
        private final String messageKey;
        private final Map<String, String> placeholders;

        private LimitCheckResult(boolean allowed, String messageKey, Map<String, String> placeholders) {
            this.allowed = allowed;
            this.messageKey = messageKey;
            this.placeholders = placeholders;
        }

        public static LimitCheckResult allow() {
            return new LimitCheckResult(true, null, Map.of());
        }

        public static LimitCheckResult deny(String messageKey, Map<String, String> placeholders) {
            return new LimitCheckResult(false, messageKey, placeholders != null ? placeholders : Map.of());
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getMessageKey() {
            return messageKey;
        }

        public Map<String, String> getPlaceholders() {
            return placeholders;
        }
    }
}

