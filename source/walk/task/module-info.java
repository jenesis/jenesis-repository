/**
 * The scheduled walks: a {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to
 * {@code rebuild} that drives every discovered {@code WalkConsumer} from one shared enumeration ({@code RebuildPass},
 * scheduled by {@code jenrepo.walks}) - the walk half of the derived-metadata contract: publication events keep the
 * steady state, and this is the first-activation back-fill, the periodic refresh and the self-heal, so a consumer
 * enabled late rebuilds its view with no re-publish. With no walk implementation nothing schedules, and with no
 * consumer nothing is enumerated; the capability surfaces say so. On by default ({@code rebuild=false} switches it
 * off), since without consumers it is a no-op.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.walk.task {
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.scope;
    requires tools.jackson.databind;
    requires spring.context;
    // Unqualified: the template engine and the JSON codec read the overview records reflectively.
    exports build.jenesis.repository.walk.task;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.walk.task.RebuildTaskProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.walk.task.WalkSettingsContributor;
}
