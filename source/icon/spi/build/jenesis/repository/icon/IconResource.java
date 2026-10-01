package build.jenesis.repository.icon;

import module java.base;

/**
 * A small SVG mark an {@link IconContributor} embeds in its own module: a uniform-square, self-contained document using
 * {@code currentColor} so it follows the light/dark theme, from a permissively licensed source recorded next to the
 * module. Metadata-sized, so it rides whole; an endpoint may serve it immutable and cached, falling back to
 * {@link Marks#neutral()}.
 *
 * @param svg the SVG document bytes, owned by the contributor's module
 * @param mediaType the content type the mark is served as, always {@link #SVG_MEDIA_TYPE}
 */
public record IconResource(byte[] svg, String mediaType) {

    /** The single media type an SVG mark is served as. */
    public static final String SVG_MEDIA_TYPE = "image/svg+xml";

    public IconResource {
        Objects.requireNonNull(svg, "svg");
        Objects.requireNonNull(mediaType, "mediaType");
    }

    /** The mark for an SVG document a contributor embeds as a constant, encoded UTF-8 so an endpoint serves exactly the
     *  declared document. */
    public static IconResource svg(String document) {
        return new IconResource(document.getBytes(StandardCharsets.UTF_8), SVG_MEDIA_TYPE);
    }
}
