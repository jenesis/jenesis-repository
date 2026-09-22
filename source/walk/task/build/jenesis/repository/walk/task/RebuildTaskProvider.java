package build.jenesis.repository.walk.task;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Discovers the scheduled walks: on by default when this module is installed ({@code rebuild=false} switches them
 * all off - the neutral scheduler's {@code Features} gate, checked before {@link #tasks(UnaryOperator)} is reached), each on the
 * cron its entry of {@code jenreg.walks} gives it ({@link WalkSchedules}): the {@code rebuild} walk every consumer
 * rides and a standing request runs, weekly by default, and a walk of its own per further entry, carrying the
 * consumers the entry names. A walk is the repair for what a crash left behind and the back-fill for a consumer
 * installed late, never the steady state, which the publication events keep - so without a crash no walk is
 * necessary, and the cadence is for the case nobody noticed. Degrades gracefully, never silently: with no walk
 * implementation resolved there is nothing to enumerate with, so no pass schedules at all; with no
 * {@link WalkConsumer} discovered there is nothing to feed, so none does either - the capability surfaces say so
 * (the {@code walk} flag, this module's console row), and a consumer module installed later is picked up on the
 * next task re-resolve without a restart.
 */
public final class RebuildTaskProvider implements MaintenanceTaskProvider {

    private static final System.Logger LOGGER = System.getLogger(RebuildTaskProvider.class.getName());

    @Override
    public String name() {
        return "rebuild";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        return tasks(config).stream().findFirst();
    }

    /**
     * One task per scheduled walk: the {@code rebuild} task, which every consumer rides and a request runs, on the
     * cron the entry of that name gives it (or never by the clock, when no entry names it); and a task of its own
     * for every other enabled entry, carrying the consumers the entry names. An entry naming no installed consumer
     * schedules nothing and says so once.
     */
    @Override
    public List<MaintenanceTask> tasks(UnaryOperator<String> config) {
        Optional<ArtifactWalk> walk = WalkProvider.resolve(config);
        if (walk.isEmpty()) {
            return List.of();
        }
        List<WalkConsumer> consumers = WalkConsumer.discovered();
        if (consumers.isEmpty()) {
            return List.of();
        }
        List<WalkSchedules.Entry> entries = WalkSchedules.parse(config.apply(WalkSchedules.SETTING));
        List<MaintenanceTask> tasks = new ArrayList<>();
        Optional<WalkSchedules.Entry> rebuild = entries.stream()
                .filter(entry -> entry.name().equals(RebuildTask.REBUILD) && entry.enabled()).findFirst();
        tasks.add(new RebuildTask(RebuildTask.REBUILD, rebuild.orElse(null), walk.get(), consumers));
        for (WalkSchedules.Entry entry : entries) {
            if (!entry.enabled() || entry.name().equals(RebuildTask.REBUILD)) {
                continue;
            }
            List<WalkConsumer> riding = consumers.stream().filter(consumer -> entry.carries(consumer.name())).toList();
            if (riding.isEmpty()) {
                LOGGER.log(System.Logger.Level.INFO, "walk '" + entry.name() + "' names no installed consumer ("
                        + entry.consumers() + "); nothing is scheduled for it");
                continue;
            }
            tasks.add(new RebuildTask(entry.name(), entry, walk.get(), riding));
        }
        return List.copyOf(tasks);
    }
}
