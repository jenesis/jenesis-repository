package build.jenesis.repository.ui;

import module java.base;

/**
 * A sign-in mechanism that can hand a new deployment's administrator a credential of its own, so the first-run guide's
 * first step needs nothing typed: it names the {@link #suggested()} administrator, and applying the guide grants that
 * id administration and {@linkplain #issue issues} its key, shown once. A deployment without such a mechanism names
 * nobody there, and the operator names an identity another mechanism signs in.
 */
public interface AdministratorKeys {

    /** The id the guide names when the operator names none, one this mechanism signs in. */
    String suggested();

    /** Whether {@code id} is one this mechanism signs in, so applying the guide issues its key. */
    boolean signsIn(String id);

    /** Issue a key for {@code id}, on behalf of {@code actor}, returning its plaintext - the only time it exists. */
    String issue(String actor, String id) throws IOException;
}
