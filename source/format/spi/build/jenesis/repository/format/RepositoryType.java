package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;

/**
 * What a repository holds, resolved from the type its document records: the formats a request in it is offered, and
 * the mount put back in front of the path within it. A repository of one format restores that format's
 * {@link RepositoryFormat#mount mount} - the segment its URLs leave out, or nothing for a format like Maven whose URLs
 * keep theirs; a {@link CombinedFormat combined} one restores none, since its URLs keep each format's own.
 *
 * @param name    the type's name, as the repository's document records it.
 * @param formats the formats a request in the repository is offered, in the order they are offered.
 * @param mount   what is put in front of the path within the repository to make the path the formats see.
 */
public record RepositoryType(String name, List<RepositoryFormat> formats, String mount) {

    public RepositoryType {
        formats = List.copyOf(formats);
    }

    /**
     * The type named {@code name} among {@code formats} - a format of that name, or a combined type whose every format
     * is among them - or empty when none is.
     */
    public static Optional<RepositoryType> of(String name, List<RepositoryFormat> formats) {
        for (RepositoryFormat format : formats) {
            if (format.name().equals(name)) {
                return Optional.of(new RepositoryType(name, List.of(format), format.mount()));
            }
        }
        for (CombinedFormat combined : CombinedFormat.installed()) {
            if (combined.name().equals(name)) {
                List<RepositoryFormat> members = new ArrayList<>();
                for (String member : combined.formats()) {
                    formats.stream().filter(format -> format.name().equals(member)).findFirst()
                            .ifPresent(members::add);
                }
                return members.size() == combined.formats().size()
                        ? Optional.of(new RepositoryType(name, members, ""))
                        : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** {@link #of} among the installed formats. */
    public static Optional<RepositoryType> installed(String name) {
        return of(name, RepositoryFormat.installed());
    }

    /**
     * The types a repository can be created as, by name: every installed format a repository can hold
     * ({@link RepositoryFormat#offered()}), and every combined type all of whose formats are among them. What every
     * surface that creates a repository offers and accepts.
     */
    public static List<String> offerable() {
        List<RepositoryFormat> offered = RepositoryFormat.installed().stream()
                .filter(RepositoryFormat::offered).toList();
        List<String> names = new ArrayList<>(offered.stream().map(RepositoryFormat::name).toList());
        for (CombinedFormat combined : CombinedFormat.installed()) {
            if (of(combined.name(), offered).isPresent()) {
                names.add(combined.name());
            }
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    /** What asking for a repository of a type did: {@link #create}'s answer. */
    public enum Creation {
        /** The repository did not hold a type and now holds this one. */
        CREATED,
        /** The repository already held this type. */
        UNCHANGED,
        /** The repository held a type this one {@link #covers covers}, and now holds this one. */
        RETYPED,
        /** The repository holds a type this one does not cover, which is left as it is. */
        CONFLICT
    }

    /**
     * Make the repository whose scope {@code repository} is hold the type named {@code type} - the one creation every
     * surface makes. A repository that holds no type is given it; one that holds it already is left as it is; one
     * whose type the requested one {@link #covers covers} is given the requested one, since everything it serves
     * still answers where it did; and any other is refused, since stored paths would stop answering.
     */
    public static Creation create(ArtifactStore repository, String type) throws IOException {
        if (new RepositoryDocument(type, Instant.now()).create(repository)) {
            return Creation.CREATED;
        }
        Optional<RepositoryDocument> held = RepositoryDocument.read(repository);
        if (held.isEmpty()) {
            return Creation.CONFLICT;
        }
        if (held.get().format().equals(type)) {
            return Creation.UNCHANGED;
        }
        Optional<RepositoryType> from = installed(held.get().format());
        Optional<RepositoryType> to = installed(type);
        return from.isPresent() && to.isPresent() && to.get().covers(from.get())
                && RepositoryDocument.retype(repository, type) ? Creation.RETYPED : Creation.CONFLICT;
    }

    /**
     * Whether a repository of {@code other}'s type may become one of this type with nothing it serves moving: this
     * type puts the same mount in front of a path and holds every format the other does. {@code java} covers
     * {@code maven} and {@code jenesis}, since all three keep each format's segment in the URL.
     */
    public boolean covers(RepositoryType other) {
        List<String> held = formats.stream().map(RepositoryFormat::name).toList();
        return mount.equals(other.mount)
                && other.formats.stream().map(RepositoryFormat::name).allMatch(held::contains);
    }

    /** The path a format sees for {@code path} within the repository. */
    public String formatPath(String path) {
        return mount + path;
    }

    /**
     * The inverse of {@link #formatPath}: the path a client names within the repository for a path a format lays out,
     * its mount taken off. A path outside the mount is not one this type serves, and is answered unchanged.
     */
    public String servedPath(String formatPath) {
        if (mount.isEmpty()) {
            return formatPath;
        }
        return formatPath.equals(mount) ? "" : formatPath.startsWith(mount + "/")
                ? formatPath.substring(mount.length()) : formatPath;
    }

    /** The format of this type that claims {@code formatPath}, or empty when none does. */
    public Optional<RepositoryFormat> claiming(String formatPath) {
        return formats.stream().filter(format -> format.handles(formatPath)).findFirst();
    }
}
