package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.HoldReleaseFixture;

/**
 * What a review release writes on this graph: the gate's own spaces, and what the discovered chain it fans out to
 * writes besides.
 *
 * <p>A release links and unpublishes through the discovered chain, so every observer this module provides rides it -
 * and the feed-splitting archetype records the withhold being cleared. That is a real consequence of the release path
 * on a graph that carries the archetype, and the namespace check attributes it to the hook under test because the
 * surface is what it drives.
 */
final class ReleaseSpaces {

    private ReleaseSpaces() {
    }

    /** The release surface's spaces on this graph, plus the hook's own {@code more}. */
    static List<String> of(String... more) {
        List<String> spaces = new ArrayList<>(HoldReleaseFixture.GATE_SPACES);
        spaces.add(FeedSplittingObserver.SPACE);
        spaces.addAll(List.of(more));
        return List.copyOf(spaces);
    }
}
