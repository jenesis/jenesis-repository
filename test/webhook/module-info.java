/**
 * Tests of event webhooks over a real filesystem artifact store and a real loopback HTTP receiver, no framework:
 * the discovered event sink and the after-commit observer record an outbox entry only when the feature is enabled;
 * the drain delivers each queued event to the subscribing endpoints over HTTP with an HMAC-SHA256 signature and an
 * event-type filter, removing a fully delivered entry, retrying a failing one with backoff and parking it after the
 * cap, and dropping an event no endpoint subscribes to; a repository with no configured endpoint accumulates no
 * outbox state; and the payload carries the authoritative tenant/repository stamped by the pass. The payload is
 * small metadata, never an artifact, and every bit of state is a store object.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.webhook
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.webhook.test {
    requires build.jenesis.repository.webhook;
    requires build.jenesis.repository.events;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires java.net.http;
    requires org.junit.jupiter;
    requires org.assertj.core;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
