package build.jenesis.repository.server.kernel;

/**
 * What the kernel needs to know about a named repository's definition, and nothing more: whether a write may land
 * in it, and whether its proxy leg is hardened - each as one tenant sees it, since a tenant routes its own
 * repositories. The definitions themselves - writability, the fallbacks and their
 * screening - are the router's model, and the router is a feature the kernel does not require; the boot module
 * hands the kernel an implementation the router's live definitions answer, and a composition without the router
 * answers {@link #HOSTED}, which is what a repository with no definition has always meant.
 *
 * <p>It exists so that {@code Repositories} does not hand out the router's {@code Definition} type directly, which
 * would make the kernel require the router and, through it, the gate, the inventory and the metadata store - so
 * every web adapter that needed only a tenant and a store would drag the whole gated edge. The two questions the
 * kernel asks of a definition are these two, so this is all that crosses.
 */
public interface RepositoryDefinitions {

    /** Whether {@code tenant}'s {@code repository} accepts uploads into its own store. Undefined means writable; only
     *  a definition can say otherwise - one that is not {@code writable}, such as a proxy or a grouped view. */
    boolean writable(String tenant, String repository);

    /** Whether {@code tenant}'s {@code repository} has a proxy leg that screens the whole body of what it fetches. */
    boolean hardened(String tenant, String repository);

    /** Every repository hosted and none hardened - a deployment with no definitions at all. */
    RepositoryDefinitions HOSTED = new RepositoryDefinitions() {
        @Override
        public boolean writable(String tenant, String repository) {
            return true;
        }

        @Override
        public boolean hardened(String tenant, String repository) {
            return false;
        }
    };
}
