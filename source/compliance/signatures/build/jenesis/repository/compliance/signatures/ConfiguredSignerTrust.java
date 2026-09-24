package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;

/**
 * Signer trust from the deployment's own configuration: the key material an operator supplied, and the identities they
 * pinned per namespace.
 *
 * <h2>What it answers, and what it deliberately does not</h2>
 *
 * This is the curated half of the trust model - the one an operator states outright. It holds no history, so it cannot
 * answer "who signed this coordinate's earlier versions"; {@link #expected} is always empty and {@link #observed} goes
 * nowhere. Key continuity is learned from what was ingested and needs durable state, which is a store-backed provider
 * rather than a settings string.
 *
 * <h2>The three postures, and why the empty one refuses</h2>
 *
 * <ul>
 *   <li><b>No keys configured.</b> Nothing is trusted. A signature may still verify against nothing - it cannot - so
 *       every one is reported untrusted. A deployment that has told us about no keys has given us no grounds to
 *       believe anyone, and reporting otherwise would be inventing an endorsement.</li>
 *   <li><b>Keys, no pins.</b> A key the operator supplied is trusted wherever it signs. They put it there.</li>
 *   <li><b>Keys and pins.</b> A namespace that carries a pin trusts <em>only</em> the identities pinned to it; a
 *       namespace with no pin falls back to the posture above. This is the scoped shape a keyring-per-namespace
 *       library has: a key admitted for one group must not thereby vouch for another, because the interesting attack
 *       is a real key signing something it has no business signing.</li>
 * </ul>
 *
 * <p>A pin is written {@code <namespace> = <identity>}, comma- or newline-separated, the namespace matching a
 * coordinate outright or as a prefix with a trailing {@code *} - the same spelling the version-floor and private-name
 * dimensions already use, so an operator learns it once.
 */
final class ConfiguredSignerTrust implements SignerTrust {

    /** The armoured public keys this deployment verifies against - one or more concatenated key blocks. */
    static final String KEYS = "signature-trusted-keys";

    /** Per-namespace pinned signer identities, e.g. {@code org.apache.* = openpgp:0x...BD0A}. */
    static final String PINS = "signature-trusted-signers";

    /** The PEM certificates a CMS (PKCS#7) signer's chain must reach - one or more concatenated CERTIFICATE blocks. */
    static final String CERTIFICATES = "signature-trusted-certificates";
    /** The PEM public keys a bare RSA signature (an apk's) is verified against, each optionally preceded by a
     *  {@code # <keyfile>} line naming the key file the ecosystem knows it by. */
    static final String PUBLIC_KEYS = "signature-trusted-public-keys";
    /** The Sigstore trusted root (the JSON naming Fulcio's certificate authorities and Rekor's logs) a bundle is
     *  verified against; holding it trusts no identity, which only a pin does. */
    static final String SIGSTORE_ROOT = "signature-sigstore-trusted-root";

    private final byte[] keys;
    private final byte[] certificates;
    private final byte[] publicKeys;
    private final byte[] sigstoreRoot;
    private final List<Pin> pins;

    private ConfiguredSignerTrust(byte[] keys, byte[] certificates, byte[] publicKeys, byte[] sigstoreRoot,
                                  List<Pin> pins) {
        this.keys = keys;
        this.certificates = certificates;
        this.publicKeys = publicKeys;
        this.sigstoreRoot = sigstoreRoot;
        this.pins = pins;
    }

    static SignerTrust from(UnaryOperator<String> config) {
        String armoured = value(config, KEYS);
        String pem = value(config, CERTIFICATES);
        String bare = value(config, PUBLIC_KEYS);
        String sigstore = value(config, SIGSTORE_ROOT);
        List<Pin> pins = pins(value(config, PINS));
        if (armoured.isBlank() && pem.isBlank() && bare.isBlank() && sigstore.isBlank() && pins.isEmpty()) {
            // Nothing configured at all: no material to verify against and nobody named. NONE says that honestly
            // rather than pretending to a trust store.
            //
            // A pin on its own is NOT nothing, and reading it as nothing was a defect. The argument used to be that
            // pins would be promises about keys we do not hold - true while this part was the only holder of any,
            // and false since the trusted root can be FETCHED rather than pasted. A deployment that names a root URL
            // and pins an identity holds material (in the fetched part) and names a signer (here), and every bundle
            // it verified read UNTRUSTED because this part had collapsed to NONE. A pin can never admit something
            // unverifiable in any case: trusts() is asked only after a signature has verified against somebody's
            // material.
            return SignerTrust.NONE;
        }
        return new ConfiguredSignerTrust(armoured.getBytes(StandardCharsets.UTF_8), pem.getBytes(StandardCharsets.UTF_8),
                bare.getBytes(StandardCharsets.UTF_8), sigstore.getBytes(StandardCharsets.UTF_8), pins);
    }

