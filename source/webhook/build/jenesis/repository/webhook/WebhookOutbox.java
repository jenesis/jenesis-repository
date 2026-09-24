package build.jenesis.repository.webhook;

import module java.base;

import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.outbox.KeyValueLines;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * The webhook outbox: the events still to be delivered to an endpoint, and the parked backlog of those that
 * terminally failed - one {@link build.jenesis.repository.outbox.Outbox} keyed by event id.
 *
 * <p>What is the webhook module's here is the {@link Entry} - the event, its detail and which endpoints have taken
 * it - and its identity, a stamp of the occurrence milli and a digest of the event so distinct events never collide
 * and an exact re-emit within the same milli dedupes to one entry. The id is already a safe object name, so it is
 * stored under itself. The protocol is the shared class's; this file no longer carries a copy of it.
 */
public final class WebhookOutbox extends build.jenesis.repository.outbox.Outbox<WebhookOutbox.Entry> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String PREFIX = WebhookKeys.OUTBOX;

    static final String PARKED = WebhookKeys.PARKED;

    private static final Codec<Entry> CODEC = new Codec<>() {
        @Override
        public byte[] serialise(Entry entry) {
            StringBuilder builder = new StringBuilder();
            KeyValueLines.line(builder, "id", entry.id());
            KeyValueLines.line(builder, "type", entry.type());
            KeyValueLines.line(builder, "ecosystem", entry.ecosystem());
            KeyValueLines.line(builder, "coordinate", entry.coordinate());
            KeyValueLines.line(builder, "version", entry.version());
            KeyValueLines.line(builder, "path", entry.path());
            KeyValueLines.line(builder, "detail", entry.detailJson());
            KeyValueLines.line(builder, "at", entry.occurredAt());
            KeyValueLines.line(builder, "attempts", Integer.toString(entry.attempts()));
            KeyValueLines.line(builder, "next", Long.toString(entry.nextAttemptMillis()));
            KeyValueLines.line(builder, "parked", Boolean.toString(entry.parked()));
            KeyValueLines.line(builder, "error", entry.lastError());
            KeyValueLines.line(builder, "delivered", String.join(",", entry.delivered()));
            KeyValueLines.line(builder, "parkedAt", Long.toString(entry.parkedAtMillis()));
            return builder.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Entry parse(byte[] content) {
            Map<String, String> fields = KeyValueLines.fields(content);
            return new Entry(fields.getOrDefault("id", ""), fields.getOrDefault("type", ""),
                    empty(fields.get("ecosystem")), empty(fields.get("coordinate")), empty(fields.get("version")),
                    empty(fields.get("path")), fields.getOrDefault("detail", "{}"), fields.getOrDefault("at", ""),
                    KeyValueLines.parseInt(fields.get("attempts")), KeyValueLines.parseLong(fields.get("next")),
                    Boolean.parseBoolean(fields.get("parked")), fields.getOrDefault("error", ""),
                    KeyValueLines.members(fields.get("delivered")), KeyValueLines.parseLong(fields.get("parkedAt")));
        }
    };

    public WebhookOutbox(ArtifactStore store) {
        super(store, PREFIX, PARKED, CODEC);
    }

    public record Entry(String id, String type, String ecosystem, String coordinate, String version, String path,
                        String detailJson, String occurredAt, int attempts, long nextAttemptMillis, boolean parked,
                        String lastError, Set<String> delivered, long parkedAtMillis)
            implements build.jenesis.repository.outbox.Outbox.Entry<Entry> {

        public Entry {
            delivered = Set.copyOf(delivered);
        }

        /** A fresh entry for a just-emitted event - no attempts, eligible immediately, nothing delivered. */
        public static Entry fresh(RepositoryEvent event) {
            String detail = JSON.valueToTree(event.detail()).toString();
            String occurredAt = event.at().toString();
            String id = identity(event.type().wire(), event.path(), event.coordinate(), event.version(),
                    detail, event.at().toEpochMilli());
            return new Entry(id, event.type().wire(), event.ecosystem(), event.coordinate(), event.version(),
                    event.path(), detail, occurredAt, 0, 0L, false, "", Set.of(), 0L);
        }

        /** Whether this entry is still eligible for a delivery attempt at {@code nowMillis} - not parked and past
         *  its backoff window. */
        public boolean eligible(long nowMillis) {
            return !parked && nowMillis >= nextAttemptMillis;
        }

        /** This entry with its delivered-endpoint set replaced (delivery progress), every other field preserved. */
        Entry withDelivered(Set<String> endpoints) {
            return new Entry(id, type, ecosystem, coordinate, version, path, detailJson, occurredAt,
                    attempts, nextAttemptMillis, parked, lastError, endpoints, parkedAtMillis);
        }

        /** This entry after a failed delivery pass: one attempt bumped, the backoff window extended exponentially
         *  (doubling {@code baseMillis}, capped at {@code capMillis}) and the entry parked once the attempt cap is
         *  hit, so a terminally-failing delivery stops retrying but stays queued for the status surface. The park
         *  instant is stamped on the transition and then carried, never restamped: a parked entry that keeps being
         *  written over must not have its retention window reset each time, or a backlog would never age out. */
        Entry withFailure(long nowMillis, long baseMillis, long capMillis, int maxAttempts, String error) {
            int next = attempts + 1;
            long backoff = Math.min(capMillis, baseMillis * (1L << Math.min(next - 1, 20)));
            boolean parking = next >= maxAttempts;
            long parkedAt = parking ? (parked && parkedAtMillis > 0 ? parkedAtMillis : nowMillis) : 0L;
            return new Entry(id, type, ecosystem, coordinate, version, path, detailJson, occurredAt,
                    next, nowMillis + backoff, parking, error, delivered, parkedAt);
        }

        /** This entry unparked for another try: the attempt count and backoff cleared and the park lifted, so the next
         *  drain picks it up again - but its already-delivered endpoint set is kept, so an operator retry re-sends only
         *  to the endpoints that never took it, never to those that already did. */
        @Override
        public Entry unparked() {
            return new Entry(id, type, ecosystem, coordinate, version, path, detailJson, occurredAt,
                    0, 0L, false, "", delivered, 0L);
        }
    }

    /** Queue an event for delivery; an exact re-emit (same identity within the same milli) replaces its own pending
     *  note rather than duplicating it. */
    public void record(RepositoryEvent event) throws IOException {
        record(Entry.fresh(event));
    }

    /** A stable, traversal-free object name for an event: its occurrence milli and a digest of its identity, so
     *  distinct events never collide and an exact-duplicate re-emit within the same milli dedupes to one entry. */
    private static String identity(String type, String path, String coordinate, String version, String detail,
                                   long millis) {
        String material = type + '\0' + n(path) + '\0' + n(coordinate) + '\0' + n(version) + '\0' + n(detail);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return millis + "-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                 // SHA-256 is a required JDK algorithm
        }
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
