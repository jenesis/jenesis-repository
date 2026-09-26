/**
 * Tests of the event-webhook recovery HTTP surface's controller directly over a real filesystem artifact store, no
 * server boot and no framework: a parked outbox entry is unparked by a retry (200, the park lifted and the mutation
 * audited), the delivered-endpoint history preserved so no re-delivery duplicates; a retry is idempotent - a second
 * retry of an already-unparked entry is a clean 404, never a duplicate or a 500; a retry of a missing or a
 * not-yet-parked entry is a 404, not a 500; and the status read renders the durably-stored entries only. The
 * write-role gate is the generic /api/ manage:write of RepositoryAuthorizationManager, proven at the server E2E
 * level; here the controller contract - idempotent recovery, history-preserving, clean misses - is proven in
 * isolation.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.webhook.web
 * @jenesis.attach org.mockito
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.webhook.web.test {
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.webhook.web;
    requires build.jenesis.repository.webhook;
    requires build.jenesis.repository.events;
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.audit;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.staging;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires jakarta.servlet;
    requires org.junit.jupiter;
    requires org.assertj.core;
    requires org.mockito;
}