    @Override
    public Optional<byte[]> material(String scheme) {
        // One pool of armoured OpenPGP key material and one PEM bundle of X.509 anchors, each handed only to the
        // verifier shaped for it; a Fulcio root gets its own key when that verifier lands.
        if (SignerIdentity.OPENPGP.equals(scheme)) {
            return keys.length == 0 ? Optional.empty() : Optional.of(keys);
        }
        if (SignerIdentity.X509.equals(scheme)) {
            return certificates.length == 0 ? Optional.empty() : Optional.of(certificates);
        }
        if (SignerIdentity.RSA.equals(scheme)) {
            return publicKeys.length == 0 ? Optional.empty() : Optional.of(publicKeys);
        }
        if (SignerIdentity.SIGSTORE.equals(scheme)) {
            return sigstoreRoot.length == 0 ? Optional.empty() : Optional.of(sigstoreRoot);
        }
        return Optional.empty();
    }

    /** An operator's keyring is deployment-wide, so it anchors every ecosystem - and an instance built from pins
     *  alone holds none, which is why this is asked rather than assumed. */
    @Override
    public boolean anchored(String ecosystem) {
        return keys.length > 0 || certificates.length > 0 || publicKeys.length > 0 || sigstoreRoot.length > 0;
    }

    @Override
    public boolean trusts(SignerIdentity signer, String ecosystem, String coordinate) {
        if (signer == null) {
            return false;
        }
        List<Pin> matching = pins.stream().filter(pin -> pin.covers(coordinate)).toList();
        if (matching.isEmpty()) {
            // No pin speaks for this coordinate, so the operator's keyring stands on its own: they supplied the key,
            // and a key that verifies is one they put there. A Sigstore root is the exception: the public-good
            // Fulcio certifies anyone the issuers know, so holding its root vouches for no identity - only a pin
            // names one this deployment believes.
            //
            // The keyring has to actually exist for that argument to hold. An instance built from pins alone holds
            // no material, so a signature that verified did so against somebody ELSE's - a fetched root, a format's
            // provisioned keyring, a discovered key - and "they put it there" is not true of it. Such an instance
            // admits exactly what its pins name and nothing else.
            return anchored(ecosystem) && !SignerIdentity.SIGSTORE.equals(signer.scheme());
        }
        return matching.stream().anyMatch(pin -> pin.signer().equals(signer));
    }

    @Override
    public Optional<Expectation> expected(String ecosystem, String coordinate, String scheme) {
        // Only a pin of the scheme being asked about. An operator who pins a keyless identity for a namespace has
        // said nothing about the OpenPGP key that signs the same artifacts, and answering with the pin anyway
        // reported every such signature as a change of signer.
        return pins.stream()
                .filter(pin -> pin.covers(coordinate) && pin.signer().scheme().equals(scheme))
                .findFirst()
                .map(pin -> new Expectation(pin.signer(), 0, null, true));
    }

    @Override
    public void observed(String ecosystem, String coordinate, String version, SignerIdentity signer, Instant when) {
        // Configuration is stated, never learned. Continuity is the store-backed provider's to record.
    }

    /** One operator pin: a coordinate pattern and the identity it admits. */
    private record Pin(String namespace, boolean prefix, SignerIdentity signer) {

        boolean covers(String coordinate) {
            if (coordinate == null) {
                return false;
            }
            return prefix ? coordinate.startsWith(namespace) : coordinate.equals(namespace);
        }
    }

    private static List<Pin> pins(String configured) {
        List<Pin> pins = new ArrayList<>();
        for (String entry : configured.split("[,\\n]")) {
            String line = entry.strip();
            int equals = line.indexOf('=');
            if (line.isEmpty() || equals <= 0) {
                continue;
            }
            String namespace = line.substring(0, equals).strip();
            Optional<SignerIdentity> signer = SignerIdentity.ofWire(line.substring(equals + 1).strip());
            if (signer.isEmpty() || namespace.isEmpty()) {
                // A malformed pin is skipped rather than throwing: this is read on the publish path, and one typo in a
                // settings string must not take the gate down. The settings screen validates on write, which is where
                // an operator can still act on the mistake.
                continue;
            }
            boolean prefix = namespace.endsWith("*");
            pins.add(new Pin(prefix ? namespace.substring(0, namespace.length() - 1) : namespace, prefix,
                    signer.get()));
        }
        return List.copyOf(pins);
    }

    private static String value(UnaryOperator<String> config, String key) {
        String value = config.apply(key);
        return value == null ? "" : value;
    }
}
