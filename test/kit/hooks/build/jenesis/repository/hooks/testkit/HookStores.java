package build.jenesis.repository.hooks.testkit;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.Mutant;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The store a check over a shipped hook is driven on, and which mutations a driver can put in front of that hook at
 * all - the two things every driver of shipped hooks decides the same way, stated once.
 */
public final class HookStores {

    private HookStores() {
    }

    /**
     * A fresh fault-armable store for one check, carrying the fixture's declared deployment state and nothing else.
     *
     * <p>{@code reset} runs first and puts back every process-global latch the driver's graph carries. A hook whose
     * enablement is a static set once by its task provider reads process state rather than the store it was handed,
     * so a fixture's deployment that sets one would otherwise leak its configuration into the next fixture's check.
     *
     * <p>Keyed on the name {@code Falsification} supplies, which carries the mutant as well as the hook and the
     * property. Keying it on hook-plus-property instead handed every run of a check the store its previous run had
     * already written, and a check about repeat behaviour then passes over a store where the unmutated run already
     * produced the right surface - so mutations read as survived when the store, not the hook, was answering.
     */
    public static FaultInjectingStore deployed(Path root, PublicationHookFixture fixture, String name, Runnable reset)
            throws IOException {
        reset.run();
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        FaultInjectingStore store = FaultInjectingStore.wrap(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null));
        if (fixture instanceof Deployment deployment) {
            deployment.deploy(store);
            // The tenant-scoping check publishes into store.scope("acme").scope("main"), and a per-repository gate is
            // per repository: a marker seeded only at the root would leave the hook inert in exactly the scope that
            // check asserts it records into.
            deployment.deploy(scoped(store));
        }
        return store;
    }

    /** The one tenant/repository scope {@code THE_OBSERVER_RECORDS_THROUGH_THE_PUBLISHED_SCOPE} publishes into. */
    public static ArtifactStore scoped(ArtifactStore store) {
        return store.scope("acme").scope("main");
    }

    /**
     * Whether a driver can put {@code mutation} in front of {@code fixture}'s hook so that its probes can show it. Two
     * measured places where they cannot - each a property of what a fixture can observe rather than of the contract.
     *
     * <p><b>{@code A_ROW_PER_DELIVERY}.</b> A fixture that is {@link ServedOnly} cannot see the row that mutant
     * appends under a never-published variant subject, and says why beside itself.
     *
     * <p><b>{@code A_PUBLISH_ROW_FROM_THE_WITHHOLD_LEG}.</b> The mutant is an observer that treats the withhold feed
     * and the publish feed as one. It is applicable only to a hook that has a publish row to misplace and holds a
     * withhold row that differs from it: a hook that upserts ONE key from both legs has declared it cannot tell them
     * apart, and for it the property is inapplicable rather than satisfied - and a {@link ServedOnly} fixture cannot
     * see a row for a held subject at all. Whether the hook overrides the withhold leg itself does not matter: the
     * mutant adds the publish row on that leg either way.
     */
    public static boolean injectable(PublicationHookFixture fixture, PublicationHookContract.Mutation mutation) {
        if (mutation.mutant() == Mutant.A_PUBLISH_ROW_FROM_THE_WITHHOLD_LEG) {
            if (fixture instanceof ServedOnly) {
                return false;   // a held subject does not serve, and this fixture's probes see only what does
            }
            // There must BE a publish row for the withhold leg to wrongly write: a hook whose only legs are the
            // withhold transitions converges on an empty view after a publish, and the mutation cannot change it.
            PublicationHookFixture.Observer observer = (PublicationHookFixture.Observer) fixture;
            ArtifactDescriptor probe = fixture.describe("/kit/withhold-probe");
            Map<String, String> publishRow = observer.converged(List.of(probe));
            // And the withhold row must differ from it.
            return !publishRow.isEmpty() && !observer.withheld(List.of(probe)).equals(publishRow);
        }
        return mutation.mutant() != Mutant.A_ROW_PER_DELIVERY || !(fixture instanceof ServedOnly);
    }
}
