package com.eu.habbo.habbohotel.wired.variables;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredVariable;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.wired.WiredVariablePersistence;
import com.eu.habbo.habbohotel.wired.creator.WiredCreatorToolsLogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Centralized, lazy write-behind store for permanent wired variable values.
 *
 * <p>The first access to a value reads it from the database. From then on the
 * cached value is authoritative inside this emulator process. Mutations advance
 * an in-memory revision and are periodically coalesced to the latest value.
 * Failed flushes remain dirty and are retried.</p>
 */
public final class WiredVariableStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(WiredVariableStore.class);
    private static final Object[] ROOM_WRITE_LOCKS = new Object[256];
    private static final ConcurrentHashMap<ValueKey, CachedValue> VALUES = new ConcurrentHashMap<>();
    private static final AtomicBoolean FLUSH_WORKER_STARTED = new AtomicBoolean(false);

    private static final long DEFAULT_FLUSH_INTERVAL_MS = 2000L;
    private static final long DEFAULT_RETRY_MAX_MS = 30000L;
    private static final int DEFAULT_FLUSH_BATCH_SIZE = 500;

    private static volatile ScheduledExecutorService flushExecutor;
    private static volatile boolean shuttingDown;
    private static volatile long flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
    private static volatile long retryMaxMs = DEFAULT_RETRY_MAX_MS;
    private static volatile int flushBatchSize = DEFAULT_FLUSH_BATCH_SIZE;

    static {
        for (int i = 0; i < ROOM_WRITE_LOCKS.length; i++) {
            ROOM_WRITE_LOCKS[i] = new Object();
        }
    }

    public static final int OWNER_ROOM = 0;
    public static final int OWNER_USER = 1;
    public static final int OWNER_ITEM = 2;

    private WiredVariableStore() {
    }

    public static long loadValue(InteractionWiredVariable variable) {
        return loadValue(variable, OWNER_ROOM, 0);
    }

    public static long loadValue(InteractionWiredVariable variable, int ownerType, int ownerId) {
        StoredValue storedValue = loadStoredValue(variable, ownerType, ownerId);
        return storedValue.exists ? storedValue.value : 0L;
    }

    public static StoredValue loadStoredValue(InteractionWiredVariable variable) {
        return loadStoredValue(variable, OWNER_ROOM, 0);
    }

    public static StoredValue loadStoredValue(InteractionWiredVariable variable, int ownerType, int ownerId) {
        if (variable == null || !variable.getPersistence().isPermanent() || variable.getVariableName().isEmpty()
                || !canPersistOwner(ownerType, ownerId)) {
            return StoredValue.empty();
        }

        return loadStoredValue(definitionOf(variable), ownerType, ownerId);
    }

    public static StoredValue loadStoredValue(int itemId, int ownerType, int ownerId) {
        if (itemId <= 0 || !canPersistOwner(ownerType, ownerId)) {
            return StoredValue.empty();
        }

        CachedValue cached = getOrLoad(new ValueKey(itemId, ownerType, ownerId), null);
        return cached == null ? StoredValue.empty() : cached.snapshotValue();
    }

    public static StoredValue loadStoredValue(VariableDefinition variable, int ownerType, int ownerId) {
        if (variable == null || variable.itemId <= 0 || !canPersistOwner(ownerType, ownerId)) {
            return StoredValue.empty();
        }

        synchronized (roomWriteLock(variable.roomId)) {
            CachedValue cached = getOrLoad(new ValueKey(variable.itemId, ownerType, ownerId), variable);
            return cached == null ? StoredValue.empty() : cached.snapshotValue();
        }
    }

    public static WiredVariableMutationReceipt setSharedValue(VariableDefinition variable, int ownerType, int ownerId, long value) {
        if (variable == null || !variable.persistence.isPermanent() || variable.variableName.isEmpty()
                || !canPersistOwner(ownerType, ownerId)) {
            return WiredVariableMutationReceipt.rejected(0L, value, 0L);
        }

        synchronized (roomWriteLock(variable.roomId)) {
            CachedValue cached = getOrLoad(new ValueKey(variable.itemId, ownerType, ownerId), variable);
            if (cached == null) {
                return WiredVariableMutationReceipt.persistenceFailed(0L, value, 0L);
            }

            StoredValue oldValue = cached.snapshotValue();
            if (oldValue.exists && oldValue.value == value) {
                return WiredVariableMutationReceipt.unchanged(value, oldValue.revision);
            }

            if (!oldValue.exists && isAnyVariableLimitReached(variable.itemId, variable.roomId, ownerType, ownerId)) {
                logTooManyVariables(variable.roomId);
                return WiredVariableMutationReceipt.capRejected(oldValue.value, value, oldValue.revision);
            }

            long now = System.currentTimeMillis();
            long revision = cached.store(variable, value, now, now);
            ensureFlushWorker();
            return WiredVariableMutationReceipt.committed(oldValue.exists, oldValue.value, value, revision);
        }
    }

    public static RemovalResult removeSharedValue(VariableDefinition variable, int ownerType, int ownerId) {
        if (variable == null || !variable.persistence.isPermanent() || !canPersistOwner(ownerType, ownerId)) {
            return RemovalResult.failed();
        }

        synchronized (roomWriteLock(variable.roomId)) {
            CachedValue cached = getOrLoad(new ValueKey(variable.itemId, ownerType, ownerId), variable);
            if (cached == null) {
                return RemovalResult.failed();
            }

            StoredValue storedValue = cached.snapshotValue();
            if (!storedValue.exists) {
                return RemovalResult.notFound();
            }

            cached.remove(variable);
            ensureFlushWorker();
            return RemovalResult.removed(storedValue);
        }
    }

    public static boolean hasValue(InteractionWiredVariable variable, int ownerType, int ownerId) {
        return loadStoredValue(variable, ownerType, ownerId).exists;
    }

    public static SaveResult saveValue(InteractionWiredVariable variable) {
        return saveValue(variable, OWNER_ROOM, 0, variable == null ? 0L : variable.getValue());
    }

    public static SaveResult saveValue(InteractionWiredVariable variable, int ownerType, int ownerId, long value) {
        boolean existed = variable != null && hasValue(variable, ownerType, ownerId);
        long currentRevision = variable == null ? 0L : variable.getRevision(ownerId);
        return saveValue(variable, ownerType, ownerId, value, existed, currentRevision);
    }

    public static SaveResult saveValue(InteractionWiredVariable variable, int ownerType, int ownerId, long value,
                                       boolean existed, long currentRevision) {
        if (variable == null) {
            return SaveResult.failed();
        }

        // Runtime-spawned furniture uses negative IDs. Its variable values are valid
        // for the lifetime of the room item, but must never enter the write-behind
        // cache or MariaDB where owner IDs refer to persistent entities.
        if (ownerType == OWNER_ITEM && ownerId < 0) {
            return SaveResult.inMemory(currentRevision);
        }

        if (!canPersistOwner(ownerType, ownerId)) {
            return SaveResult.failed();
        }

        if (!variable.getPersistence().isPermanent() || variable.getVariableName().isEmpty()) {
            if (ownerType == OWNER_ROOM && ownerId == 0) {
                deleteValues(variable);
            }
            return SaveResult.committed(currentRevision);
        }

        VariableDefinition definition = definitionOf(variable);
        synchronized (roomWriteLock(definition.roomId)) {
            CachedValue cached = getOrLoad(new ValueKey(definition.itemId, ownerType, ownerId), definition);
            if (cached == null) {
                return SaveResult.failed();
            }

            StoredValue current = cached.snapshotValue();
            if (current.exists && current.value == value) {
                return SaveResult.committed(current.revision);
            }

            if (!current.exists && isAnyVariableLimitReached(definition.itemId, definition.roomId, ownerType, ownerId)) {
                logTooManyVariables(variable);
                return SaveResult.capRejected();
            }

            long now = System.currentTimeMillis();
            long createdAt = variable.getCreatedAtMs(ownerId) > 0L ? variable.getCreatedAtMs(ownerId) : now;
            long updatedAt = variable.getUpdatedAtMs(ownerId) > 0L ? variable.getUpdatedAtMs(ownerId) : now;
            long revision = cached.store(definition, value, createdAt, updatedAt);
            ensureFlushWorker();
            return SaveResult.committed(revision);
        }
    }

    /**
     * Flushes dirty values belonging to a room. Dirty entries are retained if
     * the database is unavailable and the periodic worker will retry them.
     */
    public static void flushRoom(int roomId) {
        if (roomId > 0) {
            flushMatching(roomId, -1, -1, true);
            evictCleanMatching(roomId, -1, -1);
        }
    }

    /**
     * Flushes all cached values for a scoped owner, used for user logout.
     */
    public static void flushOwner(int ownerType, int ownerId) {
        if (ownerId > 0) {
            flushMatching(-1, ownerType, ownerId, true);
            evictCleanMatching(-1, ownerType, ownerId);
        }
    }

    public static void flushAll() {
        flushMatching(-1, -1, -1, true);
    }

    /**
     * Stops the background worker and performs a final synchronous flush while
     * the database pool is still available.
     */
    public static void shutdown() {
        shuttingDown = true;

        ScheduledExecutorService executor = flushExecutor;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        flushAll();
        long dirtyValues = VALUES.values().stream().filter(CachedValue::isDirty).count();
        if (dirtyValues > 0L) {
            LOGGER.warn("{} wired variable value(s) remain dirty after shutdown flush", dirtyValues);
        }
    }

    private static CachedValue getOrLoad(ValueKey key, VariableDefinition definition) {
        CachedValue cached = VALUES.get(key);
        if (cached != null) {
            cached.updateDefinition(definition);
            return cached;
        }

        StoredValue storedValue = loadStoredValueFromDatabase(key);
        if (storedValue == null) {
            return null;
        }

        CachedValue loaded = new CachedValue(key, definition, storedValue);
        CachedValue existing = VALUES.putIfAbsent(key, loaded);
        cached = existing == null ? loaded : existing;
        cached.updateDefinition(definition);
        return cached;
    }

    /**
     * Returns null on a database failure so callers do not cache an unknown
     * value as absent.
     */
    private static StoredValue loadStoredValueFromDatabase(ValueKey key) {
        String query = "SELECT value, created_at, updated_at, revision FROM wired_variables " +
                "WHERE item_id = ? AND owner_type = ? AND owner_id = ? LIMIT 1";

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, key.itemId);
            statement.setInt(2, key.ownerType);
            statement.setInt(3, key.ownerId);

            try (ResultSet set = statement.executeQuery()) {
                if (set.next()) {
                    return new StoredValue(
                            true,
                            set.getLong("value"),
                            set.getLong("created_at"),
                            set.getLong("updated_at"),
                            set.getLong("revision"));
                }
            }
            return StoredValue.empty();
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception while loading wired variable value", e);
            return null;
        }
    }

    private static void ensureFlushWorker() {
        if (shuttingDown || !FLUSH_WORKER_STARTED.compareAndSet(false, true)) {
            return;
        }

        flushIntervalMs = boundedLong(
                Emulator.getConfig().getInt("hotel.room.variable.flush.interval.ms", (int) DEFAULT_FLUSH_INTERVAL_MS),
                250L,
                60000L);
        retryMaxMs = boundedLong(
                Emulator.getConfig().getInt("hotel.room.variable.flush.retry.max.ms", (int) DEFAULT_RETRY_MAX_MS),
                flushIntervalMs,
                300000L);
        flushBatchSize = (int) boundedLong(
                Emulator.getConfig().getInt("hotel.room.variable.flush.batch.size", DEFAULT_FLUSH_BATCH_SIZE),
                1L,
                5000L);

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "wired-variable-flusher");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
        flushExecutor = executor;
        executor.scheduleWithFixedDelay(
                WiredVariableStore::flushDueSafely,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    private static void flushDueSafely() {
        try {
            flushMatching(-1, -1, -1, false);
        } catch (Throwable throwable) {
            LOGGER.error("Caught exception while flushing wired variable values", throwable);
        }
    }

    private static void flushMatching(int roomId, int ownerType, int ownerId, boolean force) {
        long now = System.currentTimeMillis();
        Map<Integer, List<CachedValue>> valuesByLock = new HashMap<>();

        for (CachedValue cached : VALUES.values()) {
            int cachedRoomId = cached.roomId();
            if (cachedRoomId <= 0 || !matches(cached, roomId, ownerType, ownerId)) {
                continue;
            }

            int lockIndex = roomWriteLockIndex(cachedRoomId);
            valuesByLock.computeIfAbsent(lockIndex, ignored -> new ArrayList<>()).add(cached);
        }

        for (Map.Entry<Integer, List<CachedValue>> entry : valuesByLock.entrySet()) {
            synchronized (ROOM_WRITE_LOCKS[entry.getKey()]) {
                List<FlushSnapshot> batch = new ArrayList<>(flushBatchSize);
                for (CachedValue cached : entry.getValue()) {
                    if (!matches(cached, roomId, ownerType, ownerId)) {
                        continue;
                    }

                    FlushSnapshot snapshot = cached.snapshotForFlush(now, force);
                    if (snapshot == null) {
                        continue;
                    }

                    batch.add(snapshot);
                    if (batch.size() >= flushBatchSize) {
                        flushBatch(batch);
                        batch.clear();
                    }
                }

                if (!batch.isEmpty()) {
                    flushBatch(batch);
                }
            }
        }
    }

    private static boolean matches(CachedValue cached, int roomId, int ownerType, int ownerId) {
        if (roomId > 0 && cached.roomId() != roomId) {
            return false;
        }
        if (ownerType >= 0 && cached.key.ownerType != ownerType) {
            return false;
        }
        return ownerId < 0 || cached.key.ownerId == ownerId;
    }

    private static void evictCleanMatching(int roomId, int ownerType, int ownerId) {
        Map<Integer, List<CachedValue>> valuesByLock = new HashMap<>();
        for (CachedValue cached : VALUES.values()) {
            int cachedRoomId = cached.roomId();
            if (cachedRoomId <= 0 || !matches(cached, roomId, ownerType, ownerId)) {
                continue;
            }

            int lockIndex = roomWriteLockIndex(cachedRoomId);
            valuesByLock.computeIfAbsent(lockIndex, ignored -> new ArrayList<>()).add(cached);
        }

        for (Map.Entry<Integer, List<CachedValue>> entry : valuesByLock.entrySet()) {
            synchronized (ROOM_WRITE_LOCKS[entry.getKey()]) {
                for (CachedValue cached : entry.getValue()) {
                    if (matches(cached, roomId, ownerType, ownerId) && !cached.isDirty()) {
                        VALUES.remove(cached.key, cached);
                    }
                }
            }
        }
    }

    private static void flushBatch(List<FlushSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }

        boolean succeeded = persistSnapshots(snapshots);
        long now = System.currentTimeMillis();
        for (FlushSnapshot snapshot : snapshots) {
            if (succeeded) {
                snapshot.cached.markPersisted(snapshot);
            } else {
                snapshot.cached.markFlushFailed(snapshot, now);
            }
        }
    }

    private static boolean persistSnapshots(List<FlushSnapshot> snapshots) {
        String upsertQuery = "INSERT INTO wired_variables " +
                "(item_id, room_id, variable_type, variable_name, persistence, owner_type, owner_id, value, created_at, updated_at, revision) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE room_id = VALUES(room_id), variable_type = VALUES(variable_type), " +
                "variable_name = VALUES(variable_name), persistence = VALUES(persistence), value = VALUES(value), " +
                "created_at = IF(created_at > 0, created_at, VALUES(created_at)), updated_at = VALUES(updated_at), revision = VALUES(revision)";
        String deleteQuery = "DELETE FROM wired_variables WHERE item_id = ? AND owner_type = ? AND owner_id = ?";

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement upsert = connection.prepareStatement(upsertQuery);
             PreparedStatement delete = connection.prepareStatement(deleteQuery)) {
            connection.setAutoCommit(false);
            boolean hasUpserts = false;
            boolean hasDeletes = false;

            try {
                for (FlushSnapshot snapshot : snapshots) {
                    if (!canPersistOwner(snapshot.cached.key.ownerType, snapshot.cached.key.ownerId)) {
                        // Final persistence-boundary guard. Even if a future caller
                        // accidentally places an ephemeral owner in the cache, it
                        // can never be serialized to MariaDB.
                        continue;
                    }

                    if (snapshot.exists) {
                        upsert.setInt(1, snapshot.definition.itemId);
                        upsert.setInt(2, snapshot.definition.roomId);
                        upsert.setInt(3, snapshot.definition.variableType);
                        upsert.setString(4, snapshot.definition.variableName);
                        upsert.setInt(5, snapshot.definition.persistence.code);
                        upsert.setInt(6, snapshot.cached.key.ownerType);
                        upsert.setInt(7, snapshot.cached.key.ownerId);
                        upsert.setLong(8, snapshot.value);
                        upsert.setLong(9, snapshot.createdAtMs);
                        upsert.setLong(10, snapshot.updatedAtMs);
                        upsert.setLong(11, snapshot.revision);
                        upsert.addBatch();
                        hasUpserts = true;
                    } else {
                        delete.setInt(1, snapshot.cached.key.itemId);
                        delete.setInt(2, snapshot.cached.key.ownerType);
                        delete.setInt(3, snapshot.cached.key.ownerId);
                        delete.addBatch();
                        hasDeletes = true;
                    }
                }

                if (hasUpserts) {
                    upsert.executeBatch();
                }
                if (hasDeletes) {
                    delete.executeBatch();
                }
                connection.commit();
                return true;
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to flush {} wired variable value(s); they remain dirty", snapshots.size(), e);
            return false;
        }
    }

    private static boolean isAnyVariableLimitReached(int itemId, int roomId, int ownerType, int ownerId) {
        return isOwnerVariableLimitReached(roomId, ownerType, ownerId)
                || isDefinitionVariableLimitReached(itemId)
                || isRoomVariableLimitReached(roomId);
    }

    private static boolean isOwnerVariableLimitReached(int roomId, int ownerType, int ownerId) {
        int limit = getVariableLimit(ownerType);
        if (limit < 0) {
            return false;
        }

        int stored = countValues(
                "SELECT COUNT(*) FROM wired_variables WHERE room_id = ? AND owner_type = ? AND owner_id = ?",
                roomId,
                ownerType,
                ownerId);
        int pending = countPendingCreations(roomId, -1, ownerType, ownerId);
        return (long) stored + pending >= limit;
    }

    private static boolean isDefinitionVariableLimitReached(int itemId) {
        int limit = Emulator.getConfig().getInt("hotel.room.variable.definition.max", 10000);
        if (limit < 0) {
            return false;
        }

        int stored = countValues("SELECT COUNT(*) FROM wired_variables WHERE item_id = ?", itemId);
        int pending = countPendingCreations(-1, itemId, -1, -1);
        return (long) stored + pending >= limit;
    }

    private static boolean isRoomVariableLimitReached(int roomId) {
        int limit = Emulator.getConfig().getInt("hotel.room.variable.total.max", 50000);
        if (limit < 0) {
            return false;
        }

        int stored = countValues("SELECT COUNT(*) FROM wired_variables WHERE room_id = ?", roomId);
        int pending = countPendingCreations(roomId, -1, -1, -1);
        return (long) stored + pending >= limit;
    }

    private static int countPendingCreations(int roomId, int itemId, int ownerType, int ownerId) {
        int count = 0;
        for (CachedValue cached : VALUES.values()) {
            if (cached.isPendingCreation(roomId, itemId, ownerType, ownerId)) {
                count++;
            }
        }
        return count;
    }

    private static int countValues(String query, int... values) {
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < values.length; i++) {
                statement.setInt(i + 1, values[i]);
            }
            try (ResultSet set = statement.executeQuery()) {
                return set.next() ? set.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception while checking wired variable limits", e);
            return Integer.MAX_VALUE;
        }
    }

    private static Object roomWriteLock(int roomId) {
        return ROOM_WRITE_LOCKS[roomWriteLockIndex(roomId)];
    }

    private static int roomWriteLockIndex(int roomId) {
        return Math.floorMod(roomId, ROOM_WRITE_LOCKS.length);
    }

    private static int getVariableLimit(int ownerType) {
        switch (ownerType) {
            case OWNER_ITEM:
                return Emulator.getConfig().getInt("hotel.room.furni.variable.max", 100);
            case OWNER_USER:
                return Emulator.getConfig().getInt("hotel.room.user.variable.max", 100);
            case OWNER_ROOM:
            default:
                return Emulator.getConfig().getInt("hotel.room.global.variable.max", 100);
        }
    }

    static boolean canPersistOwner(int ownerType, int ownerId) {
        switch (ownerType) {
            case OWNER_ROOM:
                return ownerId == 0;
            case OWNER_USER:
            case OWNER_ITEM:
                return ownerId > 0;
            default:
                return false;
        }
    }

    private static void logTooManyVariables(InteractionWiredVariable variable) {
        logTooManyVariables(variable.getRoomId());
    }

    private static void logTooManyVariables(int roomId) {
        Room room = Emulator.getGameEnvironment().getRoomManager().getRoom(roomId);
        WiredCreatorToolsLogManager.addSystemLog(room, "ERROR", "Wired Error: TOO_MANY_VARIABLES");
    }

    private static long nextRevision(long revision) {
        return revision == Long.MAX_VALUE ? 1L : Math.max(1L, revision + 1L);
    }

    private static long boundedLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long retryDelay(int failureCount) {
        int shift = Math.min(10, Math.max(0, failureCount - 1));
        long delay = flushIntervalMs * (1L << shift);
        return Math.min(retryMaxMs, Math.max(flushIntervalMs, delay));
    }

    private static VariableDefinition definitionOf(InteractionWiredVariable variable) {
        return new VariableDefinition(
                variable.getId(),
                variable.getRoomId(),
                variable.getType().code,
                variable.getVariableName(),
                variable.getPersistence());
    }

    public static void updateVariableName(InteractionWiredVariable variable) {
        if (variable == null || variable.getVariableName().isEmpty()) {
            return;
        }

        VariableDefinition definition = definitionOf(variable);
        synchronized (roomWriteLock(definition.roomId)) {
            for (CachedValue cached : VALUES.values()) {
                if (cached.key.itemId == definition.itemId) {
                    cached.updateDefinition(definition);
                }
            }

            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE wired_variables SET variable_name = ?, variable_type = ?, persistence = ?, room_id = ? WHERE item_id = ?")) {
                statement.setString(1, definition.variableName);
                statement.setInt(2, definition.variableType);
                statement.setInt(3, definition.persistence.code);
                statement.setInt(4, definition.roomId);
                statement.setInt(5, definition.itemId);
                statement.execute();
            } catch (SQLException e) {
                LOGGER.error("Caught SQL exception", e);
            }
        }
    }

    public static void deleteValues(InteractionWiredVariable variable) {
        if (variable == null) {
            return;
        }

        synchronized (roomWriteLock(variable.getRoomId())) {
            VALUES.keySet().removeIf(key -> key.itemId == variable.getId());

            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM wired_variables WHERE item_id = ?")) {
                statement.setInt(1, variable.getId());
                statement.execute();
            } catch (SQLException e) {
                LOGGER.error("Caught SQL exception", e);
            }
        }
    }

    public static boolean deleteValue(InteractionWiredVariable variable, int ownerType, int ownerId) {
        if (ownerType == OWNER_ITEM && ownerId < 0) {
            return true;
        }

        if (variable == null || !variable.getPersistence().isPermanent() || !canPersistOwner(ownerType, ownerId)) {
            return false;
        }

        VariableDefinition definition = definitionOf(variable);
        synchronized (roomWriteLock(definition.roomId)) {
            CachedValue cached = getOrLoad(new ValueKey(definition.itemId, ownerType, ownerId), definition);
            if (cached == null) {
                return false;
            }

            if (cached.snapshotValue().exists) {
                cached.remove(definition);
                ensureFlushWorker();
            }
            return true;
        }
    }

    public static class StoredValue {
        public final boolean exists;
        public final long value;
        public final long createdAtMs;
        public final long updatedAtMs;
        public final long revision;

        public StoredValue(boolean exists, long value, long createdAtMs, long updatedAtMs, long revision) {
            this.exists = exists;
            this.value = value;
            this.createdAtMs = createdAtMs;
            this.updatedAtMs = updatedAtMs;
            this.revision = revision;
        }

        static StoredValue empty() {
            return new StoredValue(false, 0L, 0L, 0L, 0L);
        }
    }

    public static final class VariableDefinition {
        public final int itemId;
        public final int roomId;
        public final int variableType;
        public final String variableName;
        public final WiredVariablePersistence persistence;

        public VariableDefinition(int itemId, int roomId, int variableType, String variableName,
                                  WiredVariablePersistence persistence) {
            this.itemId = itemId;
            this.roomId = roomId;
            this.variableType = variableType;
            this.variableName = variableName == null ? "" : variableName;
            this.persistence = persistence == null
                    ? WiredVariablePersistence.ROOM_ACTIVE
                    : persistence;
        }
    }

    public static final class RemovalResult {
        public final boolean succeeded;
        public final boolean removed;
        public final StoredValue storedValue;

        private RemovalResult(boolean succeeded, boolean removed, StoredValue storedValue) {
            this.succeeded = succeeded;
            this.removed = removed;
            this.storedValue = storedValue;
        }

        static RemovalResult removed(StoredValue storedValue) {
            return new RemovalResult(true, true, storedValue);
        }

        static RemovalResult notFound() {
            return new RemovalResult(true, false, StoredValue.empty());
        }

        static RemovalResult failed() {
            return new RemovalResult(false, false, StoredValue.empty());
        }
    }

    public static final class SaveResult {
        public enum Status {
            COMMITTED,
            CAP_REJECTED,
            FAILED
        }

        public final Status status;
        public final long revision;

        private SaveResult(Status status, long revision) {
            this.status = status;
            this.revision = revision;
        }

        public boolean committed() {
            return this.status == Status.COMMITTED;
        }

        static SaveResult committed(long revision) {
            return new SaveResult(Status.COMMITTED, revision);
        }

        public static SaveResult inMemory(long revision) {
            return committed(revision);
        }

        static SaveResult capRejected() {
            return new SaveResult(Status.CAP_REJECTED, 0L);
        }

        static SaveResult failed() {
            return new SaveResult(Status.FAILED, 0L);
        }
    }

    private static final class ValueKey {
        final int itemId;
        final int ownerType;
        final int ownerId;

        ValueKey(int itemId, int ownerType, int ownerId) {
            this.itemId = itemId;
            this.ownerType = ownerType;
            this.ownerId = ownerId;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof ValueKey)) return false;
            ValueKey key = (ValueKey) object;
            return this.itemId == key.itemId &&
                    this.ownerType == key.ownerType &&
                    this.ownerId == key.ownerId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.itemId, this.ownerType, this.ownerId);
        }
    }

    private static final class CachedValue {
        final ValueKey key;
        private VariableDefinition definition;
        private boolean exists;
        private boolean persistedExists;
        private long value;
        private long createdAtMs;
        private long updatedAtMs;
        private long revision;
        private long generation;
        private boolean dirty;
        private int failureCount;
        private long nextAttemptAtMs;

        CachedValue(ValueKey key, VariableDefinition definition, StoredValue storedValue) {
            this.key = key;
            this.definition = definition;
            this.exists = storedValue.exists;
            this.persistedExists = storedValue.exists;
            this.value = storedValue.value;
            this.createdAtMs = storedValue.createdAtMs;
            this.updatedAtMs = storedValue.updatedAtMs;
            this.revision = storedValue.revision;
        }

        synchronized void updateDefinition(VariableDefinition definition) {
            if (definition != null) {
                this.definition = definition;
            }
        }

        synchronized StoredValue snapshotValue() {
            return new StoredValue(
                    this.exists,
                    this.exists ? this.value : 0L,
                    this.exists ? this.createdAtMs : 0L,
                    this.exists ? this.updatedAtMs : 0L,
                    this.revision);
        }

        synchronized long store(VariableDefinition definition, long value, long createdAtMs, long updatedAtMs) {
            long now = System.currentTimeMillis();
            this.definition = definition;
            this.createdAtMs = this.exists && this.createdAtMs > 0L
                    ? this.createdAtMs
                    : (createdAtMs > 0L ? createdAtMs : now);
            this.updatedAtMs = updatedAtMs > 0L ? updatedAtMs : now;
            this.exists = true;
            this.value = value;
            this.revision = nextRevision(this.revision);
            this.generation++;
            this.dirty = true;
            return this.revision;
        }

        synchronized void remove(VariableDefinition definition) {
            this.definition = definition;
            this.exists = false;
            this.value = 0L;
            this.createdAtMs = 0L;
            this.updatedAtMs = System.currentTimeMillis();
            this.revision = nextRevision(this.revision);
            this.generation++;
            this.dirty = true;
        }

        synchronized int roomId() {
            return this.definition == null ? 0 : this.definition.roomId;
        }

        synchronized boolean isDirty() {
            return this.dirty;
        }

        synchronized boolean isPendingCreation(int roomId, int itemId, int ownerType, int ownerId) {
            if (!this.exists || this.persistedExists || this.definition == null) {
                return false;
            }
            if (roomId > 0 && this.definition.roomId != roomId) {
                return false;
            }
            if (itemId > 0 && this.definition.itemId != itemId) {
                return false;
            }
            if (ownerType >= 0 && this.key.ownerType != ownerType) {
                return false;
            }
            return ownerId < 0 || this.key.ownerId == ownerId;
        }

        synchronized FlushSnapshot snapshotForFlush(long now, boolean force) {
            if (!this.dirty || this.definition == null || (!force && now < this.nextAttemptAtMs)) {
                return null;
            }

            return new FlushSnapshot(
                    this,
                    this.definition,
                    this.exists,
                    this.value,
                    this.createdAtMs,
                    this.updatedAtMs,
                    this.revision,
                    this.generation);
        }

        synchronized void markPersisted(FlushSnapshot snapshot) {
            if (this.generation != snapshot.generation) {
                return;
            }

            this.persistedExists = snapshot.exists;
            this.dirty = false;
            this.failureCount = 0;
            this.nextAttemptAtMs = 0L;
        }

        synchronized void markFlushFailed(FlushSnapshot snapshot, long now) {
            if (this.generation != snapshot.generation || !this.dirty) {
                return;
            }

            this.failureCount = Math.min(30, this.failureCount + 1);
            this.nextAttemptAtMs = now + retryDelay(this.failureCount);
        }
    }

    private static final class FlushSnapshot {
        final CachedValue cached;
        final VariableDefinition definition;
        final boolean exists;
        final long value;
        final long createdAtMs;
        final long updatedAtMs;
        final long revision;
        final long generation;

        FlushSnapshot(CachedValue cached, VariableDefinition definition, boolean exists, long value,
                      long createdAtMs, long updatedAtMs, long revision, long generation) {
            this.cached = cached;
            this.definition = definition;
            this.exists = exists;
            this.value = value;
            this.createdAtMs = createdAtMs;
            this.updatedAtMs = updatedAtMs;
            this.revision = revision;
            this.generation = generation;
        }
    }
}
