package build.jenesis.repository.discovery;

import module java.base;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import build.jenesis.repository.discovery.DiscoveryFile.Entry;
import build.jenesis.repository.discovery.DiscoveryFile.Key;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.VersionScheme;

/**
 * Where a module's or a Maven group's files are, as the owner of the domain its name reverses into says in
 * {@link DiscoveryFile its discovery file} - the repository's reading of the discovery proposal.
 *
 * <p><b>Which file answers.</b> A name is asked of the domains {@link Domains#of} lists, shortest first, and the first
 * file found speaks for every name below its domain: a key it does not hold is absent rather than asked of a
 * subdomain. A file saying {@code delegate=true} lets the files of its subdomains be read as well, and then the most
 * specific file holding a key answers for it. Within a file, a key selecting the name exactly answers before one
 * selecting a prefix of it, the longest prefix first, and either before the key for every name; {@code module} and
 * {@code moduletomaven} select by module name, {@code maven} by artifact ID. A {@code moduletomaven} naming a
 * coordinate without placeholders and selecting nothing answers only for the module whose own domain publishes the
 * file. Each domain's file is read once per {@link #ttl()} on this node, an absent
 * one remembered as absent for as long, so a busy leg costs a domain one request an hour; at most {@link #MOST_DOMAINS}
 * domains are remembered at once. A file that cannot be fetched - none, a host that does not exist or does not answer -
 * is absent; a file the proposal refuses, and a certificate that does not verify, is a {@link DiscoveryException}
 * remembered as long, never read as absent.
 *
 * <p><b>What it answers.</b> {@link #locate} reads a request path as a {@link Request} and answers where its file is:
 * {@link Located.Relayed} under a root - a Maven repository, or a module service - at the request's own path in the
 * root's layout; {@link Located.Fetched} at a template's address filled in for this file, {@code checked} against the
 * strongest checksum beside it where the template names {@code {type}}; or {@link Located.Answered}, Maven metadata
 * answered here - the versions a latest link's {@code maven-metadata.xml} lists, the one version its redirect names, or
 * a root's own versions where its key restricts them. A version a key does not serve ({@code .since},
 * {@code .suffixes}), a request without a version where a key restricts them, a file a template cannot name (a
 * classified file without {@code {-classifier}}, anything but the plain jar without {@code {type}}) and a name no file
 * speaks for answer nothing, which leaves the request to the repository's other legs.
 *
 * <p>A module path asks {@code module} first and the Maven view ({@code /artifact/}) asks {@code moduletomaven} first,
 * as a build on the module path and one reading POMs do; a module mapped to a Maven artifact is located through the
 * {@code maven} key of that artifact's own group, and without one answers nothing here. A request without a version
 * is answered through a latest link only - the version its header or redirect names, or the newest release among those
 * the key serves in the {@code maven-metadata.xml} it names - or a root's own metadata for a mapped module.
 *
 * <p><b>Trust.</b> Every address is screened by {@code refused} - a private, loopback or link-local host - before it is
 * asked: a file at such a domain is absent, and a file naming such a location is refused. Files and locations are
 * {@code https} only; a latest link is sent a {@code HEAD} and its redirect is not followed, unless it names a
 * {@code maven-metadata.xml}, which is read. What a file names says only where bytes come from: the leg serving them
 * screens them as it screens any upstream's.
 */
public final class RepositoryDiscovery {

    /** How long a domain's file, or its absence, is remembered by default. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    /** The most domains remembered at once; past it the memory starts over, so no stream of names grows it. */
    public static final int MOST_DOMAINS = 10_000;

    /** The longest Maven metadata document read, from a root or a latest link. */
    public static final int MOST_METADATA_BYTES = 1024 * 1024;

    /** The header a module service names a module's newest version in. */
    public static final String MODULE_VERSION = "Jenesis-ModuleVersion";

    /** The header a repository names an artifact's newest version in. */
    public static final String MAVEN_VERSION = "Jenesis-MavenVersion";

