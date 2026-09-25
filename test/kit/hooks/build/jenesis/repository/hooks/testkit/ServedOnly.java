package build.jenesis.repository.hooks.testkit;

/**
 * A fixture whose probes can see only subjects that serve, so neither mutant that writes a row for a subject that does
 * not can be displayed to them.
 *
 * <p>{@code A_ROW_PER_DELIVERY} models an appending hook by re-delivering a <em>variant</em> subject - re-delivering
 * the same one is what a correct upsert already collapses - and a variant was never published.
 * {@code A_PUBLISH_ROW_FROM_THE_WITHHOLD_LEG} records a publish for a <em>held</em> subject, which has a review
 * pointer and no serving one. A hook that acts only on a path with a serving pointer, or a fixture that derives its
 * projection from the served pointer tree, declares an empty projection, or arranges a verdict for one subject only,
 * cannot show either row - the mutation is invisible to its probes by construction, not tolerated by them. The reason
 * is stated beside the hook, where it can be checked against the hook, and {@link HookStores#injectable} reads it.
 */
public interface ServedOnly {

    /** Why this fixture's probes cannot display a row written for a subject that does not serve. */
    String whyServedOnly();
}
