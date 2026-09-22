package build.jenesis.repository.server.kernel;

/**
 * What the kernel needs to know about a named repository's definition, and nothing more: whether a write may land
 * in it, and whether its proxy leg is hardened. The definitions themselves - hosted, proxy, group, their fallbacks
 * and screening - are the router's model, and the router is a feature the kernel does not require; the boot module
 * hands the kernel an implementation the router's live definitions answer, and a composition without the router
 * answers {@link #HOSTED}, which is what a repository with no definition has always meant.
 *
 * <p>It exists because {@code Repositories} used to hand out the router's {@code Definition} type directly, which
 * made the kernel require the router and, through it, the gate, the inventory and the metadata store - so every web
 * adapter that needed only a tenant and a store dragged the whole gated edge. The two questions the kernel asked of
 * a definition are these two, so this is all that crosses.
 */
public interface RepositoryDefinitions {

    /** Whether {@code repository} accepts uploads into its own store. Undefined means hosted, which is writable;
     *  only a definition can say otherwise - a proxy, a group view, or one marked read-only. */
    boolean writable(String repository);

    /** Whether {@code repository}'s proxy leg screens the whole body of what it fetches. */
    boolean hardened(String repository);

    /** Every repository hosted and none hardened - a deployment with no definitions at all. */
    RepositoryDefinitions HOSTED = new RepositoryDefinitions() {
        @Override
        public boolean writable(String repository) {
            return true;
        }

        @Override
        public boolean hardened(String repository) {
            return false;
        }
    };
}
