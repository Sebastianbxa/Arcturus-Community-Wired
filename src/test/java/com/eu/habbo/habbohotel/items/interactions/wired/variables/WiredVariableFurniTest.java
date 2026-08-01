package com.eu.habbo.habbohotel.items.interactions.wired.variables;

import com.eu.habbo.habbohotel.wired.WiredVariablePersistence;
import com.eu.habbo.habbohotel.wired.variables.WiredVariableStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiredVariableFurniTest {

    @Test
    void temporaryFurnitureValuesStayInMemoryAndCanBeCleanedUp() throws Exception {
        TestFurniVariable variable = new TestFurniVariable();
        variable.configureForTest("spawn_value", WiredVariablePersistence.PERMANENT);
        Field hasValue = WiredVariableFurni.class.getDeclaredField("hasValue");
        hasValue.setAccessible(true);
        hasValue.setBoolean(variable, true);

        variable.setValue(-1, 42L);

        assertTrue(variable.hasValue(-1));
        assertEquals(42L, variable.getValue(-1));
        assertFalse(WiredVariableStore.hasValue(variable, WiredVariableStore.OWNER_ITEM, -1));

        variable.removeValue(-1);

        assertFalse(variable.hasValue(-1));
        assertEquals(0L, variable.getValue(-1));
    }

    private static final class TestFurniVariable extends WiredVariableFurni {
        private TestFurniVariable() {
            super(1, 1, null, "0", 0, 0);
        }

        private void configureForTest(String name, WiredVariablePersistence persistence) {
            this.setVariableName(name);
            this.setPersistence(persistence);
        }

        @Override
        protected boolean canCreateLoadedValue() {
            return true;
        }
    }
}
