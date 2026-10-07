package build.jenesis.repository.compliance;

import module java.base;

/**
 * A cataloguer and a matcher a repository selects together, written {@code cataloguer>matcher}, as one
 * {@link ContentScanner}: it is handed what the cataloguer takes, has the cataloguer make its bill and, in the
 * {@linkplain #format format} the two were joined in, its catalogue, and answers the matcher's advisories over that
 * catalogue beside the cataloguer's bill and catalogue. A scan is the cataloguer's, collected by the cataloguer's
 * identifier; the match runs as the cataloguer's report is in.
 *
 * <p>The format is decided as the chain is selected: the first the cataloguer makes that the matcher reads in full,
 * else - where the repository accepts the loss - the first it reads at all. A chain no format joins is not a scanner,
 * and its selection fails saying what each side makes and reads.
 */
final class ChainedScanner implements ContentScanner {

    private final ContentScanner cataloguer;
    private final ContentScanner matcher;
    private final String format;

    private ChainedScanner(ContentScanner cataloguer, ContentScanner matcher, String format) {
        this.cataloguer = cataloguer;
        this.matcher = matcher;
        this.format = format;
    }

    /** {@code value}, a {@value ContentScanner#SETTING}, with every chain written without spaces around its
     *  {@value ContentScanner#CHAIN}, so a chain is one name however it was spaced. */
    static String normalised(String value) {
        if (value == null || value.isBlank() || value.strip().equalsIgnoreCase(NONE)) {
            return value;
        }
        return String.join(",", RepositorySelection.named(value).stream()
                .map(entry -> String.join(CHAIN, Arrays.stream(entry.split(CHAIN, -1)).map(String::strip).toList()))
                .toList());
    }

    /**
     * The chain {@code entry} names among {@code installed}.
     *
     * @param lossy whether the repository accepts a format the matcher reads only lossily
     * @throws IllegalStateException for an entry that is not two names, a name no installed scanner answers to, a
     *                               cataloguer that makes no catalogue, a matcher that is handed no bill, or a pair no
     *                               format joins - naming the entry and what is wrong
     */
    static ChainedScanner of(String entry, List<ContentScanner> installed, boolean lossy) {
        String[] names = entry.split(CHAIN, -1);
        if (names.length != 2 || names[0].isBlank() || names[1].isBlank()) {
            throw refused(entry, "is not a chain of one cataloguer and one matcher, written 'cataloguer" + CHAIN
                    + "matcher'");
        }
        ContentScanner cataloguer = find(entry, names[0].strip(), installed);
        ContentScanner matcher = find(entry, names[1].strip(), installed);
        if (cataloguer.catalogues().isEmpty()) {
            throw refused(entry, "starts with '" + cataloguer.name() + "', which makes no catalogue for a matcher");
        }
        if (!matcher.consumes().contains(Input.BILL_OF_MATERIALS) || matcher.reads().isEmpty()) {
            throw refused(entry, "ends with '" + matcher.name() + "', which is handed no bill");
        }
        Optional<String> full = joined(cataloguer, matcher, Fidelity.FULL);
        if (full.isPresent()) {
            return new ChainedScanner(cataloguer, matcher, full.get());
        }
        Optional<String> partial = joined(cataloguer, matcher, Fidelity.LOSSY);
        if (partial.isPresent() && lossy) {
            return new ChainedScanner(cataloguer, matcher, partial.get());
        }
        String says = "'" + cataloguer.name() + "' makes " + cataloguer.catalogues() + " and '" + matcher.name()
                + "' reads " + new TreeMap<>(matcher.reads());
        throw refused(entry, partial.isPresent()
                ? "joined by no format its matcher reads in full - " + says + "; set " + LOSSY + " to true to "
                        + "accept what the matcher cannot read in " + partial.get()
                : "joined by no format at all - " + says);
    }

    /**
     * {@code installed} as a repository naming no scanner is scanned: each cataloguer {@code deployment} configures
     * replaced by its chains into every configured matcher reading one of its catalogues in full, in the matchers'
     * order, and left alone where no such matcher is configured; every other scanner as it is.
     */
    static List<ContentScanner> joined(List<ContentScanner> installed, UnaryOperator<String> deployment) {
        List<ContentScanner> matchers = installed.stream().filter(scanner -> !scanner.reads().isEmpty()
                && scanner.consumes().contains(Input.BILL_OF_MATERIALS) && scanner.configured(deployment)).toList();
        List<ContentScanner> joined = new ArrayList<>();
        for (ContentScanner scanner : installed) {
            List<ContentScanner> chains = scanner.catalogues().isEmpty() || !scanner.configured(deployment) ? List.of()
                    : matchers.stream().flatMap(matcher -> joined(scanner, matcher, Fidelity.FULL).stream()
                            .map(format -> (ContentScanner) new ChainedScanner(scanner, matcher, format))).toList();
            if (chains.isEmpty()) {
                joined.add(scanner);
            } else {
                joined.addAll(chains);
            }
        }
        return joined;
    }

