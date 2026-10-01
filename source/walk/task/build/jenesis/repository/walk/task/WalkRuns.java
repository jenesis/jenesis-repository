package build.jenesis.repository.walk.task;

import module java.base;

import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * What the three surfaces do with the walks, once: ask for a walk now, read what each entry's last run cost, and
 * assemble the overview a screen or API answer renders - entries, consumers with their descriptions and dials, standing
 * requests. The console screen and the admin endpoint both reach this class, and the CLI is the endpoint's client.
 */
public final class WalkRuns {

    /** The product-owned space each entry's last run is recorded under. */
    static final String SPACE = "walks";

    private WalkRuns() {
    }

    /** Ask for a walk of the store now, on the operator's behalf, and record who asked. */
    public static void request(ArtifactStore root, AuditTrail audit, String tenant, String actor) throws IOException {
        Requests.request(root, Requests.WALK, "requested by " + actor);
        audit.record(tenant, actor, AuditActions.WALKS_RUN, Requests.WALK);
    }

    /** What one entry's last run cost, summed over the repositories it walked: when, how many repositories, and the
     *  objects handed to consumers per family. Its store operations are the scheduler's figure ({@link LastRun}),
     *  measured on the node that ran it. */
    public record Cost(String name, Instant started, Instant finished, long repositories, long pointers,
                       long inventory, long blobs, long derived) {

        public long objects() {
            return pointers + inventory + blobs + derived;
        }

        byte[] encoded() {
            return (started + "\n" + finished + "\n" + repositories + "\n" + pointers + "\n" + inventory + "\n"
                    + blobs + "\n" + derived + "\n").getBytes(StandardCharsets.UTF_8);
        }

        static Cost decode(String name, byte[] content) {
            String[] lines = new String(content, StandardCharsets.UTF_8).split("\n");
            return new Cost(name, Instant.parse(lines[0]), Instant.parse(lines[1]), Long.parseLong(lines[2]),
                    Long.parseLong(lines[3]), Long.parseLong(lines[4]), Long.parseLong(lines[5]),
                    Long.parseLong(lines[6]));
        }
    }

    static String costKey(String name) {
        return Scopes.space(SPACE) + "/" + name + "/last";
    }

    /** Record what an entry's run cost, deployment-wide: the last node to finish a run writes it. */
    static void record(ArtifactStore root, Cost cost) throws IOException {
        root.write(costKey(cost.name()), new ByteArrayInputStream(cost.encoded()));
    }

    /** What the last run of the named entry cost, or empty when none has run. */
    public static Optional<Cost> last(ArtifactStore root, String name) throws IOException {
        return root.readVersioned(costKey(name)).map(versioned -> Cost.decode(name, versioned.content()));
    }

    /** The scheduler's account of an entry's last run on this node: when it finished, how long it took, whether it
     *  failed, and the reads and writes the node issued meanwhile. */
    public record LastRun(Instant finished, Duration duration, boolean failed, long reads, long writes) {
    }

    /** One walk entry as the overview shows it: its schedule, what rides it, when it runs next, and its last run. */
    public record Entry(String name, String cron, boolean enabled, List<String> consumers, Optional<Instant> next,
                        Optional<Cost> cost, Optional<LastRun> run) {
    }

    /** One dial a consumer answers to, with its effective value, for the screen to show beside the checkbox. */
    public record Dial(String key, String label, String description, String value, String defaultValue) {
    }

    /** One installed consumer: its name, what it does and costs, and its dials. */
    public record Consumer(String name, String description, List<Dial> settings) {
    }

    /** A standing request for work, as the overview shows it. */
    public record Pending(String subject, String reason, Instant at) {
    }

    /** The walks as an operator sees them: every entry, every installed consumer, every standing request. */
    public record Overview(List<Entry> entries, List<Consumer> consumers, List<Pending> requests) {
    }

    /**
     * Assemble the overview from the stored walks document, the installed consumers and the scheduler's runs.
     *
     * @param config the effective configuration, through which the walks document and every consumer's dials are read
     * @param root the root store the requests and the runs' accounts live on
     * @param runs the scheduler's last run of a task by name, as this node recorded it
     * @param now what "next" is measured from
     */
    public static Overview overview(UnaryOperator<String> config, ArtifactStore root,
                                    Function<String, Optional<LastRun>> runs, Instant now) throws IOException {
        Map<String, Setting> catalogue = new HashMap<>();
        for (Setting setting : SettingsContributor.all()) {
            catalogue.put(setting.key(), setting);
        }
        List<Consumer> consumers = new ArrayList<>();
        for (WalkConsumer consumer : WalkConsumer.discovered()) {
            List<Dial> dials = new ArrayList<>();
            for (String key : consumer.settings()) {
                Setting setting = catalogue.get(key);
                String value = config.apply(key);
                dials.add(new Dial(key, setting == null ? key : setting.label(),
                        setting == null ? "" : setting.description(),
                        value == null || value.isBlank() ? (setting == null ? "" : setting.defaultValue()) : value,
                        setting == null ? "" : setting.defaultValue()));
            }
            consumers.add(new Consumer(consumer.name(), consumer.description(), List.copyOf(dials)));
        }
        consumers.sort(Comparator.comparing(Consumer::name));
        List<Entry> entries = new ArrayList<>();
        for (WalkSchedules.Entry entry : WalkSchedules.parse(config.apply(WalkSchedules.SETTING))) {
            entries.add(new Entry(entry.name(), entry.cron(), entry.enabled(), entry.consumers(),
                    entry.enabled() ? entry.next(now) : Optional.empty(), last(root, entry.name()),
                    runs.apply(entry.name())));
        }
        List<Pending> pending = new ArrayList<>();
        for (Requests.Request request : Requests.pending(root)) {
            pending.add(new Pending(request.subject(), request.reason(), request.at()));
        }
        return new Overview(List.copyOf(entries), List.copyOf(consumers), List.copyOf(pending));
    }

    /** The walks document with one entry added or replaced, validated as the document is parsed, so a malformed cron or
     *  an unknown consumer is refused naming the entry and the field, and nothing is written. */
    public static String upsert(String document, String name, String cron, boolean enabled, List<String> consumers) {
        List<WalkSchedules.Entry> entries = new ArrayList<>();
        boolean replaced = false;
        for (WalkSchedules.Entry entry : WalkSchedules.parse(document)) {
            if (entry.name().equals(name)) {
                entries.add(new WalkSchedules.Entry(name, cron, List.copyOf(consumers), enabled));
                replaced = true;
            } else {
                entries.add(entry);
            }
        }
        if (!replaced) {
            entries.add(new WalkSchedules.Entry(name, cron, List.copyOf(consumers), enabled));
        }
        String rendered = WalkSchedules.render(entries);
        WalkSchedules.parse(rendered);      // refuse what could not be read back, with the entry and field named
        return rendered;
    }

    /** The walks document with the named entry removed; a name no entry carries changes nothing. */
    public static String remove(String document, String name) {
        List<WalkSchedules.Entry> entries = new ArrayList<>();
        for (WalkSchedules.Entry entry : WalkSchedules.parse(document)) {
            if (!entry.name().equals(name)) {
                entries.add(entry);
            }
        }
        return WalkSchedules.render(entries);
    }
}