    /** Maven's version order, which {@code .since} is read in. */
    private static final VersionScheme VERSIONS = new GenericVersionScheme();

    /** A version a latest link may name. */
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

    /** The qualifiers that mark a version as no release, as Maven's order and common practice read them. */
    private static final Set<String> PRERELEASE = Set.of("alpha", "beta", "milestone", "rc", "cr", "snapshot", "ea",
            "pre", "prerelease", "preview", "dev", "nightly", "canary", "next", "test", "adhoc");

    /** How this reaches the network; the production one is {@link ScreenedTransport}. */
    public interface Transport {

        /** The body of {@code url} where it answers {@code 200} within {@code most} bytes; empty for any other
         *  answer, a host that does not exist or does not answer.
         *  @throws DiscoveryException where the host's certificate does not verify */
        Optional<String> read(URI url, int most);

        /** What a {@code HEAD} of {@code url} answers, its redirect not followed; empty where the host does not
         *  exist or does not answer. */
        Optional<Head> head(URI url);
    }

    /** A {@code HEAD}'s status and headers. */
    public record Head(int status, Map<String, String> headers) {

        public Head {
            headers = Map.copyOf(headers);
        }

        /** The first value of {@code name}, case-insensitively, or {@code null}. */
        public String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    /** Where a requested file is. */
    public sealed interface Located {

        /** Under a root - a Maven repository or a module service - at {@code path} below it: the request's own path
         *  in the root's layout, without the route a Maven repository does not have. */
        record Relayed(URI root, String path) implements Located {
        }

        /** At {@code url}, a template filled in for this file; {@code checked} where it is to be checked against the
         *  strongest checksum beside it. */
        record Fetched(URI url, boolean checked) implements Located {
        }

        /** Maven metadata answered here rather than fetched, or its checksum. */
        record Answered(byte[] body, String contentType) implements Located {
        }
    }

    /** One key's entry and the domain whose file holds it, which a {@code {-suffix}} is read below. */
    public record Answering(Entry entry, String domain) {
    }

    /** What a domain's file was, as remembered. */
    private record Remembered(Optional<DiscoveryFile> file, DiscoveryException refusal, Instant at) {
    }

    private final Transport transport;
    private final Predicate<URI> refused;
    private final Supplier<Duration> ttl;
    private final Clock clock;
    private final ConcurrentHashMap<String, Remembered> files = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Checking> checks = new ConcurrentHashMap<>();

    /** Over {@code transport}, refusing what {@code refused} names, remembering each file for {@code ttl} by
     *  {@code clock}. */
    public RepositoryDiscovery(Transport transport, Predicate<URI> refused, Duration ttl, Clock clock) {
        this(transport, refused, () -> ttl, clock);
    }

