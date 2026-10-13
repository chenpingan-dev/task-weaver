package dev.taskweaver;

import java.util.List;
import java.util.Set;

public final class Demo {
    private Demo() {}

    public static void main(String[] args) throws Exception {
        boolean chinese = args.length > 0 && args[0].equals("--zh");
        List<TaskEngine.Task> tasks = List.of(
                new TaskEngine.Task("extract", Set.of(), inputs -> List.of(12, 18, 24)),
                new TaskEngine.Task("total", Set.of("extract"), inputs ->
                        ((List<?>) inputs.get("extract")).stream().mapToInt(v -> (Integer) v).sum()),
                new TaskEngine.Task("count", Set.of("extract"), inputs ->
                        ((List<?>) inputs.get("extract")).size()),
                new TaskEngine.Task("report", Set.of("total", "count"), inputs ->
                        "total=" + inputs.get("total") + ", count=" + inputs.get("count")));
        try (TaskEngine engine = new TaskEngine(2)) {
            System.out.println(chinese ? "TASK WEAVER  /  任务执行结果" : "TASK WEAVER  /  Execution results");
            for (TaskEngine.Result result : engine.execute(tasks).get().values()) {
                System.out.printf("%-10s %-10s %s%n", result.id(), result.status(), result.value());
            }
        }
    }
}
