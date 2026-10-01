package build.jenesis.repository.compliance.signatures;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.PrivateHosts;
import build.jenesis.repository.net.http.BoundedBody;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The pass that resolves the keys the screen met and could not place: for each wanted key id
 * ({@link DiscoveredKeys#WANTED}) it asks the configured sources - the public keyservers by key id, the Web Key
 * Directory by each maintainer e-mail the artifact's metadata names, GitHub by each login - and appends what it finds
 * to the repository's discovered bundle, which the trust reads at the next verification. A key found through a
 * maintainer is {@linkplain DiscoveredKeys#bind bound} to them, so it vouches only for what names them. A key nobody has
 * is asked for again after a day; a source that does not answer leaves the marker and counts a failure. Off the
 * request path, so a verdict never depends on a keyserver's uptime.
 *
 * <p>{@code signature-key-discovery} is empty by default and the pass does not run, so nothing is fetched until an
 * operator names a source. {@value #KEYSERVER_UBUNTU} and {@value #KEYS_OPENPGP_ORG} look a key up by its id, which
 * names no person; named both, they are asked in order, since the first keeps every user id and a key is often on only
 * one; each has its own URL for a mirror. {@code wkd} and {@code github} are looked up by what the artifact names, a
 * further decision about whom the deployment talks to.
 *
 * <p>Fetching is not trusting: a discovered key verifies while the outcome stays {@code UNTRUSTED} until the operator
 * admits it, or accepts the sources outright ({@link DiscoveredKeys}).
 */
public final class KeyDiscoveryTask implements MaintenanceTask {

    static final String NAME = "key-discovery";

    /** The sources asked, comma-separated: {@value #KEYSERVER_UBUNTU}, {@value #KEYS_OPENPGP_ORG}, {@value #WKD},
     *  {@value #GITHUB}; empty, the default, asks none. */
    static final String SOURCES = "signature-key-discovery";
    static final String KEYSERVER_UBUNTU = "keyserver.ubuntu.com";
    static final String KEYS_OPENPGP_ORG = "keys.openpgp.org";
    static final String WKD = "wkd";
    static final String GITHUB = "github";

    /** Whether a discovered key is admitted outright, rather than verifying while the outcome stays untrusted. */
    static final String ACCEPT = "signature-key-discovery-accept";

    /** Where keys.openpgp.org is reached - the public instance by default, an internal mirror where a deployment has one. */
    static final String URL = "signature-key-discovery-url";
    static final String DEFAULT_URL = "https://keys.openpgp.org";

    /** Where keyserver.ubuntu.com is reached - the public instance by default, or any host speaking HKP. */
    static final String UBUNTU_URL = "signature-key-discovery-ubuntu-url";
    static final String DEFAULT_UBUNTU = "https://keyserver.ubuntu.com";

    static final String DEFAULT_GITHUB = "https://github.com";

    static final IntervalSetting INTERVAL = IntervalSetting.of("signature-key-discovery-interval", "PT1H");

    /** How long a key no source had waits before it is asked for again. */
    static final Duration RETRY_MISS = Duration.ofDays(1);

    /** The most wanted keys one pass resolves; a burst drains over passes. */
    static final int PER_PASS = 200;

    /** The most of one answer a lookup reads; a key flooded with certifications past it is refused. */
    static final int LARGEST_KEY = 4 * 1024 * 1024;

    private static final System.Logger LOGGER = System.getLogger(KeyDiscoveryTask.class.getName());

    /** One lookup by key id: the armoured key when the source has it, empty when it does not; a source that could
     *  not be asked throws. */
    @FunctionalInterface
    public interface Fetcher {

        Optional<byte[]> fetch(String keyId) throws IOException;
    }

    /** One lookup by a maintainer's e-mail address or GitHub login: every key the source publishes for them, or empty;
     *  a source that could not be asked throws. */
    @FunctionalInterface
    public interface MaintainerFetcher {

        Optional<byte[]> fetch(String identity) throws IOException;
    }

    /** The sources a pass asks, any of them absent: by key id, by e-mail address, by GitHub login. */
    public record Sources(Fetcher byKeyId, MaintainerFetcher byEmail, MaintainerFetcher byGithub) {
    }

    private final Duration interval;
    private final Sources sources;

    public KeyDiscoveryTask(Duration interval, Fetcher fetcher) {
        this(interval, new Sources(fetcher, null, null));
    }

    public KeyDiscoveryTask(Duration interval, Sources sources) {
        this.interval = interval;
        this.sources = sources;
    }

    /** Whether the configuration names a source this pass knows. */
    static boolean enabled(UnaryOperator<String> config) {
        return names(config, KEYSERVER_UBUNTU) || names(config, KEYS_OPENPGP_ORG)
                || names(config, WKD) || names(config, GITHUB);
    }

    /** Whether the sources setting names this source. */
    static boolean names(UnaryOperator<String> config, String source) {
        String sources = config == null ? null : config.apply(SOURCES);
        return sources != null && Arrays.stream(sources.split(","))
                .map(String::trim)
                .anyMatch(source::equalsIgnoreCase);
    }

    static boolean accepts(UnaryOperator<String> config) {
        String accept = config == null ? null : config.apply(ACCEPT);
        return accept != null && "true".equalsIgnoreCase(accept.trim());
    }

    /** The keys.openpgp.org lookup over HTTP: {@code GET <base>/vks/v1/by-keyid/<key id>}. */
    public static Fetcher vks(String base) {
        String root = (base == null || base.isBlank() ? DEFAULT_URL : base.trim()).replaceAll("/+$", "");
        HttpClient client = client();
        return keyId -> get(client, URI.create(root + "/vks/v1/by-keyid/" + keyId), "application/pgp-keys",
                "key " + keyId);
    }

    /**
     * The HKP lookup every SKS-descended server speaks: {@code GET <base>/pks/lookup?op=get&options=mr&search=0x<key id>},
     * {@code options=mr} asking for the machine-readable answer.
     */
    public static Fetcher hkp(String base) {
        String root = (base == null || base.isBlank() ? DEFAULT_UBUNTU : base.trim()).replaceAll("/+$", "");
        HttpClient client = client();
        return keyId -> get(client, URI.create(root + "/pks/lookup?op=get&options=mr&search=0x" + keyId),
                "application/pgp-keys", "key " + keyId);
    }

    /**
     * The named sources as one lookup, asked in order until one has the key; a failure is raised only if no later source
     * answers, so one server down does not hide the other.
     */
    public static Fetcher first(List<Fetcher> sources) {
        List<Fetcher> asked = List.copyOf(sources);
        if (asked.size() == 1) {
            return asked.getFirst();
        }
        return keyId -> {
            IOException unreachable = null;
            for (Fetcher source : asked) {
                try {
                    Optional<byte[]> found = source.fetch(keyId);
                    if (found.isPresent()) {
                        return found;
                    }
                } catch (IOException failed) {
                    unreachable = failed;
                }
            }
            if (unreachable != null) {
                throw unreachable;
            }
            return Optional.empty();
        };
    }

    /**
     * The Web Key Directory lookup ({@link WebKeyDirectory}): the advanced method, then the direct one, or with
     * {@code base} the direct method at that one host. A domain taken from package metadata is screened as a proxy fetch
     * is, private addresses refused and no redirect followed; an operator-named directory is asked as named.
     */
    public static MaintainerFetcher wkd(String base) {
        boolean named = base != null && !base.isBlank();
        HttpClient client = named ? client() : ScreenedHttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        return address -> {
            List<URI> lookups = named
                    ? List.of(WebKeyDirectory.lookup(base.trim(), address))
                    : WebKeyDirectory.lookups(address).stream()
                            .filter(lookup -> lookup.getHost() != null
                                    && !PrivateHosts.resolvesToPrivate(lookup.getHost()))
                            .toList();
            IOException unreachable = null;
            for (URI lookup : lookups) {
                try {
                    Optional<byte[]> found = get(client, lookup, "application/octet-stream", address);
                    if (found.isPresent()) {
                        return found;
                    }
                } catch (IOException failed) {
                    unreachable = failed;   // the advanced host is commonly absent; the direct one decides
                }
            }
            if (unreachable != null && lookups.size() > 1) {
                throw unreachable;
            }
            return Optional.empty();
        };
    }

    /** GitHub's published keys of a login: {@code GET <base>/<login>.gpg}, armoured, every key the user added. */
    public static MaintainerFetcher github(String base) {
        String root = (base == null || base.isBlank() ? DEFAULT_GITHUB : base.trim()).replaceAll("/+$", "");
        HttpClient client = client();
        return login -> {
            if (!login.matches("[A-Za-z0-9-]+")) {
                return Optional.empty();
            }
            return get(client, URI.create(root + "/" + login + ".gpg"), "text/plain", "login " + login)
                    .filter(body -> body.length > 0);
        };
    }

    private static HttpClient client() {
        return ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .redirectsWithinPrivateNetwork().build();
    }

    private static Optional<byte[]> get(HttpClient client, URI url, String accept, String what) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, BoundedBody.ofByteArray(url, LARGEST_KEY));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted asking " + url + " for " + what, interrupted);
        }
        if (response.statusCode() == 200) {
            return Optional.of(response.body());
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        throw new IOException(url + " answered " + response.statusCode() + " for " + what);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        if (!enabled(context.config())) {
            return;
        }
        ArtifactStore store = context.store();
        String configured = context.config().apply(ConfiguredSignerTrust.KEYS);
        byte[] held = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
        List<String> wanted = new ArrayList<>();
        store.page(DiscoveredKeys.WANTED.substring(0, DiscoveredKeys.WANTED.length() - 1), "", PER_PASS, wanted::add);
        Optional<SignatureScheme> installed = SignatureScheme.installed(ArtifactSignatures.Scheme.OPENPGP_DETACHED);
        if (installed.isEmpty()) {
            // Without an OpenPGP verifier nothing could read a key, so the markers stay.
            return;
        }
        SignatureScheme openpgp = installed.get();
        long discovered = 0, missed = 0, failed = 0, settled = 0;
        for (String key : wanted) {
            String keyId = key.substring(key.lastIndexOf('/') + 1);
            String marker = DiscoveredKeys.WANTED + keyId;
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(marker);
            if (stored.isEmpty()) {
                continue;
            }
            Optional<Instant> missedAt = DiscoveredKeys.missedAt(stored.get().content());
            if (missedAt.isPresent() && missedAt.get().plus(RETRY_MISS).isAfter(context.now())) {
                continue;   // asked recently and not there; a source is not asked again on every pass
            }
            if (openpgp.holdsKey(keyId, held) || DiscoveredKeys.holds(store, keyId)) {
                store.delete(marker);   // held after all: configured meanwhile, or fetched by a peer's pass
                settled++;
                continue;
            }
            try {
                if (resolve(store, openpgp, keyId, DiscoveredKeys.maintainers(stored.get().content()))) {
                    store.delete(marker);
                    discovered++;
                } else {
                    DiscoveredKeys.missed(store, keyId, context.now());
                    missed++;
                }
            } catch (IOException | RuntimeException unavailable) {
                failed++;
                LOGGER.log(System.Logger.Level.WARNING, "Key discovery could not ask for " + keyId
                        + "; the next pass asks again", unavailable);
            }
        }
        Map<String, String> tags = Map.of("tenant", context.tenant(), "repository", context.repository());
        context.counter("jenrepo.signature.keys.discovered", "Signing keys fetched from a discovery source", tags,
                discovered);
        context.counter("jenrepo.signature.keys.missed", "Wanted signing keys no discovery source had", tags,
                missed);
        context.counter("jenrepo.signature.keys.unavailable", "Wanted signing keys a discovery source could not be "
                + "asked for", tags, failed);
        if (discovered + missed + failed + settled > 0) {
            LOGGER.log(System.Logger.Level.INFO, "Key discovery for " + context.tenant() + "/" + context.repository()
                    + ": " + discovered + " discovered, " + missed + " not at any source, " + failed + " unavailable, "
                    + settled + " held already");
        }
    }

    /**
     * Asks for one wanted key by its id, then by each maintainer e-mail, then each GitHub login; the first hit lands in
     * the bundle, bound to the maintainer it was found through.
     */
    private boolean resolve(ArtifactStore store, SignatureScheme openpgp, String keyId, Set<String> maintainers)
            throws IOException {
        if (sources.byKeyId() != null) {
            Optional<byte[]> fetched = sources.byKeyId().fetch(keyId);
            if (fetched.isPresent() && openpgp.holdsKey(keyId, fetched.get())) {
                DiscoveredKeys.add(store, openpgp.trustMaterial(fetched.get()).orElseThrow());
                return true;
            }
        }
        for (String maintainer : maintainers) {
            MaintainerFetcher source;
            String identity;
            String name;
            if (maintainer.startsWith("mailto:") && sources.byEmail() != null) {
                source = sources.byEmail();
                identity = maintainer.substring("mailto:".length());
                name = WKD;
            } else if (maintainer.startsWith("github:") && sources.byGithub() != null) {
                source = sources.byGithub();
                identity = maintainer.substring("github:".length());
                name = GITHUB;
            } else {
                continue;
            }
            Optional<byte[]> fetched = source.fetch(identity);
            if (fetched.isPresent() && openpgp.holdsKey(keyId, fetched.get())) {
                DiscoveredKeys.add(store, openpgp.trustMaterial(fetched.get()).orElseThrow());
                DiscoveredKeys.bind(store, keyId, maintainer, name);
                return true;
            }
        }
        return false;
    }
}
