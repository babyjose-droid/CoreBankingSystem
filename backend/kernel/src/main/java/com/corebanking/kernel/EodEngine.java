package com.corebanking.kernel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-of-day orchestrator (US-108, US-109).
 * <ul>
 *   <li>Steps run in order. A step is either a single task or a per-item step (usually per account).</li>
 *   <li><b>Failure isolation:</b> an exception on one item is recorded as an EOD exception and the step carries on.
 *       The step ends COMPLETED_WITH_EXCEPTIONS. Only if more than {@code maxFailureRatio} of items fail does it
 *       count as FAILED (something systemic is wrong).</li>
 *   <li>A FAILED step stops the run; the business date does not move.</li>
 *   <li><b>Restart:</b> completed steps are skipped and, inside a per-item step, items already checkpointed are
 *       skipped, so a rerun never books interest twice (idempotent).</li>
 *   <li>Items are split into partitions processed in parallel on virtual threads.</li>
 * </ul>
 * Persistence is behind {@link Store}; the app provides a JDBC store, tests an in-memory one.
 */
public final class EodEngine {

    public enum StepStatus { PENDING, RUNNING, COMPLETED, COMPLETED_WITH_EXCEPTIONS, FAILED, SKIPPED }
    public enum RunStatus { RUNNING, COMPLETED, COMPLETED_WITH_EXCEPTIONS, FAILED }

    /** Context handed to steps. */
    public record Context(long runId, LocalDate businessDate, String tenant) {}

    public sealed interface Step permits TaskStep, ItemStep {
        String name();
    }

    /** One unit of work, e.g. "advance business date" or "trial balance gate". */
    public non-sealed interface TaskStep extends Step {
        void run(Context ctx) throws Exception;
    }

    /** Work per item, e.g. "accrue interest" per loan account. */
    public non-sealed interface ItemStep extends Step {
        List<String> items(Context ctx) throws Exception;

        void process(Context ctx, String item) throws Exception;

        /** Share of items that may fail before the step is treated as FAILED. Default: never. */
        default double maxFailureRatio() {
            return 1.0;
        }
    }

    /** Persistence of run state, step state, checkpoints and exceptions. */
    public interface Store {
        StepStatus stepStatus(long runId, int stepNo);

        void stepStarted(long runId, int stepNo, String name);

        void stepFinished(long runId, int stepNo, StepStatus status, int processed, int failed);

        boolean isCheckpointed(long runId, int stepNo, String item);

        /** Must be atomic with the item's own business writes where the store supports it. */
        void checkpoint(long runId, int stepNo, String item);

        void recordException(long runId, int stepNo, String stepName, String item, String error);

        void runFinished(long runId, RunStatus status);
    }

    private final List<Step> steps;
    private final Store store;
    private final int partitions;

    public EodEngine(List<Step> steps, Store store, int partitions) {
        if (steps.isEmpty()) throw new IllegalArgumentException("no steps");
        this.steps = List.copyOf(steps);
        this.store = Objects.requireNonNull(store);
        this.partitions = Math.max(1, partitions);
    }

    public RunStatus run(Context ctx) {
        boolean anyExceptions = false;
        for (int i = 0; i < steps.size(); i++) {
            int stepNo = i + 1;
            Step step = steps.get(i);
            StepStatus prior = store.stepStatus(ctx.runId(), stepNo);
            if (prior == StepStatus.COMPLETED || prior == StepStatus.SKIPPED) continue;
            if (prior == StepStatus.COMPLETED_WITH_EXCEPTIONS) {
                anyExceptions = true;
                continue;
            }
            store.stepStarted(ctx.runId(), stepNo, step.name());
            StepStatus result = switch (step) {
                case TaskStep t -> runTask(ctx, stepNo, t);
                case ItemStep s -> runItems(ctx, stepNo, s);
            };
            if (result == StepStatus.FAILED) {
                store.runFinished(ctx.runId(), RunStatus.FAILED);
                return RunStatus.FAILED;
            }
            if (result == StepStatus.COMPLETED_WITH_EXCEPTIONS) anyExceptions = true;
        }
        RunStatus done = anyExceptions ? RunStatus.COMPLETED_WITH_EXCEPTIONS : RunStatus.COMPLETED;
        store.runFinished(ctx.runId(), done);
        return done;
    }

    private StepStatus runTask(Context ctx, int stepNo, TaskStep t) {
        try {
            t.run(ctx);
            store.stepFinished(ctx.runId(), stepNo, StepStatus.COMPLETED, 1, 0);
            return StepStatus.COMPLETED;
        } catch (Exception e) {
            store.recordException(ctx.runId(), stepNo, t.name(), "-", message(e));
            store.stepFinished(ctx.runId(), stepNo, StepStatus.FAILED, 0, 1);
            return StepStatus.FAILED;
        }
    }

    private StepStatus runItems(Context ctx, int stepNo, ItemStep s) {
        List<String> items;
        try {
            items = s.items(ctx);
        } catch (Exception e) {
            store.recordException(ctx.runId(), stepNo, s.name(), "-", "could not list items: " + message(e));
            store.stepFinished(ctx.runId(), stepNo, StepStatus.FAILED, 0, 1);
            return StepStatus.FAILED;
        }
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<List<String>> parts = partition(items, partitions);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (List<String> part : parts) {
                futures.add(pool.submit(() -> {
                    for (String item : part) {
                        if (store.isCheckpointed(ctx.runId(), stepNo, item)) {
                            processed.incrementAndGet();
                            continue;
                        }
                        try {
                            s.process(ctx, item);
                            store.checkpoint(ctx.runId(), stepNo, item);
                            processed.incrementAndGet();
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            store.recordException(ctx.runId(), stepNo, s.name(), item, message(e));
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    store.recordException(ctx.runId(), stepNo, s.name(), "-", "partition aborted: " + message(e));
                }
            }
        }
        int total = items.size();
        StepStatus status;
        if (failed.get() == 0) status = StepStatus.COMPLETED;
        else if (total > 0 && (double) failed.get() / total > s.maxFailureRatio()) status = StepStatus.FAILED;
        else status = StepStatus.COMPLETED_WITH_EXCEPTIONS;
        store.stepFinished(ctx.runId(), stepNo, status, processed.get(), failed.get());
        return status;
    }

    static List<List<String>> partition(List<String> items, int n) {
        List<List<String>> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) parts.add(new ArrayList<>());
        for (int i = 0; i < items.size(); i++) parts.get(i % n).add(items.get(i));
        parts.removeIf(List::isEmpty);
        return parts;
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
