package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code about} section codec of the consolidated metadata document: what a version's own manifest says the
 * package is for and who wrote it - its description, its keywords, and the names of the people it credits - as the
 * format's inspector read them at publish. It is what a full-text index finds a package by beyond its name, read back
 * from this document rather than by opening the artifact again.
 *
 * <p>Written in the same compare-and-set as the rest of a publish's facts, so recording it costs no store operation of
 * its own, only its bytes in the document. Merged rather than replaced, because the files of one version arrive as
 * separate publishes and not every one of them carries the manifest - a Maven jar after its POM, say - so a later
 * publish that says nothing about the description keeps the one recorded, and keywords and authors are unioned
 * within their bounds. The {@code data} payload is
 * {@code {"description":<text>,"keywords":[<keyword>, ...],"authors":[<name>, ...]}}, neutral as a signal.
 */
public final class AboutSection {

    /** The section tag. */
    public static final String TAG = "about";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    /** The most keywords kept. */
    public static final int KEYWORDS = 32;

    /** The most author names kept. */
    public static final int AUTHORS = 16;

    /** The most characters of one author name kept. */
    public static final int AUTHOR = 128;

    private static final String DESCRIPTION_FIELD = "description";
    private static final String KEYWORDS_FIELD = "keywords";
    private static final String AUTHORS_FIELD = "authors";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AboutSection() {
    }

    /** What a version's manifest said about its package: a description ({@code null} for none), at most
     *  {@link #KEYWORDS} of its keywords and the names of at most {@link #AUTHORS} people it credits, each name cut to
     *  {@link #AUTHOR} characters. */
    public record About(String description, List<String> keywords, List<String> authors) {

        public About {
            description = description == null || description.isBlank() ? null : description;
            keywords = keywords == null ? List.of() : List.copyOf(keywords.subList(0, Math.min(KEYWORDS,
                    keywords.size())));
            List<String> kept = new ArrayList<>();
            for (String author : authors == null ? List.<String>of() : authors) {
                if (author != null && !author.isBlank() && kept.size() < AUTHORS) {
                    String name = author.strip();
                    name = name.length() <= AUTHOR ? name : name.substring(0, AUTHOR);
                    if (!kept.contains(name)) {
                        kept.add(name);
                    }
                }
            }
            authors = List.copyOf(kept);
        }

        /** Whether it says nothing at all. */
        public boolean empty() {
            return description == null && keywords.isEmpty() && authors.isEmpty();
        }
    }

    /** What a section records, or empty when the version has none - its publish recorded none, or its format's
     *  inspector reads no manifest. */
    public static Optional<About> about(Optional<Section> section) {
        if (section.isEmpty()) {
            return Optional.empty();
        }
        return section.get().payload().map(data -> {
            String description = data.path(DESCRIPTION_FIELD).asString("");
            return new About(description.isBlank() ? null : description, strings(data.path(KEYWORDS_FIELD)),
                    strings(data.path(AUTHORS_FIELD)));
        });
    }

    /** Record what the manifest said, merged into what the section held: a description this publish gives replaces
     *  the recorded one, and keywords and authors are unioned. Re-derivable each CAS attempt. */
    public static SectionMutation record(About about, Instant updated) {
        return current -> {
            Optional<About> held = about(current);
            if (held.isEmpty()) {
                return section(about, updated);
            }
            List<String> keywords = new ArrayList<>(held.get().keywords());
            about.keywords().stream().filter(keyword -> !keywords.contains(keyword)).forEach(keywords::add);
            List<String> authors = new ArrayList<>(held.get().authors());
            about.authors().stream().filter(author -> !authors.contains(author)).forEach(authors::add);
            return section(new About(about.description() != null ? about.description() : held.get().description(),
                    keywords, authors), updated);
        };
    }

    /** A section recording {@code about}. */
    public static Section section(About about, Instant updated) {
        ObjectNode data = JSON.createObjectNode();
        if (about.description() != null) {
            data.put(DESCRIPTION_FIELD, about.description());
        }
        ArrayNode keywords = data.putArray(KEYWORDS_FIELD);
        about.keywords().forEach(keywords::add);
        ArrayNode authors = data.putArray(AUTHORS_FIELD);
        about.authors().forEach(authors::add);
        return Section.derived(TAG, SCHEMA, updated, Signal.NEUTRAL, data);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            String text = value.asString("");
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }
}
