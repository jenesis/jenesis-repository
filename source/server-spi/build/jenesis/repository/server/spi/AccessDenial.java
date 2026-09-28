package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.store.Features;

/**
 * What a caller without access to a tenant, a repository or an artifact is answered, decided once for every surface
 * that addresses one: the repository and registry paths, the format endpoints, the build cache, the {@code /api}
 * routes and the console.
 *
 * <p>By default the answer is {@code 404}, the answer an absent name gets, so probing names and reading the status
 * cannot tell a name that exists from one that does not. An operator for whom telling "no access" from "not here"
 * matters more than hiding names sets {@value #KEY} to {@code forbidden}, and the same refusals answer {@code 403}.
 *
 * <p>The setting chooses a status and nothing else. Whether a caller has access is decided by the name it addresses
 * and never by whether that name exists, so a refusal is the same answer for an existing name and an absent one under
 * either value. A caller that presents no credential is not refused here: it is answered {@code 401} with the
 * challenge its client needs to send one, and that challenge does not depend on the name either. And a refusal is
 * answered before any route is matched, so it never carries the mark a {@code 404} gets when no route answered: a
 * hidden name reads as an absent one on a route that exists.
 *
 * <p>Read live through {@link Features#settings()}, so a value written in the console applies on the next request.
 */
public enum AccessDenial {

    /** A refusal answers {@code 404}, as the name would if it did not exist. */
    NOT_FOUND(AccessDenial.NOT_FOUND_VALUE, 404),

    /** A refusal answers {@code 403}. */
    FORBIDDEN(AccessDenial.FORBIDDEN_VALUE, 403);

    /** The setting's key, unprefixed as the catalogue carries it. */
    public static final String KEY = "access-denied-status";

    /** The value selecting {@link #NOT_FOUND}. */
    public static final String NOT_FOUND_VALUE = "not-found";

    /** The value selecting {@link #FORBIDDEN}. */
    public static final String FORBIDDEN_VALUE = "forbidden";

    /** The default the catalogue declares and a deployment that sets nothing runs: a refusal hides the name. */
    public static final String DEFAULT = NOT_FOUND_VALUE;

    private final String value;

    private final int status;

    AccessDenial(String value, int status) {
        this.value = value;
        this.status = status;
    }

    /** The setting's value that selects this answer. */
    public String value() {
        return value;
    }

    /** The HTTP status a refusal answers. */
    public int status() {
        return status;
    }

    /** Whether a refusal answers as if the name were absent. */
    public boolean hides() {
        return this == NOT_FOUND;
    }

    /** The words a refusal carries: {@code absent}, the words an absent name gets, when a refusal hides the name, and
     *  {@code refused}, which says why, when it does not. */
    public String explain(String absent, String refused) {
        return hides() ? absent : refused;
    }

    /** The answer {@code value} selects; unset or blank is the {@link #DEFAULT}. */
    public static AccessDenial of(String value) {
        String chosen = value == null || value.isBlank() ? DEFAULT : value.strip();
        for (AccessDenial denial : values()) {
            if (denial.value.equals(chosen)) {
                return denial;
            }
        }
        throw new IllegalArgumentException("jenrepo." + KEY + "=" + chosen + " is not one of "
                + Arrays.stream(values()).map(AccessDenial::value).toList());
    }

    /** The answer {@code settings}, keyed by bare name, selects now. */
    public static AccessDenial configured(UnaryOperator<String> settings) {
        return of(settings.apply(KEY));
    }

    /** The answer the deployment's settings select now. */
    public static AccessDenial configured() {
        return configured(Features.settings());
    }
}
