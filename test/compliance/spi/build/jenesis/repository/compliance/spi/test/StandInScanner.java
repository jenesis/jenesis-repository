package build.jenesis.repository.compliance.spi.test;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ContentScanner;

/**
 * A scanner a test declares: what it is handed, what it makes, the catalogues it makes and the bills it reads, the
 * setting that configures it, and a session that records what it was asked and answers what the test scripts.
 */
final class StandInScanner implements ContentScanner {

    private final String name;
    private final Set<Input> consumes;
    private final Set<Output> produces;
    private final List<String> catalogues;
    private final Map<String, Fidelity> reads;
    private final String key;

    /** Every request a session of it was handed. */
    final List<Request> submitted = new CopyOnWriteArrayList<>();

    /** Every collection a session of it was asked, as its identifier, bill and catalogue formats. */
    final List<List<String>> collected = new CopyOnWriteArrayList<>();

    /** Every bill a session of it was asked to match. */
    final List<Bill> matched = new CopyOnWriteArrayList<>();

    /** What a submission answers. */
    final AtomicReference<Submitted> submission = new AtomicReference<>(new Submitted.Accepted("scan-1"));

    /** What a collection answers. */
    final AtomicReference<Collected> collection = new AtomicReference<>(new Collected.Running(Optional.empty()));

    /** What a match answers, or the failure it throws. */
    final AtomicReference<Object> match = new AtomicReference<>(List.of());

    private StandInScanner(String name, Set<Input> consumes, Set<Output> produces, List<String> catalogues,
                           Map<String, Fidelity> reads, String key) {
        this.name = name;
        this.consumes = consumes;
        this.produces = produces;
        this.catalogues = catalogues;
        this.reads = reads;
        this.key = key;
    }

    /** A scanner of images that answers advisories, configured where {@code key} is set. */
    static StandInScanner combined(String name, String key) {
        return new StandInScanner(name, Set.of(Input.IMAGE_MANIFEST), Set.of(Output.VULNERABILITIES), List.of(),
                Map.of(), key);
    }

    /** A scanner of images that makes a bill and catalogues in {@code catalogues}, configured where {@code key} is
     *  set. */
    static StandInScanner cataloguer(String name, String key, String... catalogues) {
        return new StandInScanner(name, Set.of(Input.IMAGE_MANIFEST), Set.of(Output.BILL_OF_MATERIALS),
                List.of(catalogues), Map.of(), key);
    }

    /** A scanner handed bills that reads {@code reads}, configured where {@code key} is set. */
    static StandInScanner matcher(String name, String key, Map<String, Fidelity> reads) {
        return new StandInScanner(name, Set.of(Input.BILL_OF_MATERIALS), Set.of(Output.VULNERABILITIES), List.of(),
                reads, key);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<Input> consumes() {
        return consumes;
    }

    @Override
    public Set<Output> produces() {
        return produces;
    }

    @Override
    public List<String> catalogues() {
        return catalogues;
    }

    @Override
    public Map<String, Fidelity> reads() {
        return reads;
    }

    @Override
    public List<String> missing(UnaryOperator<String> config) {
        String value = config.apply(key);
        return value == null || value.isBlank() ? List.of(key) : List.of();
    }

    @Override
    public Session open(UnaryOperator<String> config) {
        return new Session() {

            @Override
            public String label() {
                return name + " 1.0";
            }

            @Override
            public boolean scans(String mediaType) {
                return true;
            }

            @Override
            public Optional<String> bill(String mediaType) {
                return produces.contains(Output.BILL_OF_MATERIALS) ? Optional.of("application/vnd.cyclonedx+json")
                        : Optional.empty();
            }

            @Override
            public Submitted submit(Request request) {
                submitted.add(request);
                return submission.get();
            }

            @Override
            public Collected collect(String id, String bill, String catalogue, boolean last) {
                collected.add(Arrays.asList(id, bill, catalogue));
                return collection.get();
            }

            @SuppressWarnings("unchecked")
            @Override
            public Report match(Bill bill) throws IOException {
                if (reads.isEmpty()) {
                    throw new Refused(name + " reads no bill");
                }
                matched.add(bill);
                if (match.get() instanceof IOException failure) {
                    throw failure;
                }
                return new Report(label(), (List<AdvisorySource.Advisory>) match.get(), null, null);
            }
        };
    }

    @Override
    public String toString() {
        return name;
    }
}
