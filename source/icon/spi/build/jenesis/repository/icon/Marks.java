package build.jenesis.repository.icon;

import module java.base;

/**
 * The ONE resolution of "what do I draw for this contributor?", defined once here so that every plug-in family and
 * every console asks it the same way. It answers in three shapes rather than one, holds the only two documents that
 * belong to no contributor, and computes rather than stores the one it derives.
 *
 * <p><strong>Why this exists.</strong> A console that resolves marks needs the same four things for every family:
 * how to turn a contributor's embedded bytes into the document it inlines, what to draw for a contributor that
 * declares none, what to draw for a name whose contributor is <em>gone</em>, and what to draw where there is no
 * contributor at all. Each of those answered privately per family is four opportunities to disagree, and the second
 * family never arrives with a different answer - it arrives with a second copy of the first. So they live here, on a
 * {@code java.base}-only seam below every family, and each family's own module keeps only the mapping that is
 * genuinely its own (which storage namespace a format owns, which plug-in produced a finding).
 *
 * <p><strong>What it deliberately is not.</strong> It discovers nothing - no {@link java.util.ServiceLoader}, no
 * registry, no cache - so it never becomes a second discovery pipeline beside the family clauses that already find
 * these implementations. It contains nothing either: it is a pure function, and a contributor that throws propagates
 * to whichever surface asked rather than being swallowed into a plausible-looking mark.
 *
 * <h2>The generated scheme</h2>
 * A contributor that declares no mark still gets a stable figure of its own, because a page of identical neutral
 * boxes attributes nothing. The figure is a pure function of the contributor's name:
 * <ol>
 *   <li>the name is hashed with SHA-256, whose output is fixed by its specification - not
 *       {@link String#hashCode()}, not an identity hash, nothing that could differ between two JVMs;</li>
 *   <li>the digest is read as an unsigned integer and taken apart into fifteen base-three digits, each choosing one
 *       cell's ink: empty, a filled rounded square, or a dot. Three inks rather than two roughly triples the
 *       distinguishable figures per cell and, more usefully, makes two figures differing in one cell differ
 *       <em>visibly</em> rather than by one missing dot;</li>
 *   <li>the fifteen cells fill a five-by-five grid mirrored about its vertical axis, so the result reads as a mark
 *       rather than as noise;</li>
 *   <li>a figure with fewer than three inked cells - the handful of names that would otherwise draw almost nothing -
 *       has every digit advanced once, which is deterministic, bounded to a single step (advancing an empty cell
 *       inks it, so one step can only overshoot the floor) and leaves the figure a function of the name alone.</li>
 * </ol>
 * Every stroke and fill is {@code currentColor}, so a generated mark inverts with the theme exactly like a declared
 * one, and the document contains no text at all - the name is an <em>input</em> to the geometry and never appears in
 * the output, so a mark inlined into a page can carry nothing injectable into it.
 *
 * <p>That is {@code 3^15} - 14,348,907 - distinguishable figures, and the headroom is <em>measured</em> rather than
 * assumed: {@code GeneratedMarkTest} draws a realistic contributor set (every format this product could plausibly
 * ship beside every advisory feed, inspector, gate policy, classifier and scan marker it could plausibly ship) and
 * requires the figures to be pairwise distinct, and it pins the collision rate at deliberately absurd scale so that
 * a change narrowing the space is caught by the number rather than by a rendered page. A deployment would need on
 * the order of a thousand contributors before a single colliding pair became likely at all.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@code Marks} is stateless: every method is a pure function of its arguments, it holds no
 *     mutable static state, memoizes nothing, and may be called concurrently from any thread. It is only as
 *     thread-safe as what is handed in - {@link #of} calls the contributor's own {@link IconContributor#icon()},
 *     which that interface's contract requires to be a constant.</li>
 * <li><b>Idempotency / replay.</b> Every method is referentially transparent, and {@link #generated} and
 *     {@link #orphaned} are so across <em>processes</em>, not merely within one: the same name yields the identical
 *     document on every call, after every restart, on every JVM and on every platform, because SHA-256 and integer
 *     division are specified rather than implementation-defined. That is what lets a surface render a mark, cache
 *     it, serve it with an {@code ETag} and revalidate it, and it is pinned by a golden test rather than assumed.</li>
 * <li><b>Absence sentinel.</b> There is no absent answer. {@link #of} never returns {@code null} and never returns
 *     empty - a contributor declaring no mark resolves to {@link Mark.Kind#GENERATED}, not to nothing - and
 *     {@link #neutral()} is the document for the case where there is no contributor to ask. A {@code null} or blank
 *     name is a programming error and throws rather than resolving to a shared "unknown" figure, because two
 *     different unnamed things sharing one mark is exactly the mis-attribution this type exists to prevent.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed and nothing is contained. A contributor whose
 *     {@link IconContributor#icon()} throws - which its contract forbids - propagates out of {@link #of} to the
 *     surface that asked, which contains it the way it contains any other contributor failure (a console panel's
 *     {@code Contributions}, an endpoint's error handling). This class deliberately does not become a second
 *     containment mechanism beside the one the collected-report seams already share.</li>
 * <li><b>Read purity (&sect;10).</b> Resolving or generating a mark performs <b>no I/O</b>: no file read, no
 *     classpath resource lookup, no store access, no fetch, no write, no logging. Every method computes from its
 *     arguments and returns. It is called on a render path, once per rendered row, so this is a hard requirement
 *     rather than a preference.</li>
 * <li><b>Lifecycle / ownership.</b> There is nothing to create and nothing to close: the class is not instantiable,
 *     owns no thread, client or cache, and retains no reference to a contributor after a call returns. A caller that
 *     wants memoization owns it - the resolution is cheap but not free, and which key it should be memoized under
 *     (a name, a storage namespace, an ecosystem) is the caller's question, not this one's.</li>
 * <li><b>Ordering / concurrency.</b> Results are independent of discovery order, of how many contributors are
 *     installed and of what else is on the module path: a mark is a function of one contributor, never of the set.
 *     Two deployments with the same contributor installed therefore render the identical figure for it.</li>
 * <li><b>Bounded work / cancellation.</b> Every answer is bounded by construction and by a constant: one SHA-256 of
 *     a short name, fifteen divisions, and at most twenty-five drawing elements. Nothing here scales with the store,
 *     the number of contributors or the size of anything a caller holds, so there is no cap to hit and no truncated
 *     outcome to report.</li>
 * </ol>
 */
