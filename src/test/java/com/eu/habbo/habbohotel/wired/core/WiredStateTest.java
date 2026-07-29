package com.eu.habbo.habbohotel.wired.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiredStateTest {

    @Test
    void forksShareLineageBudgetAndRunIdButUseIndependentBatchIds() {
        WiredState root = new WiredState(5, 3);
        WiredState first = root.fork();
        WiredState second = root.fork();

        assertEquals(root.runId(), first.runId());
        assertEquals(root.runId(), second.runId());
        assertNotEquals(root.batchId(), first.batchId());
        assertNotEquals(first.batchId(), second.batchId());

        root.step();
        first.step();
        second.step();

        assertEquals(3, root.executionSteps());
        assertEquals(3, first.executionSteps());
        assertFalse(first.canStep());
        assertThrows(WiredLimitException.class, second::step);
        assertTrue(root.isAborted());
        assertTrue(first.isAborted());
    }

    @Test
    void explicitAbortIsInheritedByForks() {
        WiredState root = new WiredState(5, 20);
        root.abort("test abort");

        WiredState fork = root.fork();

        assertTrue(fork.isAborted());
        assertEquals("test abort", fork.abortReason());
        assertThrows(WiredLimitException.class, fork::step);
    }
}
