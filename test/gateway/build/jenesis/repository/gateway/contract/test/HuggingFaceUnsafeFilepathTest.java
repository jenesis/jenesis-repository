package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gateway.testkit.FormatDrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static build.jenesis.repository.gateway.testkit.FormatDrive.Call;
import static build.jenesis.repository.gateway.testkit.FormatDrive.MemStore;
import static build.jenesis.repository.gateway.testkit.FormatDrive.format;

/**
 * Pins the {@code HuggingFaceFormat.unsafeFilepath} guard on the resolve path. A resolve/download request carries a
 * client-supplied filepath ({@code .../resolve/<revision>/<path>}) that the format turns into a store key
 * ({@code .../revs/<revision>/files/<enc(path)>}); the format's {@code enc} percent-encodes only {@code %} and
 * {@code /}, so a {@code ..} segment, a backslash or a control character would otherwise survive into the key. The guard
 * refuses such a filepath up front, so a hostile path is a clean rejection, never a {@code 500}.
 *
 * <p><b>All three shapes are now refused before the guard is reached, and all three answer the same {@code 404}.</b>
 * The shared {@code ArtifactStore.traversalFree} screen at the top of {@code handle()} answers a {@code ..} segment
 * with contract clause 6's {@code 404}; it grew the backslash for the reason a {@code \} is a separator on a
 * Windows-hosted filesystem backend and a literal character on the three object stores; and it has now grown the C0
 * control characters for the same kind of reason - a {@code NUL} truncates the key at the first C API that touches it
 * and a newline forges a line in every log record and index the key reaches.
 *
 * <p><b>The control-character cell asserted {@code 400} until then, and that difference was an accident of which
 * layer caught the shape</b> rather than a decision about what a hostile publish deserves. This class's own argument
 * for the {@code ..} cell is that the contract prescribes the status so that "all fourteen answer the same 404 rather
 * than each format's own idea of a refusal" - and by that argument the {@code 400} was the deviation, kept alive only
 * because the shared screen could not see the shape. It can now, so the three agree (&sect;13). What the cells assert
 * has not weakened: a clean client-side refusal, no exception, and nothing stored.
 *
 * <p>Driven in-process through the {@link FormatDrive} seam ({@code hf.handle(call, store)}), NOT over HTTP, on purpose:
 * the servlet container's own path firewall rejects an encoded {@code ..}/backslash before the format ever sees it, so
 * an HTTP test could not tell the guard apart from the container. The {@link Call} double hands the format the raw path
 * verbatim, the way the guard's own contract is stated. This is what makes the cell load-bearing: with the guard the
 * publish is a clean {@code 400} that stores nothing; delete it and the control-character filepath flows into
 * {@code Blobs.write}, whose {@code requireSafeKey} throws on that segment - so the "clean 400, no exception"
 * assertion turns red.
 */
class HuggingFaceUnsafeFilepathTest {

    private static final String REPO_ID = "acme/model";

    @Test
    void a_valid_filepath_publishes_as_the_baseline() throws IOException {
        MemStore store = new MemStore();
        RepositoryFormat hf = format("huggingface");
        Call put = new Call("PUT", resolve("config.json"), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        hf.handle(put, store);
        assertThat(put.status).as("a well-formed filepath is accepted").isEqualTo(201);
    }

    /** A {@code ..} segment is the one hostile shape {@code RepositoryFormat} contract clause 6 names explicitly, and
     *  it prescribes the status: such a path "addresses nothing in this format's namespace and is answered 404". Since
     *  every format screens it at the request seam through the shared {@code ArtifactStore.traversalFree}
     *  predicate, so all fourteen answer the same 404 rather than each format's own idea of a refusal - which is the
     *  point of a shared guard. The store-stays-empty half of the guard's contract is unchanged. */
    @Test
    void a_parent_directory_filepath_is_a_clean_404_that_stores_nothing() throws IOException {
        assertHostilePutRefused("..", 404);
    }

    /** A backslash used to be answered by this format's own filepath guard, because the shared screen split on
     *  {@code /} alone and read {@code weights\evil} as one long, harmless leaf name. The shared screen
     *  closed that: {@code ArtifactStore.traversalFree} now refuses a {@code \} anywhere, since a backslash is a real
     *  separator on a Windows-hosted filesystem backend and a literal character on the three object stores - one
     *  publish, three placements, and on the first of them a real traversal. So a backslash joined the {@code ..}
     *  family above rather than staying a format-local hostile character, and it takes that family's status: contract
     *  clause 6's shared {@code 404}, decided before {@code unsafeFilepath} is reached. The half of the guard's
     *  contract this cell exists for - a clean refusal that stores nothing and never throws - is what it asserts
     *  either way. */
    @Test
    void a_backslash_filepath_is_a_clean_404_that_stores_nothing() throws IOException {
        assertHostilePutRefused("weights\\evil", 404);
    }

    /** The third shape, answered by the shared screen rather than by {@code unsafeFilepath} - so it is
     *  now a cell about the <em>format staying consistent with its peers</em>, not about this format's own guard.
     *  {@code unsafeFilepath} is still reached for what the shared screen deliberately allows (an empty segment) and
     *  is still what stops a hostile filepath reaching {@code Blobs.write}, whose {@code requireSafeKey} would throw
     *  it out of {@code handle()} as a 500; that falsifier now lives on the shapes the shared screen does not
     *  cover. */
    @Test
    void a_control_character_filepath_is_a_clean_404_that_stores_nothing() throws IOException {
        assertHostilePutRefused("weights\u0001evil", 404);
    }

    @Test
    void a_hostile_filepath_get_is_a_clean_404_never_a_500() throws IOException {
        MemStore store = new MemStore();
        RepositoryFormat hf = format("huggingface");
        // A published main revision, so a GET that clears the guard would otherwise reach the store read.
        hf.handle(new Call("PUT", resolve("config.json"), "{\"a\":1}".getBytes(StandardCharsets.UTF_8)), store);
        Call get = new Call("GET", resolve(".."));
        assertThatCode(() -> hf.handle(get, store)).doesNotThrowAnyException();
        assertThat(get.status).as("a hostile filepath GET is declined as a clean 404, not a 500").isEqualTo(404);
    }

    /** A PUT whose filepath is {@code hostile} must be a clean client error ({@code expected} - no thrown exception,
     *  so not a 500) and must leave the store empty - the guard's contract, and the half of it that does not depend on
     *  which seam refuses the shape. Without a screen the filepath reaches {@code Blobs.write}, whose
     *  {@code requireSafeKey} throws on the same {@code ..}/backslash/control segment, failing the no-throw
     *  assertion. */
    private static void assertHostilePutRefused(String hostile, int expected) throws IOException {
        MemStore store = new MemStore();
        RepositoryFormat hf = format("huggingface");
        Call put = new Call("PUT", resolve(hostile), "payload".getBytes(StandardCharsets.UTF_8));
        assertThatCode(() -> hf.handle(put, store))
                .as("a hostile filepath is refused up front, never thrown out of the handler as a 500")
                .doesNotThrowAnyException();
        assertThat(put.status).as("a hostile filepath PUT is a clean client error").isEqualTo(expected);
        assertThat(store.objects).as("nothing was written under a traversal-unsafe key").isEmpty();
        assertThat(store.pointers).isEmpty();
    }

    private static String resolve(String filepath) {
        return "/huggingface/hf/" + REPO_ID + "/resolve/main/" + filepath;
    }
}
