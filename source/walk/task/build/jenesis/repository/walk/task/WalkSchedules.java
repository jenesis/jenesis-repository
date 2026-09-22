package build.jenesis.repository.walk.task;

import module java.base;

import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The walks a deployment schedules, as the {@code jenreg.walks} setting holds them: a JSON array of entries, each a
 * name, a cron expression and the consumers that ride it, {@code enabled} unless said otherwise. A walk is a
 * configured thing because it costs: every object the store holds is read at least once per walk, and on an
 * object store that is a bill per pass, so an operator states which walks run, when, and carrying what.
 *
 * <p>The entry named {@code rebuild} schedules the pass every consumer rides - the one a request runs too, so
 * an operator's or a crash's request and the safety cadence are one task, one scope, one report. Any other entry
 * is a task, a lease and a pass scope of its own, walking only for the consumers it names. Cron is Spring's
 * grammar with seconds ({@code 0 0 3 * * SUN}: Sundays at three), evaluated in UTC on whichever node holds the
 * lease. Consumers are named by their {@code WalkConsumer.name()}; {@code *} is every consumer installed.
 */
public final class WalkSchedules {

    /** The setting; bare key, as every dial's: {@code jenreg.walks}. */
    public static final String SETTING = "walks";

    /**
     * The two entries a deployment gets unless it says otherwise: the safety walk of every consumer on Sunday
     * nights, and retention's daily walk - which runs by itself because a retention policy is a configuration
     * that runs, and which an operator opts out of by removing the entry.
     *
     * <p><b>Collection is not among the daily consumers, deliberately.</b> It is the expensive one: measured
     * 2026-09-08 over twenty thousand objects, a collection costs about 25 reads and 0.7 writes per object held,
     * where every other consumer of a whole walk together costs about 33 reads and 1.4 writes - and on the
     * hyperscalers a write is priced at twelve to thirteen reads. Daily, that is the largest recurring line a
     * deployment on an object store has, larger than serving its users. It rides the weekly {@code rebuild}
     * entry instead, which reaches it through {@code "*"}: space comes back within the week rather than within
     * the day, and the bill is a seventh. An operator who would rather have the space back sooner adds
     * {@code collect} to the daily entry, which is why the schedule is a setting.
     */
    public static final String DEFAULT = "[{\"name\":\"rebuild\",\"cron\":\"0 0 3 * * SUN\",\"consumers\":[\"*\"]},"
            + "{\"name\":\"retention\",\"cron\":\"0 0 3 * * *\",\"consumers\":[\"retention-sweep\",\"rollup\"]}]";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One scheduled walk. */
    public record Entry(String name, String cron, List<String> consumers, boolean enabled) {

        public Entry {
            consumers = List.copyOf(consumers);
        }

        /** The cron expression, parsed. */
        public CronExpression schedule() {
            return CronExpression.parse(cron);
        }

        /** The next moment after {@code after} this entry's schedule names, in UTC; empty when it names none. */
        public Optional<Instant> next(Instant after) {
            ZonedDateTime moment = schedule().next(after.atZone(ZoneOffset.UTC));
            return Optional.ofNullable(moment).map(ZonedDateTime::toInstant);
        }

        /** Whether this entry carries every installed consumer. */
        public boolean every() {
            return consumers.contains("*");
        }

        /** Whether this entry carries the consumer {@code name}. */
        public boolean carries(String name) {
            return every() || consumers.contains(name);
        }
    }

    private WalkSchedules() {
    }

    /** The entries of {@code setting}, the {@link #DEFAULT} ones for a blank setting; refused with the entry and the
     *  field named when the document is not what an entry needs. */
    public static List<Entry> parse(String setting) {
        String document = setting == null || setting.isBlank() ? DEFAULT : setting;
        JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("jenreg.walks is not a JSON array of entries: " + malformed.getMessage(),
                    malformed);
        }
        if (!(root instanceof ArrayNode entries)) {
            throw new IllegalArgumentException("jenreg.walks is a JSON array of {name, cron, consumers, enabled} "
                    + "entries, not " + root.getNodeType());
        }
        List<Entry> parsed = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int index = 0;
        for (JsonNode node : entries) {
            index++;
            if (!(node instanceof ObjectNode entry)) {
                throw new IllegalArgumentException("jenreg.walks entry " + index + " is not an object");
            }
            String name = entry.path("name").asString("").trim();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("jenreg.walks entry " + index + ": a name is lower-case letters, "
                        + "digits and hyphens, not '" + name + "'");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("jenreg.walks names '" + name + "' twice");
            }
            String cron = entry.path("cron").asString("").trim();
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("jenreg.walks entry '" + name + "': " + invalid.getMessage(),
                        invalid);
            }
            List<String> consumers = new ArrayList<>();
            for (JsonNode consumer : entry.path("consumers")) {
                String value = consumer.asString("").trim();
                if (!value.isEmpty()) {
                    consumers.add(value);
                }
            }
            if (consumers.isEmpty()) {
                throw new IllegalArgumentException("jenreg.walks entry '" + name + "' names no consumer; \"*\" is "
                        + "every consumer installed");
            }
            parsed.add(new Entry(name, cron, consumers, entry.path("enabled").asBoolean(true)));
        }
        return List.copyOf(parsed);
    }

    /** The entries as a JSON document - what the console writes back. */
    public static String render(List<Entry> entries) {
        ArrayNode array = JSON.createArrayNode();
        for (Entry entry : entries) {
            ObjectNode node = array.addObject();
            node.put("name", entry.name());
            node.put("cron", entry.cron());
            ArrayNode consumers = node.putArray("consumers");
            entry.consumers().forEach(consumers::add);
            node.put("enabled", entry.enabled());
        }
        return array.toString();
    }
}
