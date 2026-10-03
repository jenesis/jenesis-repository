package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;

/**
 * Signer trust from the deployment's own configuration: the key material an operator supplied and the identities they
 * pinned per namespace. It holds no history, so {@link #expected} is empty and {@link #observed} records nothing;
 * continuity is the store-backed part's.
 *
 * <ul>
 *   <li><b>No keys:</b> nothing is trusted, since nothing gives grounds to believe anyone.</li>
 *   <li><b>Keys, no pins:</b> a supplied key is trusted wherever it signs.</li>
 *   <li><b>Keys and pins:</b> a pinned namespace trusts only its pinned identities, so a key admitted for one group
 *       cannot vouch for another; an unpinned namespace falls back to the keys.</li>
 * </ul>
 *
 * <p>A pin is {@code <namespace> = <identity>}, comma- or newline-separated, the namespace matching outright or as a
 * prefix with a trailing {@code *}, as the version-floor and private-name dimensions spell it.
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
            // Nothing configured. A pin alone is not nothing: it names a signer whose material may be fetched.
            return SignerTrust.NONE;
        }
        return new ConfiguredSignerTrust(armoured.getBytes(StandardCharsets.UTF_8), pem.getBytes(StandardCharsets.UTF_8),
                bare.getBytes(StandardCharsets.UTF_8), sigstore.getBytes(StandardCharsets.UTF_8), pins);
    }

    @Override
    public Optional<byte[]> material(String scheme) {
        // Each kind of material goes only to the verifier shaped for it.
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

    /** An operator's keyring anchors every ecosystem; an instance built from pins alone anchors none. */
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
            // Unpinned: the operator's own keyring is trusted, if there is one; a Sigstore root vouches for nobody.
            return anchored(ecosystem) && !SignerIdentity.SIGSTORE.equals(signer.scheme());
        }
        return matching.stream().anyMatch(pin -> pin.signer().equals(signer));
    }

    @Override
    public Optional<Expectation> expected(String ecosystem, String coordinate, String scheme) {
        // Only a pin of the scheme asked about, so a keyless pin says nothing about an OpenPGP signer.
        return pins.stream()
                .filter(pin -> pin.covers(coordinate) && pin.signer().scheme().equals(scheme))
                .findFirst()
                .map(pin -> new Expectation(pin.signer(), 0, null, true));
    }

    @Override
    public void observed(String ecosystem, String coordinate, String version, SignerIdentity signer, Instant when) {
        // Configuration is stated, never learned.
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
                // Skipped rather than thrown on the publish path; the settings screen validates on write.
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

    @Override
    public String source() {
        return "configured";
    }
}
