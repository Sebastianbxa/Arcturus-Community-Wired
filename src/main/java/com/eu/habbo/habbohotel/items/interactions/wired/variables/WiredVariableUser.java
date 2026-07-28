package com.eu.habbo.habbohotel.items.interactions.wired.variables;

import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredVariable;
import com.eu.habbo.habbohotel.items.interactions.wired.extra.WiredExtraLevelUpSystem;
import com.eu.habbo.habbohotel.items.interactions.wired.extra.WiredExtraTimeUtilities;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.wired.WiredVariablePersistence;
import com.eu.habbo.habbohotel.wired.WiredVariableType;
import com.eu.habbo.habbohotel.wired.core.WiredManager;
import com.eu.habbo.habbohotel.wired.variables.WiredVariableStore;
import com.eu.habbo.habbohotel.wired.variables.WiredVariableMutationReceipt;
import com.eu.habbo.messages.ServerMessage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WiredVariableUser extends InteractionWiredVariable {
    public static final WiredVariableType type = WiredVariableType.USER;

    private final ConcurrentHashMap<Integer, Long> userValues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> userCreatedAtMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> userUpdatedAtMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> userRevisions = new ConcurrentHashMap<>();
    private final Set<Integer> usersWithValue = ConcurrentHashMap.newKeySet();
    private final Set<Integer> loadedPermanentUsers = ConcurrentHashMap.newKeySet();
    private boolean hasValue;

    public WiredVariableUser(ResultSet set, Item baseItem) throws SQLException {
        super(set, baseItem);
    }

    public WiredVariableUser(int id, int userId, Item item, String extradata, int limitedStack, int limitedSells) {
        super(id, userId, item, extradata, limitedStack, limitedSells);
    }

    @Override
    public WiredVariableType getType() {
        return type;
    }

    public void configure(String variableName, WiredVariablePersistence persistence, boolean hasValue) {
        boolean changedName = !this.getVariableName().equals(variableName);
        boolean changedValueShape = this.getPersistence() != persistence || this.hasValue != hasValue;

        this.setVariableName(variableName);
        this.setPersistence(persistence);
        this.hasValue = hasValue;
        this.setLoadedValue(0L);

        if (changedValueShape) {
            this.userValues.clear();
            this.userCreatedAtMs.clear();
            this.userUpdatedAtMs.clear();
            this.userRevisions.clear();
            this.usersWithValue.clear();
            this.loadedPermanentUsers.clear();
            WiredVariableStore.deleteValues(this);
        } else if (changedName) {
            WiredVariableStore.updateVariableName(this);
        }
    }

    @Override
    public boolean hasValue() {
        return this.hasValue;
    }

    @Override
    public long getValue(int userId) {
        if (!this.hasValue || !this.hasValue(userId)) {
            return 0L;
        }

        return this.userValues.getOrDefault(userId, 0L);
    }

    @Override
    public void setValue(int userId, long value) {
        this.setValueWithReceipt(userId, value);
    }

    @Override
    public WiredVariableMutationReceipt setValueWithReceipt(int userId, long value) {
        if (!this.hasValue || userId <= 0 || this.getVariableName().isEmpty()) {
            return WiredVariableMutationReceipt.rejected(0L, value, this.getRevision(userId));
        }

        final boolean existed;
        final long oldValue;
        final long committedRevision;
        synchronized (this) {
            existed = this.hasValue(userId);
            oldValue = existed ? this.userValues.getOrDefault(userId, 0L) : 0L;
            long currentRevision = this.userRevisions.getOrDefault(userId, 0L);
            if (existed && oldValue == value) {
                return WiredVariableMutationReceipt.unchanged(value, currentRevision);
            }
            if (!existed && !this.canCreateLoadedValue()) {
                return WiredVariableMutationReceipt.capRejected(oldValue, value, currentRevision);
            }

            Long oldCreatedAt = this.userCreatedAtMs.get(userId);
            Long oldUpdatedAt = this.userUpdatedAtMs.get(userId);
            this.userValues.put(userId, value);
            this.usersWithValue.add(userId);
            this.markUserValueUpdated(userId);

            WiredVariableStore.SaveResult saveResult = this.getPersistence().isPermanent()
                    ? WiredVariableStore.saveValue(this, WiredVariableStore.OWNER_USER, userId, value, existed, currentRevision)
                    : WiredVariableStore.SaveResult.inMemory(currentRevision);
            if (!saveResult.committed()) {
                if (existed) {
                    this.userValues.put(userId, oldValue);
                    restoreTimestamp(this.userCreatedAtMs, userId, oldCreatedAt);
                    restoreTimestamp(this.userUpdatedAtMs, userId, oldUpdatedAt);
                } else {
                    this.userValues.remove(userId);
                    this.usersWithValue.remove(userId);
                    this.userCreatedAtMs.remove(userId);
                    this.userUpdatedAtMs.remove(userId);
                }
                return saveResult.status == WiredVariableStore.SaveResult.Status.CAP_REJECTED
                        ? WiredVariableMutationReceipt.capRejected(oldValue, value, currentRevision)
                        : WiredVariableMutationReceipt.persistenceFailed(oldValue, value, currentRevision);
            }

            this.loadedPermanentUsers.add(userId);
            committedRevision = this.getPersistence().isPermanent()
                    ? saveResult.revision
                    : nextRevision(currentRevision);
            this.userRevisions.put(userId, committedRevision);
        }

        WiredExtraTimeUtilities.applyForVariable(this, WiredVariableStore.OWNER_USER, userId);
        WiredExtraLevelUpSystem.applyForVariable(this, WiredVariableStore.OWNER_USER, userId);
        this.fireVariableChanged(WiredVariableStore.OWNER_USER, userId, existed ? this.changeAction(oldValue, value) : VARIABLE_ACTION_CREATED, oldValue, value);
        return WiredVariableMutationReceipt.committed(existed, oldValue, value, committedRevision);
    }

    @Override
    public synchronized boolean hasValue(int userId) {
        if (userId <= 0 || this.getVariableName().isEmpty()) {
            return false;
        }

        if (this.getPersistence().isPermanent() && this.loadedPermanentUsers.add(userId)) {
            WiredVariableStore.StoredValue storedValue = WiredVariableStore.loadStoredValue(this, WiredVariableStore.OWNER_USER, userId);
            if (storedValue.exists) {
                this.usersWithValue.add(userId);
                this.userValues.put(userId, storedValue.value);
                this.markUserValueLoaded(userId, storedValue.createdAtMs, storedValue.updatedAtMs);
                this.userRevisions.put(userId, storedValue.revision);
            }
        }

        return this.usersWithValue.contains(userId);
    }

    @Override
    public void giveValue(int userId, long value, boolean overrideExisting) {
        if (userId <= 0 || this.getVariableName().isEmpty()) {
            return;
        }

        if (this.hasValue) {
            if (!overrideExisting && this.hasValue(userId)) return;
            this.setValueWithReceipt(userId, value);
            return;
        }

        synchronized (this) {
            if (this.hasValue(userId) || !this.canCreateLoadedValue()) return;

            long currentRevision = this.userRevisions.getOrDefault(userId, 0L);
            this.userValues.put(userId, 0L);
            this.usersWithValue.add(userId);
            this.markUserValueUpdated(userId);
            WiredVariableStore.SaveResult saveResult = this.getPersistence().isPermanent()
                    ? WiredVariableStore.saveValue(this, WiredVariableStore.OWNER_USER, userId, 0L, false, currentRevision)
                    : WiredVariableStore.SaveResult.inMemory(currentRevision);
            if (!saveResult.committed()) {
                this.userValues.remove(userId);
                this.usersWithValue.remove(userId);
                this.userCreatedAtMs.remove(userId);
                this.userUpdatedAtMs.remove(userId);
                return;
            }
            this.loadedPermanentUsers.add(userId);
            this.userRevisions.put(userId, this.getPersistence().isPermanent() ? saveResult.revision : nextRevision(currentRevision));
        }

        WiredExtraTimeUtilities.applyForVariable(this, WiredVariableStore.OWNER_USER, userId);
        WiredExtraLevelUpSystem.applyForVariable(this, WiredVariableStore.OWNER_USER, userId);
        this.fireVariableChanged(WiredVariableStore.OWNER_USER, userId, VARIABLE_ACTION_CREATED, 0L, 0L);
    }

    @Override
    public void removeValue(int userId) {
        if (userId <= 0) {
            return;
        }

        long oldValue;
        synchronized (this) {
            if (!this.hasValue(userId)) return;

            oldValue = this.getValue(userId);
            Long oldCreatedAt = this.userCreatedAtMs.get(userId);
            Long oldUpdatedAt = this.userUpdatedAtMs.get(userId);
            long oldRevision = this.userRevisions.getOrDefault(userId, 0L);
            this.userValues.remove(userId);
            this.userCreatedAtMs.remove(userId);
            this.userUpdatedAtMs.remove(userId);
            this.usersWithValue.remove(userId);

            if (this.getPersistence().isPermanent()
                    && !WiredVariableStore.deleteValue(this, WiredVariableStore.OWNER_USER, userId)) {
                this.userValues.put(userId, oldValue);
                this.usersWithValue.add(userId);
                restoreTimestamp(this.userCreatedAtMs, userId, oldCreatedAt);
                restoreTimestamp(this.userUpdatedAtMs, userId, oldUpdatedAt);
                return;
            }

            this.loadedPermanentUsers.add(userId);
            this.userRevisions.put(userId, nextRevision(oldRevision));
        }

        this.fireVariableChanged(WiredVariableStore.OWNER_USER, userId, VARIABLE_ACTION_DELETED, oldValue, 0L);
    }

    @Override
    public void removeRoomActiveValue(int userId) {
        if (!this.getPersistence().isPermanent()) {
            this.removeValue(userId);
        }
    }

    @Override
    public synchronized void releaseOwnerCache(int userId) {
        if (!this.getPersistence().isPermanent() || userId <= 0) return;
        this.userValues.remove(userId);
        this.userCreatedAtMs.remove(userId);
        this.userUpdatedAtMs.remove(userId);
        this.userRevisions.remove(userId);
        this.usersWithValue.remove(userId);
        this.loadedPermanentUsers.remove(userId);
    }

    @Override
    public synchronized void refreshSharedValue(int userId, WiredVariableStore.StoredValue storedValue) {
        if (this.getPersistence() != WiredVariablePersistence.SHARED_PERMANENT || userId <= 0 || storedValue == null) {
            return;
        }

        this.loadedPermanentUsers.add(userId);
        if (storedValue.exists) {
            long now = System.currentTimeMillis();
            long createdAt = storedValue.createdAtMs > 0L ? storedValue.createdAtMs : now;
            long updatedAt = storedValue.updatedAtMs > 0L ? storedValue.updatedAtMs : createdAt;
            this.usersWithValue.add(userId);
            this.userValues.put(userId, storedValue.value);
            this.userCreatedAtMs.put(userId, createdAt);
            this.userUpdatedAtMs.put(userId, updatedAt);
            this.userRevisions.put(userId, storedValue.revision);
        } else {
            this.usersWithValue.remove(userId);
            this.userValues.remove(userId);
            this.userCreatedAtMs.remove(userId);
            this.userUpdatedAtMs.remove(userId);
            this.userRevisions.remove(userId);
        }
    }

    @Override
    public int getLoadedValueCount() {
        return this.usersWithValue.size();
    }

    @Override
    public String getWiredData() {
        return WiredManager.getGson().toJson(new JsonData(this.getVariableName(), this.getPersistence().code, this.hasValue));
    }

    @Override
    public void loadWiredData(ResultSet set, Room room) throws SQLException {
        String wiredData = set.getString("wired_data");

        this.setVariableName("");
        this.setPersistence(WiredVariablePersistence.ROOM_ACTIVE);
        this.hasValue = false;
        this.setLoadedValue(0L);
        this.userValues.clear();
        this.userCreatedAtMs.clear();
        this.userUpdatedAtMs.clear();
        this.userRevisions.clear();
        this.usersWithValue.clear();
        this.loadedPermanentUsers.clear();

        if (wiredData != null && wiredData.startsWith("{")) {
            JsonData data = WiredManager.getGson().fromJson(wiredData, JsonData.class);

            if (data != null) {
                this.setVariableName(data.name);
                this.setPersistence(WiredVariablePersistence.fromCode(data.persistence));
                this.hasValue = data.hasValue;
            }
        }
    }

    @Override
    public void serializeWiredData(ServerMessage message, Room room) {
        message.appendBoolean(false);
        message.appendInt(0);
        message.appendInt(0);
        message.appendInt(this.getBaseItem().getSpriteId());
        message.appendInt(this.getId());
        message.appendString("");
        message.appendInt(0);
        message.appendInt(0);
        message.appendInt(this.getType().code);
        message.appendString(this.getVariableName());
        message.appendInt(this.getPersistence().code);
        message.appendString(this.hasValue ? "1" : "0");
    }

    @Override
    public void onPickUp() {
        WiredVariableFromAnotherRoom.invalidateSourceDefinition(this.getRoomId(), this.getType(), this.getVariableName());
        super.onPickUp();
        this.userValues.clear();
        this.userCreatedAtMs.clear();
        this.userUpdatedAtMs.clear();
        this.userRevisions.clear();
        this.usersWithValue.clear();
        this.loadedPermanentUsers.clear();
    }

    @Override
    public long getCreatedAtMs(int userId) {
        return this.userCreatedAtMs.getOrDefault(userId, 0L);
    }

    @Override
    public long getUpdatedAtMs(int userId) {
        return this.userUpdatedAtMs.getOrDefault(userId, 0L);
    }

    @Override
    public long getRevision(int userId) {
        return this.userRevisions.getOrDefault(userId, 0L);
    }

    private static void restoreTimestamp(ConcurrentHashMap<Integer, Long> timestamps, int ownerId, Long value) {
        if (value == null) timestamps.remove(ownerId);
        else timestamps.put(ownerId, value);
    }

    private void markUserValueLoaded(int userId, long createdAtMs, long updatedAtMs) {
        long now = System.currentTimeMillis();
        long createdAt = createdAtMs > 0L ? createdAtMs : now;
        this.userCreatedAtMs.putIfAbsent(userId, createdAt);
        this.userUpdatedAtMs.putIfAbsent(userId, updatedAtMs > 0L ? updatedAtMs : createdAt);
    }

    private void markUserValueUpdated(int userId) {
        long now = System.currentTimeMillis();
        this.userCreatedAtMs.putIfAbsent(userId, now);
        this.userUpdatedAtMs.put(userId, now);
    }

    static class JsonData {
        String name;
        int persistence;
        boolean hasValue;

        public JsonData(String name, int persistence, boolean hasValue) {
            this.name = name;
            this.persistence = persistence;
            this.hasValue = hasValue;
        }
    }
}