    /** As {@link #RepositoryDiscovery(Transport, Predicate, Duration, Clock)}, the period read as each file is asked
     *  for, so a changed setting applies without a restart. */
    public RepositoryDiscovery(Transport transport, Predicate<URI> refused, Supplier<Duration> ttl, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.refused = Objects.requireNonNull(refused, "refused");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** How long a file is remembered now: the period given, or {@link #DEFAULT_TTL} where it gives none. */
    public Duration ttl() {
        Duration period = ttl.get();
        return period == null || period.isNegative() ? DEFAULT_TTL : period;
    }

    /** Forgets every file remembered, so the next request asks each domain again. */
    public void forget() {
        files.clear();
    }

    /** What one domain's file was when a {@link #check} asked: {@code found} with the {@code file}, {@code absent},
     *  {@code refused} with the {@code refusal}, or {@code not-reached} - a host this deployment does not reach. */
    public record Asked(String domain, URI address, String state, DiscoveryFile file, String refusal) {
    }

    /**
     * What the domains of {@code name} say now, for an operator asking why a request went where it went: each domain
     * the walk reaches, asked afresh rather than as remembered, with what its file holds or why it is refused; which
     * entry answers each key for {@code name} - selected by {@code name} itself, so an artifact whose ID differs from
     * its group is checked through its path; and, where {@code path} is given, where that request path's file is - or
     * the refusal that ends its leg. The files read stay remembered as a request would leave them.
     */
    public Check check(String name, String path) {
        List<String> domains = Domains.of(name);
        domains.forEach(files::remove);
        List<Asked> asked = new ArrayList<>();
        for (String domain : domains) {
            URI address = DiscoveryFile.address(domain);
            if (refused.test(address)) {
                asked.add(new Asked(domain, address, "not-reached", null, null));
                continue;
            }
            Optional<DiscoveryFile> file;
            try {
                file = file(domain);
            } catch (DiscoveryException refusal) {
                asked.add(new Asked(domain, address, "refused", null, refusal.getMessage()));
                break;
            }
            asked.add(new Asked(domain, address, file.isPresent() ? "found" : "absent", file.orElse(null), null));
            if (file.isPresent() && !file.get().delegate()) {
                break;
            }
        }
        Map<Key, Answering> answering;
        try {
            answering = answering(name);
        } catch (DiscoveryException refusal) {
            answering = Map.of();
        }
        Located located = null;
        String refusal = null;
        if (path != null && !path.isBlank()) {
            try {
                located = locate(path.strip()).orElse(null);
            } catch (DiscoveryException refused) {
                refusal = refused.getMessage();
            }
        }
        return new Check(name, List.copyOf(asked), Map.copyOf(answering), path, located, refusal);
    }

    /** A check as an operator surface sees it: {@code running} or {@code done}, when it started and finished, and
     *  the {@link Check} once done. */
    public record Checking(String name, String path, String state, Instant started, Instant finished, Check check) {
    }

    /** The most checks remembered at once; past it the oldest finished ones are forgotten. */
    public static final int MOST_CHECKS = 100;

    /**
     * Starts a {@link #check} of {@code name} and {@code path} off the caller's thread, unless one is running, and
     * answers its state at once - so a screen or a command never waits on the domains, and reads the outcome back
     * through {@link #checking}.
     */
    public Checking ask(String name, String path) {
        String key = name + "\u0000" + (path == null ? "" : path);
        Checking running = new Checking(name, path, "running", clock.instant(), null, null);
        Checking previous = checks.putIfAbsent(key, running);
        if (previous != null && previous.state().equals("running")) {
            return previous;
        }
        if (previous != null && !checks.replace(key, previous, running)) {
            return checks.get(key);
        }
        if (checks.size() > MOST_CHECKS) {
            checks.entrySet().removeIf(entry -> !entry.getKey().equals(key)
                    && entry.getValue().state().equals("done"));
        }
        Thread.ofVirtual().name("discovery-check").start(() -> {
            Check check;
            try {
                check = check(name, path);
            } catch (RuntimeException failed) {
                check = new Check(name, List.of(), Map.of(), path, null, String.valueOf(failed.getMessage()));
            }
            checks.put(key, new Checking(name, path, "done", running.started(), clock.instant(), check));
        });
        return running;
    }

    /** The last check of {@code name} and {@code path} this node ran or is running, or empty where none was asked. */
    public Optional<Checking> checking(String name, String path) {
        return Optional.ofNullable(checks.get(name + "\u0000" + (path == null ? "" : path)));
    }

    /** A {@link #check}'s answer: the domains asked, which answers each key, and where {@code path} is -
     *  {@code located} {@code null} where no file names it, or {@code refusal} where its leg is refused. */
    public record Check(String name, List<Asked> domains, Map<Key, Answering> answering, String path,
                        Located located, String refusal) {
    }

    /**
     * Where the file {@code path} asks for is, or empty where no domain's file names it.
     *
     * @throws DiscoveryException where the file that answers is refused, a latest link misleads, or a location is a
     *                            host {@code refused} names
     */
    public Optional<Located> locate(String path) {
        Optional<Request> request = Request.of(path);
        if (request.isEmpty()) {
            return Optional.empty();
        }
        Optional<Located> located = switch (request.get()) {
            case Request.MavenFile file -> maven(file);
            case Request.MavenMetadata metadata -> metadata(metadata);
            case Request.ModuleFile file -> module(file);
        };
        String below = path.substring(route(path).length());
        return located.map(found -> found instanceof Located.Relayed relayed
                ? new Located.Relayed(relayed.root(), below) : found);
    }

    /** The part of a request path a root's layout leaves out: {@code /maven/} below a Maven repository, which is laid
     *  out without the route, and {@code /} below a module service, which keeps the module's own. */
    public static String route(String path) {
        return path.startsWith("/maven/") ? "/maven/" : "/";
    }

    /**
     * What answers for {@code name}, key by key, each key selected by {@code name} itself: empty where no domain's file
     * speaks for the name.
     */
    public Map<Key, Answering> answering(String name) {
        Map<Key, Answering> answering = new EnumMap<>(Key.class);
        for (Key key : Key.values()) {
            answering(name, key, name).ifPresent(answer -> answering.put(key, answer));
        }
        return answering;
    }

    /**
     * The entry of {@code key} that answers for {@code selected} below the domains {@code name} reverses into - a
     * module name, or for {@code maven} a groupId with {@code selected} its artifact ID - and the domain whose file
     * holds it; empty where no file holds one.
     */
    public Optional<Answering> answering(String name, Key key, String selected) {
        List<String> domains = Domains.of(name);
        Answering answering = null;
        for (int index = 0; index < domains.size(); index++) {
            String domain = domains.get(index);
            Optional<DiscoveryFile> file = file(domain);
            if (file.isEmpty()) {
                continue;
            }
            Optional<Entry> entry = file.get().entry(key, selected);
            if (entry.isPresent() && (key != Key.MODULE_TO_MAVEN || entry.get().template()
                    || entry.get().selector() != null || index == domains.size() - 1)) {
                answering = new Answering(entry.get(), domain);
            }
            if (!file.get().delegate()) {
                break;
            }
        }
        return Optional.ofNullable(answering);
    }

    /** The file {@code domain} publishes, as remembered or read now. */
    public Optional<DiscoveryFile> file(String domain) {
        Instant now = clock.instant();
        Remembered remembered = files.get(domain);
        if (remembered == null || !remembered.at().plus(ttl()).isAfter(now)) {
            remembered = read(domain, now);
            if (files.size() >= MOST_DOMAINS) {
                files.clear();
            }
            files.put(domain, remembered);
        }
        if (remembered.refusal() != null) {
            throw remembered.refusal();
        }
        return remembered.file();
    }

    private Remembered read(String domain, Instant now) {
        URI address = DiscoveryFile.address(domain);
        if (refused.test(address)) {
            return new Remembered(Optional.empty(), null, now);
        }
        try {
            Optional<String> text = transport.read(address, DiscoveryFile.MOST_BYTES);
            return new Remembered(text.map(body -> DiscoveryFile.parse(domain, body)), null, now);
        } catch (DiscoveryException refusal) {
            return new Remembered(Optional.empty(), refusal, now);
        }
    }

    private Optional<Located> maven(Request.MavenFile file) {
        Answering answering = answering(file.groupId(), Key.MAVEN, file.artifactId()).orElse(null);
        if (answering == null || !serves(answering.entry(), file.version())) {
            return Optional.empty();
        }
        if (!answering.entry().template()) {
            return Optional.of(new Located.Relayed(location(answering.entry().value()), ""));
        }
        return filled(answering.entry().value(), Map.of("groupId", file.groupId(),
                "groupPath", file.groupId().replace('.', '/'), "artifactId", file.artifactId(),
                "version", file.version()), file.classifier(), file.type());
    }

    private Optional<Located> metadata(Request.MavenMetadata metadata) {
        Answering answering = answering(metadata.groupId(), Key.MAVEN, metadata.artifactId()).orElse(null);
        if (answering == null) {
            return Optional.empty();
        }
        Entry entry = answering.entry();
        Map<String, String> values = Map.of("groupId", metadata.groupId(),
                "groupPath", metadata.groupId().replace('.', '/'), "artifactId", metadata.artifactId());
        Optional<Listing> listing;
        if (!entry.template()) {
            if (unrestricted(entry)) {
                return Optional.of(new Located.Relayed(location(entry.value()), ""));
            }
            listing = listing(rootMetadata(entry.value(), metadata.groupId(), metadata.artifactId()), false)
                    .map(listed -> listed.admitted(entry));
        } else if (entry.listsVersions()) {
            listing = listing(location(fill(entry.latest(), values)), true).map(listed -> listed.admitted(entry));
        } else {
            listing = latest(entry, MAVEN_VERSION, values).map(Listing::of);
        }
        if (listing.isEmpty() || listing.get().versions().isEmpty()) {
            return Optional.empty();
        }
        byte[] document = metadataDocument(metadata.groupId(), metadata.artifactId(), listing.get());
        if (metadata.digest() == null) {
            return Optional.of(new Located.Answered(document, "application/xml"));
        }
        try {
            byte[] digest = MessageDigest.getInstance(Request.DIGESTS.get(metadata.digest())).digest(document);
            return Optional.of(new Located.Answered(HexFormat.of().formatHex(digest).getBytes(StandardCharsets.US_ASCII),
                    "text/plain"));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private Optional<Located> module(Request.ModuleFile file) {
        return file.mavenView()
                ? mapped(file).or(() -> direct(file))
                : direct(file).or(() -> mapped(file));
    }

    // Through the module key: a module service relays the path, a template is filled for the file.
    private Optional<Located> direct(Request.ModuleFile file) {
        Answering module = answering(file.module(), Key.MODULE, file.module()).orElse(null);
        if (module == null) {
            return Optional.empty();
        }
        if (!module.entry().template()) {
            return serves(module.entry(), file.version())
                    ? Optional.of(new Located.Relayed(location(module.entry().value()), ""))
                    : Optional.empty();
        }
        String suffix = Domains.suffix(file.module(), module.domain());
        String version = file.version();
        if (version == null) {
            Map<String, String> values = Map.of("module", file.module(), "-suffix", suffix);
            Optional<String> newest = module.entry().listsVersions()
                    ? listing(location(fill(module.entry().latest(), values)), true)
                            .map(listed -> listed.admitted(module.entry())).map(Listing::release)
                    : latest(module.entry(), MODULE_VERSION, values);
            if (newest.isEmpty()) {
                return Optional.empty();
            }
            version = newest.get();
        } else if (!serves(module.entry(), version)) {
            return Optional.empty();
        }
        return filled(module.entry().value(), Map.of("module", file.module(), "-suffix", suffix, "version", version),
                file.classifier(), file.type());
    }

    // Through moduletomaven: the module's Maven artifact, located by the maven key of that artifact's own group.
    private Optional<Located> mapped(Request.ModuleFile file) {
        Answering mapping = answering(file.module(), Key.MODULE_TO_MAVEN, file.module()).orElse(null);
        if (mapping == null || !serves(mapping.entry(), file.version())) {
            return Optional.empty();
        }
        String[] coordinate = fill(mapping.entry().value(), Map.of("module", file.module(),
                "-suffix", Domains.suffix(file.module(), mapping.domain()))).split(":", -1);
        String groupId = coordinate[0];
        String artifactId = coordinate[1];
        String extension = coordinate.length > 2 ? coordinate[2] : "jar";
        String classifier = file.classifier() != null ? file.classifier()
                : coordinate.length > 3 ? coordinate[3] : null;
        String type = file.type().equals("jar") || file.type().startsWith("jar.")
                ? extension + file.type().substring(3) : file.type();
        Answering maven = answering(groupId, Key.MAVEN, artifactId).orElse(null);
        if (maven == null) {
            return Optional.empty();
        }
        Entry entry = maven.entry();
        String version = file.version();
        if (version == null) {
            Map<String, String> values = Map.of("groupId", groupId, "groupPath", groupId.replace('.', '/'),
                    "artifactId", artifactId);
            Optional<String> newest;
            if (!entry.template()) {
                newest = listing(rootMetadata(entry.value(), groupId, artifactId), false)
                        .map(listed -> listed.admitted(entry)).map(Listing::release);
            } else if (entry.listsVersions()) {
                newest = listing(location(fill(entry.latest(), values)), true)
                        .map(listed -> listed.admitted(entry)).map(Listing::release);
            } else {
                newest = latest(entry, MAVEN_VERSION, values);
            }
            if (newest.isEmpty()) {
                return Optional.empty();
            }
            version = newest.get();
        }
        if (!serves(entry, version)) {
            return Optional.empty();
        }
        if (!entry.template()) {
            String root = entry.value().endsWith("/") ? entry.value() : entry.value() + "/";
            String name = artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + "." + type;
            return Optional.of(new Located.Fetched(location(root + groupId.replace('.', '/') + "/" + artifactId
                    + "/" + version + "/" + name), false));
        }
        return filled(entry.value(), Map.of("groupId", groupId, "groupPath", groupId.replace('.', '/'),
                "artifactId", artifactId, "version", version), classifier, type);
    }

    // A template filled for one file, or empty where it cannot name the file: a classified file without
    // {-classifier}, or anything but the plain jar without {type}.
    private Optional<Located> filled(String template, Map<String, String> values, String classifier, String type) {
        boolean classified = template.contains("{-classifier}");
        boolean typed = template.contains("{type}");
        if ((classifier != null && !classified) || (!typed && !"jar".equals(type))) {
            return Optional.empty();
        }
        Map<String, String> all = new HashMap<>(values);
        all.put("-classifier", classifier == null ? "" : "-" + classifier);
        all.put("type", type);
        boolean checksum = type.endsWith(".asc") || type.endsWith(".md5") || type.endsWith(".sha1")
                || type.endsWith(".sha256") || type.endsWith(".sha512");
        return Optional.of(new Located.Fetched(location(fill(template, all)), typed && !checksum));
    }

    // Whether an entry restricts the versions it serves at all.
    private static boolean unrestricted(Entry entry) {
        return entry.since() == null && entry.suffixes().isEmpty();
    }

    // Whether an entry serves a version: from its .since on, and with a qualifier one of its .suffixes begins - the
    // part after the first dash, ignoring case, the suffix ending where a letter does not follow. A request without a
    // version is served only by an entry that restricts none.
    private static boolean serves(Entry entry, String version) {
        if (version == null) {
            return unrestricted(entry);
        }
        if (entry.since() != null && compare(version, entry.since()) < 0) {
            return false;
        }
        if (entry.suffixes().isEmpty()) {
            return true;
        }
        int dash = version.indexOf('-');
        if (dash < 0) {
            return entry.suffixes().contains("none");
        }
        String qualifier = version.substring(dash + 1).toLowerCase(Locale.ROOT);
        for (String suffix : entry.suffixes()) {
            if (qualifier.startsWith(suffix) && (qualifier.length() == suffix.length()
                    || !Character.isLetter(qualifier.charAt(suffix.length())))) {
                return true;
            }
        }
        return false;
    }

    // Two versions in Maven's order; a version Maven cannot read orders before every other.
    private static int compare(String left, String right) {
        try {
            return VERSIONS.parseVersion(left).compareTo(VERSIONS.parseVersion(right));
        } catch (InvalidVersionSpecificationException unordered) {
            return -1;
        }
    }

    // Whether a version is a release: no word of it marks a pre-release, a lone a, b or m before a digit included.
    private static boolean stable(String version) {
        Matcher word = Pattern.compile("[a-z]+").matcher(version.toLowerCase(Locale.ROOT));
        while (word.find()) {
            boolean numbered = word.end() < version.length() && Character.isDigit(version.charAt(word.end()));
            if (PRERELEASE.contains(word.group())
                    || numbered && (word.group().equals("a") || word.group().equals("b") || word.group().equals("m"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The newest version a template's latest link names, or empty where it has none or the link answers {@code 404}:
     * the version {@code header} carries, else the one its redirect names, matched against the template up to the end
     * of the segment holding {@code {version}}; a version the entry does not serve is none.
     */
    private Optional<String> latest(Entry entry, String header, Map<String, String> values) {
        if (entry.latest() == null) {
            return Optional.empty();
        }
        URI link = location(fill(entry.latest(), values));
        Optional<Head> head = transport.head(link);
        if (head.isEmpty() || head.get().status() == 404 || head.get().status() == 410) {
            return Optional.empty();
        }
        String named = head.get().header(header);
        if (named == null || named.isBlank()) {
            String target = head.get().header("Location");
            if (head.get().status() / 100 != 3 || target == null) {
                throw new DiscoveryException("The latest link " + link + " names no version: it answered "
                        + head.get().status() + " with neither " + header + " nor a redirect");
            }
            named = versionIn(entry.value(), values, link.resolve(target.strip()).toString())
                    .orElseThrow(() -> new DiscoveryException("The latest link " + link + " redirects to " + target
                            + ", which its template " + entry.value() + " does not describe"));
        }
        String version = named.strip();
        if (!VERSION.matcher(version).matches()) {
            throw new DiscoveryException("The latest link " + link + " names '" + version
                    + "', where it should name the newest version, such as 1.2.3");
        }
        return serves(entry, version) ? Optional.of(version) : Optional.empty();
    }

    // The version a redirect target names: the template up to the end of the segment holding {version}, every other
    // placeholder filled, matched against the start of the target.
    private static Optional<String> versionIn(String template, Map<String, String> values, String target) {
        int at = template.indexOf("{version}");
        if (at < 0) {
            return Optional.empty();
        }
        int end = template.indexOf('/', at);
        String prefix = end < 0 ? template : template.substring(0, end + 1);
        StringBuilder pattern = new StringBuilder();
        Matcher placeholder = Pattern.compile("\\{([^{}]*)}").matcher(prefix);
        int last = 0;
        boolean grouped = false;
        while (placeholder.find()) {
            pattern.append(Pattern.quote(prefix.substring(last, placeholder.start())));
            if (placeholder.group(1).equals("version") && !grouped) {
                pattern.append("([A-Za-z0-9._+-]+?)");
                grouped = true;
            } else if (placeholder.group(1).equals("version")) {
                pattern.append("\\1");
            } else {
                String value = values.get(placeholder.group(1));
                pattern.append(value == null ? "[^/]*" : Pattern.quote(value));
            }
            last = placeholder.end();
        }
        pattern.append(Pattern.quote(prefix.substring(last)));
        Matcher matcher = Pattern.compile(pattern.toString()).matcher(target);
        return matcher.lookingAt() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    // Where a root's Maven metadata of an artifact is.
    private URI rootMetadata(String root, String groupId, String artifactId) {
        String base = root.endsWith("/") ? root : root + "/";
        return location(base + groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml");
    }

    /** The versions a {@code maven-metadata.xml} lists, with the newest and the newest release it names. */
    private record Listing(String latest, String release, List<String> versions) {

        private Listing {
            versions = List.copyOf(versions);
        }

        // One version alone, a release only where it is one.
        static Listing of(String version) {
            return new Listing(version, stable(version) ? version : null, List.of(version));
        }

        // The versions an entry serves: its newest and newest release where the entry serves them, else the newest
        // of those it serves.
        Listing admitted(Entry entry) {
            List<String> served = versions.stream().filter(version -> serves(entry, version)).toList();
            return new Listing(
                    latest != null && serves(entry, latest) ? latest
                            : served.stream().max(RepositoryDiscovery::compare).orElse(null),
                    release != null && serves(entry, release) ? release
                            : served.stream().filter(RepositoryDiscovery::stable).max(RepositoryDiscovery::compare)
                                    .orElse(null),
                    served);
        }
    }

    // The Maven metadata at a document, or empty where it is not there; one that is no Maven metadata, or lists what is
    // no version, is refused for a latest link and absent for a root.
    private Optional<Listing> listing(URI document, boolean linked) {
        Optional<String> body = transport.read(document, MOST_METADATA_BYTES);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(body.get()));
            String latest = null;
            String release = null;
            List<String> versions = new ArrayList<>();
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "latest" -> latest = reader.getElementText().strip();
                        case "release" -> release = reader.getElementText().strip();
                        case "version" -> versions.add(reader.getElementText().strip());
                        default -> {
                        }
                    }
                }
            }
            for (String version : versions) {
                if (linked && !VERSION.matcher(version).matches()) {
                    throw new XMLStreamException("the version '" + version + "' is no version");
                }
            }
            return Optional.of(new Listing(latest == null || latest.isEmpty() ? null : latest,
                    release == null || release.isEmpty() ? null : release, versions));
        } catch (XMLStreamException unreadable) {
            if (linked) {
                throw new DiscoveryException("The latest link " + document + " names no Maven metadata: "
                        + unreadable.getMessage());
            }
            return Optional.empty();
        }
    }

    // Maven metadata of a listing, deterministic so its checksum is the same however often it is asked.
    private static byte[] metadataDocument(String groupId, String artifactId, Listing listing) {
        StringWriter text = new StringWriter();
        try {
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(text);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("metadata");
            element(writer, "groupId", groupId);
            element(writer, "artifactId", artifactId);
            writer.writeStartElement("versioning");
            if (listing.latest() != null) {
                element(writer, "latest", listing.latest());
            }
            if (listing.release() != null) {
                element(writer, "release", listing.release());
            }
            writer.writeStartElement("versions");
            for (String version : listing.versions()) {
                element(writer, "version", version);
            }
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (XMLStreamException unwritable) {
            throw new IllegalStateException(unwritable);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void element(XMLStreamWriter writer, String name, String text) throws XMLStreamException {
        writer.writeStartElement(name);
        writer.writeCharacters(text);
        writer.writeEndElement();
    }

    // A filled location as a URI, refused where it is no https address or names a host the screen refuses.
    private URI location(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException malformed) {
            throw new DiscoveryException("A discovered location is not a URI: " + value);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new DiscoveryException("A discovered location is not an https address: " + value);
        }
        if (refused.test(uri)) {
            throw new DiscoveryException("A discovered location names a host this deployment does not reach: "
                    + uri.getHost());
        }
        return uri;
    }

    // Every placeholder of a value replaced by its value, each percent-encoded as a path segment.
    private static String fill(String template, Map<String, String> values) {
        Matcher placeholder = Pattern.compile("\\{([^{}]*)}").matcher(template);
        StringBuilder filled = new StringBuilder();
        while (placeholder.find()) {
            String value = values.get(placeholder.group(1));
            if (value == null) {
                throw new DiscoveryException("The placeholder {" + placeholder.group(1) + "} of " + template
                        + " has no value for this request");
            }
            String encoded = placeholder.group(1).equals("groupPath")
                    ? String.join("/", Arrays.stream(value.split("/")).map(RepositoryDiscovery::segment).toList())
                    : segment(value);
            placeholder.appendReplacement(filled, Matcher.quoteReplacement(encoded));
        }
        placeholder.appendTail(filled);
        return filled.toString();
    }

    // A value as a path segment: every character but the unreserved ones percent-encoded.
    private static String segment(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append(c);
            } else {
                encoded.append('%').append(HexFormat.of().withUpperCase().toHexDigits(b));
            }
        }
        return encoded.toString();
    }
}
