/**
 * Tests for the shared {@code multipart/form-data} reader: that a file part is handed out as a stream bounded to that
 * part (so an artifact-sized upload is never materialised), that the cursor drains and advances past a part the caller
 * ignores whatever order the parts arrive in, and - the bound that did not exist before - that a form field over
 * the field limit yields no value at all rather than a truncated one.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.multipart
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.multipart.test {
    requires build.jenesis.repository.multipart;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
