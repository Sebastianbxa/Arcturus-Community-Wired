package com.eu.habbo.habbohotel.wired.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks execution state for a wired stack run, providing loop safety and metadata.
 * <p>
 * Each wired stack execution gets its own WiredState instance that tracks:
 * <ul>
 *   <li>A unique run ID for debugging/tracing</li>
 *   <li>Step count to prevent infinite loops</li>
 *   <li>Maximum allowed steps before throwing {@link WiredLimitException}</li>
 * </ul>
 * </p>
 * 
 * <h3>Usage:</h3>
 * <pre>{@code
 * WiredState state = new WiredState(100); // max 100 steps
 * state.step(); // must call before each condition/effect
 * // ... execute condition/effect ...
 * }</pre>
 * 
 * @see WiredLimitException
 * @see WiredContext
 */
public final class WiredState {
    
    /** Stable ID for the complete execution lineage, including signals and delays. */
    private final UUID runId;
    /** ID for one event/mutation batch within the execution lineage. */
    private final UUID batchId;
    private final int maxSteps;
    private int steps = 0;
    private final ExecutionBudget executionBudget;

    // Per-execution context variable storage.
    // Context variables live only for the duration of this signal execution.
    private final Map<String, Long> contextValues = new HashMap<>();
    private final Map<String, Long> contextCreatedAtMs = new HashMap<>();
    private final Map<String, Long> contextUpdatedAtMs = new HashMap<>();
    private final Set<String> contextGiven = new HashSet<>();
    private final Map<String, Map<String, Long>> scopedContextValues = new HashMap<>();
    private final Map<String, Set<String>> scopedContextGiven = new HashMap<>();
    private String contextScopeKey = "";

    /**
     * Create a new wired state with the specified step limit.
     * @param maxSteps maximum number of steps allowed (triggers, conditions, effects)
     */
    public WiredState(int maxSteps) {
        this(maxSteps, defaultExecutionStepLimit(maxSteps), UUID.randomUUID());
    }

    public WiredState(int maxSteps, int maxExecutionSteps) {
        this(maxSteps, maxExecutionSteps, UUID.randomUUID());
    }

    WiredState(int maxSteps, UUID runId) {
        this(maxSteps, defaultExecutionStepLimit(maxSteps), runId);
    }

    WiredState(int maxSteps, int maxExecutionSteps, UUID runId) {
        if (maxSteps <= 0 || maxExecutionSteps <= 0) {
            throw new IllegalArgumentException("Wired step limits must be positive");
        }

        UUID normalizedRunId = runId == null ? UUID.randomUUID() : runId;
        this.runId = normalizedRunId;
        this.batchId = normalizedRunId;
        this.maxSteps = maxSteps;
        this.executionBudget = new ExecutionBudget(maxExecutionSteps);
    }

    private WiredState(int maxSteps, UUID runId, UUID batchId, ExecutionBudget executionBudget) {
        this.runId = runId;
        this.batchId = batchId;
        this.maxSteps = maxSteps;
        this.executionBudget = executionBudget;
    }

    /**
     * Get the unique identifier for this execution run.
     * Useful for debugging and tracing wired execution across logs.
     * @return the run UUID
     */
    public UUID runId() {
        return runId;
    }

    /**
     * Identifies the current event/movement batch. Unlike {@link #runId()}, this
     * changes when execution crosses a signal, delayed effect, or event boundary.
     */
    public UUID batchId() {
        return batchId;
    }

    /**
     * Get the current step count.
     * @return number of steps executed so far
     */
    public int steps() {
        return steps;
    }

    /**
     * Get the maximum allowed steps.
     * @return the step limit
     */
    public int maxSteps() {
        return maxSteps;
    }

    public int executionSteps() {
        return this.executionBudget.steps();
    }

    public int maxExecutionSteps() {
        return this.executionBudget.maxSteps();
    }

    /**
     * Get the time when this execution started.
     * @return start time in milliseconds since epoch
     */
    public long startTimeMs() {
        return this.executionBudget.startTimeMs();
    }

    /**
     * Get the elapsed time since execution started.
     * @return elapsed time in milliseconds
     */
    public long elapsedMs() {
        return System.currentTimeMillis() - this.executionBudget.startTimeMs();
    }

    /**
     * Check if the execution has been aborted.
     * @return true if aborted
     */
    public boolean isAborted() {
        return this.executionBudget.isAborted();
    }

    /**
     * Get the reason for abortion, if any.
     * @return the abort reason, or null if not aborted
     */
    public String abortReason() {
        return this.executionBudget.abortReason();
    }

