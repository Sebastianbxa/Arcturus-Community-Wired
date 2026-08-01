package com.eu.habbo.habbohotel.wired.variables;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiredVariableStoreTest {

    @Test
    void onlyPersistentOwnerShapesCanReachMariaDb() {
        assertTrue(WiredVariableStore.canPersistOwner(WiredVariableStore.OWNER_ROOM, 0));
        assertTrue(WiredVariableStore.canPersistOwner(WiredVariableStore.OWNER_USER, 1));
        assertTrue(WiredVariableStore.canPersistOwner(WiredVariableStore.OWNER_ITEM, 1));

        assertFalse(WiredVariableStore.canPersistOwner(WiredVariableStore.OWNER_ROOM, 1));
        assertFalse(WiredVariableStore.canPersistOwner(WiredVariableStore.OWNER_USER, 0));
        assertFalse(WiredVariableStore.canPersistOwner(WiredVariableStore.OWNER_ITEM, 0));
        assertFalse(WiredVariableStore.canPersistOwner(WiredVariableStore.OWNER_ITEM, -1));
        assertFalse(WiredVariableStore.canPersistOwner(99, 1));
    }
}
