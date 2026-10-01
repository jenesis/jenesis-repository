package build.jenesis.repository.walk.task;

import module java.base;

import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The walks a deployment schedules, as {@code jenrepo.walks} holds them: a JSON array of entries, each a name, a cron
 * expression and the consumers riding it, {@code enabled} unless said otherwise. Every walk reads every object at least
 * once - a bill per pass on an object store - so an operator states which walks run, when, and carrying what.
 *
 * <p>The entry named {@code rebuild} schedules the pass every consumer rides, the one a request runs too, so a request
 * and the safety cadence are one task, scope and report. Any other entry is a task, lease and pass scope of its own.
 * Cron is Spring's grammar with seconds ({@code 0 0 3 * * SUN}: Sundays at three), in UTC, on whichever node holds the
 * lease. Consumers are named by {@code WalkConsumer.name()}; {@code *} is every one installed.
 */
public final class WalkSchedules {

    /** The setting; bare key, as every dial's: {@code jenrepo.walks}. */
    public static final String SETTING = "walks";

    /**
     * The entries a deployment gets unless it says otherwise: the safety walk of every consumer on Sunday nights, and
     * retention's daily walk, which runs by itself because a retention policy is a configuration that runs.
     *
     * <p><b>Collection is not daily.</b> A collection costs about 25 reads and 0.7 writes per object held, against
     * about 33 reads and 1.4 writes for every other consumer of a whole walk together, and a write costs twelve to
     * thirteen reads on the hyperscalers - daily, the largest recurring line of an object-store deployment. It rides
     * the weekly entry through {@code "*"}, so space returns within the week for a seventh of the bill; an operator
     * wanting it sooner adds {@code collect} to the daily entry.
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

    /** The entries of {@code setting}, or the {@link #DEFAULT} ones for a blank setting; refused naming the entry and
     *  field when the document is not what an entry needs. */
    public static List<Entry> parse(String setting) {
        String document = setting == null || setting.isBlank() ? DEFAULT : setting;
        JsonNode root;
        try {
            root = JSON.readTree(document);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("jenrepo.walks is not a JSON array of entries: " + malformed.getMessage(),
                    malformed);
        }
        if (!(root instanceof ArrayNode entries)) {
            throw new IllegalArgumentException("jenrepo.walks is a JSON array of {name, cron, consumers, enabled} "
                    + "entries, not " + root.getNodeType());
        }
        List<Entry> parsed = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int index = 0;
        for (JsonNode node : entries) {
            index++;
            if (!(node instanceof ObjectNode entry)) {
                throw new IllegalArgumentException("jenrepo.walks entry " + index + " is not an object");
            }
            String name = entry.path("name").asString("").trim();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("jenrepo.walks entry " + index + ": a name is lower-case letters, "
                        + "digits and hyphens, not '" + name + "'");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("jenrepo.walks names '" + name + "' twice");
            }
            String cron = entry.path("cron").asString("").trim();
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("jenrepo.walks entry '" + name + "': " + invalid.getMessage(),
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
                throw new IllegalArgumentException("jenrepo.walks entry '" + name + "' names no consumer; \"*\" is "
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
