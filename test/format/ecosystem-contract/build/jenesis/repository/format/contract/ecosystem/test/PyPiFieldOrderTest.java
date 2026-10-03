package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code twine upload} is screened under the coordinate its form names whichever order the client sent the fields
 * in. The screen keys a deny list, an advisory and a malware verdict on the package and version, so an upload whose
 * {@code name} follows its file and is screened without them is one those screens cannot see - and the distribution
 * is still linked under the name.
 */
class PyPiFieldOrderTest {

    private static final String BOUNDARY = "field-order-boundary";
    private static final String FILE = "order_lib-1.0.0-py3-none-any.whl";

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
        StoredCounter.settle();
    }

    @Test
    void an_upload_naming_its_project_after_the_file_is_screened_under_the_project() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        byte[] distribution = "a wheel whose name follows it".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream form = new ByteArrayOutputStream();
        form.write(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"content\"; filename=\"" + FILE + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        form.write(distribution);
        form.write(("\r\n--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + "Order-Lib\r\n"
                + "--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        ContractExchange upload = ContractExchange.of("POST", "/pypi/", form.toByteArray())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

        ContractHoldInterceptor.recordAssessed();
        List<ArtifactDescriptor> assessed;
        try {
            new PyPiFormatFixture().serving().handle(upload, store);
        } finally {
            assessed = ContractHoldInterceptor.recordedArtifacts();
        }

        assertThat(upload.status()).as("the upload is published").isEqualTo(200);
        assertThat(assessed).as("the distribution was screened once, under the project and version its form names")
                .singleElement().satisfies(artifact -> {
                    assertThat(artifact.path()).isEqualTo("/pypi/simple/order-lib/" + FILE);
                    assertThat(artifact.coordinate()).isEqualTo("order-lib");
                    assertThat(artifact.version()).isEqualTo("1.0.0");
                    assertThat(artifact.hash()).isEqualTo(Packages.sha256(distribution));
                });
    }
}