    /**
     * Increment the step counter and check for limit violation.
     * Call this before each trigger match, condition evaluation, or effect execution.
     * 
     * @throws WiredLimitException if the step limit has been exceeded
     */
    public void step() {
        if (this.executionBudget.isAborted()) {
            throw new WiredLimitException("Wired execution was aborted: " + this.executionBudget.abortReason());
        }
        
        steps++;
        if (steps > maxSteps) {
            throw new WiredLimitException(
                    "Wired execution exceeded max steps: " + maxSteps + 
                    " (runId: " + runId + ")");
        }

        this.executionBudget.step(this.runId);
    }

    /**
     * Check if we can still execute more steps without throwing.
     * @return true if more steps are allowed
     */
    public boolean canStep() {
        return steps < maxSteps && this.executionBudget.canStep();
    }

    /**
     * Get remaining steps before hitting the limit.
     * @return number of remaining steps
     */
    public int remainingSteps() {
        return Math.min(
                Math.max(0, maxSteps - steps),
                this.executionBudget.remainingSteps());
    }

    public int remainingExecutionSteps() {
        return this.executionBudget.remainingSteps();
    }

    /**
     * Abort this execution with a reason.
     * Subsequent calls to {@link #step()} will throw.
     * @param reason the reason for aborting
     */
    public void abort(String reason) {
        this.executionBudget.abort(reason);
    }

    /**
     * Reset the step counter (use with caution).
     * This is mainly for testing purposes.
     */
    public void reset() {
        this.steps = 0;
        this.executionBudget.reset();
    }

    // =========== Context Variable Access ===========

    /**
     * Give a context variable a value for this execution.
     * If {@code overrideExisting} is false and the variable was already given, the call is a no-op.
     *
     * @param name             variable name
     * @param value            numeric value to assign
     * @param overrideExisting if true, replaces any previously given value
     */
    public void giveContextValue(String name, long value, boolean overrideExisting) {
        if (name == null || name.isEmpty()) return;

        Set<String> given = scopedGiven();
        if (!overrideExisting && given.contains(name)) {
            return;
        }

        long now = System.currentTimeMillis();
        contextCreatedAtMs.putIfAbsent(name, now);
        contextUpdatedAtMs.put(name, now);
        scopedValues().put(name, value);
        given.add(name);
    }

    /**
     * Directly set a context variable value (always overwrites).
     *
     * @param name  variable name
     * @param value numeric value
     */
    public void setContextValue(String name, long value) {
        if (name == null || name.isEmpty()) return;
        long now = System.currentTimeMillis();
        contextCreatedAtMs.putIfAbsent(name, now);
        contextUpdatedAtMs.put(name, now);
        scopedValues().put(name, value);
        scopedGiven().add(name);
    }

    /**
     * Check whether a context variable has been given a value this execution.
     *
     * @param name variable name
     * @return true if the variable was given via giveContextValue / setContextValue
     */
    public boolean hasContextValue(String name) {
        if (name == null) return false;
        return scopedGiven().contains(name) || (!this.contextScopeKey.isEmpty() && contextGiven.contains(name));
    }

    /**
     * Get the numeric value of a context variable.
     * Returns 0 if the variable was never given or tracks no value.
     *
     * @param name variable name
     * @return the stored long value, or 0
     */
    public long getContextValue(String name) {
        if (name == null) return 0L;
        Map<String, Long> values = scopedValues();
        if (values.containsKey(name)) {
            return values.getOrDefault(name, 0L);
        }

        return this.contextScopeKey.isEmpty() ? 0L : contextValues.getOrDefault(name, 0L);
    }

    public long getContextCreatedAtMs(String name) {
        if (name == null) return 0L;
        return contextCreatedAtMs.getOrDefault(name, 0L);
    }

    public long getContextUpdatedAtMs(String name) {
        if (name == null) return 0L;
        return contextUpdatedAtMs.getOrDefault(name, 0L);
    }

    /**
     * Remove a context variable from this execution, as if it was never given.
     *
     * @param name variable name
     */
    public void removeContextValue(String name) {
        if (name == null) return;
        scopedValues().remove(name);
        contextCreatedAtMs.remove(name);
        contextUpdatedAtMs.remove(name);
        scopedGiven().remove(name);
    }

    public Map<String, Long> contextValuesSnapshot() {
        Map<String, Long> snapshot = new HashMap<>();
        if (!this.contextScopeKey.isEmpty()) {
            snapshot.putAll(contextValues);
        }
        snapshot.putAll(scopedValues());
        return snapshot;
    }

