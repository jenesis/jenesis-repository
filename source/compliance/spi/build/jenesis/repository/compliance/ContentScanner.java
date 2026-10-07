package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.Providers;

/**
 * A scanner of what an artifact contains: a service an operator runs or a tool beside the deployment - an adapter
 * speaking Harbor's pluggable scanner API, Trivy, Syft, Grype, a vendor's service - that is handed an artifact and
 * answers what it found in it. A scanner declares what it {@linkplain #consumes consumes} and what it
 * {@linkplain #produces produces}, so the host scanning a kind of artifact hands it only to a scanner that takes it,
 * and asks for a bill of materials only of one that makes one.
 *
 * <p>A scanner plays one of three roles, by what it declares. A <b>combined</b> scanner is handed an artifact and
 * answers its vulnerabilities, and its bill where it makes one. A <b>cataloguer</b> is handed an artifact and answers
 * its bill alone, and names the formats it can hand a matcher in ({@link #catalogues}). A <b>matcher</b> is handed a
 * bill - it {@linkplain #consumes consumes} {@link Input#BILL_OF_MATERIALS} - and answers the vulnerabilities its
 * database matches against it, naming the formats it reads and how fully ({@link #reads}). A repository selects a
 * cataloguer and a matcher together as one <b>chain</b>, written {@code cataloguer>matcher}: the host has the
 * cataloguer make its catalogue in the first format the matcher reads in full, hands that document to the matcher, and
 * records the matcher's advisories beside the cataloguer's bill - both made by the one cataloguing, so the bill
 * matched and the bill attached name the same packages.
 *
 * <p>A scanner that runs on a tool an operator keeps fit - a command, a service - says what state the tool is in
 * through the {@link ScannerTool} role beside this one.
 *
 * <p>A scanner decides nothing. It carries the artifact there and the report back; the gate decides the advisories
 * it reports exactly as it decides a feed's, and the host keeps the bill it makes. Which scanners screen a repository
 * is the repository's {@value #SETTING} ({@link #selected}); where each answers and with which credential is the
 * deployment's configuration, read by {@link #configured} and {@link #open}.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> A provider is a constant: {@link #name}, {@link #consumes}, {@link #produces},
 *       {@link #catalogues}, {@link #reads} and {@link #missing} read no store and call nothing. A {@link Session} is
 *       used by one pass at a time and may hold what it learned during it - a scanner's metadata - for that pass
 *       alone.</li>
 *   <li><b>Idempotency / replay.</b> {@link Session#submit} may be asked again for an artifact whose earlier
 *       submission was lost to a crash; a scanner answers it as a new scan. {@link Session#collect} may be asked again
 *       for the same identifier until it answers {@link Collected.Done} or {@link Collected.Failed} - and, of a
 *       cataloguer in a chain whose matcher could not be reached, after it answered {@link Collected.Done}, so the
 *       catalogue is matched on a later pass. {@link Session#match} holds nothing between calls.</li>
 *   <li><b>Absence sentinel.</b> {@link Session#bill} answers {@link Optional#empty()} where the scanner makes no bill
 *       of the artifact's type; {@link #catalogues} and {@link #reads} answer empty where the scanner plays no such
 *       role; {@link #selected} answers an empty list where the repository is scanned by none. A {@link Report}'s
 *       bill and catalogue are {@code null} where none was made. {@code null} is never a legal return.</li>
 *   <li><b>Selection failure.</b> {@link #selected} throws, naming the entry and what is wrong, for a name the
 *       repository selects that no installed provider answers to, one whose configuration is not set, one that cannot
 *       be handed what the host scans, and a chain whose cataloguer makes nothing its matcher reads in full - unless
 *       the repository's {@value #LOSSY} accepts a format the matcher reads lossily. A repository that selects nothing
 *       is scanned by every installed scanner the deployment configured that takes what the host scans, which leaves
 *       every matcher out: a matcher is handed a bill only as a chain's.</li>
 *   <li><b>Error visibility.</b> A scanner that cannot be reached throws an {@link IOException}, which is an outage
 *       the host retries, never a report. One that will refuse the same request again throws {@link Refused}. A
 *       report that does not parse is an {@link IOException}, never an empty one, because an empty list of
 *       vulnerabilities is what a clean artifact looks like. A chain whose cataloguer answers no catalogue fails the
 *       scan, since nothing was matched.</li>
 *   <li><b>Read purity.</b> Every call of a {@link Session} may reach the scanner; none is made on a request path - the
 *       host submits, collects and matches off it, in a pass.</li>
 *   <li><b>Selection.</b> {@code ALL}: every installed scanner the deployment configured screens a repository that
 *       names none; two answering to one name fail {@link #installed}.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link #installed} instantiates afresh per call, so a caller on a repeated path
 *       holds the list. A provider owns no thread; a session owns its client for the pass and nothing past it.</li>
 *   <li><b>Bounded work / cancellation.</b> Every call is bounded in time by the scanner, and every body it reads in
 *       bytes; a scan that does not finish in the host's timeout is given up by the host, which stops collecting
 *       it. {@link Session#match} runs to completion while it is asked, within the scanner's own timeout, since a
 *       match reads a document it is handed and pulls nothing.</li>
 * </ol>
 */
