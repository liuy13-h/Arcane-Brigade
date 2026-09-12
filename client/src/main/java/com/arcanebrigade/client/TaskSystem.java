package com.arcanebrigade.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Properties;

/** Local persistence and presentation state for the lobby task board. */
public final class TaskSystem {
    public enum Category { DAILY, WEEKLY }
    public enum TaskId { DAILY_CHECK_IN, DAILY_KILLS, DAILY_RUNS, WEEKLY_KILLS, WEEKLY_RUNS }
    public record TaskView(TaskId id, String title, String detail, int progress, int target,
                           int reward, boolean claimed) {
        public boolean complete() { return progress >= target; }
        public boolean claimable() { return complete() && !claimed; }
    }

    private final File file;
    private String dailyKey = "", weeklyKey = "";
    private int dailyKills, dailyRuns, weeklyKills, weeklyRuns, dailyClaimed, weeklyClaimed, marks;

    public TaskSystem() {
        this(new File(new File(System.getProperty("user.home"), ".arcanebrigade"), "tasks.properties"));
    }

    TaskSystem(File file) {
        this.file = file;
        load();
        refresh(LocalDate.now());
    }

    public void refresh(LocalDate today) {
        String newDaily = today.toString();
        String newWeekly = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
        boolean changed = false;
        if (!newDaily.equals(dailyKey)) {
            dailyKey = newDaily; dailyKills = 0; dailyRuns = 0; dailyClaimed = 0; changed = true;
        }
        if (!newWeekly.equals(weeklyKey)) {
            weeklyKey = newWeekly; weeklyKills = 0; weeklyRuns = 0; weeklyClaimed = 0; changed = true;
        }
        if (changed) save();
    }

    public TaskView[] tasks(Category category) {
        return switch (category) {
            case DAILY -> new TaskView[] {
                    task(TaskId.DAILY_CHECK_IN, "每日签到", "今日登录一次", 1, 1, 10),
                    task(TaskId.DAILY_KILLS, "清剿威胁", "累计击败敌人", dailyKills, 50, 20),
                    task(TaskId.DAILY_RUNS, "整装出征", "完成一局战斗", dailyRuns, 1, 30),
            };
            case WEEKLY -> new TaskView[] {
                    task(TaskId.WEEKLY_KILLS, "旅团讨伐", "本周累计击败敌人", weeklyKills, 500, 100),
                    task(TaskId.WEEKLY_RUNS, "远征记录", "本周完成战斗局数", weeklyRuns, 5, 150),
            };
        };
    }

    public void recordKills(int count) {
        if (count <= 0) return;
        dailyKills += count; weeklyKills += count; save();
    }

    public void recordCompletedRun() { dailyRuns++; weeklyRuns++; save(); }

    public boolean claim(TaskId id) {
        TaskView view = find(id);
        if (view == null || !view.claimable()) return false;
        if (id.ordinal() <= TaskId.DAILY_RUNS.ordinal()) dailyClaimed |= 1 << id.ordinal();
        else weeklyClaimed |= 1 << (id.ordinal() - TaskId.WEEKLY_KILLS.ordinal());
        marks += view.reward();
        save();
        return true;
    }

    public int marks() { return marks; }
    public int claimableCount() {
        int n = 0;
        for (Category c : Category.values()) for (TaskView t : tasks(c)) if (t.claimable()) n++;
        return n;
    }
    public String[] runBrief() {
        TaskView[] daily = tasks(Category.DAILY);
        return new String[] { "本局任务", daily[1].title() + "  " + Math.min(daily[1].progress(), daily[1].target()) + "/" + daily[1].target(), daily[2].title() + "  " + Math.min(daily[2].progress(), daily[2].target()) + "/" + daily[2].target(), "完成后回到大厅点击领取奖励" };
    }

    private TaskView find(TaskId id) {
        for (Category c : Category.values()) for (TaskView t : tasks(c)) if (t.id() == id) return t;
        return null;
    }
    private TaskView task(TaskId id, String title, String detail, int progress, int target, int reward) {
        boolean claimed = id.ordinal() <= TaskId.DAILY_RUNS.ordinal()
                ? (dailyClaimed & (1 << id.ordinal())) != 0
                : (weeklyClaimed & (1 << (id.ordinal() - TaskId.WEEKLY_KILLS.ordinal()))) != 0;
        return new TaskView(id, title, detail, progress, target, reward, claimed);
    }
    private void load() {
        Properties p = new Properties();
        if (file.isFile()) try (FileInputStream in = new FileInputStream(file)) { p.load(in); } catch (IOException ignored) { }
        dailyKey = p.getProperty("dailyKey", ""); weeklyKey = p.getProperty("weeklyKey", "");
        dailyKills = number(p, "dailyKills"); dailyRuns = number(p, "dailyRuns"); weeklyKills = number(p, "weeklyKills"); weeklyRuns = number(p, "weeklyRuns");
        dailyClaimed = number(p, "dailyClaimed"); weeklyClaimed = number(p, "weeklyClaimed"); marks = number(p, "marks");
    }
    private void save() {
        Properties p = new Properties();
        p.setProperty("dailyKey", dailyKey); p.setProperty("weeklyKey", weeklyKey);
        p.setProperty("dailyKills", String.valueOf(dailyKills)); p.setProperty("dailyRuns", String.valueOf(dailyRuns)); p.setProperty("weeklyKills", String.valueOf(weeklyKills)); p.setProperty("weeklyRuns", String.valueOf(weeklyRuns));
        p.setProperty("dailyClaimed", String.valueOf(dailyClaimed)); p.setProperty("weeklyClaimed", String.valueOf(weeklyClaimed)); p.setProperty("marks", String.valueOf(marks));
        File parent = file.getParentFile(); if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(file)) { p.store(out, "Arcane Brigade tasks"); } catch (IOException ignored) { }
    }
    private static int number(Properties p, String key) {
        try { return Math.max(0, Integer.parseInt(p.getProperty(key, "0"))); } catch (NumberFormatException ignored) { return 0; }
    }
}
