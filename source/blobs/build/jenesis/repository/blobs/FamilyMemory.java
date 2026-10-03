package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.UpstreamMemory;

/**
 * Mutable upstream documents that vouch for each other - a Debian {@code InRelease}, {@code Release} and
 * {@code Release.gpg}, an RPM {@code repomd.xml} with its signature and key - remembered in the node's
 * {@link UpstreamMemory} only together.
 *
 * <p>Remembered one at a time, a signature could be kept from one moment and the document it signs from another, and
 * a root could name files the upstream has since replaced. So on a miss the document asked for is fetched - or, for
 * one that only signs or keys another, a document it vouches for, one of the family's probes - and only a probe its
 * format judges pinnable brings the rest of the family in, every member then remembered at once. A family the format
 * cannot pin is answered from what was fetched, at the one fetch a fresh relay costs, and nothing of it is remembered.
 * What the root names beyond the family is the format's to fetch so it agrees with the remembered root - by the digest
 * the root names, or by a name that carries its digest.
 */
public final class FamilyMemory {

    /**
     * One family of documents in one upstream directory: its members, the members whose body tells whether the family
     * can be pinned - a probe, in the order one is chosen for a member that is not one - and that judgement.
     */
    public record Family(URI directory, List<String> members, List<String> probes, Predicate<byte[]> pinnable) {

        public Family {
            Objects.requireNonNull(directory, "directory");
            members = List.copyOf(members);
            probes = List.copyOf(probes);
            Objects.requireNonNull(pinnable, "pinnable");
            if (probes.isEmpty() || !members.containsAll(probes)) {
                throw new IllegalArgumentException("the probes " + probes + " are not members of " + members);
            }
        }

        URI url(String member) {
            return directory.resolve(member);
        }
    }

    private FamilyMemory() {
    }

    /**
     * Answer {@code member} of {@code family} from what the node remembers of the family, remembering the family first
     * when it holds none of it and its probe is pinnable; else answered from the fetch, or relayed fresh where nothing
     * of it was answered. {@code remembered} is told each member as it is remembered, for what a format keeps of it.
     */
    public static boolean relay(ProxyFormat.Fetcher fetcher, Family family, String member, FormatExchange exchange,
                                ArtifactStore store, ProxyRelay.Document document, ProxyRelay.Tap tap,
                                Remembered remembered) throws IOException {
        URI url = family.url(member);
        Optional<UpstreamMemory.Remembered> kept = UpstreamMemory.node().get(store, url);
        if (kept.isEmpty()) {
            Map<String, ProxyFormat.Fetched> fetched = fetch(fetcher, family, member,
                    ProxyRelay.conditionalHeaders(exchange));
            if (remember(fetched, family, store, remembered)) {
                kept = UpstreamMemory.node().get(store, url);
            } else if (fetched.get(member) != null && fetched.get(member).status() == 304) {
                ProxyRelay.relayValidators(fetched.get(member), exchange);
                exchange.respond(304);
                return true;
            } else if (fetched.get(member) != null && fetched.get(member).status() == 200) {
                ProxyFormat.Fetched own = fetched.get(member);
                answer(own, exchange);
                if (tap != null) {
                    tap.read(new ByteArrayInputStream(own.body()));
                }
                return true;
            }
        }
        if (kept.isPresent()) {
            ProxyRelay.answerRemembered(kept.get(), null, exchange);
            return true;
        }
        return ProxyRelay.streamFresh(fetcher, url, null, exchange, document, tap);
    }

    /** What the node remembers of one member of a family for {@code store}, or empty. */
    public static Optional<byte[]> remembered(Family family, String member, ArtifactStore store) {
        return UpstreamMemory.node().get(store, family.url(member)).map(UpstreamMemory.Remembered::body);
    }

    /** Forget a family for {@code store}, so the next read fetches it again - for a format that found a file the
     *  remembered root names gone from the upstream. */
    public static void forget(Family family, ArtifactStore store) {
        for (String member : family.members()) {
            UpstreamMemory.node().forget(store, family.url(member));
        }
    }

    /** Told each member of a family as it is remembered. */
    @FunctionalInterface
    public interface Remembered {

        void member(String name, byte[] body) throws IOException;
    }

    /** The members a miss of {@code member} fetches: the probe, and the rest of the family when the probe is
     *  pinnable. A member the upstream did not answer is absent; one it answered otherwise carries its status. The
     *  client's {@code conditional} headers go with the member it asked for when that is the probe, so an unchanged
     *  document is answered {@code 304} as a fresh relay would answer it, and nothing of the family is remembered. */
    private static Map<String, ProxyFormat.Fetched> fetch(ProxyFormat.Fetcher fetcher, Family family, String member,
                                                          Map<String, String> conditional) throws IOException {
        Map<String, ProxyFormat.Fetched> fetched = new LinkedHashMap<>();
        boolean asked = family.probes().contains(member);
        String probe = asked ? member : family.probes().getFirst();
        Optional<ProxyFormat.Fetched> first = fetcher.fetch(family.url(probe), asked ? conditional : Map.of());
        if (first.isEmpty()) {
            return fetched;
        }
        fetched.put(probe, first.get());
        if (first.get().status() != 200 || !family.pinnable().test(first.get().body())) {
            return fetched;
        }
        for (String sibling : family.members()) {
            if (!fetched.containsKey(sibling)) {
                fetcher.fetch(family.url(sibling), Map.of()).ifPresent(answer -> fetched.put(sibling, answer));
            }
        }
        return fetched;
    }

    /** Remember a family fetched in one go: every member the upstream answered, when every member was asked and
     *  each answered one is small enough to keep. Answers whether it remembered it. */
    private static boolean remember(Map<String, ProxyFormat.Fetched> fetched, Family family, ArtifactStore store,
                                    Remembered remembered) throws IOException {
        if (!fetched.keySet().containsAll(family.members())) {
            return false;
        }
        for (ProxyFormat.Fetched answer : fetched.values()) {
            if (answer.status() == 200 && answer.body().length > UpstreamMemory.ENTRY_CAP) {
                return false;
            }
        }
        for (Map.Entry<String, ProxyFormat.Fetched> entry : fetched.entrySet()) {
            if (entry.getValue().status() == 200) {
                UpstreamMemory.node().put(store, family.url(entry.getKey()), entry.getValue().body(),
                        entry.getValue()::header);
                remembered.member(entry.getKey(), entry.getValue().body());
            }
        }
        return true;
    }

    /** Answer a document fetched whole: its body, type and validators. */
    private static void answer(ProxyFormat.Fetched fetched, FormatExchange exchange) throws IOException {
        String contentType = fetched.header("Content-Type");
        if (contentType != null) {
            exchange.setResponseHeader("Content-Type", contentType);
        }
        ProxyRelay.relayValidators(fetched, exchange);
        exchange.respond(200, fetched.body());
    }
}
