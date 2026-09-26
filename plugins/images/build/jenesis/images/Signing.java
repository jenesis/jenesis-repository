package build.jenesis.images;

import module java.base;

/**
 * How a publish signs what it pushed - {@code images.sign} in the plugin's values, which a profile brings beside the
 * target it publishes to, and absent everywhere else so a push to a test registry signs nothing.
 *
 * <ul>
 *   <li>{@code keyless}: Sigstore, the signer the identity the pushing job holds - a CI job's OIDC token
 *       ({@code ACTIONS_ID_TOKEN_REQUEST_URL}, or {@code SIGSTORE_ID_TOKEN} handed in); the signature and the
 *       certificate are recorded in the public transparency log;</li>
 *   <li>{@code key}: a cosign key pair's private half in {@code COSIGN_KEY} and its password in
 *       {@code COSIGN_PASSWORD}, handed to cosign as {@code env://COSIGN_KEY} so the key never reaches a command
 *       line - for a push from anywhere that holds no workload identity;</li>
 *   <li>absent: nothing is signed.</li>
 * </ul>
 *
 * <p>What is signed is the digest the registry now holds, never a tag: a tag moves, and a signature over one says
 * nothing about what a later pull of it gets. Beside each image's signature its CycloneDX SBOM is attested to the same
 * digest, so an image carries an account of what it contains and not only its jars do; a chart is signed by the
 * digest {@code helm push} reports.
 *
 * <p><b>A named mode that cannot sign refuses the push</b>, before anything is pushed, as a missing registry
 * credential does: an image a release pushed unsigned is the failure to design against, and finding out after the
 * push has already published it is too late.
 */
record Signing(String mode) implements Serializable {

    static final String KEYLESS = "keyless", KEY = "key";

    static final Signing NONE = new Signing(null);

    static Signing of(String named) {
        if (named == null || named.isBlank()) {
            return NONE;
        }
        String mode = named.strip();
        if (!mode.equals(KEYLESS) && !mode.equals(KEY)) {
            throw new IllegalArgumentException("images.sign names '" + named + "' - keyless or key, or leave it out "
                    + "to sign nothing");
        }
        return new Signing(mode);
    }

    boolean active() {
        return mode != null;
    }

    /** Refuse, before anything is pushed, a mode that cannot sign here. */
    void require() {
        if (!active()) {
            return;
        }
        if (!Cli.available("cosign")) {
            throw new IllegalStateException("images.sign=" + mode + " but cosign is not on the PATH, so nothing this "
                    + "publish pushed could be signed. Install cosign, or leave images.sign out to publish unsigned.");
        }
        if (mode.equals(KEY) && blank(System.getenv("COSIGN_KEY"))) {
            throw new IllegalStateException("images.sign=key but COSIGN_KEY is not set: set it to the signing key (and "
                    + "COSIGN_PASSWORD to its password).");
        }
        if (mode.equals(KEYLESS) && blank(System.getenv("ACTIONS_ID_TOKEN_REQUEST_URL"))
                && blank(System.getenv("SIGSTORE_ID_TOKEN"))) {
            throw new IllegalStateException("images.sign=keyless but this process holds no identity to sign as: run in "
                    + "a GitHub Actions job granted id-token: write, or hand one in as SIGSTORE_ID_TOKEN.");
        }
    }

    /** Sign {@code reference} - {@code <repository>@sha256:<digest>} - and, where one is given, attest an SBOM to it. */
    void sign(String reference, Path sbom) throws IOException {
        if (!active()) {
            return;
        }
        Cli.run(command("sign", reference));
        System.out.println("[images]   signed " + reference);
        if (sbom != null) {
            List<String> attest = command("attest", reference);
            attest.addAll(attest.size() - 1, List.of("--type", "cyclonedx", "--predicate", sbom.toString()));
            Cli.run(attest);
            System.out.println("[images]   attested " + sbom.getFileName() + " to " + reference);
        }
    }

    private List<String> command(String verb, String reference) {
        List<String> command = new ArrayList<>(List.of("cosign", verb, "--yes"));
        if (mode.equals(KEY)) {
            command.addAll(List.of("--key", "env://COSIGN_KEY"));
        }
        command.add(reference);
        return command;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
