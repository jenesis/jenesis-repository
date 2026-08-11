package build.jenesis.repository.icon;

import module java.base;

/**
 * A small SVG mark an {@link IconContributor} embeds in its own module and lends to the console through
 * {@link IconContributor#icon()}: a uniform-square, self-contained document (one common {@code viewBox},
 * {@code currentColor}-friendly so it inverts with the light/dark theme) drawn only from permissively-licensed
 * sources, its source and licence recorded next to the module. The bytes are metadata-sized - a brand mark, not an
 * artifact - so they ride whole rather than through the streaming store; a serving endpoint may hand them out
 * immutable and cached, falling back to {@link Marks#neutral()} for a contributor that declares none. The core stays
 * mark-agnostic: it holds this contract, never a brand mark of its own.
 *
 * @param svg       the SVG document bytes, embedded in and owned by the contributor's module
 * @param mediaType the content type the mark is served as, always {@link #SVG_MEDIA_TYPE}
 */
public record IconResource(byte[] svg, String mediaType) {

    /** The single media type an SVG mark is served as. */
    public static final String SVG_MEDIA_TYPE = "image/svg+xml";

    public IconResource {
        Objects.requireNonNull(svg, "svg");
        Objects.requireNonNull(mediaType, "mediaType");
    }

    /**
     * The mark for an SVG document a contributor embeds as a constant in its own module - a uniform-square,
     * {@code currentColor}-friendly glyph from a permissively-licensed source. The text is encoded UTF-8, so the
     * bytes an endpoint serves are exactly the document the contributor declared.
     */
    public static IconResource svg(String document) {
        return new IconResource(document.getBytes(StandardCharsets.UTF_8), SVG_MEDIA_TYPE);
    }
}
