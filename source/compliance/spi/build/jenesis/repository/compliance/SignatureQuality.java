package build.jenesis.repository.compliance;

import module java.base;

/**
 * How much a signature is worth, as a grade and the reasons behind it. Every input is arithmetic over what the
 * signature packet and the signer's key already state - an algorithm, a bit length, a digest name, two instants - so
 * a grade is a fact rather than an opinion, and the reasons name the values they were computed from.
 *
 * <p>That distinction is the whole design. A check that fails a build on a judgement earns an ignore list, which is
 * the argument the console's accessibility check is already held to: contrast is arithmetic over computed colours, and
 * the judgement calls stay a review question. The same line is drawn here. "This signature's digest is SHA-1" and
 * "this key is RSA-1024" are computable and actionable; "this maintainer seems trustworthy" is not, and nothing in
 * this type tries.
 *
 * <p>It grades a signature, never a signer. Whether a key <em>ought</em> to have signed a coordinate is trust, decided
 * elsewhere from continuity and an operator's pins; a perfectly strong signature by an unexpected key grades
 * {@link Grade#STRONG} and is still held. Keeping the two apart is what lets an operator raise a quality floor without
 * touching a trust policy, and read a finding that says which of the two bit.
 */
public record SignatureQuality(Grade grade, List<String> reasons) {

    /**
     * How much weight a signature carries, worst first so a fold takes the minimum.
     *
     * <p>The bands are deliberately coarse. A score would invite a threshold nobody can justify; four bands map onto
     * the four things an operator actually does - ignore it, note it, plan to fix it, refuse it.
     */
    public enum Grade {

        /** Nothing can be said - no signature was assessed, or the material could not be read. */
        UNASSESSED,

        /** Cryptographically broken by current practice: a signature of this shape proves nothing. */
        UNUSABLE,

        /** Works, but rests on a primitive or a key that is past its useful life. */
        WEAK,

        /** Sound by current practice, with something worth noting. */
        ACCEPTABLE,

        /** Sound by current practice, with nothing to note. */
        STRONG;

        /** The weaker of two grades, {@link #UNASSESSED} losing to anything that was actually assessed. */
        public Grade min(Grade other) {
            if (this == UNASSESSED) {
                return other;
            }
            if (other == UNASSESSED) {
                return this;
            }
            return compareTo(other) <= 0 ? this : other;
        }

        /** Whether this grade is at or above {@code floor} - the comparison a quality dial makes. */
        public boolean atLeast(Grade floor) {
            return this != UNASSESSED && compareTo(floor) >= 0;
        }
    }

    /** The digests no signature made after their breaks should still be resting on. */
    private static final Set<String> BROKEN_DIGESTS = Set.of("MD5", "MD2", "SHA1", "SHA-1");

    /** Below this many bits an RSA or DSA key is not worth checking a signature against. */
    public static final int RSA_UNUSABLE_BITS = 1024;

    /** Below this many bits an RSA or DSA key is past its useful life, though not yet worthless. */
    public static final int RSA_WEAK_BITS = 2048;

