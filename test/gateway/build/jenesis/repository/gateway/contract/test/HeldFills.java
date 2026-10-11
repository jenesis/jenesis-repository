package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.store.Withheld;

/**
 * What a screened fetch handed the pull-through, told apart by the hold its bytes carry: a held body is handed on
 * with its content withheld, to be laid out behind the hold, an admitted one with none, and a refused one not at all.
 */
final class HeldFills {

    private HeldFills() {
    }

    /** Whether {@code fetched} is a body handed on behind its hold. */
    static boolean held(ArtifactStore store, Optional<ProxyFormat.Fetched> fetched) throws IOException {
        return fetched.isPresent() && Withheld.is(store, Checksums.sha256(fetched.get().body()));
    }

    /** Whether {@code fetched} is a body handed on with no hold over it. */
    static boolean admitted(ArtifactStore store, Optional<ProxyFormat.Fetched> fetched) throws IOException {
        return fetched.isPresent() && !Withheld.is(store, Checksums.sha256(fetched.get().body()));
    }

    /** Whether {@code download} is a streamed body handed on behind its hold, reading and closing it. */
    static boolean heldDownload(ArtifactStore store, Optional<ProxyFormat.Download> download) throws IOException {
        if (download.isEmpty()) {
            return false;
        }
        try (ProxyFormat.Download body = download.get()) {
            return Withheld.is(store, Checksums.sha256(body.body().readAllBytes()));
        }
    }
}
