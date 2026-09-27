package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.StoreCache;

/**
 * The credential space, {@code .system/auth/<tenant>/...}: where each of its documents lives, and the one path every
 * read and write of one takes.
 *
 * <p>Every document here - a subject's grants, metadata and derived grants, a group's members, and a tenant's
 * lifetime policy, ceilings, OIDC trusts and roles - is a {@link Properties} document read through one node-local
 * {@link StoreCache} and written through a mutation that bumps one deployment-wide {@link Epoch}. That pair is what
 * lets a node cache credentials for minutes and still honour a revocation made on another node within
 * {@link #EPOCH_TTL}: a node re-reads the epoch at most that often and drops its cache when the token has moved.
 * Every concern of the credential model reads and writes through the same instance, so a change to any of them
 * reaches the whole fleet the same way.
 *
 * <p>An open deployment has no store, and this space then holds no cache and no epoch; {@link #enforcing()} tells
 * the two apart and {@link #require()} refuses a write on the open one.
 */
final class CredentialSpace {

    /** The credential space, deployment-wide: {@code .system/auth/<tenant>/...}. */
    static final String AUTH = Scopes.space(Scopes.AUTH);

    /** The key the deployment's auth epoch lives under - one token, bumped by every credential mutation. */
    static final String EPOCH = AUTH + "/epoch";

    /** How long this node may believe its own reading of the epoch. Seconds, not minutes: this is the interval a
     *  revocation takes to reach another node, and one small read amortised over every request the node serves in
     *  that window is the whole of its cost. */
    static final Duration EPOCH_TTL = Duration.ofSeconds(5);

    private final ArtifactStore store;

    /** The documents through the read-through, write-through cache - a request pays for a credential's two
     *  documents once per ttl, not three times per request. */
    private final StoreCache cache;

    private final Epoch epoch;

    /** The epoch token this node last read, and when - the pair that turns one document into a fleet-wide
     *  invalidation without a read per request. */
    private volatile String seenEpoch;
    private volatile long seenAt;

    /** The space over {@code store}, its documents cached for {@code ttl}; a {@code null} store is an open
     *  deployment, which caches nothing and keeps no epoch. */
    CredentialSpace(ArtifactStore store, Duration ttl) {
        this.store = store;
        this.cache = store == null ? null : StoreCache.of("authorization", store, ttl);
        this.epoch = store == null ? null : new Epoch(store, EPOCH);
    }

    boolean enforcing() {
        return store != null;
    }

    /** The store itself, for the reads that deliberately go past the cache: an enumeration, and the membership
     *  probe a derivation makes. */
    ArtifactStore store() {
        return store;
    }

    void require() {
        if (store == null) {
            throw new IllegalStateException("Cannot manage credentials on an anonymous authorization");
        }
    }

    /**
     * Drop this node's credential cache if another node has changed a credential since it last looked.
     *
     * <p>Called before a decision is read, and it is what makes a fifteen-minute credential ttl safe: without it a
     * grant or a **revocation** made on one node reaches the others only when their entries expire, and
     * {@code POST /api/admin/caches/clear} cannot help because it clears one node. The epoch is one small document
     * that every credential mutation bumps; a node re-reads it at most once per {@link #EPOCH_TTL} and clears its
     * cache when the token has moved. Revocation latency therefore follows the epoch's window rather than the
     * credential's, and the cost is one read every few seconds spread across every request the node answers.
     *
     * <p>An unreadable epoch clears the cache and reads through: that costs latency, never a stale grant.
     */
    void freshen() {
        if (epoch == null) {
            return;
        }
        long now = System.nanoTime();
        long last = seenAt;
        if (last != 0 && now - last < EPOCH_TTL.toNanos()) {
            return;
        }
        String token;
        try {
            token = epoch.current();
        } catch (IOException unreadable) {
            cache.clear();   // fail closed: better a re-read than a decision from a cache we cannot vouch for
            seenAt = now;
            return;
        }
        seenAt = now;
        String previous = seenEpoch;
        if (previous != null && !previous.equals(token)) {
            cache.clear();
        }
        seenEpoch = token;
    }

