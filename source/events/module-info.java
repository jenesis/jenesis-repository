/**
 * The repository event contract: a small, discovered fire-and-forget seam a producer calls when something
 * externally interesting happens to an artifact - an accepted publish, its unpublish counterpart, a gate quarantine,
 * a newly recorded finding, a staging promotion, a hold's release or discard - and a delivery module
 * ({@code webhook}) turns into an outbound notification. Every {@link build.jenesis.repository.events.EventType}
 * constant travels the seam, which is asserted rather than claimed: the publish and unpublish producers are
 * this module's own {@link build.jenesis.repository.events.EventPublicationObserver}, riding the store's
 * after-commit hook, because a producer that wrote into a delivery module's own store would leave every other sink
 * blind to a publish. The
 * emit fan-out ({@link build.jenesis.repository.events.EventSink#emit}) lives here in the SPI home that
 * {@code uses} the service, so a producer names no delivery mechanism and reaches every installed {@link
 * build.jenesis.repository.events.EventSink} through one static; with no sink installed the fan-out is a no-op, so
 * a deployment without the delivery module emits nothing and pays nothing. Emission is best-effort by contract - a
 * sink failure is swallowed, never failing the operation it observes - and the event carries only the small
 * metadata a notification needs (the coordinate, the path, a handful of detail strings), never an artifact body,
 * so the seam is a metadata hop, not a copy.
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
