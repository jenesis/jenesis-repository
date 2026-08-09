package build.jenesis.repository.format.contract.test;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis module layout's leg of the shared contract - the smallest of the four, and honestly so: it serves
 * {@code /module/} and {@code /artifact/} pointers and carries the {@code ArtifactLayout} coordinate seam, but it
 * publishes no listing, generates no document and does not proxy. Three properties are therefore excluded with
 * reasons naming the absent protocol surface rather than an absent implementation.
 */
final class JenesisFormatFixture implements FormatFixture {

    private static final String MODULE = "contract.module";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "jenesis";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.jenesis.JenesisFormat";
    }

    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = FormatFixture.super.serving();
        }
        return serving;
    }

    @Override
    public List<String> namespaces() {
        return List.of("publish/module", "publish/artifact", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        String path = "/module/" + MODULE + "/1.0.0/" + MODULE + ".jar";
        ContractExchange put = ContractExchange.of("PUT", path, body);
        serving().handle(put, store);
        if (put.status() != 201) {
            throw new AssertionError("seeding " + path + " answered " + put.status() + " rather than 201");
        }
        try {
            return new Published(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String probe(String vector) {
        return "/module/" + vector;
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION,
                "the module layout publishes no enumeration surface at all - no listing, no version index, no "
                        + "catalogue - so there is no name for a hold to leave. Its serve-side retraction (a held "
                        + "path answers 404) is the publish/-namespace screen the Maven and raw legs prove over the "
                        + "same Publication.located chain",
                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY,
                "JenesisFormat implements no ProxyFormat: the module layout is publish-only, with no upstream to "
                        + "mirror, so there is no fetched body to verify",
                FormatContract.Property.PROXY_STREAMS_UPSTREAM_BODY,
                "JenesisFormat implements no ProxyFormat, so it has no pull-through leg to stream. Its publish path "
                        + "streams through the same Publication.storeBlob the Maven and raw legs stream through",
                FormatContract.Property.GENERATED_INDEX_IS_REVALIDATABLE,
                "the module layout renders nothing on read - every /module/ and /artifact/ response is stored bytes "
                        + "streamed back - so it has no generated document to revalidate");
    }
}
