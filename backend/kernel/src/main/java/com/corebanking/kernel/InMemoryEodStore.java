package com.corebanking.kernel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Reference {@link EodEngine.Store} used by tests and by the standalone "dry run" command. */
public final class InMemoryEodStore implements EodEngine.Store {

    public record EodError(int stepNo, String step, String item, String error) {}

    public final Map<Integer, EodEngine.StepStatus> steps = new ConcurrentHashMap<>();
    public final Map<Integer, int[]> counts = new ConcurrentHashMap<>();
    public final Set<String> checkpoints = ConcurrentHashMap.newKeySet();
    public final List<EodError> exceptions = new CopyOnWriteArrayList<>();
    public volatile EodEngine.RunStatus runStatus;

    @Override public EodEngine.StepStatus stepStatus(long runId, int stepNo) {
        return steps.getOrDefault(stepNo, EodEngine.StepStatus.PENDING);
    }
    @Override public void stepStarted(long runId, int stepNo, String name) {
        steps.put(stepNo, EodEngine.StepStatus.RUNNING);
    }
    @Override public void stepFinished(long runId, int stepNo, EodEngine.StepStatus status, int processed, int failed) {
        steps.put(stepNo, status);
        counts.put(stepNo, new int[] {processed, failed});
    }
    @Override public boolean isCheckpointed(long runId, int stepNo, String item) {
        return checkpoints.contains(stepNo + "|" + item);
    }
    @Override public void checkpoint(long runId, int stepNo, String item) {
        checkpoints.add(stepNo + "|" + item);
    }
    @Override public void recordException(long runId, int stepNo, String stepName, String item, String error) {
        exceptions.add(new EodError(stepNo, stepName, item, error));
    }
    @Override public void runFinished(long runId, EodEngine.RunStatus status) {
        runStatus = status;
    }
}
