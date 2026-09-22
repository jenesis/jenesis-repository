/**
 * The scheduled rebuild pass as a plugin module: a discovered
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code rebuild} that drives
 * every discovered {@code WalkConsumer} from <em>one</em> shared enumeration of the pointer roots (the free
 * {@code RebuildPass}, scheduled by {@code jenreg.walks}) - the walk half of the two-route derived-metadata
 * contract made scheduled: steady state stays with the publication events, and this pass is the first-activation
 * back-fill, the periodic refresh and the self-heal, so a consumer plugin enabled late rebuilds its whole view
 * with no operator re-publish or re-import. Degrades gracefully and never silently: with no walk implementation
 * resolved the pass does not schedule at all, and with no consumer discovered nothing is enumerated - either way
 * the capability surfaces say so (the {@code walk} capability flag, this module's row on the modules console). On
 * by default when installed ({@code rebuild=false} switches it off), because the pass without consumers is a no-op
 * and a consumer's own enablement is the real opt-in.
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
    // Unqualified: the overview records are read reflectively by the template engine and the JSON codec.
    exports build.jenesis.repository.walk.task;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.walk.task.RebuildTaskProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.walk.task.WalkSettingsContributor;
}
