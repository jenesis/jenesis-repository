package build.jenesis.repository.compliance;

import module java.base;

/**
 * Who signed something, in a form no scheme owns - a {@code scheme} naming the kind of identity and a {@code value}
 * spelling it within that kind. An OpenPGP key is its fingerprint, a Sigstore signer is the OIDC issuer and subject
 * that Fulcio certified, an X.509 signer is the SHA-256 of its subject public-key info.
 *
 * <p>It exists because "browse everything this key signed" and "hold everything this key signed" have to work for a
 * cosign workflow identity as well as for a maintainer's PGP key, and a fingerprint-shaped field cannot hold the
 * first. Keying the index, the trust ledger and the operator's pinning syntax on this instead means a new scheme adds
 * a constant and nothing else: no second index, no second screen, no second settings key.
 *
 * <p>{@link #wire()} is the one spelling every surface uses - the settings value an operator pins, the path segment
 * the API addresses, the index key. It is deliberately stable and lower-case so two surfaces cannot disagree about
 * whether {@code 0xAB} and {@code 0xab} are the same signer.
 */
public record SignerIdentity(String scheme, String value) implements Comparable<SignerIdentity> {

    /** An OpenPGP key, by fingerprint. */
    public static final String OPENPGP = "openpgp";

    /** A Sigstore signer, by the OIDC issuer and subject its Fulcio certificate carries. */
    public static final String SIGSTORE = "sigstore";

    /** An X.509 signer, by the SHA-256 of its {@code SubjectPublicKeyInfo}. */
    public static final String X509 = "x509";
    /** A bare RSA key, named as the ecosystem names it: the key file an apk signature member carries. */
    public static final String RSA = "rsa";

    private static final Pattern SCHEME = Pattern.compile("[a-z0-9]{1,16}");

    public SignerIdentity {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(value, "value");
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!SCHEME.matcher(scheme).matches()) {
            throw new IllegalArgumentException("Not a signer scheme: " + scheme);
        }
        if (value.isBlank() || value.indexOf('/') >= 0) {
            // The wire form is one path segment on the API and one key segment in the index, so a value carrying a
            // separator would address a neighbouring space - the same screen every coordinate takes before it becomes
            // a key.
            throw new IllegalArgumentException("Not a signer value: " + value);
        }
    }

    /** An OpenPGP signer, by key fingerprint; case and any {@code 0x} prefix are normalised away. */
    public static SignerIdentity openpgp(String fingerprint) {
        String bare = fingerprint.strip();
        if (bare.regionMatches(true, 0, "0x", 0, 2)) {
            bare = bare.substring(2);
        }
        return new SignerIdentity(OPENPGP, bare.replace(" ", "").toUpperCase(Locale.ROOT));
    }

    /** A Sigstore signer, by the issuer and subject its certificate carries. */
    public static SignerIdentity sigstore(String issuer, String subject) {
        return new SignerIdentity(SIGSTORE, encode(issuer) + "|" + encode(subject));
    }

    /** The issuer and subject a Sigstore identity carries, or empty for an identity of another scheme. */
    public Optional<Sigstore> sigstore() {
        int bar = SIGSTORE.equals(scheme) ? value.indexOf('|') : -1;
        return bar < 0
                ? Optional.empty()
                : Optional.of(new Sigstore(decode(value.substring(0, bar)), decode(value.substring(bar + 1))));
    }

    /** A Sigstore identity taken apart: the OIDC issuer, and the subject it certified. */
    public record Sigstore(String issuer, String subject) {
    }

    /** A bare RSA signer, by the key file the ecosystem names it with. */
    public static SignerIdentity rsa(String keyFile) {
        return new SignerIdentity(RSA, keyFile.strip());
    }

    public static SignerIdentity x509(String spkiSha256) {
        return new SignerIdentity(X509, spkiSha256.toLowerCase(Locale.ROOT));
    }

    /** The one spelling every surface uses: {@code <scheme>:<value>}. */
    public String wire() {
        return scheme + ":" + value;
    }

    /** The identity a wire spelling names, or empty when it is not one. */
    public static Optional<SignerIdentity> ofWire(String wire) {
        int colon = wire == null ? -1 : wire.indexOf(':');
        if (colon <= 0 || colon == wire.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SignerIdentity(wire.substring(0, colon), wire.substring(colon + 1)));
        } catch (IllegalArgumentException notAnIdentity) {
            return Optional.empty();
        }
    }

    /** A short form for a screen: a fingerprint's last sixteen, a Sigstore subject as it was certified, an X.509
     *  digest's first sixteen. */
    public String abbreviated() {
        return switch (scheme) {
            case OPENPGP -> value.length() > 16 ? value.substring(value.length() - 16) : value;
            case SIGSTORE -> decode(value.substring(value.indexOf('|') + 1));
            default -> value.length() > 16 ? value.substring(0, 16) : value;
        };
    }

    @Override
    public int compareTo(SignerIdentity other) {
        int scheme = this.scheme.compareTo(other.scheme);
        return scheme != 0 ? scheme : value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return wire();
    }

    /** A component of a composite value, with the separator and the segment separator escaped rather than refused. */
    private static String encode(String part) {
        return part.strip().replace("%", "%25").replace("|", "%7C").replace("/", "%2F");
    }

    /** The inverse of {@link #encode}, the escapes undone in the reverse order they were applied. */
    private static String decode(String part) {
        return part.replace("%2F", "/").replace("%7C", "|").replace("%25", "%");
    }
}
