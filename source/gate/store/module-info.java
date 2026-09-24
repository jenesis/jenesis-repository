/**
 * The compliance gate as a discovered publication screen: it provides the
 * {@link build.jenesis.repository.store.PublishInterceptor}, so every artifact that publishes through the
 * {@link build.jenesis.repository.store.Publication} - a format's own deploy handling, a staging promotion, a
 * pull-through caching, an import - is assessed by the same {@link build.jenesis.repository.compliance.ComplianceGate}
 * a deploy controller once wired by hand, and a quarantined path is withheld from every serving surface
 * through the screen's read side. The gate itself stays behind the compliance SPI: this module inspects an upload
 * through the discovered {@link build.jenesis.repository.compliance.QualityInspector}s and routes the verdict; the
 * {@code compliance/*} modules are untouched behind it. The screen is inert until a deployment wires the live gate
 * ({@code ComplianceScreen.live}), so a plain module-path presence never gates a test JVM by accident. Beside the
 * screen: the release and discard primitive every review surface delegates to ({@code HoldLifecycle}), the review
 * queue, the two hold-release observers of the KEV and licence kinds, the retention pass that keeps the quarantine
 * log from growing without bound, the withheld-marker reconcile and listing-rebuild walk consumers, and the
 * settings for their dials.
 *
 * <p>The vocabulary these are written against - the two SPIs, the hold records and kinds, the quarantine log, the
 * dispatch context, the retroactive hold and the guarded clear - is {@code build.jenesis.repository.gate.spi}, the
 * contract half this module requires and never the reverse, so a module that only reacts to a hold requires that
 * and not this. This half keeps the module name the gate always had, on purpose: its settings contributors' dials
 * are stored under {@code config/settings/build.jenesis.repository.gate.json} and its namespace declaration is the
 * manifest entry of that name, and a rename would have stranded both on every deployment that upgraded.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.gate {
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.events;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires org.slf4j;
    exports build.jenesis.repository.gate.store;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.gate.store.ComplianceScreen,
                    build.jenesis.repository.gate.store.OciHoldRecorder;
    provides build.jenesis.repository.gate.HoldReleaseObserver
            with build.jenesis.repository.gate.store.KevHoldReleaseObserver,
                    build.jenesis.repository.gate.store.LicenseHoldReleaseObserver;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.gate.store.QuarantineRetentionTaskProvider;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.gate.store.WithheldReconcileConsumer,
                    build.jenesis.repository.gate.store.ListingRebuildConsumer;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.gate.store.WithheldReconcileConsumer.Observability;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.gate.store.GateStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.gate.store.InspectionSettingsContributor,
                    build.jenesis.repository.gate.store.QuarantineSettingsContributor,
                    build.jenesis.repository.gate.store.WithheldReconcileSettingsContributor,
                    build.jenesis.repository.gate.store.ListingRebuildSettingsContributor;
}
