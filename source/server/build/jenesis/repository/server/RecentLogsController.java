package build.jenesis.repository.server;

import module java.base;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The recent-logs tail - {@code GET /api/logs}, the console / CLI / API's one read of the instance's most recent log
 * entries from the bounded in-memory {@link LogRingBuffer ring} a logback appender feeds (never re-reading a file,
 * never unbounded). Supports a {@code level} filter (entries at that level or higher), a {@code q} case-insensitive
 * text search over the logger name and message, a {@code since} cursor a tailing reader passes back (the response
 * carries the current {@code cursor}) to fetch only what is new, an optional {@code tenant} scope - a
 * deployment-wide entry, one no tenant was threaded for, is returned only when no scope is asked - and a
 * {@code limit}. Every parameter is optional, and an empty ring answers an empty list rather than an error.
 *
 * <p>Registered as an explicit {@code @Bean} by {@link RepositoryAutoConfiguration} beside {@link RepositoryController}
 * (Spring MVC maps its handler on the bean), reading the same {@link LogRingBuffer} the {@link LogRingAppender} feeds.
 * It reads every tenant's entries, so {@link RepositoryAuthorizationManager#global} holds it to the operator tenant's
 * {@code manage:read}, as it does the actuator: one route, gated once, in both editions.
 */
@RestController
public final class RecentLogsController {

    /** The default number of entries a read returns when {@code limit} is not supplied. */
    static final int DEFAULT_LIMIT = 200;

    private final LogRingBuffer buffer;

    public RecentLogsController(LogRingBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    /** The tail matching the filters, oldest first, as one document: the {@code cursor} (the highest {@code seq} in
     *  the ring, which a tailing reader passes back as {@code since}), the {@code count} returned, and the
     *  {@code entries}. */
    @GetMapping(value = "/api/logs", produces = "application/json")
    public Map<String, Object> logs(@RequestParam(name = "level", required = false) String level,
                                    @RequestParam(name = "q", required = false) String q,
                                    @RequestParam(name = "since", required = false) Long since,
                                    @RequestParam(name = "tenant", required = false) String tenant,
                                    @RequestParam(name = "limit", required = false) Integer limit) {
        List<LogEntry> entries = buffer.recent(LogRingAppender.levelValue(level), q, since, tenant,
                limit == null ? DEFAULT_LIMIT : limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LogEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", entry.seq());
            row.put("timestamp", entry.timestamp().toString());
            row.put("level", entry.level());
            row.put("logger", entry.logger());
            row.put("message", entry.message());
            row.put("tenant", entry.tenant());
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cursor", buffer.cursor());
        body.put("count", entries.size());
        body.put("entries", rows);
        return body;
    }
}