    public void importContextValues(Map<String, Long> values, boolean overrideExisting) {
        if (values == null || values.isEmpty()) return;

        Map<String, Long> targetValues = scopedValues();
        Set<String> targetGiven = scopedGiven();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
            if (!overrideExisting && targetGiven.contains(entry.getKey())) continue;

            long now = System.currentTimeMillis();
            contextCreatedAtMs.putIfAbsent(entry.getKey(), now);
            contextUpdatedAtMs.put(entry.getKey(), now);
            targetValues.put(entry.getKey(), entry.getValue() == null ? 0L : entry.getValue());
            targetGiven.add(entry.getKey());
        }
    }

    public void setContextScope(String scopeKey) {
        this.contextScopeKey = scopeKey == null ? "" : scopeKey;
    }

    public String contextScope() {
        return this.contextScopeKey;
    }

    public Map<String, Map<String, Long>> scopedContextValuesSnapshot() {
        Map<String, Map<String, Long>> snapshot = new HashMap<>();
        snapshot.put("", new HashMap<>(contextValues));

        for (Map.Entry<String, Map<String, Long>> entry : scopedContextValues.entrySet()) {
            snapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        return snapshot;
    }

    public void importScopedContextValues(Map<String, Map<String, Long>> values, boolean overrideExisting) {
        if (values == null || values.isEmpty()) return;

        String previousScope = this.contextScopeKey;
        for (Map.Entry<String, Map<String, Long>> entry : values.entrySet()) {
            this.setContextScope(entry.getKey());
            this.importContextValues(entry.getValue(), overrideExisting);
        }
        this.setContextScope(previousScope);
    }

    public WiredState fork() {
        return this.fork(UUID.randomUUID());
    }

    WiredState fork(UUID sharedRunId) {
        UUID forkBatchId = sharedRunId == null ? UUID.randomUUID() : sharedRunId;
        WiredState forked = new WiredState(
                this.maxSteps,
                this.runId,
                forkBatchId,
                this.executionBudget);
        forked.setContextScope(this.contextScopeKey);
        forked.importScopedContextValues(this.scopedContextValuesSnapshot(), true);
        return forked;
    }

    static int defaultExecutionStepLimit(int maxSteps) {
        return (int) Math.min(
                Integer.MAX_VALUE,
                Math.max((long) maxSteps, (long) maxSteps * 10L));
    }

    private Map<String, Long> scopedValues() {
        if (this.contextScopeKey.isEmpty()) {
            return contextValues;
        }

        return scopedContextValues.computeIfAbsent(this.contextScopeKey, key -> new HashMap<>());
    }

    private Set<String> scopedGiven() {
        if (this.contextScopeKey.isEmpty()) {
            return contextGiven;
        }

        return scopedContextGiven.computeIfAbsent(this.contextScopeKey, key -> new HashSet<>());
    }

    @Override
    public String toString() {
        return "WiredState{" +
                "runId=" + runId +
                ", batchId=" + batchId +
                ", steps=" + steps + "/" + maxSteps +
                ", executionSteps=" + this.executionBudget.steps() + "/" + this.executionBudget.maxSteps() +
                ", elapsed=" + elapsedMs() + "ms" +
                (this.executionBudget.isAborted() ? ", ABORTED: " + this.executionBudget.abortReason() : "") +
                '}';
    }

    private static final class ExecutionBudget {
        private final int maxSteps;
        private final AtomicInteger steps = new AtomicInteger();
        private final AtomicReference<String> abortReason = new AtomicReference<>();
        private volatile long startTimeMs = System.currentTimeMillis();

        private ExecutionBudget(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        private void step(UUID runId) {
            int currentSteps = this.steps.incrementAndGet();
            if (currentSteps > this.maxSteps) {
                String reason = "Wired execution exceeded max lineage steps: " + this.maxSteps
                        + " (runId: " + runId + ")";
                this.abortReason.compareAndSet(null, reason);
                throw new WiredLimitException(reason);
            }
        }

        private int steps() {
            return this.steps.get();
        }

        private int maxSteps() {
            return this.maxSteps;
        }

        private boolean canStep() {
            return !this.isAborted() && this.steps.get() < this.maxSteps;
        }

        private int remainingSteps() {
            return Math.max(0, this.maxSteps - this.steps.get());
        }

        private long startTimeMs() {
            return this.startTimeMs;
        }

        private boolean isAborted() {
            return this.abortReason.get() != null;
        }

        private String abortReason() {
            return this.abortReason.get();
        }

        private void abort(String reason) {
            this.abortReason.compareAndSet(null, reason == null ? "Aborted" : reason);
        }

        private void reset() {
            this.steps.set(0);
            this.abortReason.set(null);
            this.startTimeMs = System.currentTimeMillis();
        }
    }
}