    private static Optional<String> joined(ContentScanner cataloguer, ContentScanner matcher, Fidelity fidelity) {
        return cataloguer.catalogues().stream().filter(made -> matcher.reads().get(made) == fidelity).findFirst();
    }

    private static ContentScanner find(String entry, String name, List<ContentScanner> installed) {
        return installed.stream().filter(scanner -> scanner.name().equals(name)).findFirst()
                .orElseThrow(() -> refused(entry, "names '" + name + "', which is not installed; installed: "
                        + installed.stream().map(ContentScanner::name).toList()));
    }

    private static IllegalStateException refused(String entry, String why) {
        return new IllegalStateException(SETTING + " names the scanner '" + entry + "', which " + why);
    }

    /** The format the cataloguer hands the matcher its catalogue in. */
    String format() {
        return format;
    }

    @Override
    public String name() {
        return cataloguer.name() + CHAIN + matcher.name();
    }

    @Override
    public Set<Input> consumes() {
        return cataloguer.consumes();
    }

    @Override
    public Set<Output> produces() {
        Set<Output> produces = EnumSet.of(Output.VULNERABILITIES);
        if (cataloguer.produces().contains(Output.BILL_OF_MATERIALS)) {
            produces.add(Output.BILL_OF_MATERIALS);
        }
        return Set.copyOf(produces);
    }

    @Override
    public List<String> catalogues() {
        return List.of();
    }

    @Override
    public Map<String, Fidelity> reads() {
        return Map.of();
    }

    @Override
    public List<String> missing(UnaryOperator<String> config) {
        return Stream.concat(cataloguer.missing(config).stream(), matcher.missing(config).stream()).distinct()
                .toList();
    }

    @Override
    public Session open(UnaryOperator<String> config) {
        return new Chain(cataloguer.open(config), matcher.open(config));
    }

    @Override
    public String toString() {
        return name();
    }

    /** One pass's conversation with both. */
    private final class Chain implements Session {

        private final Session cataloguing;
        private final Session matching;

        private Chain(Session cataloguing, Session matching) {
            this.cataloguing = cataloguing;
            this.matching = matching;
        }

        @Override
        public String label() throws IOException {
            return cataloguing.label() + " " + CHAIN + " " + matching.label();
        }

        @Override
        public boolean scans(String mediaType) throws IOException {
            return cataloguing.scans(mediaType);
        }

        @Override
        public Optional<String> bill(String mediaType) throws IOException {
            return cataloguing.bill(mediaType);
        }

        @Override
        public Submitted submit(Request request) throws IOException {
            Submitted submitted = cataloguing.submit(new Request(request.registry(), request.authorization(),
                    request.repository(), request.digest(), request.mediaType(), request.bill(), format));
            return switch (submitted) {
                case Submitted.Accepted accepted -> accepted;
                case Submitted.Done done -> new Submitted.Done(matched(done.report()));
            };
        }

        @Override
        public Collected collect(String id, String bill, String catalogue, boolean last) throws IOException {
            return switch (cataloguing.collect(id, bill, format, last)) {
                case Collected.Done done -> {
                    try {
                        yield new Collected.Done(matched(done.report()));
                    } catch (Refused refused) {
                        yield new Collected.Failed(refused.getMessage());
                    }
                }
                case Collected other -> other;
            };
        }

        @Override
        public Report match(Bill bill) throws Refused {
            throw new Refused(name() + " is handed what its cataloguer takes, never a bill");
        }

        /** {@code catalogued}'s catalogue matched: the matcher's advisories, beside the cataloguer's documents. */
        private Report matched(Report catalogued) throws IOException {
            Bill catalogue = catalogued.catalogue();
            if (catalogue == null) {
                throw new Refused(catalogued.scanner() + " made no " + format + " catalogue for "
                        + matcher.name() + " to match");
            }
            Report matched = matching.match(catalogue);
            return new Report(catalogued.scanner() + " " + CHAIN + " " + matched.scanner(), matched.advisories(),
                    catalogued.bill(), catalogue);
        }
    }
}