public final class Marks {

    /**
     * The neutral mark: an isometric package box, rendered where <em>nothing markable was identified at all</em> - an
     * ecosystem no installed plug-in declares, a row whose subject has no contributor to attribute it to - so a
     * surface degrades to one uniform glyph rather than to a hole or a broken image. It is the only document here
     * that stands for nobody, which is why it is not a {@link Mark}: a {@link Mark} always names a contributor.
     * An original CC0 line glyph, recorded in this module's {@code ICONS.md}; it lives with the resolution rather
     * than in any contributing module precisely because it belongs to no contributor.
     */
    private static final String NEUTRAL = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2 4 6.5v11L12 22l8-4.5v-11z"/><path d="M4 6.5 12 11l8-4.5"/><path d="M12 11v11"/>
            </svg>""";

    /** The grid is five cells square and mirrored about its vertical axis, so three columns are free and two repeat
     *  them; fifteen free cells is what the digest is taken apart into. */
    private static final int SIZE = 5;
    private static final int FREE_COLUMNS = 3;
    private static final int CELLS = SIZE * FREE_COLUMNS;

    /** Each cell's ink, chosen by one base-three digit: nothing, a filled rounded square, or a dot. */
    private static final int INKS = 3;

    /** A figure with fewer inked cells than this draws almost nothing, so its digits are advanced once (see the
     *  scheme above). Advancing turns every empty cell into an inked one, so a single step always clears the
     *  floor - the correction is bounded rather than a loop that could in principle not terminate. */
    private static final int MINIMUM_INK = 3;

    /** Cell centres and the top-left corners of the squares drawn at them, as literal coordinate text rather than
     *  formatted numbers: the document must be byte-identical across platforms, and locale-sensitive or
     *  precision-sensitive formatting is the one way it would not be. */
    private static final String[] CENTRES = {"6.4", "9.2", "12", "14.8", "17.6"};
    private static final String[] CORNERS = {"5.4", "8.2", "11", "13.8", "16.6"};

    private static final BigInteger INK_BASE = BigInteger.valueOf(INKS);

    private Marks() {
    }

    /**
     * The neutral mark's document - see {@link #NEUTRAL}. Rendered where there is no contributor to ask at all; a
     * contributor that is installed but declares none gets {@link #generated} instead, and one that is not installed
     * gets {@link #orphaned}, because collapsing either of those onto this glyph is exactly the loss of information
     * a console needs.
     */
    public static String neutral() {
        return NEUTRAL;
    }

    /**
     * The mark for an installed contributor: its own document when it declares one ({@link Mark.Kind#DECLARED}), and
     * the figure derived from its name when it does not ({@link Mark.Kind#GENERATED}). This is the call a surface
     * makes for every contributor it can still reach; {@link #orphaned} is the call it makes for the ones it cannot.
     */
    public static Mark of(IconContributor contributor) {
        Objects.requireNonNull(contributor, "contributor");
        String name = contributor.name();
        Optional<IconResource> declared = Objects.requireNonNull(contributor.icon(), "icon");
        return declared
                .map(icon -> new Mark(named(name), Mark.Kind.DECLARED, render(icon)))
                .orElseGet(() -> generated(name));
    }

    /**
     * The figure derived from a contributor's name, for one that is installed and declares no mark of its own: a
     * solid tile around the cells the name chooses. Deterministic across renders, restarts and JVMs (contract clause
     * 2), so a plug-in draws the same figure everywhere and an operator learns it as that plug-in's mark.
     */
    public static Mark generated(String name) {
        return new Mark(named(name), Mark.Kind.GENERATED, document(name, false));
    }

    /**
     * The figure for a name <b>no installed contributor answers to</b> - a finding whose plug-in has been removed,
     * a stored namespace whose format module is gone. It is the same figure {@link #generated} would draw, so the
     * row keeps the identity it was recorded with and stays recognisable, inside a <b>dashed</b> tile, so that "this
     * plug-in declares no mark" and "this plug-in is gone" are different drawings and not merely different colours.
     */
    public static Mark orphaned(String name) {
        return new Mark(named(name), Mark.Kind.ORPHANED, document(name, true));
    }

    /**
     * The rendering rule for a declared mark: the contributor's embedded bytes decoded as the UTF-8 document a
     * console inlines. Inline, never fetched as an image, is what lets the mark's {@code currentColor} inherit the
     * surrounding text colour and invert with the light/dark theme; the caller sizes every mark identically.
     */
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
                // Columns 3 and 4 repeat columns 1 and 0: the figure is mirrored about its vertical axis, which is
                // what makes fifteen digits read as a mark rather than as fifteen random cells.
                int mirrored = column < FREE_COLUMNS ? column : SIZE - 1 - column;
                switch (cells[row * FREE_COLUMNS + mirrored]) {
                    case 1 -> svg.append("\n  <rect x=\"").append(CORNERS[column])
                            .append("\" y=\"").append(CORNERS[row])
                            .append("\" width=\"2\" height=\"2\" rx=\"0.5\" fill=\"currentColor\" stroke=\"none\"/>");
                    case 2 -> svg.append("\n  <circle cx=\"").append(CENTRES[column])
                            .append("\" cy=\"").append(CENTRES[row])
                            .append("\" r=\"0.7\" fill=\"currentColor\" stroke=\"none\"/>");
                    default -> {
                        // An empty cell draws nothing - the tile's own outline is what keeps the figure square.
                    }
                }
            }
        }
        return svg.append("\n</svg>").toString();
    }

    /** The fifteen cells a name chooses: the base-three digits of its SHA-256 digest, floored so a figure is never
     *  almost empty. Method-local mutation only; the array never escapes as anything but a read. */
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

    /** SHA-256 of the name's UTF-8 bytes. Specified output, so the figure is the same on every JVM; pure
     *  computation, so a render path pays a hash and no I/O. */
    private static byte[] sha256(String name) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** A contributor's name is its attribution key, so an absent one is a programming error rather than a row that
     *  quietly shares an "unknown" mark with every other unnamed thing. */
    private static String named(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a contributor's name is its attribution key and cannot be blank");
        }
        return name;
    }
}
