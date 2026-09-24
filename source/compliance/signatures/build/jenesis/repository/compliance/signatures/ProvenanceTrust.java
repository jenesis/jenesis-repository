package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.Maintainers;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Provenance-bound trust: a keyless identity is believed for a coordinate when the workflow that signed is one of
 * the repository the artifact's own metadata names, and the identity's issuer is one the operator accepts.
 *
 * <p>A Sigstore signer is an OIDC identity Fulcio certified for ten minutes - for a CI build, the workflow file of
 * a repository at a ref - and holding the public-good root vouches for nobody, since Fulcio certifies anyone the
 * issuers know. A pin names one such identity outright, which is exact and does not scale past a handful of
 * coordinates. What does scale is what the artifact already says about itself: a POM's {@code <scm>} and a
 * package.json's {@code repository} name the source repository, and the maintainer seam records it per coordinate
 * before the signature question is asked. So this part trusts {@code sigstore:<issuer>|<subject>} for a coordinate
 * exactly when the issuer is one the {@code signature-provenance-accept} dial lists and the subject's repository -
 * the host, owner and name a GitHub workflow subject carries before {@code /.github/}, or a GitLab one before the
 * double slash - is the repository the coordinate's recorded metadata names. A bundle from a fork of the declared
 * repository names another repository and stays UNTRUSTED; a repository the metadata never named admits nothing.
 *
 * <p>The dial is empty by default, so nothing is admitted this way until an operator names an issuer - GitHub
 * Actions' {@code https://token.actions.githubusercontent.com} being the one worth naming first. A pin still wins
 * where one covers the coordinate, since the configured trust is asked first, and continuity still reports a
 * change of signer. Every signature admitted here says so: {@link #source()} is {@value #SOURCE}, recorded on the
 * signature as its key source, so a screen and a finding can tell "the operator pinned this identity" from "this
 * identity is a workflow of the declared repository".
 *
 * <p>A point read of the coordinate's maintainer record at trust time, never a walk and never a network call.
 */
public final class ProvenanceTrust implements SignerTrust {

    /** The issuers whose identities are admitted by provenance, comma- or newline-separated; empty admits none. */
    static final String ACCEPT = "signature-provenance-accept";

    /** The source name a signature admitted by provenance is reported with. */
    static final String SOURCE = "provenance";

    private final ArtifactStore store;
    private final Set<String> issuers;

    ProvenanceTrust(ArtifactStore store, Set<String> issuers) {
        this.store = store;
        this.issuers = Set.copyOf(issuers);
    }

    /** The issuers the dial names, trimmed, or none. */
    static Set<String> issuers(UnaryOperator<String> config) {
        String value = config == null ? null : config.apply(ACCEPT);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> issuers = new LinkedHashSet<>();
        for (String issuer : value.split("[,\\n]")) {
            if (!issuer.isBlank()) {
                issuers.add(issuer.strip());
            }
        }
        return issuers;
    }

    @Override
    public Optional<byte[]> material(String scheme) {
        return Optional.empty();   // the trusted root is the configured trust's; this part holds no key
    }

    @Override
    public boolean trusts(SignerIdentity signer, String ecosystem, String coordinate) {
        if (signer == null || store == null || ecosystem == null || coordinate == null || issuers.isEmpty()) {
            return false;
        }
        Optional<SignerIdentity.Sigstore> keyless = signer.sigstore();
        if (keyless.isEmpty() || !issuers.contains(keyless.get().issuer())) {
            return false;
        }
        Optional<String> repository = repository(keyless.get().subject());
        if (repository.isEmpty()) {
            return false;
        }
        try {
            return Maintainers.named(store, ecosystem, coordinate).contains(Maintainers.REPOSITORY + repository.get());
        } catch (IOException unreadable) {
            return false;   // could not read the record: not trusted, which is the safe answer
        }
    }

    @Override
    public Optional<Expectation> expected(String ecosystem, String coordinate, String scheme) {
        return Optional.empty();   // provenance admits; what a coordinate's history expects is continuity's
    }

    @Override
    public void observed(String ecosystem, String coordinate, String version, SignerIdentity signer, Instant when) {
        // Nothing is learned here: the record this reads is written by the maintainer seam on every accepted publish.
    }

    @Override
    public String source() {
        return SOURCE;
    }

    /**
     * The repository a workflow subject names, spelled as {@link build.jenesis.repository.compliance.Maintainer}
     * records one: GitHub's {@code https://github.com/<owner>/<name>/.github/workflows/<file>@<ref>} is
     * {@code github.com/<owner>/<name>}, GitLab's {@code https://gitlab.com/<group>/<project>//<file>@<ref>} is
     * everything before the double slash, and any other subject - an e-mail address, a service account - names no
     * repository at all.
     */
    public static Optional<String> repository(String subject) {
        if (subject == null) {
            return Optional.empty();
        }
        String text = subject.strip();
        int scheme = text.indexOf("://");
        if (scheme < 0) {
            return Optional.empty();
        }
        String rest = text.substring(scheme + 3);
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(0, at);
        }
        int workflow = rest.indexOf("/.github/");
        if (workflow >= 0) {
            rest = rest.substring(0, workflow);
        }
        int doubled = rest.indexOf("//");
        if (doubled >= 0) {
            rest = rest.substring(0, doubled);
        }
        String[] segments = rest.split("/");
        if (segments.length < 3 || segments[0].isEmpty() || segments[1].isEmpty() || segments[2].isEmpty()) {
            return Optional.empty();
        }
        String host = segments[0].toLowerCase(Locale.ROOT);
        if ("github.com".equals(host)) {
            return Optional.of(host + "/" + segments[1].toLowerCase(Locale.ROOT) + "/"
                    + segments[2].toLowerCase(Locale.ROOT));
        }
        return Optional.of(rest.toLowerCase(Locale.ROOT));
    }
}
