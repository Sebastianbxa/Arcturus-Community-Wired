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

public final class WiredVariableStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(WiredVariableStore.class);
    private static final Object[] ROOM_WRITE_LOCKS = new Object[256];

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
        if (variable == null || !variable.getPersistence().isPermanent() || variable.getVariableName().isEmpty()) {
            return StoredValue.empty();
        }

        return loadStoredValue(variable.getId(), ownerType, ownerId);
    }

    public static StoredValue loadStoredValue(int itemId, int ownerType, int ownerId) {
        if (itemId <= 0) {
            return StoredValue.empty();
        }

        String query = "SELECT value, created_at, updated_at, revision FROM wired_variables WHERE item_id = ? AND owner_type = ? AND owner_id = ? LIMIT 1";

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, itemId);
            statement.setInt(2, ownerType);
            statement.setInt(3, ownerId);

            try (ResultSet set = statement.executeQuery()) {
                if (set.next()) {
                    return new StoredValue(true, set.getLong("value"), set.getLong("created_at"), set.getLong("updated_at"), set.getLong("revision"));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
        }

        return StoredValue.empty();
    }

    public static StoredValue loadStoredValue(VariableDefinition variable, int ownerType, int ownerId) {
        return variable == null ? StoredValue.empty() : loadStoredValue(variable.itemId, ownerType, ownerId);
    }

    public static WiredVariableMutationReceipt setSharedValue(VariableDefinition variable, int ownerType, int ownerId, long value) {
        if (variable == null || !variable.persistence.isPermanent() || variable.variableName.isEmpty()) {
            return WiredVariableMutationReceipt.rejected(0L, value, 0L);
        }

        synchronized (roomWriteLock(variable.roomId)) {
            StoredValue storedValue = loadStoredValue(variable.itemId, ownerType, ownerId);
            long oldValue = storedValue.exists ? storedValue.value : 0L;
            if (storedValue.exists && oldValue == value) {
                return WiredVariableMutationReceipt.unchanged(value, storedValue.revision);
            }

            if (!storedValue.exists && isAnyVariableLimitReached(variable.itemId, variable.roomId, ownerType, ownerId)) {
                logTooManyVariables(variable.roomId);
                return WiredVariableMutationReceipt.capRejected(oldValue, value, storedValue.revision);
            }

            long now = System.currentTimeMillis();
            long createdAt = storedValue.exists && storedValue.createdAtMs > 0L ? storedValue.createdAtMs : now;
            long nextRevision = nextRevision(storedValue.revision);
            String query = "INSERT INTO wired_variables (item_id, room_id, variable_type, variable_name, persistence, owner_type, owner_id, value, created_at, updated_at, revision) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE room_id = VALUES(room_id), variable_type = VALUES(variable_type), " +
                    "variable_name = VALUES(variable_name), persistence = VALUES(persistence), value = VALUES(value), " +
                    "created_at = IF(created_at > 0, created_at, VALUES(created_at)), updated_at = VALUES(updated_at), revision = VALUES(revision)";

            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setInt(1, variable.itemId);
                statement.setInt(2, variable.roomId);
                statement.setInt(3, variable.variableType);
                statement.setString(4, variable.variableName);
                statement.setInt(5, variable.persistence.code);
                statement.setInt(6, ownerType);
                statement.setInt(7, ownerId);
                statement.setLong(8, value);
                statement.setLong(9, createdAt);
                statement.setLong(10, now);
                statement.setLong(11, nextRevision);
                statement.executeUpdate();
                return WiredVariableMutationReceipt.committed(storedValue.exists, oldValue, value, nextRevision);
            } catch (SQLException e) {
                LOGGER.error("Caught SQL exception", e);
                return WiredVariableMutationReceipt.persistenceFailed(oldValue, value, storedValue.revision);
            }
        }
    }

    public static RemovalResult removeSharedValue(VariableDefinition variable, int ownerType, int ownerId) {
        if (variable == null) {
            return RemovalResult.failed();
        }

        synchronized (roomWriteLock(variable.roomId)) {
            StoredValue storedValue = loadStoredValue(variable.itemId, ownerType, ownerId);
            if (!storedValue.exists) {
                return RemovalResult.notFound();
            }

            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM wired_variables WHERE item_id = ? AND owner_type = ? AND owner_id = ?")) {
                statement.setInt(1, variable.itemId);
                statement.setInt(2, ownerType);
                statement.setInt(3, ownerId);
                statement.executeUpdate();
                return RemovalResult.removed(storedValue);
            } catch (SQLException e) {
                LOGGER.error("Caught SQL exception", e);
                return RemovalResult.failed(storedValue);
            }
        }
    }

    public static boolean hasValue(InteractionWiredVariable variable, int ownerType, int ownerId) {
        if (variable == null || variable.getVariableName().isEmpty()) {
            return false;
        }

        String query = "SELECT 1 FROM wired_variables WHERE item_id = ? AND owner_type = ? AND owner_id = ? LIMIT 1";

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, variable.getId());
            statement.setInt(2, ownerType);
            statement.setInt(3, ownerId);

            try (ResultSet set = statement.executeQuery()) {
                return set.next();
            }
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
        }

        return false;
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

        if (!variable.getPersistence().isPermanent() || variable.getVariableName().isEmpty()) {
            if (ownerType == OWNER_ROOM && ownerId == 0) {
                deleteValues(variable);
            }
            return SaveResult.committed(currentRevision);
        }

        synchronized (roomWriteLock(variable.getRoomId())) {
            if (!existed && isAnyVariableLimitReached(variable, ownerType, ownerId)) {
                logTooManyVariables(variable);
                return SaveResult.capRejected();
            }

            long now = System.currentTimeMillis();
            long createdAt = variable.getCreatedAtMs(ownerId) > 0L ? variable.getCreatedAtMs(ownerId) : now;
            long updatedAt = variable.getUpdatedAtMs(ownerId) > 0L ? variable.getUpdatedAtMs(ownerId) : now;
            long nextRevision = currentRevision == Long.MAX_VALUE ? 1L : Math.max(1L, currentRevision + 1L);

            String query = "INSERT INTO wired_variables (item_id, room_id, variable_type, variable_name, persistence, owner_type, owner_id, value, created_at, updated_at, revision) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE room_id = VALUES(room_id), variable_type = VALUES(variable_type), " +
                    "variable_name = VALUES(variable_name), persistence = VALUES(persistence), value = VALUES(value), " +
                    "created_at = IF(created_at > 0, created_at, VALUES(created_at)), updated_at = VALUES(updated_at), revision = VALUES(revision)";

            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setInt(1, variable.getId());
                statement.setInt(2, variable.getRoomId());
                statement.setInt(3, variable.getType().code);
                statement.setString(4, variable.getVariableName());
                statement.setInt(5, variable.getPersistence().code);
                statement.setInt(6, ownerType);
                statement.setInt(7, ownerId);
                statement.setLong(8, value);
                statement.setLong(9, createdAt);
                statement.setLong(10, updatedAt);
                statement.setLong(11, nextRevision);
                statement.executeUpdate();
                return SaveResult.committed(nextRevision);
            } catch (SQLException e) {
                LOGGER.error("Caught SQL exception", e);
            }
        }

        return SaveResult.failed();
    }

    private static boolean isAnyVariableLimitReached(InteractionWiredVariable variable, int ownerType, int ownerId) {
        return isAnyVariableLimitReached(variable.getId(), variable.getRoomId(), ownerType, ownerId);
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

        String query = "SELECT COUNT(*) FROM wired_variables WHERE room_id = ? AND owner_type = ? AND owner_id = ?";

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, roomId);
            statement.setInt(2, ownerType);
            statement.setInt(3, ownerId);

            try (ResultSet set = statement.executeQuery()) {
                return set.next() && set.getInt(1) >= limit;
            }
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
        }

        return false;
    }

    private static boolean isDefinitionVariableLimitReached(int itemId) {
        int limit = Emulator.getConfig().getInt("hotel.room.variable.definition.max", 10000);
        return limit >= 0 && countValues(
                "SELECT COUNT(*) FROM wired_variables WHERE item_id = ?",
                itemId) >= limit;
    }

    private static boolean isRoomVariableLimitReached(int roomId) {
        int limit = Emulator.getConfig().getInt("hotel.room.variable.total.max", 50000);
        return limit >= 0 && countValues(
                "SELECT COUNT(*) FROM wired_variables WHERE room_id = ?",
                roomId) >= limit;
    }

    private static int countValues(String query, int value) {
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, value);
            try (ResultSet set = statement.executeQuery()) {
                return set.next() ? set.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
            return Integer.MAX_VALUE;
        }
    }

    private static Object roomWriteLock(int roomId) {
        return ROOM_WRITE_LOCKS[Math.floorMod(roomId, ROOM_WRITE_LOCKS.length)];
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

    public static void updateVariableName(InteractionWiredVariable variable) {
        if (variable == null || variable.getVariableName().isEmpty()) {
            return;
        }

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE wired_variables SET variable_name = ?, variable_type = ?, persistence = ?, room_id = ? WHERE item_id = ?")) {
            statement.setString(1, variable.getVariableName());
            statement.setInt(2, variable.getType().code);
            statement.setInt(3, variable.getPersistence().code);
            statement.setInt(4, variable.getRoomId());
            statement.setInt(5, variable.getId());
            statement.execute();
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
        }
    }

    public static void deleteValues(InteractionWiredVariable variable) {
        if (variable == null) {
            return;
        }

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM wired_variables WHERE item_id = ?")) {
            statement.setInt(1, variable.getId());
            statement.execute();
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
        }
    }

    public static boolean deleteValue(InteractionWiredVariable variable, int ownerType, int ownerId) {
        if (variable == null) {
            return false;
        }

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM wired_variables WHERE item_id = ? AND owner_type = ? AND owner_id = ?")) {
            statement.setInt(1, variable.getId());
            statement.setInt(2, ownerType);
            statement.setInt(3, ownerId);
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
        }
        return false;
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
            return failed(StoredValue.empty());
        }

        static RemovalResult failed(StoredValue storedValue) {
            return new RemovalResult(false, false, storedValue);
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
}