    /** Mark the deployment's credentials changed, so every other node drops its cache within {@link #EPOCH_TTL}.
     *  Called from the one place each mutation funnels through, never at the call sites, so a future write cannot
     *  forget it. A failed bump is not swallowed: a caller who was told their revocation landed must not have it
     *  reach one node only. */
    void mutated() throws IOException {
        if (epoch != null) {
            epoch.bump();
            seenEpoch = null;   // this node has just changed things; do not clear on its own bump
            seenAt = 0;
        }
    }

    /** Forget everything this node has cached and tell every other node to do the same. A no-op on an open
     *  deployment, which caches nothing. */
    void forget() throws IOException {
        if (cache == null) {
            return;
        }
        cache.clear();
        mutated();
    }

    /** Bump the epoch without changing a document; {@code false} on an open deployment, which keeps none. */
    boolean invalidateAcrossNodes() throws IOException {
        if (epoch == null) {
            return false;
        }
        mutated();
        return true;
    }

    Properties read(String path) throws IOException {
        Optional<ArtifactStore.Versioned> object = cache.readVersioned(path);
        if (object.isEmpty()) {
            return null;
        }
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(object.get().content()));
        return properties;
    }

    /** Whether a document is present, read through the cache. */
    boolean present(String path) throws IOException {
        return cache.readVersioned(path).isPresent();
    }

    /**
     * Read a document, change it, write it back - under compare-and-set, re-reading on a loss.
     *
     * <p>A plain read-modify-write - read the grants object, set one scope, put the whole object back
     * unconditionally - would let two administrators granting <em>different</em> scopes to one subject at the same
     * moment race, and the loser's grant would be overwritten with no error and nothing to notice it by - on the
     * object that decides what a caller may do. It is the shape the project's own rule names: a
     * read-modify-write on a store with no atomic update is a compare-and-set through {@link Retries}, which
     * re-reads and re-applies rather than clobbering, and throws when it has genuinely lost rather than pretending
     * it kept the record.
     *
     * <p>The write goes to the store rather than through the cache, so the node's own next read is invalidated
     * explicitly and every other node's within the epoch's ttl - the same pair {@link #tryUpdate} makes.
     */
    void mutate(String path, Consumer<Properties> change) throws IOException {
        Retries.update(store, path, current -> {
            Properties properties = new Properties();
            if (current.isPresent()) {
                properties.load(new ByteArrayInputStream(current.get().content()));
            }
            change.accept(properties);
            return Documents.bytes(properties);
        });
        cache.invalidate(path);
        mutated();
    }

    /** {@link Retries#tryUpdate} on one document, past the cache: the node's own next read is invalidated and every
     *  other node's within {@link #EPOCH_TTL}, whether or not the write landed. Answers whether it landed. */
    boolean tryUpdate(String path, Retries.Mutation mutation) throws IOException {
        boolean landed = Retries.tryUpdate(store, path, mutation);
        cache.invalidate(path);   // written past the cache: the node's next read must see it
        mutated();                // ...and every other node's, within EPOCH_TTL
        return landed;
    }

    void write(String path, Properties properties) throws IOException {
        cache.write(path, Documents.bytes(properties));
        mutated();
    }

    /** Remove one document and mark the deployment changed - the delete half of {@link #write}, so both directions
     *  of a mutation bump the epoch from one place. */
    void remove(String path) throws IOException {
        cache.delete(path);
        mutated();
    }

    /** Remove one document without bumping the epoch, for a caller removing several that bumps once after the
     *  last of them. */
    void delete(String path) throws IOException {
        cache.delete(path);
    }

    /** One page of the subject ids under {@code prefix}, decoded, in key order: at most {@code limit} strictly after
     *  {@code after} ({@code null} from the start), and the id to continue from ({@code null} on the last page). It
     *  freshens first, since a subject written on another node is one a listing must not omit. */
    Authorization.SubjectPage subjects(String prefix, String after, int limit) {
        if (store == null) {
            return new Authorization.SubjectPage(List.of(), null);
        }
        freshen();
        List<String> names = new ArrayList<>();
        store.page(prefix, after == null ? "" : segment(after), ArtifactStore.oneMoreThan(limit), names::add);
        boolean more = names.size() > limit;
        List<String> page = more ? names.subList(0, limit) : names;
        List<String> ids = new ArrayList<>(page.size());
        for (String name : page) {
            ids.add(unsegment(name));
        }
        return new Authorization.SubjectPage(List.copyOf(ids), more ? ids.getLast() : null);
    }

    /** A tenant-wide document of the credential space, beside the tenant's kind segments:
     *  {@code .system/auth/<tenant>/<name>}. */
    static String tenantDocument(String tenant, String name) {
        return AUTH + "/" + tenant + "/" + name;
    }

    static String kindPrefix(String tenant, Authorization.Kind kind) {
        return AUTH + "/" + tenant + "/" + kind.segment();
    }

    static String grantsPath(String tenant, String hash) {
        return grantsPath(tenant, Authorization.Subject.credential(hash));
    }

    static String grantsPath(String tenant, Authorization.Subject subject) {
        return subjectPath(tenant, subject) + "/grants";
    }

    static String metadataPath(String tenant, String hash) {
        return metadataPath(tenant, Authorization.Subject.credential(hash));
    }

    static String metadataPath(String tenant, Authorization.Subject subject) {
        return subjectPath(tenant, subject) + "/metadata";
    }

    /**
     * Where a group's members live: one small object per member, under the group's own subject path.
     *
     * <p>One object each rather than one document listing them all. A single document cannot be paged, is re-read
     * whole on every lost compare-and-set, and makes two administrators adding two <em>unrelated</em> people
     * contend; a group is exactly where that hurts, because the thing that fills one is an identity provider
     * pushing a few hundred members at once.
     */
    static String memberPath(String tenant, String group, String principal) {
        return membersPrefix(tenant, group) + "/" + segment(principal);
    }

    static String membersPrefix(String tenant, String group) {
        return subjectPath(tenant, Authorization.Subject.group(group)) + "/members";
    }

    /**
     * Where a principal's <em>effective</em> group rights are kept: the union of every group they belong to, as one
     * document read by one point read.
     *
     * <p>It exists because the honest computation is a fan-out. Effective rights are a union over the caller's own
     * grants and every group's, and enumerating a principal's groups on the authorization path would make the cost
     * of a request a function of how an operator organises their directory - which is the unbounded read the
     * project's own rule forbids, on the hottest path there is. So it takes the shape the stored listings take:
     * computed off the request path, maintained by every write that could change it, and read as it is.
     *
     * <p>Derived state can drift - a node that dies between writing a group's grants and re-deriving the last of
     * its members leaves that member stale - so it is repaired rather than trusted, and the repair is
     * {@link GroupMembership#rederive}. It is deliberately a separate document from {@code grants}: a direct grant
     * and a grant held through a group are different facts about a person, one revocable on its own and one not,
     * and a repair that recomputed a document holding both would erase the half it does not own.
     */
    static String derivedPath(String tenant, Authorization.Subject subject) {
        return subjectPath(tenant, subject) + "/derived";
    }

    /**
     * Where one subject's documents live: {@code .system/auth/<tenant>/<kind>/<id>}.
     *
     * <p>The kind segment is what makes a grant's holder part of the key rather than a convention, and it also keeps
     * a listing right: directly under the tenant, credentials would sit beside that tenant's {@code policy},
     * {@code quota} and {@code roles} objects, and enumerating them would return those too. Under a kind segment the
     * enumeration names credentials and nothing else.
     */
    static String subjectPath(String tenant, Authorization.Subject subject) {
        return kindPrefix(tenant, subject.kind()) + "/" + segment(subject.id());
    }

    /**
     * One subject id as one key segment: a slash becomes {@code %2F} and everything else is left alone.
     *
     * <p>Deliberately the identity for an id that needs no encoding: a hash is hex, so its key is the hash itself.
     * Only a principal's provider-qualified id - {@code github/octocat} - is rewritten.
     *
     * <p>A percent is escaped first, so the encoding is reversible and two different ids cannot collide on one
     * key: without it {@code a%2Fb} and {@code a/b} would both key as {@code a%2Fb}, which on an authorization
     * path means one person's grants answering for another's.
     */
    static String segment(String id) {
        return id.indexOf('%') < 0 && id.indexOf('/') < 0
                ? id
                : id.replace("%", "%25").replace("/", "%2F");
    }

    /** The id a key segment names - {@link #segment} read backwards, so an enumeration hands back the id a caller
     *  granted rather than the shape it is stored under. The slash is decoded before the percent, mirroring the
     *  order the encoder escapes them in. */
    static String unsegment(String name) {
        return name.indexOf('%') < 0 ? name : name.replace("%2F", "/").replace("%25", "%");
    }
}
