package build.jenesis.repository.icon;

import module java.base;

/**
 * The one resolution of "what do I draw for this contributor?", shared by every plug-in family and every console: how a
 * contributor's bytes become the inlined document, what to draw for one that declares none, for a name whose
 * contributor is gone, and where there is no contributor at all. Each family keeps only the mapping that is its own
 * (which namespace a format owns, which plug-in produced a finding).
 *
 * <p>It discovers nothing - no {@link java.util.ServiceLoader}, registry or cache - and contains nothing: a pure
 * function, so a contributor that throws propagates to the surface that asked.
 *
 * <h2>The generated scheme</h2>
 * A contributor that declares no mark still gets a stable figure, a pure function of its name:
 * <ol>
 *   <li>the name is hashed with SHA-256, whose output is fixed by specification - nothing that could differ between
 *       JVMs;</li>
 *   <li>the digest is read as an unsigned integer and taken apart into fifteen base-three digits, each choosing one
 *       cell's ink: empty, a filled rounded square, or a dot - three inks so two figures differing in one cell differ
 *       visibly;</li>
 *   <li>the fifteen cells fill a five-by-five grid mirrored about its vertical axis, so the result reads as a
 *       mark;</li>
 *   <li>a figure with fewer than three inked cells has every digit advanced once - deterministic, and bounded to one
 *       step because advancing inks every empty cell.</li>
 * </ol>
 * Every stroke and fill is {@code currentColor}, and the document contains no text: the name is an input to the
 * geometry, never output, so an inlined mark can carry nothing injectable. That gives {@code 3^15} (14,348,907)
 * figures; {@code GeneratedMarkTest} requires a realistic contributor set to draw pairwise distinct figures and pins
 * the collision rate at scale.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Stateless: every method is a pure function of its arguments, memoizes nothing and may be
 *       called concurrently. {@link #of} calls the contributor's {@link IconContributor#icon()}, which its contract
 *       requires to be a constant.</li>
 *   <li><b>Idempotency / replay.</b> Every method is referentially transparent, and {@link #generated} and
 *       {@link #orphaned} across processes too: the same name yields the identical document on every JVM and platform,
 *       so a surface can cache a mark and serve it with an {@code ETag}. A golden test pins it.</li>
 *   <li><b>Absence sentinel.</b> There is no absent answer: {@link #of} never returns {@code null} or empty (a
 *       contributor declaring no mark resolves to {@link Mark.Kind#GENERATED}), and {@link #neutral()} serves where
 *       there is no contributor. A {@code null} or blank name throws rather than sharing an "unknown" figure.</li>
 *   <li><b>Error visibility.</b> Nothing is swallowed: a contributor whose {@link IconContributor#icon()} throws
 *       propagates out of {@link #of} to the surface that asked, which contains it as it contains any contributor
 *       failure.</li>
 *   <li><b>Read purity.</b> No I/O and no logging: every method computes from its arguments. It runs once per rendered
 *       row.</li>
 *   <li><b>Lifecycle / ownership.</b> Not instantiable, owns nothing, retains no contributor. A caller wanting
 *       memoization owns it and chooses its key.</li>
 *   <li><b>Ordering / concurrency.</b> A mark is a function of one contributor, never of the installed set or discovery
 *       order.</li>
 *   <li><b>Bounded work / cancellation.</b> One SHA-256 of a short name, fifteen divisions and at most twenty-five
 *       drawing elements; nothing scales with the store or the contributor count.</li>
 * </ol>
 */
public final class Marks {