    public SignatureQuality {
        Objects.requireNonNull(grade, "grade");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Nothing was assessed - no material, or material that could not be read. */
    public static SignatureQuality unassessed() {
        return new SignatureQuality(Grade.UNASSESSED, List.of());
    }

    /**
     * The grade of one OpenPGP signature, from what its packet and its signer's key state.
     *
     * @param keyAlgorithm  the public-key algorithm name, as the packet spells it ({@code RSA}, {@code EDDSA}, ...)
     * @param keyBits       the key's size in bits, or {@code 0} when the algorithm has no meaningful one
     * @param hashAlgorithm the digest the signature was made over
     * @param created       when the signature was made, or {@code null} when the packet does not say
     * @param keyExpiry     when the signing key expires, or {@code null} when it does not
     * @param now           the clock's reading, for deciding whether a key has since lapsed
     */
    public static SignatureQuality openPgp(String keyAlgorithm, int keyBits, String hashAlgorithm,
                                           Instant created, Instant keyExpiry, Instant now) {
        return graded(keyAlgorithm, keyBits, hashAlgorithm, created, keyExpiry, now);
    }

    /** A CMS (PKCS#7) signature graded by the same rules: the key is the signing certificate's, the expiry its
     *  {@code notAfter}, the digest the signer's. Nothing about X.509 changes what a weak key or a broken digest is. */
    public static SignatureQuality x509(String keyAlgorithm, int keyBits, String hashAlgorithm,
                                        Instant signingTime, Instant notAfter, Instant now) {
        return graded(keyAlgorithm, keyBits, hashAlgorithm, signingTime, notAfter, now);
    }

    /** A bare RSA signature graded by the same rules; it carries no creation time and its key no expiry. */
    public static SignatureQuality rsa(int keyBits, String hashAlgorithm, Instant now) {
        return graded("RSA", keyBits, hashAlgorithm, null, null, now);
    }

    private static SignatureQuality graded(String keyAlgorithm, int keyBits, String hashAlgorithm,
                                           Instant created, Instant keyExpiry, Instant now) {
        List<String> reasons = new ArrayList<>();
        Grade grade = Grade.STRONG;

        String digest = hashAlgorithm == null ? "" : hashAlgorithm.toUpperCase(Locale.ROOT).replace("-", "");
        if (hashAlgorithm == null) {
            grade = grade.min(Grade.WEAK);
            reasons.add("the signature does not name its digest algorithm");
        } else if (BROKEN_DIGESTS.contains(digest) || BROKEN_DIGESTS.contains(hashAlgorithm.toUpperCase(Locale.ROOT))) {
            grade = grade.min(Grade.UNUSABLE);
            reasons.add("the signature digest is " + hashAlgorithm + ", which is broken for collision resistance");
        }

        boolean sized = keyAlgorithm != null
                && (keyAlgorithm.toUpperCase(Locale.ROOT).startsWith("RSA")
                || keyAlgorithm.toUpperCase(Locale.ROOT).startsWith("DSA")
                || keyAlgorithm.toUpperCase(Locale.ROOT).startsWith("ELGAMAL"));
        if (sized && keyBits > 0) {
            if (keyBits <= RSA_UNUSABLE_BITS) {
                grade = grade.min(Grade.UNUSABLE);
                reasons.add("the signing key is " + keyAlgorithm + "-" + keyBits + ", below the " + RSA_WEAK_BITS
                        + "-bit floor and within reach of factoring");
            } else if (keyBits < RSA_WEAK_BITS) {
                grade = grade.min(Grade.WEAK);
                reasons.add("the signing key is " + keyAlgorithm + "-" + keyBits + ", under the " + RSA_WEAK_BITS
                        + "-bit floor current practice expects");
            }
        }

        if (keyExpiry != null) {
            if (created != null && !created.isBefore(keyExpiry)) {
                grade = grade.min(Grade.UNUSABLE);
                reasons.add("the signing key had already expired at " + keyExpiry + " when the signature was made");
            } else if (now != null && !now.isBefore(keyExpiry)) {
                grade = grade.min(Grade.ACCEPTABLE);
                reasons.add("the signing key expired at " + keyExpiry
                        + "; the signature predates that and still verifies");
            }
        }

        // Signing time against PUBLISH time is deliberately not graded here, though it is the check this type would
        // most like to make: a signature made long after the bytes were published is one made over something already
        // in circulation, and one made long before is a signature carried across from other content. It needs the
        // artifact's durable publish record, which an inspector does not have - and guessing "now" for it is wrong in
        // the direction that matters, because a proxy fill of a decade-old artifact would then read as back-dated and
        // every old release would grade down. It belongs with the stored per-version record rather than here, and is
        // left out until that exists: a documented check that never runs is worse than an absent one.

        return new SignatureQuality(grade, reasons);
    }

    /** This grade lowered to {@code other}'s if that is weaker, with both sets of reasons kept. */
    public SignatureQuality and(SignatureQuality other) {
        if (other == null) {
            return this;
        }
        List<String> merged = new ArrayList<>(reasons);
        other.reasons.stream().filter(reason -> !merged.contains(reason)).forEach(merged::add);
        return new SignatureQuality(grade.min(other.grade), merged);
    }

    /** The grade and its reasons as one sentence, for a finding's detail. */
    public String describe() {
        return reasons.isEmpty()
                ? grade.name().toLowerCase(Locale.ROOT)
                : grade.name().toLowerCase(Locale.ROOT) + " (" + String.join("; ", reasons) + ")";
    }
}
