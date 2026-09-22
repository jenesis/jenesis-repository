package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.cleanup.RetentionSweeper;

/**
 * Discovers the retention engine: always available when this module is installed - the engine needs no
 * configuration; the policy it applies is supplied per call.
 */
public final class RepositoryCleanerProvider implements RetentionProvider {

    @Override
    public String name() {
        return "cleaner";
    }

    @Override
    public Optional<RetentionSweeper> create(UnaryOperator<String> config) {
        return Optional.of(new RepositoryCleanerSweeper());
    }
}