    /** The neutral mark, an isometric package box, rendered where nothing markable was identified - an ecosystem no
     *  installed plug-in declares, a row with no contributor - so a surface shows one uniform glyph rather than a hole.
     *  It stands for nobody, so it is not a {@link Mark}, which always names a contributor. An original CC0 line glyph
     *  drawn for this project. */
    private static final String NEUTRAL = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2 4 6.5v11L12 22l8-4.5v-11z"/><path d="M4 6.5 12 11l8-4.5"/><path d="M12 11v11"/>
            </svg>""";

    /** The grid is five cells square and mirrored about its vertical axis: three free columns, so fifteen free
     *  cells. */
    private static final int SIZE = 5;
    private static final int FREE_COLUMNS = 3;
    private static final int CELLS = SIZE * FREE_COLUMNS;

    /** Each cell's ink, chosen by one base-three digit: nothing, a filled rounded square, or a dot. */
    private static final int INKS = 3;

    /** A figure with fewer inked cells than this has its digits advanced once; advancing inks every empty cell, so one
     *  step always clears the floor. */
    private static final int MINIMUM_INK = 3;

    /** Cell centres and square corners as literal text rather than formatted numbers, so the document is byte-identical
     *  across platforms and locales. */
    private static final String[] CENTRES = {"6.4", "9.2", "12", "14.8", "17.6"};
    private static final String[] CORNERS = {"5.4", "8.2", "11", "13.8", "16.6"};

    private static final BigInteger INK_BASE = BigInteger.valueOf(INKS);

    private Marks() {
    }

    /** The neutral mark's document ({@link #NEUTRAL}), for where there is no contributor to ask. An installed
     *  contributor without a mark gets {@link #generated} and an uninstalled one {@link #orphaned}, never this
     *  glyph. */
    public static String neutral() {
        return NEUTRAL;
    }

    /** The mark for an installed contributor: its own document ({@link Mark.Kind#DECLARED}), or the figure derived from
     *  its name ({@link Mark.Kind#GENERATED}). {@link #orphaned} is the call for a name nothing answers to. */
    public static Mark of(IconContributor contributor) {
        Objects.requireNonNull(contributor, "contributor");
        String name = contributor.name();
        Optional<IconResource> declared = Objects.requireNonNull(contributor.icon(), "icon");
        return declared
                .map(icon -> new Mark(named(name), Mark.Kind.DECLARED, render(icon)))
                .orElseGet(() -> generated(name));
    }

    /** How many tints the palette holds. Changing it renumbers every mark, so it is a constant pinned by the golden
     *  test. */
    public static final int TINTS = 12;

    /** The digest's top bits, which the geometry never reaches (its fifteen digits consume about 24 low bits), so the
     *  tint varies independently of the figure. */
    private static final int TINT_SHIFT = 232;

    /**
     * The palette bucket a name is tinted with - a second axis of identity beside the figure. Deterministic across
     * processes, like the figure.
     *
     * <p><b>A bucket rather than a colour</b>: the colour lives in the console's stylesheet, one per bucket per theme,
     * so each theme holds its contrast floor and the document's bytes - and its {@code ETag} - do not depend on the
     * theme.
     */
    public static int tint(String name) {
        return new BigInteger(1, sha256(named(name)))
                .shiftRight(TINT_SHIFT).mod(BigInteger.valueOf(TINTS)).intValue();
    }

    /** The figure derived from a contributor's name, on a solid tile, for an installed contributor that declares no
     *  mark. Deterministic across renders, restarts and JVMs (clause 2). */
    public static Mark generated(String name) {
        return new Mark(named(name), Mark.Kind.GENERATED, document(name, false));
    }

    /** The figure for a name <b>no installed contributor answers to</b> - a finding whose plug-in was removed, a
     *  namespace whose format module is gone. The same figure {@link #generated} draws, so the row keeps its identity,
     *  inside a <b>dashed</b> tile, so "declares no mark" and "is gone" are different drawings. */
    public static Mark orphaned(String name) {
        return new Mark(named(name), Mark.Kind.ORPHANED, document(name, true));
    }

    /** A declared mark's bytes decoded as the UTF-8 document a console inlines. Inlined rather than fetched as an
     *  image, so its {@code currentColor} follows the surrounding text and theme; the caller sizes every mark
     *  identically. */
    public static String render(IconResource icon) {
        Objects.requireNonNull(icon, "icon");
        return new String(icon.svg(), StandardCharsets.UTF_8);
    }

    /** The figure for a name, tiled solid or dashed. Pure: one digest, fifteen divisions, one document. */
    private static String document(String name, boolean dashed) {
        int[] cells = cells(name);
        StringBuilder svg = new StringBuilder(512);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" ")
                .append("stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" ")
                .append("stroke-linejoin=\"round\">\n  <rect x=\"1.5\" y=\"1.5\" width=\"21\" height=\"21\" ")
                .append("rx=\"4.5\"")
                .append(dashed ? " stroke-dasharray=\"3 2.5\"" : "")
                .append("/>");
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                // Columns 3 and 4 mirror columns 1 and 0.
                int mirrored = column < FREE_COLUMNS ? column : SIZE - 1 - column;
                switch (cells[row * FREE_COLUMNS + mirrored]) {
                    case 1 -> svg.append("\n  <rect x=\"").append(CORNERS[column])
                            .append("\" y=\"").append(CORNERS[row])
                            .append("\" width=\"2\" height=\"2\" rx=\"0.5\" fill=\"currentColor\" stroke=\"none\"/>");
                    case 2 -> svg.append("\n  <circle cx=\"").append(CENTRES[column])
                            .append("\" cy=\"").append(CENTRES[row])
                            .append("\" r=\"0.7\" fill=\"currentColor\" stroke=\"none\"/>");
                    default -> {
                        // An empty cell draws nothing; the tile's outline keeps the figure square.
                    }
                }
            }
        }
        return svg.append("\n</svg>").toString();
    }

    /** The fifteen cells a name chooses: the base-three digits of its SHA-256 digest, floored so a figure is never
     *  almost empty. */
    private static int[] cells(String name) {
        BigInteger digest = new BigInteger(1, sha256(name));
        int[] cells = new int[CELLS];
        int inked = 0;
        for (int cell = 0; cell < CELLS; cell++) {
            BigInteger[] split = digest.divideAndRemainder(INK_BASE);
            cells[cell] = split[1].intValue();
            digest = split[0];
            if (cells[cell] != 0) {
                inked++;
            }
        }
        if (inked < MINIMUM_INK) {
            for (int cell = 0; cell < CELLS; cell++) {
                cells[cell] = (cells[cell] + 1) % INKS;
            }
        }
        return cells;
    }

    /** SHA-256 of the name's UTF-8 bytes. */
    private static byte[] sha256(String name) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** A name is the attribution key, so an absent one is a programming error rather than a shared "unknown" mark. */
    private static String named(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a contributor's name is its attribution key and cannot be blank");
        }
        return name;
    }
}
