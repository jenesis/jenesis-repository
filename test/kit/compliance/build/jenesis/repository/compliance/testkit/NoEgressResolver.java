package build.jenesis.repository.compliance.testkit;

import module java.base;

/**
 * The hermetic-run tripwire, as a mechanism rather than a copy: a JDK {@link InetAddressResolverProvider} that refuses
 * to resolve any host but the local one and <em>records</em> every attempt, so "this suite performs no network I/O" is
 * an enforced property of the test JVM rather than a convention the next fixture can quietly break.
 *
 * <p><strong>Why it is a base class and not a shared instance.</strong> A JDK service implementation must be a member
 * of the module whose {@code provides} clause names it, so two test modules cannot share one provider class - but they
 * can, and here do, share the refusal, the delegation rule and the record. A subclass is four lines: a public
 * no-argument constructor for {@link ServiceLoader}, a {@link #name()}, and the {@link #refusal(String)} sentence its
 * own suite wants read when a check reaches for the network. Copying the mechanism instead is what produced twelve
 * subtly different {@code Requirement}s before {@code source/ecosystem-testkit} ended it.
 *
 * <p>Refusing rather than merely observing is deliberate: on a box with no egress a real lookup blocks for a DNS
 * timeout, so a tripwire that only recorded would still be paying for the mistake it reports. Loopback and the
 * machine's own host name are delegated to the built-in resolver, so nothing else in the JVM is disturbed - and a
 * recorded-response server bound to the loopback <em>address</em> needs no name resolution at all, which is what makes
 * every entry in {@link #attempted()} unambiguously a reach for a vendor.
 *
 * <p>The record is static because a JDK-installed resolver is process-wide by construction: a suite brackets each
 * check with {@link #attempted()} and {@link #since(List)} to attribute an egress attempt to the check that made it.
 * Every consumer owes one test proving its provider is really installed - an undiscovered provider would leave every
 * no-egress assertion in that suite vacuously true while real lookups sailed past.
 *
 * <h2>A JVM-wide proxy is switched off, because it carries a connection past the tripwire</h2>
 * A resolver provider sees name lookups, and a connection made through a proxy makes none: the client connects to
 * the proxy's address, a literal that needs no lookup, and the proxy resolves the host. So a JVM started with
 * {@code -Dhttps.proxyHost} - which a hosted session's tool options do, for every JVM the build forks - reaches
 * any host it likes while this resolver records nothing, and the hermetic claim is a convention again. Measured
 * 2026-09-20 in exactly that session: the Maven inspector's transitive walk, which the contract suite proves
 * degrades when nothing is published beside a POM, reached Maven Central through the session's proxy, took the
 * proxy's {@code 404} for the unpublished dependency as a resolved closure, and answered two subjects the
 * hermetic run never sees. {@link #direct()} clears the JDK's proxy properties for the life of the JVM, from this
 * class's initializer and from the suites that assert on the network's absence, so a proxied JVM refuses exactly
 * what an unproxied one does.
 *
 * <h2>The JDK's failed-lookup cache is switched off, because it makes the record lie</h2>
 * {@code java.security} ships {@code networkaddress.cache.negative.ttl=10}, so a name that was refused once is
 * answered from {@link InetAddress}'s own cache for the next ten seconds and this resolver is <em>never consulted</em>
 * for the repeat. The record then shows one attempt where two were made, and a second check reaching the same vendor
 * reads as a check that reached nothing at all - which is the exact opposite of the truth, and the failure mode a
 * measuring instrument may not have. It matters as soon as two implementations share a host, which they do: the
 * OpenSSF malicious-packages dataset is served by the OSV API, and VulnCheck ships an advisory feed and a
 * known-exploited index behind one endpoint.
 *
 * <p>The static initializer therefore sets the security property to {@code 0} for this test JVM. It runs in time
 * because {@code ServiceLoader} constructs this provider from inside the <em>first</em> name resolution, before the
 * JDK's cache policy has been read - but "in time" is a timing claim, so {@link #negativeLookupCacheTtl()} reports
 * the value that actually took effect and each consumer's tripwire test asserts a repeated lookup is recorded twice.
 * A failure there means the record has silently become lossy rather than wrong-looking.
 */
public abstract class NoEgressResolver extends InetAddressResolverProvider {

    /** The JDK security property governing how long a <em>failed</em> lookup is answered from cache. */
    private static final String NEGATIVE_TTL = "networkaddress.cache.negative.ttl";

    private static final List<String> ATTEMPTED = new CopyOnWriteArrayList<>();

    /** The system properties a JVM-wide proxy is configured through - the JDK's own names, read by
     *  {@code URLConnection} and {@code HttpClient} alike on every connection. */
    private static final List<String> PROXY_KEYS = List.of(
            "http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
            "socksProxyHost", "socksProxyPort", "java.net.useSystemProxies");

    static {
        // Every refusal must reach this resolver, or the record under-counts and a shared vendor host makes one
        // consumer's measurement vacuous. Test-JVM only: this class is never on a runtime module path.
        Security.setProperty(NEGATIVE_TTL, "0");
        direct();
    }

    /**
     * Switch off any JVM-wide proxy, so that every connection resolves its host here and none is carried past the
     * tripwire by a proxy that resolves it instead. Process-wide and never restored, for the same reason the
     * failed-lookup cache is: a module that installs this resolver claims that no suite in its JVM performs network
     * I/O, so no suite in it has a use for a proxy. Idempotent; the static initializer calls it, and a suite that
     * asserts on the network's absence calls it too, since the JDK loads a resolver provider on the first name
     * lookup and a connection to a proxy's address is not one.
     *
     * @return the properties that were set and are now cleared, oldest first, for a message that says what happened
     */
    public static List<String> direct() {
        List<String> cleared = new ArrayList<>();
        for (String key : PROXY_KEYS) {
            if (System.getProperty(key) != null) {
                System.clearProperty(key);
                cleared.add(key);
            }
        }
        return List.copyOf(cleared);
    }

    /** For {@code ServiceLoader}, which constructs the subclass named in a test module's {@code provides} clause. */
    protected NoEgressResolver() {
    }

    /** The failed-lookup cache window this JVM ended up with - {@code "0"} when the class initialized before the JDK
     *  read its cache policy, which is what makes {@link #attempted()} a complete record rather than a sampled one. */
    public static String negativeLookupCacheTtl() {
        return Security.getProperty(NEGATIVE_TTL);
    }

    /** The sentence a refused lookup carries - the suite's own explanation of why reaching {@code host} is a defect,
     *  written where the reader of the failure needs it. */
    protected abstract String refusal(String host);

    @Override
    public final InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {

            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                if (local(host, configuration)) {
                    return builtin.lookupByName(host, policy);
                }
                ATTEMPTED.add(host);
                throw new UnknownHostException(refusal(host));
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return builtin.lookupByAddress(address);
            }
        };
    }

    /** Every host a lookup was refused for in this JVM, oldest first. */
    public static List<String> attempted() {
        return List.copyOf(ATTEMPTED);
    }

    /** The hosts attempted since {@code before} was taken - what one bracketed check reached for. */
    public static List<String> since(List<String> before) {
        List<String> now = attempted();
        return now.size() <= before.size() ? List.of() : List.copyOf(now.subList(before.size(), now.size()));
    }

    private static boolean local(String host, Configuration configuration) {
        if (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            return host.equalsIgnoreCase(configuration.lookupLocalHostName());
        } catch (RuntimeException _) {
            return false;
        }
    }
}
