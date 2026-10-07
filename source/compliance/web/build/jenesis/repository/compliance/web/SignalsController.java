package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.compliance.scan.SignalStatus;
import build.jenesis.repository.store.ArtifactStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/admin/signals}: what each refreshable signal source holds, as the signal-refresh pass last recorded
 * it ({@link SignalStatus}) - when each was last drawn, whether that answer is authoritative, why its last refresh
 * failed, and for a feed keeping a copy of its vendor's records each ecosystem's copy. One point read, so it stands when
 * every vendor is down; deployment-wide, so an operator's. The console's signal-sources screen reads the same record.
 */
@RestController
public class SignalsController {

    private final ArtifactStore root;

    public SignalsController(ArtifactStore root) {
        this.root = root;
    }

    @GetMapping("/api/admin/signals")
    public SignalsView signals() throws IOException {
        return SignalsView.of(SignalStatus.read(SignalStatus.space(root)));
    }

    /**
     * The record as the API answers it: {@code state} is {@code recorded}, or {@code not-recorded} before the pass has
     * run - then {@code recorded} is {@code null} and {@code sources} empty, which reads as "not known yet" rather than
     * "no source".
     */
    public record SignalsView(String state, Instant recorded, List<SignalStatus.Source> sources) {

        static SignalsView of(Optional<SignalStatus.Status> status) {
            return status.map(found -> new SignalsView("recorded", found.recorded(), found.sources()))
                    .orElse(new SignalsView("not-recorded", null, List.of()));
        }
    }
}
