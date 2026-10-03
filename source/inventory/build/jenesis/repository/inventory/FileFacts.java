package build.jenesis.repository.inventory;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.metadata.Section;

/**
 * The per-file half of a version's section: each file's own facts under {@code "files"}, keyed by the file's path in
 * the repository, from which the section's version-level fields are derived. A file recorded again replaces its own
 * entry and no other, so what a version says is the same whichever of its files landed last - a Maven POM's
 * dependencies survive the jar that follows it, and a signed jar does not hide the unsigned POM beside it.
 */
final class FileFacts {

    private static final String FILES_FIELD = "files";

    private FileFacts() {
    }

    /** Each file's entry the section holds, in path order; empty when it holds none. */
    static SortedMap<String, JsonNode> read(Optional<Section> section) {
        SortedMap<String, JsonNode> files = new TreeMap<>();
        section.flatMap(Section::payload).ifPresent(data -> data.path(FILES_FIELD).properties()
                .forEach(entry -> files.put(entry.getKey(), entry.getValue())));
        return files;
    }

    /** The entries {@code current} holds with {@code file}'s replaced by {@code entry}. */
    static SortedMap<String, JsonNode> with(Optional<Section> current, String file, JsonNode entry) {
        SortedMap<String, JsonNode> files = read(current);
        files.put(Objects.requireNonNull(file, "file"), entry);
        return files;
    }

    /** Write {@code files} into a section's {@code data}. */
    static void write(ObjectNode data, SortedMap<String, JsonNode> files) {
        ObjectNode written = data.putObject(FILES_FIELD);
        files.forEach(written::set);
    }
}
