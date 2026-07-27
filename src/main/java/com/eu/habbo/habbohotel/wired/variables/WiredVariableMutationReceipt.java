package com.eu.habbo.habbohotel.wired.variables;

public final class WiredVariableMutationReceipt {
    public enum Status {
        CREATED,
        CHANGED,
        UNCHANGED,
        CAP_REJECTED,
        PERSISTENCE_FAILED,
        REJECTED
    }

    public final Status status;
    public final long oldValue;
    public final long newValue;
    public final long revision;

    private WiredVariableMutationReceipt(Status status, long oldValue, long newValue, long revision) {
        this.status = status;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.revision = revision;
    }

    public boolean committed() {
        return this.status == Status.CREATED || this.status == Status.CHANGED;
    }

    public static WiredVariableMutationReceipt committed(boolean existed, long oldValue, long newValue, long revision) {
        return new WiredVariableMutationReceipt(existed ? Status.CHANGED : Status.CREATED, oldValue, newValue, revision);
    }

    public static WiredVariableMutationReceipt unchanged(long value, long revision) {
        return new WiredVariableMutationReceipt(Status.UNCHANGED, value, value, revision);
    }

    public static WiredVariableMutationReceipt capRejected(long oldValue, long requestedValue, long revision) {
        return new WiredVariableMutationReceipt(Status.CAP_REJECTED, oldValue, requestedValue, revision);
    }

    public static WiredVariableMutationReceipt persistenceFailed(long oldValue, long requestedValue, long revision) {
        return new WiredVariableMutationReceipt(Status.PERSISTENCE_FAILED, oldValue, requestedValue, revision);
    }

    public static WiredVariableMutationReceipt rejected(long oldValue, long requestedValue, long revision) {
        return new WiredVariableMutationReceipt(Status.REJECTED, oldValue, requestedValue, revision);
    }
}
