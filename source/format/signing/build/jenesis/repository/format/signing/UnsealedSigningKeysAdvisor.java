package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.settings.SecretCipher;

/**
 * Reports a deployment that signs repositories without a master key: {@link SigningKeys} then stores a repository's
 * secret signing key in the clear rather than refusing it, because a format that publishes signed indexes cannot
 * work unsigned, so anyone who can read the store can sign as the repository. It asks the question the signer asks -
 * whether {@value SecretCipher#ENV} names a key - and nothing else, so the report stands whatever the store holds.
 */
public final class UnsealedSigningKeysAdvisor implements SafetyAdvisor {

    static final String ID = "jenrepo.signing.unsealed";

    private final Supplier<SecretCipher> cipher;

    /** The advisor over the environment's master key, as the signer reads it. */
    public UnsealedSigningKeysAdvisor() {
        this(SecretCipher::fromEnvironment);
    }

    /** The advisor over a given cipher - a test's, which cannot set the process environment. */
    public UnsealedSigningKeysAdvisor(Supplier<SecretCipher> cipher) {
        this.cipher = cipher;
    }

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        boolean sealed;
        try {
            sealed = cipher.get().configured();
        } catch (RuntimeException malformed) {
            // A malformed master key fails every signing loudly, which is its own report.
            return List.of();
        }
        if (sealed) {
            return List.of();
        }
        return List.of(SecurityAdvisory.deployment(ID, Severity.WARN,
                "Repository signing keys are stored unsealed",
                SecretCipher.ENV + " names no master key, so the secret key each signed repository (RPM, Debian, "
                        + "Terraform, APK) signs with is stored in the clear: anyone who can read the store can sign "
                        + "as the repository.",
                "Set " + SecretCipher.ENV + " to a master key on every node; each key is sealed the next time it "
                        + "signs.",
                "", "", "https://jenesis.build/repository/operations/#" + ID));
    }
}
