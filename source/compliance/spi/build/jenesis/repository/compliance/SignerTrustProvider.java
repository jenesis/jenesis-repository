package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Binds a deployment's {@link SignerTrust} to one repository's scoped store - the seam a screen resolves so it can
 * overlay trust onto the inspectors that verify signatures.
 *
 * <p>It takes the store rather than being a plain singleton because trust is tenant state: which keys a deployment
 * holds and which signer a coordinate has carried are facts about one repository, not about the process. A provider
 * that needs no store simply ignores it, which is what the configuration-backed one does.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #over} is called concurrently from publish and proxy threads and returns a value
 *     usable from the calling thread; an implementation keeps no per-call state on itself.</li>
 * <li><b>Absence sentinel.</b> No provider installed means {@link SignerTrust#NONE}, under which every signature that
 *     verifies is reported untrusted rather than trusted. That direction is deliberate and is the whole reason this
 *     is a sentinel rather than an exception: a deployment holding no keys has no grounds to believe a signature, and
 *     must never be told that it does because a module is missing.</li>
 * <li><b>Selection is ALL, and each provider is scoped.</b> Every installed provider contributes and their answers
 *     are unioned through {@link SignerTrust#composite}. This used to be {@code Providers.singleton}, on the
 *     reasoning that two providers disagreeing about a key would make the gate's answer a property of module-path
 *     ordering. That was the wrong cure: trust genuinely has several sources - what an operator configured, what a
 *     format's own operator-provisioned keyring holds, what a coordinate's history established - and forcing them
 *     into one implementation meant one class knowing about all of them. What keeps the union honest is that a
 *     provider answers {@code trusts} only for what it speaks for; a provider that trusts everything is a defect in
 *     that provider, composed or not.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #over} itself performs no I/O; the returned {@link SignerTrust} reads
 *     durably stored state and makes no outbound call. Fetching a key from a keyserver at verification time would put
 *     a third party on the publish path and make a verdict depend on their uptime.</li>
 * </ol>
 */
public interface SignerTrustProvider {

    /**
     * Bind signer trust to one tenant's effective configuration, and to the store its durable state lives in.
     *
     * <p>Configuration comes first because that is what trust mostly is: the keys an operator supplied and the
     * signers they pinned. It is deliberately not read from the store by the provider - a dial written through
     * {@code PUT /api/settings/{key}} lands in the deployment root while a screen is scoped to one repository, so a
     * provider reading "its" store would answer "nothing configured" for a deployment whose operator had just
     * configured it. The store is here for what genuinely is per-repository state: the continuity a coordinate's own
     * history establishes.
     */
    SignerTrust over(UnaryOperator<String> config, ArtifactStore store);

    /** Every installed provider, in discovery order. Empty means the gate verifies against nothing and trusts
     *  nobody, which is the fail-closed direction stated in the contract above. */
    static List<SignerTrustProvider> installed() {
        return Installed.PROVIDERS;
    }

    /**
     * The discovered providers, resolved once for the life of the class loader.
     *
     * <h4>Why this is a holder and not a {@code ServiceLoader.load} in the method</h4>
     *
     * It was the latter, and that made a per-request cost out of what had been a one-time one. Both screens resolve
     * trust per inspection - the publish screen per claiming inspector, the proxy screen per screened fetch - so a
     * {@code load()} inside {@link #installed} meant walking the whole module graph's service declarations on every
     * artifact, where the previous single-provider resolution had happened once in a static field.
     *
     * <p>It was measured rather than reasoned about: a rubygems proxy suite went from passing to failing across the
     * commit that introduced it, twice each way, with four {@code Broken pipe} writes in the failing runs and none
     * in the passing ones - {@code bundle install} abandoning a large index download the proxy had become too slow
     * to serve. No verdict changed and no logic differed, which is exactly why reading the code found nothing: the
     * defect was in what the path <em>cost</em>, not in what it decided.
     *
     * <p>Resolution happens on first use rather than at class-init so that a composition which never screens pays
     * nothing, and the list is immutable so callers cannot disturb it. A provider set is fixed for a JVM, so caching
     * it costs no correctness - which is what {@code Providers.singleton} was already relying on before this became
     * a list.
     */
    final class Installed {

        private static final List<SignerTrustProvider> PROVIDERS = load();

        private Installed() {
        }

        private static List<SignerTrustProvider> load() {
            List<SignerTrustProvider> providers = new ArrayList<>();
            ServiceLoader.load(SignerTrustProvider.class).forEach(providers::add);
            return List.copyOf(providers);
        }
    }

    /**
     * The deployment's trust for one tenant's configuration and one repository's store - every installed provider's
     * answer, unioned.
     *
     * <p>The one seam a screen or an observer calls, so none of them composes this itself and they cannot drift in
     * how they do it. With nothing installed it is {@link SignerTrust#NONE} rather than an exception or a null, so a
     * composition that carries no trust module verifies against nothing and reports every signature untrusted -
     * which is what a deployment holding no keys deserves to be told.
     */
    static SignerTrust trust(UnaryOperator<String> config, ArtifactStore store) {
        return SignerTrust.composite(installed().stream().map(provider -> provider.over(config, store)).toList());
    }
}
