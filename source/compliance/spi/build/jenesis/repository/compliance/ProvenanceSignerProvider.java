package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link ProvenanceSigner}, discovered at runtime with {@link ServiceLoader} - so a signing
 * implementation (DSSE over an RSA PEM key, an HSM-backed signer) is a drop-in module that {@code provides} this
 * interface, and the composition names no implementation. Each provider reads its own configuration through the
 * {@code config} lookup (a property/setting accessor returning {@code null} when unset), staying free of any
 * framework dependency, and yields empty when its signer is not configured. Unlike the feed SPIs there is no
 * combining: the repository's attestation identity is one signer, so where more than one is configured the
 * {@code provenance-signer} setting selects it by provider name, and an ambiguous or dangling selection is a
 * configuration error surfaced at startup rather than a silently multi-signed envelope.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations; {@link #create}
 *     runs on the resolving thread. The {@link ProvenanceSigner} it returns signs concurrently from every publish
 *     thread, so <em>that</em> object must be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> Signing the same statement twice must produce an envelope that verifies against
 *     the same identity; a signer that mints a fresh short-lived certificate per call (the keyless flow) may emit
 *     different bytes, but never a different attesting identity.</li>
 * <li><b>Absence sentinel.</b> {@link ProvenanceSigner#disabled()} is the sentinel and it is identity-comparable,
 *     so a caller tells "no signer is active" with {@code signer == ProvenanceSigner.disabled()}. It is the answer
 *     when no module is installed and when every installed signer is unconfigured. {@link #create} declares "I am
 *     not configured" with an empty {@link Optional}; {@code null} is never a legal return from it, from
 *     {@link #name()} or from {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> The {@code provenance-signer} setting selects the attestation identity by
 *     provider name. A selection no installed provider answers to, or whose provider is unconfigured, throws
 *     {@link IllegalStateException} at resolution naming the selection and the installed signer names - a
 *     misconfigured attestation identity must stop the start, never sign with the wrong key or silently fall back
 *     to the disabled signer. More than one <em>configured</em> signer with no selection is likewise ambiguous and
 *     throws rather than letting discovery order pick which key the repository attests with. Only an
 *     <em>unselected</em> deployment with nothing configured degrades, and only to the disabled signer.</li>
 * <li><b>Error visibility (&sect;9).</b> A provider whose configuration is present but unusable (an unreadable key
 *     file, a malformed Fulcio URL) is fail-soft <em>at the provider</em>: it logs and declines, so the deployment
 *     starts unsigned rather than refusing to boot. What may never happen silently is signing with an identity the
 *     operator did not choose, which is why an explicit selection is fail-fast.</li>
 * <li><b>Lifecycle / ownership.</b> The composition resolves the signer once and owns it; a signer may own the key
 *     material, HTTP client or transparency-log connection it needs and closes them itself. Exactly zero or one
 *     signer is ever constructed - the unconfigured candidates are eliminated by {@link #requiredConfig()} before
 *     anything is built, so no throw-away key is loaded.</li>
 * <li><b>Ordering / determinism.</b> The resolved signer is a function of the configuration and the installed
 *     providers only, never of discovery order; {@link #installed()} reports the same names on every module
 *     path.</li>
 * </ol>
 */
public interface ProvenanceSignerProvider {

    /** The signer name this provider answers to, e.g. {@code provenance}. */
    String name();

    /** Build the signer if the configuration enables it, reading settings through {@code config}; empty when off. */
    Optional<ProvenanceSigner> create(UnaryOperator<String> config);

    /** The config keys this signer cannot run without (the key path, the Fulcio URL); empty (the default) for a
     *  signer that needs nothing. This is what "configured" means for the selection primitive: a provider whose
     *  required keys are unset {@link Features#active self-disables} and is never a candidate, so an unconfigured
     *  second signer never makes the identity ambiguous - and no key is loaded to find that out. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The signer names installed on this deployment, regardless of configuration - the capability signal a
     *  console or API gates its surface on. */
    static Set<String> installed() {
        return Providers.installedNames("provenance-signer",
                ServiceLoader.load(ProvenanceSignerProvider.class),
                ProvenanceSignerProvider::name,
                _ -> true);
    }

    /** The single configured signer, resolved through the shared {@link Providers#optionalUnique} policy: the
     *  {@code provenance-signer} setting selects one by provider name and a selection nothing can honour
     *  <em>throws</em> (§9), more than one configured signer without a selection is ambiguous rather than a
     *  discovery-order winner, and only a deployment with nothing configured gets the
     *  {@link ProvenanceSigner#disabled() disabled} signer - a misconfigured attestation identity should stop the
     *  start, not silently sign with the wrong key. */
    static ProvenanceSigner resolve(UnaryOperator<String> config) {
        return Providers.optionalUnique("provenance-signer",
                        ServiceLoader.load(ProvenanceSignerProvider.class),
                        ProvenanceSignerProvider::name,
                        Features.selection(config, "provenance-signer"),
                        provider -> Features.active(config, provider.name(), provider.requiredConfig()),
                        provider -> provider.create(config))
                .orElseGet(ProvenanceSigner::disabled);
    }
}
