package com.eu.habbo.habbohotel.items.interactions.wired.extra;

import com.eu.habbo.habbohotel.items.interactions.InteractionWiredExtra;
import com.eu.habbo.habbohotel.items.interactions.wired.WiredSettings;
import com.eu.habbo.habbohotel.wired.api.WiredStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiredExtraAggregationTest {

    @Test
    void animationTimesAreAddedAcrossTheStack() throws Exception {
        List<InteractionWiredExtra> extras = new ArrayList<>();
        for (int id = 1; id <= 5; id++) {
            WiredExtraAnimationTime animationTime =
                    new WiredExtraAnimationTime(id, 1, null, "0", 0, 0);
            animationTime.saveData(settings(2000), null);
            extras.add(animationTime);
        }

        assertEquals(10_000, WiredExtraAnimationTime.resolveAnimationTime(stack(extras)));
    }

    @Test
    void executionLimitsContributeIndependentQuotaSlots() throws Exception {
        List<InteractionWiredExtra> extras = new ArrayList<>();
        for (int id = 1; id <= 5; id++) {
            WiredExtraExecutionLimit executionLimit =
                    new WiredExtraExecutionLimit(id, 1, null, "0", 0, 0);
            executionLimit.saveData(settings(1, 4), null);
            extras.add(executionLimit);
        }

        WiredStack stack = stack(extras);
        long now = 1_000L;
        for (int execution = 0; execution < 5; execution++) {
            assertTrue(WiredExtraExecutionLimit.allowExecution(stack, now));
        }
        assertFalse(WiredExtraExecutionLimit.allowExecution(stack, now));
        assertTrue(WiredExtraExecutionLimit.allowExecution(stack, now + 2_001L));
    }

    private static WiredStack stack(List<InteractionWiredExtra> extras) {
        return new WiredStack(
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                extras,
                false,
                false,
                false);
    }

    private static WiredSettings settings(int... intParams) {
        return new WiredSettings(intParams, "", new int[0], 0);
    }
}
