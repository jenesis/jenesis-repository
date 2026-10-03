package build.jenesis.repository.demo;

import module java.base;

/**
 * One demo run, as a {@link DemoContributor} loads through it: the tenant it fills, and the operations a client and
 * an operator would make - switching a setting, publishing an artifact, fetching one through a proxy, asking for a
 * pass - each made the way they make it and each recording its own outcome on the run, so the operator is told what
 * was created, what was held or refused, and what stayed empty.
 *
 * <p>Nothing here throws for an outcome a deployment can produce: a refused setting, a held or refused publish and a
 * registry that does not answer are recorded and answered, and the load carries on. An {@link IOException} is the
 * run's own store failing.
 */
public interface Demo {

    /** How an operation of a run ended. */
    enum Outcome {
        /** It was done. */
        DONE,
        /** It was published and held for review. */
        HELD,
        /** The deployment refused it. */
        REFUSED,
        /** It could not be done: a registry that did not answer, a repository that is not there. */
        FAILED,
        /** It was left out, and the run says why. */
        SKIPPED
    }

    /** The tenant the demo fills. */
    String tenant();

    /** The operator who confirmed the demo, as whom a contributor records what it creates on the audit trail. */
    String actor();

    /** The deployment's value of {@code key} in force - a pin, else the stored value - or empty when none is set. */
    String setting(String key);

    /**
     * Set each of {@code values} deployment-wide in one change, recorded on the audit trail as the operator who
     * confirmed the demo. Every key must be one the contributor's plan names; a refused change - an undeclared key, a
     * value pinned by the environment, one the catalogue refuses - sets none of them.
     *
     * @return whether the change was made.
     */
    boolean settings(Map<String, String> values) throws IOException;

    /**
     * Publish {@code body} into {@code repository} at {@code path} - the path a client names after the repository's
     * URL - through the edge a client's upload takes, screened by the deployment's gate.
     *
     * @return how the publish ended: done, held for review, refused by the gate, or failed - claimed by no format, no
     *         repository, or no repository edge running here.
     */
    Outcome publish(String repository, String path, InputStream body) throws IOException;

    /**
     * Read {@code path} from {@code repository} through the serving path a client's {@code GET} takes, so a proxy
     * pulls it from its registry, screens it and caches it.
     *
     * @return {@link Outcome#DONE} when it was read, else {@link Outcome#FAILED}: a registry that cannot be reached, a
     *         version the gate withholds.
     */
    Outcome fetch(String repository, String path) throws IOException;

    /**
     * Ask for the background pass {@code pass} to run no earlier than {@code after} from now, for {@code reason} - as
     * an operator asks for one - rather than waiting for its schedule.
     */
    void request(String pass, String reason, Duration after) throws IOException;

    /** Record that {@code what} was left out, and why. */
    void skipped(String what, String why) throws IOException;

    /**
     * Record an operation the contributor made through its own module's services rather than through this run - a
     * build-cache project created, the entries a build stored in it - with how it ended and what the deployment said,
     * which is what the run's screen lists for it.
     *
     * @return {@code outcome}, so a load may branch on it as on the run's own operations.
     */
    Outcome made(String what, Outcome outcome, String detail) throws IOException;
}
