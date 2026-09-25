/**
 * What a publication-hook fixture over a <em>shipped</em> hook needs beyond the store kit's contract: the durable
 * state that makes a hook live at all, the real pass a repair leg runs, the one synthetic format that gives the
 * contract's {@code /kit/} paths a coordinate, the shared shape of a hold-release fixture, and the store each check is
 * driven over.
 *
 * <p>The store kit's {@code PublicationHookContract} was written against archetypes, which act on the first publish
 * they are handed. A shipped hook usually does not: it is gated on the surface it feeds, keys its records by a
 * coordinate the kit's synthetic paths do not carry, and is repaired by a maintenance pass rather than by hand. Every
 * suite driving shipped hooks needs the same answers to those three facts, so they are stated once here and required
 * by each such suite rather than copied into it.
 *
 * <p>It provides one service, {@code HookTestFormat}, and that is deliberate: a hook that resolves a coordinate
 * through the installed formats resolves none for a path no format claims, and every hold record it writes would then
 * be skipped - so the contract would pass vacuously. Only test modules require this kit, so the format reaches no
 * product graph.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.hooks.testkit {
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.store.testkit;
    requires transitive build.jenesis.repository.format;
    requires transitive build.jenesis.repository.gate.spi;
    requires transitive build.jenesis.repository.maintenance;
    // The review surface a hold-release fixture releases and discards through.
    requires build.jenesis.repository.gate;
    exports build.jenesis.repository.hooks.testkit;

    // A fixture reaches its hook the way the product does, so the kit's own loader declares both services.
    uses build.jenesis.repository.store.PublicationObserver;
    uses build.jenesis.repository.gate.HoldReleaseObserver;

    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.hooks.testkit.HookTestFormat;
}
