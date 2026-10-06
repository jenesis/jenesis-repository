package build.jenesis.repository.compliance;

import module java.base;

/**
 * A scanner of what an artifact contains: a service an operator runs or a tool beside the deployment - an adapter
 * speaking Harbor's pluggable scanner API, Trivy, Grype, a vendor's service - that is handed an artifact and answers
 * what it found in it. A scanner declares what it {@linkplain #consumes consumes} and what it
 * {@linkplain #produces produces}, so the host scanning a kind of artifact hands it only to a scanner that takes it,
 * and asks for a bill of materials only of one that makes one.
 *
 * <p>A scanner decides nothing. It carries the artifact there and the report back; the gate decides the advisories
 * it reports exactly as it decides a feed's, and the host keeps the bill it makes. Which scanners screen a repository
 * is the repository's {@value #SETTING} ({@link #selected}); where each answers and with which credential is the
 * deployment's configuration, read by {@link #configured} and {@link #open}.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> A provider is a constant: {@link #name}, {@link #consumes}, {@link #produces} and
 *       {@link #missing} read no store and call nothing. A {@link Session} is used by one pass at a time and may
 *       hold what it learned during it - a scanner's metadata - for that pass alone.</li>
 *   <li><b>Idempotency / replay.</b> {@link Session#submit} may be asked again for an artifact whose earlier
 *       submission was lost to a crash; a scanner answers it as a new scan. {@link Session#collect} may be asked again
 *       for the same identifier until it answers {@link Collected.Done} or {@link Collected.Failed}.</li>
 *   <li><b>Absence sentinel.</b> {@link Session#bill} answers {@link Optional#empty()} where the scanner makes no bill
 *       of the artifact's type; {@link #selected} answers an empty list where the repository is scanned by none.
 *       {@code null} is never a legal return.</li>
 *   <li><b>Selection failure.</b> {@link #selected} throws, naming the scanner and what is missing, for a name the
 *       repository selects that no installed provider answers to, or one whose configuration is not set; a
 *       repository that selects nothing is scanned by every installed scanner the deployment configured.</li>
 *   <li><b>Error visibility.</b> A scanner that cannot be reached throws an {@link IOException}, which is an outage
 *       the host retries, never a report. One that will refuse the same request again throws {@link Refused}. A
 *       report that does not parse is an {@link IOException}, never an empty one, because an empty list of
 *       vulnerabilities is what a clean artifact looks like.</li>
 *   <li><b>Read purity.</b> Every call of a {@link Session} may reach the scanner; none is made on a request path - the
 *       host submits and collects off it, in a pass.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link #installed} instantiates afresh per call, so a caller on a repeated path
 *       holds the list. A provider owns no thread; a session owns its client for the pass and nothing past it.</li>
 *   <li><b>Bounded work / cancellation.</b> Every call is bounded in time by the scanner, and every body it reads in
 *       bytes; a scan that does not finish in the host's timeout is given up by the host, which stops collecting
 *       it.</li>
 * </ol>
 */
public interface ContentScanner {

    /** The repository setting naming the scanners that screen it, comma-separated; empty for every configured
     *  scanner, {@value #NONE} for none. */
    String SETTING = "content-scanners";

    /** The {@value #SETTING} value a repository scanned by no scanner names. */
    String NONE = "none";

    /** What a scanner can be handed. */
    enum Input {
        /** A container image, by its manifest, pulled from the registry the request names. */
        IMAGE_MANIFEST,
        /** A bill of materials, handed as the document. */
        BILL_OF_MATERIALS
    }

    /** What a scanner can make of what it is handed. */
    enum Output {
        /** The vulnerabilities it found, as advisories. */
        VULNERABILITIES,
        /** A bill of materials of what it scanned. */
        BILL_OF_MATERIALS
    }

    /** The name a repository selects the scanner by, as {@value #SETTING} spells it. */
    String name();

    /** What the scanner can be handed. */
    Set<Input> consumes();

    /** What the scanner can make. */
    Set<Output> produces();

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

        /** Ask after the scan {@code id} identifies, its bill asked for in {@code bill} where that is not
         *  {@code null}. On the {@code last} ask before the host gives the scan up, a scanner whose report is in and
         *  whose bill is still being made answers the report without it. */
        Collected collect(String id, String bill, boolean last) throws IOException;
    }

    /** An image to scan: where the scanner pulls it from and the {@code Authorization} header it pulls with
     *  ({@code null} for none), the image's repository in that registry, its manifest's digest and media type, and the
     *  bill format to make, {@code null} for none. */
    record Request(String registry, String authorization, String repository, String digest, String mediaType,
                   String bill) {
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
     *  identifier - and the bill it made, {@code null} where it made none. */
    record Report(String scanner, List<AdvisorySource.Advisory> advisories, Bill bill) {

        public Report {
            advisories = List.copyOf(advisories);
        }
    }

    /** A bill of materials a scanner made: its media type and the document. */
    record Bill(String format, byte[] document) {
    }

    /** A request the scanner refused outright: it will refuse the same request again. */
    final class Refused extends IOException {

        public Refused(String message) {
            super(message);
        }
    }

    /** Every installed scanner, in discovery order. */
    static List<ContentScanner> installed() {
        return ServiceLoader.load(ContentScanner.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    /**
     * The scanners of {@code installed} that screen a repository whose effective lookup is {@code repository}, over the
     * deployment's {@code deployment} lookup: those its {@value #SETTING} names, in its order, or every configured one
     * where it names none; none for {@value #NONE}.
     *
     * @throws IllegalStateException for a name no installed scanner answers to, or one the deployment has not
     *                               configured, naming it and what is missing
     */
    static List<ContentScanner> selected(List<ContentScanner> installed, UnaryOperator<String> repository,
                                         UnaryOperator<String> deployment) {
        String value = repository == null ? null : repository.apply(SETTING);
        if (value == null || value.isBlank()) {
            return installed.stream().filter(scanner -> scanner.configured(deployment)).toList();
        }
        if (value.strip().equalsIgnoreCase(NONE)) {
            return List.of();
        }
        List<ContentScanner> selected = new ArrayList<>();
        for (String name : value.split(",")) {
            String named = name.strip();
            if (named.isEmpty()) {
                continue;
            }
            ContentScanner scanner = installed.stream().filter(candidate -> candidate.name().equals(named))
                    .findFirst().orElseThrow(() -> new IllegalStateException(SETTING + " names the scanner '" + named
                            + "', which is not installed; installed: " + installed.stream()
                            .map(ContentScanner::name).toList()));
            if (!scanner.configured(deployment)) {
                throw new IllegalStateException(SETTING + " names the scanner '" + named + "', which is not "
                        + "configured: set " + String.join(", ", scanner.missing(deployment)));
            }
            if (!selected.contains(scanner)) {
                selected.add(scanner);
            }
        }
        return List.copyOf(selected);
    }
}
