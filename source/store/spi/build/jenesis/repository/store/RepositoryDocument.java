package build.jenesis.repository.store;

import module java.base;
import build.jenesis.repository.scope.Scopes;

/**
 * A repository's own document, {@link Scopes#REPOSITORY} at the root of its scope: the one format the repository
 * holds, and when it was created. A repository exists when it has one - a request to a name that has none is not
 * answered, and neither is a request to a repository whose format this deployment does not carry - and only its
 * format is offered in it.
 *
 * <p>It is written when the repository is created ({@link #create}) and rewritten only to give the repository a type
 * that holds everything its old one did ({@link #retype}) - which of those are compatible is the formats' to say, so
 * the decision lives with them; nothing stored ever stops answering.
 */
public record RepositoryDocument(String format, Instant created) {

    public RepositoryDocument {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(created, "created");
        if (!format.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Not a format name: " + format);
        }
    }

    /** The document of the repository whose scope this is, or empty when it has none. */
    public static Optional<RepositoryDocument> read(ArtifactStore repository) throws IOException {
        Optional<ArtifactStore.Versioned> stored = repository.readVersioned(Scopes.REPOSITORY);
        return stored.isEmpty() ? Optional.empty() : parse(stored.get().content());
    }

    /**
     * The document of {@code tenant}'s {@code repository}, read through the node's {@link StoreCache} for a path that
     * asks on every request. A document found is served from the cache for its ttl; one not found is asked of the
     * store again, so a repository created on another node answers here as soon as it exists.
     */
    public static Optional<RepositoryDocument> cached(ArtifactStore root, String tenant, String repository)
            throws IOException {
        StoreCache cache = StoreCache.of("formats", root, StoreCache.configuredTtl());
        String key = tenant + "/" + repository + "/" + Scopes.REPOSITORY;
        Optional<ArtifactStore.Versioned> stored = cache.readVersioned(key);
        if (stored.isEmpty()) {
            cache.invalidate(key);
            stored = cache.readVersioned(key);
        }
        return stored.isEmpty() ? Optional.empty() : parse(stored.get().content());
    }

    /**
     * Write this document into a repository's scope if it has none yet, creating the repository - or giving a
     * repository that holds content but no format the one it holds. Every surface that creates a repository creates
     * it here, so the console, the API and the command line create the same thing.
     *
     * @return {@code false} when the repository already has a document, which is left as it is.
     */
    public boolean create(ArtifactStore repository) throws IOException {
        return repository.writeVersioned(Scopes.REPOSITORY, content(), null);
    }

    /**
     * Rewrite the document of the repository whose scope this is to record {@code format}, keeping when it was
     * created. Only for a type that holds every format the old one did, which the caller has decided; compare-and-set,
     * so a concurrent rewrite loses rather than interleaves.
     *
     * @return {@code false} when the repository has no document, or another write moved it since it was read.
     */
    public static boolean retype(ArtifactStore repository, String format) throws IOException {
        Optional<ArtifactStore.Versioned> stored = repository.readVersioned(Scopes.REPOSITORY);
        if (stored.isEmpty()) {
            return false;
        }
        Optional<RepositoryDocument> current = parse(stored.get().content());
        if (current.isEmpty()) {
            return false;
        }
        return repository.writeVersioned(Scopes.REPOSITORY,
                new RepositoryDocument(format, current.get().created()).content(), stored.get().token());
    }

    private byte[] content() {
        return ("format=" + format + "\ncreated=" + created + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Optional<RepositoryDocument> parse(byte[] content) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(new String(content, StandardCharsets.UTF_8)));
        String format = properties.getProperty("format");
        String created = properties.getProperty("created");
        if (format == null || created == null) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryDocument(format, Instant.parse(created)));
    }
}
