/**
 * The repository event contract: a discovered fire-and-forget seam a producer calls when something externally
 * interesting happens to an artifact - a publish, an unpublish, a quarantine, a finding, a promotion, a hold's release
 * or discard - and a delivery module turns into a notification. The publish and unpublish producers are this module's
 * {@link build.jenesis.repository.events.EventPublicationObserver}, so every sink sees them. The fan-out
 * ({@link build.jenesis.repository.events.EventSink#emit}) lives here in the SPI home, so a producer reaches every
 * installed sink through one static, a no-op with none. Emission is best-effort and carries metadata, never a body.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.events {
    requires transitive build.jenesis.repository.store;
    requires org.slf4j;
    exports build.jenesis.repository.events;
    uses build.jenesis.repository.events.EventSink;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.events.EventPublicationObserver;
}
