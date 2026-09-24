package build.jenesis.repository.compliance.signatures;

import module java.base;
import module java.net.http;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The pass that resolves the keys the screen met and could not place: for every wanted key id
 * ({@link DiscoveredKeys#WANTED}) it asks the sources configured - the two public keyservers by key id, the Web
 * Key Directory by each e-mail address the artifact's metadata names as a maintainer, GitHub by each login it
 * names - and appends what it finds to the repository's discovered bundle,
 * where the trust reads it by point read at the next verification. A key found through a maintainer is
 * {@linkplain DiscoveredKeys#bind bound} to that maintainer, since it was found because an artifact named them and
 * must not vouch for artifacts that do not. A key no source has is asked for again after a day; a source that does
 * not answer leaves the marker for the next pass and counts a failure. Nothing here runs on a request path, which
 * is what the trust provider's read-purity clause asks: a verdict reads what was last discovered, never a
 * keyserver's uptime.
 *
 * <h2>Nothing is asked until a source is named</h2>
 *
 * {@code signature-key-discovery} is empty by default and this pass does not run, so a stock deployment makes
 * no outbound call of any kind. That is the posture the rest of the product keeps - the public advisory feeds
 * are opt-in for the same reason - and it is the one an operator can reverse in one place: naming a source
 * switches the pass on for that source alone. The dial is the list rather than a boolean beside one, so what
 * is asked and whether anything is asked are one answer.
 *
 * <p><b>Two public keyservers are supported, and each is one word.</b> {@value #KEYSERVER_UBUNTU} and
 * {@value #KEYS_OPENPGP_ORG} both look a key up by the signature's own key id, which names no person and
 * reveals only that this deployment met a signature by that key; naming both asks them in the order written,
 * which is the order the build tool asks them in and for the same reason - the first keeps every user id a key
 * carries, the second serves them only for an address its owner verified, and a key exists on one and not the
 * other often enough that asking one is asking half. Each has a URL of its own so an internal mirror answers
 * instead. {@code wkd} and {@code github} are looked up by an address or a login the <em>artifact</em> names
 * rather than by a host the operator chose, so switching those on is a further decision about whom a
 * deployment talks to on a publisher's say-so.
 *
 * <p>Fetching is still not trusting: a discovered key verifies a signature while the outcome stays
 * {@code UNTRUSTED}, saying the key was discovered, until the operator admits it - by pasting it into the
 * trusted keys, or by accepting the sources outright, which is off by default ({@link DiscoveredKeys}).
 */
public final class KeyDiscoveryTask implements MaintenanceTask {

    static final String NAME = "key-discovery";

    /** The sources asked, comma-separated: {@value #KEYSERVER_UBUNTU}, {@value #KEYS_OPENPGP_ORG},
     *  {@value #WKD}, {@value #GITHUB}. Empty - which is what a deployment that says nothing has - asks none. */
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

    /** The most wanted keys one pass resolves, so a burst of unknown signers is drained over passes rather than in one. */
    static final int PER_PASS = 200;

    private static final System.Logger LOGGER = System.getLogger(KeyDiscoveryTask.class.getName());

    /** One lookup by key id: the armoured key when the source has it, empty when it does not; a source that could
     *  not be asked throws. */
    @FunctionalInterface
    public interface Fetcher {

        Optional<byte[]> fetch(String keyId) throws IOException;
    }

    /** One lookup by a maintainer's own identity - an e-mail address, a GitHub login - answering every key the
     *  source publishes for them, binary or armoured, or empty when it publishes none; a source that could not be
     *  asked throws. Whether the wanted key is among them is the pass's question, not the source's. */
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

    /** Whether the sources setting names this source; a setting nothing has written names none, so nothing is
     *  asked and the pass does not run. */
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
     * The HKP lookup keyserver.ubuntu.com and every SKS-descended server speaks:
     * {@code GET <base>/pks/lookup?op=get&options=mr&search=0x<key id>}, answering one armoured key.
     * {@code options=mr} is what asks for the machine-readable answer rather than the HTML page, and the
     * {@code 0x} prefix is the spelling the protocol names a key id in.
     */
    public static Fetcher hkp(String base) {
        String root = (base == null || base.isBlank() ? DEFAULT_UBUNTU : base.trim()).replaceAll("/+$", "");
        HttpClient client = client();
        return keyId -> get(client, URI.create(root + "/pks/lookup?op=get&options=mr&search=0x" + keyId),
                "application/pgp-keys", "key " + keyId);
    }

    /**
     * The named sources as one lookup, asked in order until one has the key.
     *
     * <p>A source that cannot be reached must not hide the next: the failure is kept and raised only if no
     * later source answers, so one keyserver being down leaves the pass asking the other rather than counting a
     * failure and waiting a day. Empty from every source is empty, which is the pass's "nobody has it".
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
     * The Web Key Directory lookup: the advanced method on the address's {@code openpgpkey} subdomain, then the
     * direct method on the domain, as the draft orders them ({@link WebKeyDirectory}). With {@code base} given,
     * the direct method rooted at that one host for every domain - a stub, or an internal directory that mirrors
     * every domain a deployment's maintainers use.
     */
    public static MaintainerFetcher wkd(String base) {
        HttpClient client = client();
        return address -> {
            List<URI> lookups = base == null || base.isBlank()
                    ? WebKeyDirectory.lookups(address)
                    : List.of(WebKeyDirectory.lookup(base.trim(), address));
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
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private static Optional<byte[]> get(HttpClient client, URI url, String accept, String what) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
            // No OpenPGP verifier is installed, so nothing could read what a source served or say whether a keyring
            // holds a key: the wanted markers stay for a deployment that gains the verifier, and nothing is fetched.
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
        context.counter("jenreg.signature.keys.discovered", "Signing keys fetched from a discovery source", tags,
                discovered);
        context.counter("jenreg.signature.keys.missed", "Wanted signing keys no discovery source had", tags,
                missed);
        context.counter("jenreg.signature.keys.unavailable", "Wanted signing keys a discovery source could not be "
                + "asked for", tags, failed);
        if (discovered + missed + failed + settled > 0) {
            LOGGER.log(System.Logger.Level.INFO, "Key discovery for " + context.tenant() + "/" + context.repository()
                    + ": " + discovered + " discovered, " + missed + " not at any source, " + failed + " unavailable, "
                    + settled + " held already");
        }
    }

    /**
     * Ask the sources for one wanted key, in the order they are named: by the key's own id, then by each maintainer
     * e-mail address, then by each GitHub login. A source that answers keys not including the wanted one has not
     * found it - a maintainer may publish several. The first hit lands in the bundle; found through a maintainer, it
     * is bound to them.
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
