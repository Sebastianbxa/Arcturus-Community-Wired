package com.eu.habbo.habbohotel.items.interactions.wired.variables;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredVariable;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.wired.WiredVariablePersistence;
import com.eu.habbo.habbohotel.wired.WiredVariableType;
import com.eu.habbo.habbohotel.wired.core.WiredManager;
import com.eu.habbo.habbohotel.wired.variables.WiredVariableMutationReceipt;
import com.eu.habbo.habbohotel.wired.variables.WiredVariableName;
import com.eu.habbo.habbohotel.wired.variables.WiredVariableStore;
import com.eu.habbo.messages.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class WiredVariableFromAnotherRoom extends InteractionWiredVariable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WiredVariableFromAnotherRoom.class);
    private static final int LAYOUT_CODE = 4;
    private static final ConcurrentHashMap<SourceKey, SourceDefinition> SOURCE_DEFINITIONS = new ConcurrentHashMap<>();

    private int sourceRoomId;
    private WiredVariableType sourceVariableType = WiredVariableType.GLOBAL;
    private String sourceVariableName = "";
    private boolean readOnly = true;
    private int ownerId;

    public WiredVariableFromAnotherRoom(ResultSet set, Item baseItem) throws SQLException {
        super(set, baseItem);
    }

    public WiredVariableFromAnotherRoom(int id, int userId, Item item, String extradata, int limitedStack, int limitedSells) {
        super(id, userId, item, extradata, limitedStack, limitedSells);
    }

    @Override
    public WiredVariableType getType() {
        return this.sourceVariableType == null ? WiredVariableType.GLOBAL : this.sourceVariableType;
    }

    @Override
    public WiredVariablePersistence getPersistence() {
        return WiredVariablePersistence.SHARED_PERMANENT;
    }

    public void configure(String variableName, int sourceRoomId, WiredVariableType sourceVariableType, String sourceVariableName, boolean readOnly, int ownerId) {
        this.setVariableName(variableName);
        this.setPersistence(WiredVariablePersistence.SHARED_PERMANENT);
        this.sourceRoomId = Math.max(0, sourceRoomId);
        this.sourceVariableType = normalizeSourceType(sourceVariableType);
        this.sourceVariableName = WiredVariableName.normalize(sourceVariableName);
        this.readOnly = readOnly;
        this.ownerId = ownerId;
        this.setLoadedValue(0L);
    }

    public int getSourceRoomId() {
        return this.sourceRoomId;
    }

    public WiredVariableType getSourceVariableType() {
        return this.getType();
    }

    public String getSourceVariableName() {
        return this.sourceVariableName;
    }

    public void renameSourceReference(String newSourceVariableName) {
        String normalizedName = WiredVariableName.normalize(newSourceVariableName);
        if (!WiredVariableName.isValid(normalizedName) || this.sourceVariableName.equals(normalizedName)) {
            return;
        }

        this.sourceVariableName = normalizedName;
        this.setLoadedValue(0L);
    }

    @Override
    public boolean hasValue() {
        InteractionWiredVariable source = this.getLoadedSourceVariable();
        if (source != null) {
            return source.hasValue();
        }

        SourceDefinition definition = this.findSourceDefinition();
        return definition != null && (definition.type == WiredVariableType.GLOBAL || definition.hasValue);
    }

    @Override
    public long getValue() {
        InteractionWiredVariable source = this.getLoadedSourceVariable();
        if (source != null) {
            return source.getValue();
        }

        SourceDefinition definition = this.findSourceDefinition();
        if (definition == null || definition.type != WiredVariableType.GLOBAL) {
            return 0L;
        }

        return WiredVariableStore.loadStoredValue(
                definition.variable,
                WiredVariableStore.OWNER_ROOM,
                0).value;
    }

    @Override
    public void setValue(long value) {
        this.setValueWithReceipt(value);
    }

    @Override
    public WiredVariableMutationReceipt setValueWithReceipt(long value) {
        if (this.getType() != WiredVariableType.GLOBAL) {
            return WiredVariableMutationReceipt.rejected(0L, value, 0L);
        }

        if (this.readOnly) {
            return WiredVariableMutationReceipt.rejected(this.getValue(), value, this.getRevision());
        }

        SourceDefinition definition = this.findSourceDefinition();
        if (definition == null || definition.type != WiredVariableType.GLOBAL) {
            return WiredVariableMutationReceipt.rejected(0L, value, 0L);
        }

        WiredVariableMutationReceipt receipt = WiredVariableStore.setSharedValue(
                definition.variable,
                WiredVariableStore.OWNER_ROOM,
                0,
                value);
        this.refreshLoadedSource(definition, 0);
        this.broadcastGlobalChange(definition, receipt);
        return receipt;
    }

    @Override
    public long getValue(int ownerId) {
        InteractionWiredVariable source = this.getLoadedSourceVariable();
        if (source != null) {
            return source.getValue(ownerId);
        }

        SourceDefinition definition = this.findSourceDefinition();
        if (definition == null) {
            return 0L;
        }

        int ownerType = definition.type == WiredVariableType.USER
                ? WiredVariableStore.OWNER_USER
                : WiredVariableStore.OWNER_ROOM;
        int storedOwnerId = definition.type == WiredVariableType.USER ? ownerId : 0;
        return WiredVariableStore.loadStoredValue(definition.variable, ownerType, storedOwnerId).value;
    }

    @Override
    public void setValue(int ownerId, long value) {
        this.setValueWithReceipt(ownerId, value);
    }

    @Override
    public WiredVariableMutationReceipt setValueWithReceipt(int ownerId, long value) {
        if (this.getType() != WiredVariableType.USER) {
            return this.setValueWithReceipt(value);
        }

        if (this.readOnly || ownerId <= 0) {
            return WiredVariableMutationReceipt.rejected(this.getValue(ownerId), value, this.getRevision(ownerId));
        }

        SourceDefinition definition = this.findSourceDefinition();
        if (definition == null || definition.type != WiredVariableType.USER || !definition.hasValue) {
            return WiredVariableMutationReceipt.rejected(0L, value, 0L);
        }

        WiredVariableMutationReceipt receipt = WiredVariableStore.setSharedValue(
                definition.variable,
                WiredVariableStore.OWNER_USER,
                ownerId,
                value);
        this.refreshLoadedSource(definition, ownerId);
        this.fireCommittedChange(WiredVariableStore.OWNER_USER, ownerId, receipt);
        return receipt;
    }

    @Override
    public boolean hasValue(int ownerId) {
        InteractionWiredVariable source = this.getLoadedSourceVariable();
        if (source != null) {
            return source.hasValue(ownerId);
        }

        SourceDefinition definition = this.findSourceDefinition();
        if (definition == null) {
            return false;
        }
        if (definition.type == WiredVariableType.GLOBAL) {
            return true;
        }

        return ownerId > 0 && WiredVariableStore.loadStoredValue(
                definition.variable,
                WiredVariableStore.OWNER_USER,
                ownerId).exists;
    }

    @Override
    public void giveValue(int ownerId, long value, boolean overrideExisting) {
        if (this.readOnly) return;

        SourceDefinition definition = this.findSourceDefinition();
        if (definition == null) {
            return;
        }

        if (definition.type == WiredVariableType.GLOBAL) {
            if (overrideExisting) {
                WiredVariableMutationReceipt receipt = WiredVariableStore.setSharedValue(
                        definition.variable,
                        WiredVariableStore.OWNER_ROOM,
                        0,
                        value);
                this.refreshLoadedSource(definition, 0);
                this.broadcastGlobalChange(definition, receipt);
            }
            return;
        }

        if (ownerId <= 0) {
            return;
        }

        WiredVariableStore.StoredValue storedValue = WiredVariableStore.loadStoredValue(
                definition.variable,
                WiredVariableStore.OWNER_USER,
                ownerId);
        if (storedValue.exists && !overrideExisting) {
            return;
        }

        WiredVariableMutationReceipt receipt = WiredVariableStore.setSharedValue(
                definition.variable,
                WiredVariableStore.OWNER_USER,
                ownerId,
                definition.hasValue ? value : 0L);
        this.refreshLoadedSource(definition, ownerId);
        this.fireCommittedChange(WiredVariableStore.OWNER_USER, ownerId, receipt);
    }

    @Override
    public void removeValue(int ownerId) {
        if (this.readOnly) return;

        SourceDefinition definition = this.findSourceDefinition();
        if (definition == null) {
            return;
        }

        if (definition.type == WiredVariableType.GLOBAL) {
            WiredVariableMutationReceipt receipt = WiredVariableStore.setSharedValue(
                    definition.variable,
                    WiredVariableStore.OWNER_ROOM,
                    0,
                    0L);
            this.refreshLoadedSource(definition, 0);
            if (receipt.committed()) {
                notifyLoadedGlobalConsumers(
                        this.ownerId,
                        definition.variable.roomId,
                        definition.variable.itemId,
                        definition.variable.variableName,
                        VARIABLE_ACTION_DELETED,
                        receipt.oldValue,
                        0L,
                        true);
            }
            return;
        }

        if (ownerId <= 0) {
            return;
        }

        WiredVariableStore.RemovalResult result = WiredVariableStore.removeSharedValue(
                definition.variable,
                WiredVariableStore.OWNER_USER,
                ownerId);
        this.refreshLoadedSource(definition, ownerId);
        if (result.succeeded && result.removed) {
            this.fireVariableChanged(
                    WiredVariableStore.OWNER_USER,
                    ownerId,
                    VARIABLE_ACTION_DELETED,
                    result.storedValue.value,
                    0L);
        }
    }

    @Override
    public long getCreatedAtMs() {
        return this.getStoredMetadata(false, 0).createdAtMs;
    }

    @Override
    public long getUpdatedAtMs() {
        return this.getStoredMetadata(false, 0).updatedAtMs;
    }

    @Override
    public long getCreatedAtMs(int ownerId) {
        return this.getStoredMetadata(true, ownerId).createdAtMs;
    }

    @Override
    public long getUpdatedAtMs(int ownerId) {
        return this.getStoredMetadata(true, ownerId).updatedAtMs;
    }

    @Override
    public long getRevision() {
        return this.getStoredMetadata(false, 0).revision;
    }

    @Override
    public long getRevision(int ownerId) {
        return this.getStoredMetadata(true, ownerId).revision;
    }

    @Override
    public String getWiredData() {
        return WiredManager.getGson().toJson(new JsonData(this.getVariableName(), this.sourceRoomId, this.getType().code, this.sourceVariableName, this.readOnly, this.ownerId));
    }

    @Override
    public void loadWiredData(ResultSet set, Room room) throws SQLException {
        this.setVariableName("");
        this.setPersistence(WiredVariablePersistence.SHARED_PERMANENT);
        this.sourceRoomId = 0;
        this.sourceVariableType = WiredVariableType.GLOBAL;
        this.sourceVariableName = "";
        this.readOnly = true;
        this.ownerId = room == null ? 0 : room.getOwnerId();

        String wiredData = set.getString("wired_data");
        if (wiredData != null && wiredData.startsWith("{")) {
            JsonData data = WiredManager.getGson().fromJson(wiredData, JsonData.class);
            if (data != null) {
                this.setVariableName(data.name);
                this.sourceRoomId = data.sourceRoomId;
                this.sourceVariableType = normalizeSourceType(WiredVariableType.fromCode(data.sourceVariableType));
                this.sourceVariableName = WiredVariableName.normalize(data.sourceVariableName);
                this.readOnly = data.readOnly;
            }
        }
    }

    @Override
    public void serializeWiredData(ServerMessage message, Room room) {
        int editorOwnerId = room == null ? this.ownerId : room.getOwnerId();
        message.appendBoolean(false);
        message.appendInt(0);
        message.appendInt(0);
        message.appendInt(this.getBaseItem().getSpriteId());
        message.appendInt(this.getId());
        message.appendString("");
        message.appendInt(0);
        message.appendInt(0);
        message.appendInt(LAYOUT_CODE);
        message.appendString(this.getVariableName());
        message.appendInt(this.readOnly ? 1 : 0);
        message.appendString(WiredManager.getGson().toJson(new EditorData(this.sourceRoomId, this.getType().code, this.sourceVariableName, this.getSharedRooms(editorOwnerId))));
    }

    @Override
    public void onPickUp() {
        super.onPickUp();
        this.sourceRoomId = 0;
        this.sourceVariableType = WiredVariableType.GLOBAL;
        this.sourceVariableName = "";
        this.readOnly = true;
        this.ownerId = 0;
    }

    @Override
    public void removeRoomActiveValue(int ownerId) {
        // References never own room-active values, so room cleanup must not delete the source value.
    }

    @Override
    public void releaseOwnerCache(int ownerId) {
        if (this.getType() != WiredVariableType.USER || ownerId <= 0) {
            return;
        }

        InteractionWiredVariable source = this.getLoadedSourceVariable();
        if (source != null) {
            source.releaseOwnerCache(ownerId);
        }
    }

    private InteractionWiredVariable getLoadedSourceVariable() {
        if (this.sourceRoomId <= 0 || this.sourceVariableName.isEmpty()) {
            return null;
        }

        // A reference may reuse an already-active source, but must never wake an unloaded room.
        Room sourceRoom = Emulator.getGameEnvironment().getRoomManager().getRoom(this.sourceRoomId);
        if (sourceRoom == null || !sourceRoom.isLoaded() || sourceRoom.getOwnerId() != this.ownerId ||
                sourceRoom.getRoomSpecialTypes() == null) {
            return null;
        }

        InteractionWiredVariable source = sourceRoom.getRoomSpecialTypes().getVariable(this.getType(), this.sourceVariableName);
        if (source == null || source == this || source.getPersistence() != WiredVariablePersistence.SHARED_PERMANENT) {
            return null;
        }

        return source;
    }

    private SourceDefinition findSourceDefinition() {
        if (this.sourceRoomId <= 0 || this.ownerId <= 0 || this.sourceVariableName.isEmpty()) {
            return null;
        }

        SourceKey key = new SourceKey(this.ownerId, this.sourceRoomId, this.getType(), this.sourceVariableName);
        InteractionWiredVariable loadedSource = this.getLoadedSourceVariable();
        if (loadedSource != null) {
            WiredVariableStore.VariableDefinition variable = new WiredVariableStore.VariableDefinition(
                    loadedSource.getId(),
                    loadedSource.getRoomId(),
                    loadedSource.getType().code,
                    loadedSource.getVariableName(),
                    loadedSource.getPersistence());
            SourceDefinition definition = new SourceDefinition(
                    variable,
                    loadedSource.getType(),
                    loadedSource.hasValue());
            SOURCE_DEFINITIONS.put(key, definition);
            return definition;
        }

        SourceDefinition cached = SOURCE_DEFINITIONS.get(key);
        if (cached != null) {
            return cached;
        }

        // Definition metadata is enough to address the centralized value store.
        String interactionType = this.getType() == WiredVariableType.USER ? "wf_var_user" : "wf_var_room";
        String query = "SELECT items.id, items.wired_data " +
                "FROM items " +
                "INNER JOIN rooms ON rooms.id = items.room_id " +
                "INNER JOIN items_base ON items_base.id = items.item_id " +
                "WHERE items.room_id = ? AND rooms.owner_id = ? AND items_base.interaction_type = ?";

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, this.sourceRoomId);
            statement.setInt(2, this.ownerId);
            statement.setString(3, interactionType);

            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    VariableBoxData data = readVariableBoxData(set.getString("wired_data"));
                    if (data == null || data.persistence != WiredVariablePersistence.SHARED_PERMANENT.code ||
                            !this.sourceVariableName.equals(WiredVariableName.normalize(data.name))) {
                        continue;
                    }

                    WiredVariableStore.VariableDefinition variable = new WiredVariableStore.VariableDefinition(
                            set.getInt("id"),
                            this.sourceRoomId,
                            this.getType().code,
                            this.sourceVariableName,
                            WiredVariablePersistence.SHARED_PERMANENT);
                    SourceDefinition definition = new SourceDefinition(variable, this.getType(), data.hasValue);
                    SourceDefinition existing = SOURCE_DEFINITIONS.putIfAbsent(key, definition);
                    return existing == null ? definition : existing;
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
        }

        return null;
    }

    private void refreshLoadedSource(SourceDefinition definition, int ownerId) {
        InteractionWiredVariable source = this.getLoadedSourceVariable();
        if (source == null || definition == null || source.getId() != definition.variable.itemId) {
            return;
        }

        int ownerType = definition.type == WiredVariableType.USER
                ? WiredVariableStore.OWNER_USER
                : WiredVariableStore.OWNER_ROOM;
        int storedOwnerId = ownerType == WiredVariableStore.OWNER_USER ? ownerId : 0;
        WiredVariableStore.StoredValue storedValue = WiredVariableStore.loadStoredValue(
                definition.variable,
                ownerType,
                storedOwnerId);
        source.refreshSharedValue(storedOwnerId, storedValue);
    }

    private void broadcastGlobalChange(SourceDefinition definition, WiredVariableMutationReceipt receipt) {
        if (definition == null || definition.type != WiredVariableType.GLOBAL ||
                receipt == null || !receipt.committed()) {
            return;
        }

        int action = receipt.status == WiredVariableMutationReceipt.Status.CREATED
                ? VARIABLE_ACTION_CREATED
                : this.changeAction(receipt.oldValue, receipt.newValue);
        notifyLoadedGlobalConsumers(
                this.ownerId,
                definition.variable.roomId,
                definition.variable.itemId,
                definition.variable.variableName,
                action,
                receipt.oldValue,
                receipt.newValue,
                true);
    }

    public static void broadcastGlobalChangeFromSource(InteractionWiredVariable source, int action,
                                                       long oldValue, long newValue) {
        if (source == null || source.getType() != WiredVariableType.GLOBAL ||
                source.getPersistence() != WiredVariablePersistence.SHARED_PERMANENT) {
            return;
        }

        Room sourceRoom = Emulator.getGameEnvironment().getRoomManager().getRoom(source.getRoomId());
        if (sourceRoom == null || !sourceRoom.isLoaded()) {
            return;
        }

        notifyLoadedGlobalConsumers(
                sourceRoom.getOwnerId(),
                source.getRoomId(),
                source.getId(),
                source.getVariableName(),
                action,
                oldValue,
                newValue,
                false);
    }

    private static void notifyLoadedGlobalConsumers(int ownerId, int sourceRoomId, int sourceItemId,
                                                    String sourceVariableName, int action,
                                                    long oldValue, long newValue, boolean notifySourceRoom) {
        String normalizedName = WiredVariableName.normalize(sourceVariableName);
        if (ownerId <= 0 || sourceRoomId <= 0 || sourceItemId <= 0 || normalizedName.isEmpty()) {
            return;
        }

        for (Room room : Emulator.getGameEnvironment().getRoomManager().getActiveRooms(-1)) {
            if (room == null || !room.isLoaded() || room.getRoomSpecialTypes() == null) {
                continue;
            }

            if (room.getId() == sourceRoomId) {
                if (notifySourceRoom) {
                    InteractionWiredVariable source = room.getRoomSpecialTypes().getVariable(
                            WiredVariableType.GLOBAL,
                            normalizedName);
                    if (source != null && source.getId() == sourceItemId &&
                            source.getPersistence() == WiredVariablePersistence.SHARED_PERMANENT) {
                        source.notifyChangedFromAnotherRoom(
                                WiredVariableStore.OWNER_ROOM,
                                0,
                                action,
                                oldValue,
                                newValue);
                    }
                }
                continue;
            }

            for (InteractionWiredVariable variable : room.getRoomSpecialTypes().getVariables(WiredVariableType.GLOBAL)) {
                if (!(variable instanceof WiredVariableFromAnotherRoom)) {
                    continue;
                }

                WiredVariableFromAnotherRoom reference = (WiredVariableFromAnotherRoom) variable;
                if (reference.ownerId == ownerId &&
                        reference.sourceRoomId == sourceRoomId &&
                        reference.getSourceVariableType() == WiredVariableType.GLOBAL &&
                        reference.sourceVariableName.equals(normalizedName)) {
                    reference.notifyChangedFromAnotherRoom(
                            WiredVariableStore.OWNER_ROOM,
                            0,
                            action,
                            oldValue,
                            newValue);
                }
            }
        }
    }

    public static void invalidateSourceDefinition(int roomId, WiredVariableType type, String variableName) {
        String normalizedName = WiredVariableName.normalize(variableName);
        WiredVariableType normalizedType = normalizeSourceType(type);
        SOURCE_DEFINITIONS.keySet().removeIf(key ->
                key.roomId == roomId &&
                        key.type == normalizedType &&
                        (normalizedName.isEmpty() || key.variableName.equals(normalizedName)));
    }

    public static void invalidateSourceDefinitions(int roomId) {
        SOURCE_DEFINITIONS.keySet().removeIf(key -> key.roomId == roomId);
    }

    private WiredVariableStore.StoredValue getStoredMetadata(boolean ownerScoped, int ownerId) {
        InteractionWiredVariable source = this.getLoadedSourceVariable();
        if (source != null) {
            boolean exists = ownerScoped ? source.hasValue(ownerId) : source.hasValue();
            long value = ownerScoped ? source.getValue(ownerId) : source.getValue();
            long createdAt = ownerScoped ? source.getCreatedAtMs(ownerId) : source.getCreatedAtMs();
            long updatedAt = ownerScoped ? source.getUpdatedAtMs(ownerId) : source.getUpdatedAtMs();
            long revision = ownerScoped ? source.getRevision(ownerId) : source.getRevision();
            return new WiredVariableStore.StoredValue(exists, value, createdAt, updatedAt, revision);
        }

        SourceDefinition definition = this.findSourceDefinition();
        if (definition == null) {
            return new WiredVariableStore.StoredValue(false, 0L, 0L, 0L, 0L);
        }

        int ownerType = ownerScoped && definition.type == WiredVariableType.USER
                ? WiredVariableStore.OWNER_USER
                : WiredVariableStore.OWNER_ROOM;
        int storedOwnerId = ownerType == WiredVariableStore.OWNER_USER ? ownerId : 0;
        return WiredVariableStore.loadStoredValue(definition.variable, ownerType, storedOwnerId);
    }

    private void fireCommittedChange(int ownerType, int ownerId, WiredVariableMutationReceipt receipt) {
        if (receipt == null || !receipt.committed()) {
            return;
        }

        int action = receipt.status == WiredVariableMutationReceipt.Status.CREATED
                ? VARIABLE_ACTION_CREATED
                : this.changeAction(receipt.oldValue, receipt.newValue);
        this.fireVariableChanged(ownerType, ownerId, action, receipt.oldValue, receipt.newValue);
    }

    private List<RoomOption> getSharedRooms(int ownerId) {
        Map<Integer, RoomOption> rooms = new LinkedHashMap<>();

        String query = "SELECT rooms.id AS room_id, rooms.name AS room_name, items_base.interaction_type, items.wired_data " +
                "FROM rooms " +
                "INNER JOIN items ON items.room_id = rooms.id " +
                "INNER JOIN items_base ON items_base.id = items.item_id " +
                "WHERE rooms.owner_id = ? AND items_base.interaction_type IN ('wf_var_room', 'wf_var_user') " +
                "ORDER BY rooms.id DESC, items.id ASC";

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, ownerId);

            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    SharedVariable variable = readSharedVariable(set.getString("interaction_type"), set.getString("wired_data"));
                    if (variable == null) continue;

                    RoomOption room = rooms.computeIfAbsent(set.getInt("room_id"), id -> new RoomOption(id, safeRoomName(set)));
                    room.variables.add(variable);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Caught SQL exception", e);
        }

        for (Integer roomId : new ArrayList<>(rooms.keySet())) {
            this.mergeLoadedSharedRoom(rooms, roomId);
        }

        if (this.sourceRoomId > 0) {
            this.mergeLoadedSharedRoom(rooms, this.sourceRoomId);
        }

        return new ArrayList<>(rooms.values());
    }

    private void mergeLoadedSharedRoom(Map<Integer, RoomOption> rooms, int roomId) {
        Room loadedRoom = Emulator.getGameEnvironment().getRoomManager().getRoom(roomId);
        if (loadedRoom == null || !loadedRoom.isLoaded() || loadedRoom.getRoomSpecialTypes() == null) {
            return;
        }

        RoomOption room = rooms.computeIfAbsent(loadedRoom.getId(), id -> new RoomOption(id, loadedRoom.getName()));
        room.name = loadedRoom.getName();
        room.variables.clear();

        for (InteractionWiredVariable variable : loadedRoom.getRoomSpecialTypes().getVariables()) {
            SharedVariable sharedVariable = readSharedVariable(variable);
            if (sharedVariable == null) continue;
            if (room.variables.stream().anyMatch(existing -> existing.type == sharedVariable.type && existing.name.equals(sharedVariable.name))) continue;

            room.variables.add(sharedVariable);
        }
    }

    private static SharedVariable readSharedVariable(String interactionType, String wiredData) {
        VariableBoxData data = readVariableBoxData(wiredData);
        if (data == null || data.persistence != WiredVariablePersistence.SHARED_PERMANENT.code) {
            return null;
        }

        String name = WiredVariableName.normalize(data.name);
        if (!WiredVariableName.isValid(name)) {
            return null;
        }

        WiredVariableType type = "wf_var_user".equals(interactionType) ? WiredVariableType.USER : WiredVariableType.GLOBAL;
        return new SharedVariable(type.code, name);
    }

    private static VariableBoxData readVariableBoxData(String wiredData) {
        if (wiredData == null || !wiredData.startsWith("{")) {
            return null;
        }

        try {
            return WiredManager.getGson().fromJson(wiredData, VariableBoxData.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedVariable readSharedVariable(InteractionWiredVariable variable) {
        if (variable == null || variable instanceof WiredVariableFromAnotherRoom || variable.getPersistence() != WiredVariablePersistence.SHARED_PERMANENT) {
            return null;
        }

        WiredVariableType type = variable.getType();
        if (type != WiredVariableType.GLOBAL && type != WiredVariableType.USER) {
            return null;
        }

        String name = WiredVariableName.normalize(variable.getVariableName());
        if (!WiredVariableName.isValid(name)) {
            return null;
        }

        return new SharedVariable(type.code, name);
    }

    private static String safeRoomName(ResultSet set) {
        try {
            return set.getString("room_name");
        } catch (SQLException e) {
            return "";
        }
    }

    private static WiredVariableType normalizeSourceType(WiredVariableType type) {
        return type == WiredVariableType.USER ? WiredVariableType.USER : WiredVariableType.GLOBAL;
    }

    public static ReferenceSaveData readSaveData(String value) {
        if (value == null || !value.startsWith("{")) {
            return new ReferenceSaveData();
        }

        try {
            ReferenceSaveData data = WiredManager.getGson().fromJson(value, ReferenceSaveData.class);
            if (data == null) return new ReferenceSaveData();

            data.name = WiredVariableName.normalize(data.name);
            data.sourceVariableName = WiredVariableName.normalize(data.sourceVariableName);
            data.sourceVariableType = normalizeSourceType(WiredVariableType.fromCode(data.sourceVariableType)).code;
            return data;
        } catch (Exception ignored) {
            return new ReferenceSaveData();
        }
    }

    public static void retargetReferences(int ownerId, int sourceRoomId, WiredVariableType sourceVariableType, String oldSourceVariableName, String newSourceVariableName) {
        WiredVariableType normalizedType = normalizeSourceType(sourceVariableType);
        String oldName = WiredVariableName.normalize(oldSourceVariableName);
        String newName = WiredVariableName.normalize(newSourceVariableName);

        if (ownerId <= 0 || sourceRoomId <= 0 || oldName.isEmpty() || !WiredVariableName.isValid(newName) || oldName.equals(newName)) {
            return;
        }

        for (Room room : Emulator.getGameEnvironment().getRoomManager().getActiveRooms(-1)) {
            if (room == null || room.getOwnerId() != ownerId || room.getRoomSpecialTypes() == null) {
                continue;
            }

            for (InteractionWiredVariable variable : room.getRoomSpecialTypes().getVariables()) {
                if (!(variable instanceof WiredVariableFromAnotherRoom)) {
                    continue;
                }

                WiredVariableFromAnotherRoom reference = (WiredVariableFromAnotherRoom) variable;
                if (reference.sourceRoomId == sourceRoomId &&
                        reference.getSourceVariableType() == normalizedType &&
                        reference.sourceVariableName.equals(oldName)) {
                    reference.renameSourceReference(newName);
                    room.getRoomSpecialTypes().refreshVariable(reference);
                    reference.needsUpdate(true);
                    Emulator.getThreading().run(reference);
                    WiredManager.invalidateRoom(room);
                }
            }
        }

        String selectQuery = "SELECT items.id, items.wired_data " +
                "FROM items " +
                "INNER JOIN rooms ON rooms.id = items.room_id " +
                "INNER JOIN items_base ON items_base.id = items.item_id " +
                "WHERE rooms.owner_id = ? AND items_base.interaction_type = 'wf_var_reference' AND items.wired_data <> ''";

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement select = connection.prepareStatement(selectQuery);
             PreparedStatement update = connection.prepareStatement("UPDATE items SET wired_data = ? WHERE id = ?")) {
            select.setInt(1, ownerId);

            try (ResultSet set = select.executeQuery()) {
                while (set.next()) {
                    String wiredData = set.getString("wired_data");
                    if (wiredData == null || !wiredData.startsWith("{")) {
                        continue;
                    }

                    JsonData data = WiredManager.getGson().fromJson(wiredData, JsonData.class);
                    if (data == null ||
                            data.sourceRoomId != sourceRoomId ||
                            normalizeSourceType(WiredVariableType.fromCode(data.sourceVariableType)) != normalizedType ||
                            !oldName.equals(WiredVariableName.normalize(data.sourceVariableName))) {
                        continue;
                    }

                    data.sourceVariableName = newName;

                    update.setString(1, WiredManager.getGson().toJson(data));
                    update.setInt(2, set.getInt("id"));
                    update.addBatch();
                }
            }

            update.executeBatch();
        } catch (Exception e) {
            LOGGER.error("Caught exception while retargeting shared variable references", e);
        }
    }

    public static class ReferenceSaveData {
        public String name = "";
        public int sourceRoomId = 0;
        public int sourceVariableType = WiredVariableType.GLOBAL.code;
        public String sourceVariableName = "";
    }

    static class JsonData {
        String name;
        int sourceRoomId;
        int sourceVariableType;
        String sourceVariableName;
        boolean readOnly;
        int ownerId;

        JsonData(String name, int sourceRoomId, int sourceVariableType, String sourceVariableName, boolean readOnly, int ownerId) {
            this.name = name;
            this.sourceRoomId = sourceRoomId;
            this.sourceVariableType = sourceVariableType;
            this.sourceVariableName = sourceVariableName;
            this.readOnly = readOnly;
            this.ownerId = ownerId;
        }
    }

    static class EditorData {
        int sourceRoomId;
        int sourceVariableType;
        String sourceVariableName;
        List<RoomOption> rooms;

        EditorData(int sourceRoomId, int sourceVariableType, String sourceVariableName, List<RoomOption> rooms) {
            this.sourceRoomId = sourceRoomId;
            this.sourceVariableType = sourceVariableType;
            this.sourceVariableName = sourceVariableName;
            this.rooms = rooms;
        }
    }

    static class RoomOption {
        int id;
        String name;
        List<SharedVariable> variables = new ArrayList<>();

        RoomOption(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static class SharedVariable {
        int type;
        String name;

        SharedVariable(int type, String name) {
            this.type = type;
            this.name = name;
        }
    }

    static class VariableBoxData {
        String name;
        int persistence;
        boolean hasValue;
    }

    static class SourceDefinition {
        final WiredVariableStore.VariableDefinition variable;
        final WiredVariableType type;
        final boolean hasValue;

        SourceDefinition(WiredVariableStore.VariableDefinition variable, WiredVariableType type, boolean hasValue) {
            this.variable = variable;
            this.type = type;
            this.hasValue = hasValue;
        }
    }

    static class SourceKey {
        final int ownerId;
        final int roomId;
        final WiredVariableType type;
        final String variableName;

        SourceKey(int ownerId, int roomId, WiredVariableType type, String variableName) {
            this.ownerId = ownerId;
            this.roomId = roomId;
            this.type = normalizeSourceType(type);
            this.variableName = WiredVariableName.normalize(variableName);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof SourceKey)) return false;
            SourceKey key = (SourceKey) object;
            return this.ownerId == key.ownerId &&
                    this.roomId == key.roomId &&
                    this.type == key.type &&
                    this.variableName.equals(key.variableName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.ownerId, this.roomId, this.type, this.variableName);
        }
    }

    @Override
    protected int defaultChangeOrigin() {
        return CHANGE_ORIGIN_ANOTHER_ROOM;
    }
}
