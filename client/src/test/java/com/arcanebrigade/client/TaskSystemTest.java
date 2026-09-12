package com.arcanebrigade.client;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;

/** Dependency-free regression test for task progress, claiming, persistence and daily reset. */
public final class TaskSystemTest {
    public static void main(String[] args) throws Exception {
        File state = Files.createTempDirectory("ab-tasks-test").resolve("tasks.properties").toFile();
        TaskSystem tasks = new TaskSystem(state);
        LocalDate day = LocalDate.of(2026, 9, 7);
        tasks.refresh(day);
        check(tasks.claim(TaskSystem.TaskId.DAILY_CHECK_IN), "daily check-in should claim");
        tasks.recordKills(50); tasks.recordCompletedRun();
        check(tasks.claim(TaskSystem.TaskId.DAILY_KILLS), "daily kills should claim");
        check(tasks.claim(TaskSystem.TaskId.DAILY_RUNS), "daily run should claim");
        check(tasks.marks() == 60, "daily rewards should total 60");
        TaskSystem restored = new TaskSystem(state); restored.refresh(day);
        check(restored.marks() == 60, "reward state should persist");
        restored.refresh(day.plusDays(1));
        check(restored.tasks(TaskSystem.Category.DAILY)[1].progress() == 0, "daily progress should reset");
        check(restored.tasks(TaskSystem.Category.WEEKLY)[0].progress() == 50, "weekly progress should remain");
        System.out.println("OK: TaskSystemTest");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
