package dev.taskweaver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class TaskEngineTest {
    private static int checks;
    private TaskEngineTest() {}

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejected(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        try (TaskEngine engine = new TaskEngine(2)) {
            Map<String, TaskEngine.Result> results = engine.execute(List.of(
                    new TaskEngine.Task("sum", Set.of("left", "right"), inputs ->
                            (int) inputs.get("left") + (int) inputs.get("right")),
                    new TaskEngine.Task("right", Set.of(), inputs -> 20),
                    new TaskEngine.Task("left", Set.of(), inputs -> 22))).get(5, TimeUnit.SECONDS);
            check(results.get("sum").value().equals(42), "dependency values");
            check(List.copyOf(results.keySet()).equals(List.of("sum", "right", "left")), "input order");
            check(results.values().stream().allMatch(r -> r.status() == TaskEngine.Status.SUCCEEDED), "success");
            check(engine.execute(List.of()).get().isEmpty(), "empty graph");

            AtomicBoolean ran = new AtomicBoolean();
            List<TaskEngine.Task> broken = List.of(
                    new TaskEngine.Task("bad", Set.of(), inputs -> { throw new Exception("example"); }),
                    new TaskEngine.Task("child", Set.of("bad"), inputs -> { ran.set(true); return 1; }),
                    new TaskEngine.Task("grandchild", Set.of("child"), inputs -> 2),
                    new TaskEngine.Task("independent", Set.of(), inputs -> 3));
            results = engine.execute(broken).get(5, TimeUnit.SECONDS);
            check(results.get("bad").status() == TaskEngine.Status.FAILED, "failure reported");
            check(results.get("child").status() == TaskEngine.Status.SKIPPED && !ran.get(), "skip child");
            check(results.get("grandchild").status() == TaskEngine.Status.SKIPPED, "skip descendants");
            check(results.get("independent").status() == TaskEngine.Status.SUCCEEDED, "failure isolated");

            rejected(() -> engine.execute(List.of(
                    new TaskEngine.Task("a", Set.of("b"), inputs -> { ran.set(true); return 1; }),
                    new TaskEngine.Task("b", Set.of("a"), inputs -> 2))), "cycle must reject");
            rejected(() -> engine.execute(List.of(new TaskEngine.Task("a", Set.of("missing"), inputs -> 1))), "missing dependency");
            rejected(() -> engine.execute(List.of(new TaskEngine.Task("a", Set.of(), inputs -> 1),
                    new TaskEngine.Task("a", Set.of(), inputs -> 2))), "duplicate id");
            results = engine.execute(List.of(new TaskEngine.Task("null", Set.of(), inputs -> null),
                    new TaskEngine.Task("read", Set.of("null"), inputs -> inputs.containsKey("null")))).get();
            check(results.get("read").value().equals(true), "null dependency values preserved");
        }

        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        try (TaskEngine engine = new TaskEngine(2)) {
            TaskEngine.Action blocking = inputs -> {
                int concurrent = active.incrementAndGet();
                maximum.accumulateAndGet(concurrent, Math::max);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new Exception("test timed out");
                    return 1;
                } finally {
                    active.decrementAndGet();
                }
            };
            var execution = engine.execute(List.of(new TaskEngine.Task("a", Set.of(), blocking),
                    new TaskEngine.Task("b", Set.of(), blocking), new TaskEngine.Task("c", Set.of(), blocking)));
            try {
                check(entered.await(5, TimeUnit.SECONDS), "independent tasks execute in parallel");
                check(maximum.get() == 2, "worker limit enforced");
            } finally {
                release.countDown();
            }
            execution.get(5, TimeUnit.SECONDS);
        }
        rejected(() -> new TaskEngine(0), "invalid worker count");
        rejected(() -> new TaskEngine.Task(" ", Set.of(), inputs -> 1), "blank id");
        TaskEngine closed = new TaskEngine(1);
        closed.close();
        try {
            closed.execute(List.of());
            throw new AssertionError("closed engine accepted work");
        } catch (IllegalStateException expected) {
            checks++;
        }
        System.out.println(checks + " checks passed");
    }
}
