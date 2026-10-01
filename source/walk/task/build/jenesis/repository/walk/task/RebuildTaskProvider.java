package build.jenesis.repository.walk.task;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Discovers the scheduled walks: on by default when installed ({@code rebuild=false} switches all off, the scheduler's
 * {@code Features} gate), each on the cron its {@code jenrepo.walks} entry gives it ({@link WalkSchedules}): the
 * {@code rebuild} walk every consumer rides and a request runs, weekly by default, and one walk per further entry
 * carrying the consumers it names. A walk is the repair for what a crash left and the back-fill for a consumer
 * installed late; publication events keep the steady state. With no walk implementation, or no {@link WalkConsumer},
 * nothing schedules, and the capability surfaces say so; a consumer module installed later is picked up on the next
 * re-resolve without a restart.
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

    /** One task per scheduled walk: {@code rebuild}, which every consumer rides and a request runs, on its entry's cron
     *  (or never by the clock without one), and one task per other enabled entry carrying its consumers. An entry
     *  naming no installed consumer schedules nothing and says so once. */
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
