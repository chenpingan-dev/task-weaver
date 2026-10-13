package dev.taskweaver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes a validated dependency graph without blocking workers on dependencies. */
public final class TaskEngine implements AutoCloseable {
    @FunctionalInterface
    public interface Action {
        Object run(Map<String, Object> inputs) throws Exception;
    }

    public record Task(String id, Set<String> dependencies, Action action) {
        public Task {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Task id must not be blank");
            }
            dependencies = Set.copyOf(Objects.requireNonNull(dependencies));
            Objects.requireNonNull(action);
        }
    }

    public enum Status { SUCCEEDED, FAILED, SKIPPED }

    public record Result(String id, Status status, Object value, long elapsedNanos, String error) {}

    private final ExecutorService workers;

    public TaskEngine(int parallelism) {
        if (parallelism < 1 || parallelism > 64) {
            throw new IllegalArgumentException("Parallelism must be between 1 and 64");
        }
        workers = Executors.newFixedThreadPool(parallelism);
    }

    /** Validate the entire graph before scheduling any action. Results keep input order. */
    public CompletableFuture<Map<String, Result>> execute(List<Task> input) {
        if (workers.isShutdown()) {
            throw new IllegalStateException("Task engine is closed");
        }
        List<Task> tasks = List.copyOf(input);
        List<Task> order = validate(tasks);
        Map<String, CompletableFuture<Result>> futures = new LinkedHashMap<>();
        for (Task task : order) {
            List<CompletableFuture<Result>> dependencies = task.dependencies().stream()
                    .sorted().map(futures::get).toList();
            CompletableFuture<Void> ready = CompletableFuture.allOf(
                    dependencies.toArray(CompletableFuture[]::new));
            futures.put(task.id(), ready.thenApplyAsync(ignored -> {
                Map<String, Object> values = new LinkedHashMap<>();
                for (CompletableFuture<Result> dependency : dependencies) {
                    Result result = dependency.join();
                    if (result.status() != Status.SUCCEEDED) {
                        return new Result(task.id(), Status.SKIPPED, null, 0,
                                "Dependency did not succeed: " + result.id());
                    }
                    values.put(result.id(), result.value());
                }
                long start = System.nanoTime();
                try {
                    Object value = task.action().run(Collections.unmodifiableMap(values));
                    return new Result(task.id(), Status.SUCCEEDED, value,
                            System.nanoTime() - start, null);
                } catch (Exception error) {
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return new Result(task.id(), Status.FAILED, null,
                            System.nanoTime() - start,
                            error.getClass().getSimpleName() + ": " + error.getMessage());
                }
            }, workers));
        }
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<String, Result> results = new LinkedHashMap<>();
                    tasks.forEach(task -> results.put(task.id(), futures.get(task.id()).join()));
                    return Collections.unmodifiableMap(results);
                });
    }

    private static List<Task> validate(List<Task> tasks) {
        if (tasks.size() > 1000) {
            throw new IllegalArgumentException("A graph supports at most 1000 tasks");
        }
        Map<String, Task> byId = new LinkedHashMap<>();
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (Task task : tasks) {
            if (byId.putIfAbsent(task.id(), task) != null) {
                throw new IllegalArgumentException("Duplicate task: " + task.id());
            }
            remaining.put(task.id(), task.dependencies().size());
            children.put(task.id(), new ArrayList<>());
        }
        for (Task task : tasks) {
            for (String dependency : task.dependencies()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException("Unknown dependency: " + dependency);
                }
                children.get(dependency).add(task.id());
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        remaining.forEach((id, count) -> { if (count == 0) ready.add(id); });
        List<Task> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            order.add(byId.get(id));
            for (String child : children.get(id)) {
                int count = remaining.compute(child, (key, value) -> value - 1);
                if (count == 0) ready.add(child);
            }
        }
        if (order.size() != tasks.size()) {
            throw new IllegalArgumentException("Dependency cycle detected");
        }
        return order;
    }

    /** Finish accepted work. Close after awaiting execution; this is not cancellation. */
    @Override
    public void close() {
        workers.shutdown();
    }
}
