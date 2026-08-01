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

public class WiredVariableFurni extends InteractionWiredVariable {
    public static final WiredVariableType type = WiredVariableType.FURNI;

    private final ConcurrentHashMap<Integer, Long> itemValues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> itemCreatedAtMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> itemUpdatedAtMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> itemRevisions = new ConcurrentHashMap<>();
    private final Set<Integer> itemsWithValue = ConcurrentHashMap.newKeySet();
    private final Set<Integer> loadedPermanentItems = ConcurrentHashMap.newKeySet();
    private boolean hasValue;

    public WiredVariableFurni(ResultSet set, Item baseItem) throws SQLException {
        super(set, baseItem);
    }

    public WiredVariableFurni(int id, int userId, Item item, String extradata, int limitedStack, int limitedSells) {
        super(id, userId, item, extradata, limitedStack, limitedSells);
    }

    @Override
    public WiredVariableType getType() {
        return type;
    }

    public void configure(String variableName, WiredVariablePersistence persistence, boolean hasValue) {
        WiredVariablePersistence normalizedPersistence = normalizePersistence(persistence);
        boolean changedName = !this.getVariableName().equals(variableName);
        boolean changedValueShape = this.getPersistence() != normalizedPersistence || this.hasValue != hasValue;

        this.setVariableName(variableName);
        this.setPersistence(normalizedPersistence);
        this.hasValue = hasValue;
        this.setLoadedValue(0L);

        if (changedValueShape) {
            this.itemValues.clear();
            this.itemCreatedAtMs.clear();
            this.itemUpdatedAtMs.clear();
            this.itemRevisions.clear();
            this.itemsWithValue.clear();
            this.loadedPermanentItems.clear();
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
    public long getValue(int itemId) {
        if (!this.hasValue || !this.hasValue(itemId)) {
            return 0L;
        }

        return this.itemValues.getOrDefault(itemId, 0L);
    }

    @Override
    public void setValue(int itemId, long value) {
        this.setValueWithReceipt(itemId, value);
    }

    @Override
    public WiredVariableMutationReceipt setValueWithReceipt(int itemId, long value) {
        if (!this.hasValue || itemId == 0 || this.getVariableName().isEmpty()) {
            return WiredVariableMutationReceipt.rejected(0L, value, this.getRevision(itemId));
        }

        boolean persistValue = this.getPersistence().isPermanent() && itemId > 0;
        final boolean existed;
        final long oldValue;
        final long committedRevision;
        synchronized (this) {
            existed = this.hasValue(itemId);
            oldValue = existed ? this.itemValues.getOrDefault(itemId, 0L) : 0L;
            long currentRevision = this.itemRevisions.getOrDefault(itemId, 0L);
            if (existed && oldValue == value) {
                return WiredVariableMutationReceipt.unchanged(value, currentRevision);
            }
            if (!existed && !this.canCreateLoadedValue()) {
                return WiredVariableMutationReceipt.capRejected(oldValue, value, currentRevision);
            }

            Long oldCreatedAt = this.itemCreatedAtMs.get(itemId);
            Long oldUpdatedAt = this.itemUpdatedAtMs.get(itemId);
            this.itemValues.put(itemId, value);
            this.itemsWithValue.add(itemId);
            this.markItemValueUpdated(itemId);

            WiredVariableStore.SaveResult saveResult = persistValue
                    ? WiredVariableStore.saveValue(this, WiredVariableStore.OWNER_ITEM, itemId, value, existed, currentRevision)
                    : WiredVariableStore.SaveResult.inMemory(currentRevision);
            if (!saveResult.committed()) {
                if (existed) {
                    this.itemValues.put(itemId, oldValue);
                    restoreTimestamp(this.itemCreatedAtMs, itemId, oldCreatedAt);
                    restoreTimestamp(this.itemUpdatedAtMs, itemId, oldUpdatedAt);
                } else {
                    this.itemValues.remove(itemId);
                    this.itemsWithValue.remove(itemId);
                    this.itemCreatedAtMs.remove(itemId);
                    this.itemUpdatedAtMs.remove(itemId);
                }
                return saveResult.status == WiredVariableStore.SaveResult.Status.CAP_REJECTED
                        ? WiredVariableMutationReceipt.capRejected(oldValue, value, currentRevision)
                        : WiredVariableMutationReceipt.persistenceFailed(oldValue, value, currentRevision);
            }

            if (persistValue) {
                this.loadedPermanentItems.add(itemId);
            }
            committedRevision = persistValue
                    ? saveResult.revision
                    : nextRevision(currentRevision);
            this.itemRevisions.put(itemId, committedRevision);
        }

        WiredExtraTimeUtilities.applyForVariable(this, WiredVariableStore.OWNER_ITEM, itemId);
        WiredExtraLevelUpSystem.applyForVariable(this, WiredVariableStore.OWNER_ITEM, itemId);
        this.fireVariableChanged(WiredVariableStore.OWNER_ITEM, itemId, existed ? this.changeAction(oldValue, value) : VARIABLE_ACTION_CREATED, oldValue, value);
        return WiredVariableMutationReceipt.committed(existed, oldValue, value, committedRevision);
    }

    @Override
    public synchronized boolean hasValue(int itemId) {
        if (itemId == 0 || this.getVariableName().isEmpty()) {
            return false;
        }

        if (itemId > 0 && this.getPersistence().isPermanent() && this.loadedPermanentItems.add(itemId)) {
            WiredVariableStore.StoredValue storedValue = WiredVariableStore.loadStoredValue(this, WiredVariableStore.OWNER_ITEM, itemId);
            if (storedValue.exists) {
                this.itemsWithValue.add(itemId);
                this.itemValues.put(itemId, storedValue.value);
                this.markItemValueLoaded(itemId, storedValue.createdAtMs, storedValue.updatedAtMs);
                this.itemRevisions.put(itemId, storedValue.revision);
            }
        }

        return this.itemsWithValue.contains(itemId);
    }

    @Override
    public void giveValue(int itemId, long value, boolean overrideExisting) {
        if (itemId == 0 || this.getVariableName().isEmpty()) {
            return;
        }

        if (this.hasValue) {
            if (!overrideExisting && this.hasValue(itemId)) return;
            this.setValueWithReceipt(itemId, value);
            return;
        }

        boolean persistValue = this.getPersistence().isPermanent() && itemId > 0;
        synchronized (this) {
            if (this.hasValue(itemId) || !this.canCreateLoadedValue()) return;

            long currentRevision = this.itemRevisions.getOrDefault(itemId, 0L);
            this.itemValues.put(itemId, 0L);
            this.itemsWithValue.add(itemId);
            this.markItemValueUpdated(itemId);
            WiredVariableStore.SaveResult saveResult = persistValue
                    ? WiredVariableStore.saveValue(this, WiredVariableStore.OWNER_ITEM, itemId, 0L, false, currentRevision)
                    : WiredVariableStore.SaveResult.inMemory(currentRevision);
            if (!saveResult.committed()) {
                this.itemValues.remove(itemId);
                this.itemsWithValue.remove(itemId);
                this.itemCreatedAtMs.remove(itemId);
                this.itemUpdatedAtMs.remove(itemId);
                return;
            }
            if (persistValue) {
                this.loadedPermanentItems.add(itemId);
            }
            this.itemRevisions.put(itemId, persistValue ? saveResult.revision : nextRevision(currentRevision));
        }

        WiredExtraTimeUtilities.applyForVariable(this, WiredVariableStore.OWNER_ITEM, itemId);
        WiredExtraLevelUpSystem.applyForVariable(this, WiredVariableStore.OWNER_ITEM, itemId);
        this.fireVariableChanged(WiredVariableStore.OWNER_ITEM, itemId, VARIABLE_ACTION_CREATED, 0L, 0L);
    }

    @Override
    public void removeValue(int itemId) {
        if (itemId == 0) {
            return;
        }

        long oldValue;
        synchronized (this) {
            if (!this.hasValue(itemId)) return;

            oldValue = this.getValue(itemId);
            Long oldCreatedAt = this.itemCreatedAtMs.get(itemId);
            Long oldUpdatedAt = this.itemUpdatedAtMs.get(itemId);
            long oldRevision = this.itemRevisions.getOrDefault(itemId, 0L);
            this.itemValues.remove(itemId);
            this.itemCreatedAtMs.remove(itemId);
            this.itemUpdatedAtMs.remove(itemId);
            this.itemsWithValue.remove(itemId);

            if (itemId > 0 && this.getPersistence().isPermanent()
                    && !WiredVariableStore.deleteValue(this, WiredVariableStore.OWNER_ITEM, itemId)) {
                this.itemValues.put(itemId, oldValue);
                this.itemsWithValue.add(itemId);
                restoreTimestamp(this.itemCreatedAtMs, itemId, oldCreatedAt);
                restoreTimestamp(this.itemUpdatedAtMs, itemId, oldUpdatedAt);
                return;
            }

            if (itemId > 0) {
                this.loadedPermanentItems.add(itemId);
                this.itemRevisions.put(itemId, nextRevision(oldRevision));
            } else {
                this.loadedPermanentItems.remove(itemId);
                this.itemRevisions.remove(itemId);
            }
        }

        this.fireVariableChanged(WiredVariableStore.OWNER_ITEM, itemId, VARIABLE_ACTION_DELETED, oldValue, 0L);
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
        this.itemValues.clear();
        this.itemCreatedAtMs.clear();
        this.itemUpdatedAtMs.clear();
        this.itemRevisions.clear();
        this.itemsWithValue.clear();
        this.loadedPermanentItems.clear();

        if (wiredData != null && wiredData.startsWith("{")) {
            JsonData data = WiredManager.getGson().fromJson(wiredData, JsonData.class);

            if (data != null) {
                this.setVariableName(data.name);
                this.setPersistence(normalizePersistence(WiredVariablePersistence.fromCode(data.persistence)));
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
        super.onPickUp();
        this.itemValues.clear();
        this.itemCreatedAtMs.clear();
        this.itemUpdatedAtMs.clear();
        this.itemRevisions.clear();
        this.itemsWithValue.clear();
        this.loadedPermanentItems.clear();
    }

    @Override
    public long getCreatedAtMs(int itemId) {
        return this.itemCreatedAtMs.getOrDefault(itemId, 0L);
    }

    @Override
    public long getUpdatedAtMs(int itemId) {
        return this.itemUpdatedAtMs.getOrDefault(itemId, 0L);
    }

    @Override
    public long getRevision(int itemId) {
        return this.itemRevisions.getOrDefault(itemId, 0L);
    }

    @Override
    public int getLoadedValueCount() {
        return this.itemsWithValue.size();
    }

    private static void restoreTimestamp(ConcurrentHashMap<Integer, Long> timestamps, int ownerId, Long value) {
        if (value == null) timestamps.remove(ownerId);
        else timestamps.put(ownerId, value);
    }

    private void markItemValueLoaded(int itemId, long createdAtMs, long updatedAtMs) {
        long now = System.currentTimeMillis();
        long createdAt = createdAtMs > 0L ? createdAtMs : now;
        this.itemCreatedAtMs.putIfAbsent(itemId, createdAt);
        this.itemUpdatedAtMs.putIfAbsent(itemId, updatedAtMs > 0L ? updatedAtMs : createdAt);
    }

    private void markItemValueUpdated(int itemId) {
        long now = System.currentTimeMillis();
        this.itemCreatedAtMs.putIfAbsent(itemId, now);
        this.itemUpdatedAtMs.put(itemId, now);
    }

    private static WiredVariablePersistence normalizePersistence(WiredVariablePersistence persistence) {
        return persistence == WiredVariablePersistence.PERMANENT ? WiredVariablePersistence.PERMANENT : WiredVariablePersistence.ROOM_ACTIVE;
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