public interface ContentScanner {

    /** The repository setting naming the scanners that screen it, comma-separated, a chain written
     *  {@code cataloguer>matcher}; empty for every configured scanner, {@value #NONE} for none. */
    String SETTING = "content-scanners";

    /** The repository setting that, {@code true}, lets a chain hand its matcher a catalogue the matcher reads only
     *  lossily, where the cataloguer makes none it reads in full. */
    String LOSSY = "content-scanners-lossy";

    /** The {@value #SETTING} value a repository scanned by no scanner names. */
    String NONE = RepositorySelection.NONE;

    /** How a chain is written in {@value #SETTING}: the cataloguer, this, then the matcher. */
    String CHAIN = ">";

    /** The image manifest types a scanner of {@link Input#IMAGE_MANIFEST} is handed. */
    Set<String> IMAGE_MANIFESTS = Set.of("application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    /** What a scanner can be handed. */
    enum Input {
        /** A container image, by its manifest, pulled from the registry the request names. */
        IMAGE_MANIFEST,
        /** A bill of materials, in a format the scanner {@linkplain #reads reads}, handed to it whole. */
        BILL_OF_MATERIALS
    }

    /** What a scanner can make of what it is handed. */
    enum Output {
        /** The vulnerabilities it found, as advisories. */
        VULNERABILITIES,
        /** A bill of materials of what it scanned. */
        BILL_OF_MATERIALS
    }

    /** How much of what a bill says a matcher reads. */
    enum Fidelity {
        /** Everything the matcher matches on: it finds what it would find cataloguing the artifact itself. */
        FULL,
        /** Less than that - a distribution it cannot tell, a package type it does not take - so it may find less. */
        LOSSY
    }

    /** The name a repository selects the scanner by, as {@value #SETTING} spells it. */
    String name();

    /** What the scanner can be handed. */
    Set<Input> consumes();

    /** What the scanner can make. */
    Set<Output> produces();

    /** The formats, as media types, the scanner can make a catalogue in for a chain's matcher, the most complete
     *  first; empty where it is no chain's cataloguer. */
    List<String> catalogues();

    /** The formats, as media types, the scanner matches a bill in, and how fully; empty where it is handed no bill. */
    Map<String, Fidelity> reads();

    /** What is missing from {@code config} - the deployment's - for the scanner to be asked anything, as the setting
     *  keys to set; empty where nothing is. */
    List<String> missing(UnaryOperator<String> config);

    /** Whether {@code config} configures the scanner: nothing is {@link #missing}. */
    default boolean configured(UnaryOperator<String> config) {
        return missing(config).isEmpty();
    }

    /** A session with the scanner for one pass, over the deployment's {@code config}. */
    Session open(UnaryOperator<String> config);

    /** One pass's conversation with the scanner. */
    interface Session {

        /** How a finding names the scanner that reported it: its name and version, as the scanner states them. */
        String label() throws IOException;

        /** Whether the scanner scans an image manifest of {@code mediaType}. */
        boolean scans(String mediaType) throws IOException;

        /** The format of the bill of materials the scanner would make of an image manifest of {@code mediaType}, in
         *  the order this product reads them - CycloneDX, then SPDX - or empty where it makes none. */
        Optional<String> bill(String mediaType) throws IOException;

        /** Ask the scanner to scan what {@code request} names. */
        Submitted submit(Request request) throws IOException;

        /** Ask after the scan {@code id} identifies, its bill asked for in {@code bill} and its catalogue in
         *  {@code catalogue} where those are not {@code null}. On the {@code last} ask before the host gives the scan
         *  up, a scanner whose report is in and whose bill is still being made answers the report without it. */
        Collected collect(String id, String bill, String catalogue, boolean last) throws IOException;

        /** Match {@code bill}, in a format the scanner {@linkplain #reads reads}: its report names the advisories the
         *  scanner's database matched and carries no bill. A scanner that reads no bill throws {@link Refused}. */
        Report match(Bill bill) throws IOException;
    }

    /** An image to scan: where the scanner pulls it from and the {@code Authorization} header it pulls with
     *  ({@code null} for none), the image's repository in that registry, its manifest's digest and media type, the
     *  bill format to make and the catalogue format to make for a chain's matcher, each {@code null} for none. */
    record Request(String registry, String authorization, String repository, String digest, String mediaType,
                   String bill, String catalogue) {
    }

    /** What a submission came to. */
    sealed interface Submitted {

        /** The scanner accepted the scan, to be collected by {@code id}. */
        record Accepted(String id) implements Submitted {
        }

        /** The scanner scanned it while asked. */
        record Done(Report report) implements Submitted {
        }
    }

    /** What asking after a scan came to. */
    sealed interface Collected {

        /** Still scanning; asked to be asked again after {@code retryAfter} where the scanner said. */
        record Running(Optional<Duration> retryAfter) implements Collected {
        }

        /** The scanner answered that the scan failed, or that it knows no such scan, saying why. */
        record Failed(String reason) implements Collected {
        }

        /** The report. */
        record Done(Report report) implements Collected {
        }
    }

    /** A finished scan: the scanner's {@link Session#label}, the advisories it reported - one per vulnerability
     *  identifier - the bill it made, and the catalogue it made for a chain's matcher, each {@code null} where it made
     *  none. A chain's report carries both the bill and the catalogue its cataloguer made, so what was matched is
     *  kept beside what is attached. */
    record Report(String scanner, List<AdvisorySource.Advisory> advisories, Bill bill, Bill catalogue) {

        public Report {
            advisories = List.copyOf(advisories);
        }
    }

    /** A document a scanner made of what it scanned - a bill of materials, or a catalogue in its own format: its media
     *  type and its bytes, held as a copy and handed out as one, and equal to another of the same type and bytes. */
    final class Bill {

        private final String format;
        private final byte[] document;

        public Bill(String format, byte[] document) {
            this.format = Objects.requireNonNull(format, "format");
            this.document = document.clone();
        }

        /** The document's media type. */
        public String format() {
            return format;
        }

        /** A copy of the document. */
        public byte[] document() {
            return document.clone();
        }

        /** The document's length in bytes. */
        public int length() {
            return document.length;
        }

        /** The document, read without a copy. */
        public InputStream open() {
            return new ByteArrayInputStream(document);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bill bill && format.equals(bill.format) && Arrays.equals(document, bill.document);
        }

        @Override
        public int hashCode() {
            return 31 * format.hashCode() + Arrays.hashCode(document);
        }

        @Override
        public String toString() {
            return format + " (" + document.length + " bytes)";
        }
    }

    /** A request the scanner refused outright: it will refuse the same request again. */
    final class Refused extends IOException {

        public Refused(String message) {
            super(message);
        }
    }

    /** Every installed scanner, ordered by name; two answering to one name fail here, naming both, since which one a
     *  repository's {@value #SETTING} selected would otherwise be an accident of the module path. */
    static List<ContentScanner> installed() {
        return Providers.all("content-scanner", ServiceLoader.load(ContentScanner.class), ContentScanner::name,
                _ -> true, Optional::of);
    }

    /**
     * The scanners of {@code installed} that screen what is handed as {@code input} in a repository whose effective
     * lookup is {@code repository}, over the deployment's {@code deployment} lookup, as {@link RepositorySelection}
     * reads its {@value #SETTING}: those it names, in its order, each chain it names as one scanner named as it is
     * written; or every configured one taking {@code input} where it names none; none for {@value #NONE}.
     *
     * @throws IllegalStateException for a name no installed scanner answers to, one the deployment has not configured,
     *                               one that does not take {@code input}, or a chain no format joins in full that
     *                               {@value #LOSSY} does not accept lossily - naming it and what is wrong
     */
    static List<ContentScanner> selected(List<ContentScanner> installed, UnaryOperator<String> repository,
                                         UnaryOperator<String> deployment, Input input) {
        String value = ChainedScanner.normalised(repository == null ? null : repository.apply(SETTING));
        boolean lossy = repository != null && Boolean.parseBoolean(Objects.requireNonNullElse(
                repository.apply(LOSSY), "false").strip());
        List<ContentScanner> candidates = new ArrayList<>(installed);
        for (String entry : RepositorySelection.named(value)) {
            if (entry.contains(CHAIN)) {
                candidates.add(ChainedScanner.of(entry, installed, lossy));
            }
        }
        return RepositorySelection.select(SETTING, "scanner", value, List.copyOf(candidates), ContentScanner::name,
                scanner -> !scanner.consumes().contains(input)
                        ? Optional.of(scanner.consumes().contains(Input.BILL_OF_MATERIALS)
                                ? "handed a bill only as a chain's matcher: name it after a cataloguer, as "
                                        + "'<cataloguer>" + CHAIN + scanner.name() + "'"
                                : "not handed " + input)
                        : scanner.configured(deployment) ? Optional.empty()
                        : Optional.of("not configured: set " + String.join(", ", scanner.missing(deployment))));
    }
}
