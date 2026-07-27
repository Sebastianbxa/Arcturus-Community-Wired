package com.eu.habbo.habbohotel.wired.variables;

import java.math.BigInteger;

public final class WiredVariableNumbers {
    private WiredVariableNumbers() {
    }

    /**
     * Parse an arbitrary-size integer using two's-complement low-64-bit
     * semantics. This matches the wraparound produced by Java long arithmetic.
     */
    public static long parseWrappingLong(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || !normalized.matches("-?[0-9]+")) {
            throw new NumberFormatException("Not an integer");
        }
        return new BigInteger(normalized).longValue();
    }
}
