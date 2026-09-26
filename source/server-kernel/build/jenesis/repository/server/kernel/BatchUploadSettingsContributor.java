package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the batch archive-ingestion knobs on the settings screens, API and CLI. Both are read live through
 * {@link LiveConfig} on the next request, so an operator switches the explode feature on or retunes its entry cap
 * without a restart.
 */
public final class BatchUploadSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("batch-upload", "Operations", "Batch archive upload",
                        "Explode a single PUT carrying the Jenesis-Explode: zip header into one publish per archive "
                                + "entry, each screened by the compliance gate. Off by default.",
                        Setting.Kind.BOOLEAN, "false", true),
                new Setting("batch-upload-max-entries", "Operations", "Batch upload entry cap",
                        "The most members one exploded archive may publish; the walk stops at this cap.",
                        Setting.Kind.INTEGER, "10000", true),
                new Setting("batch-upload-max-bytes", "Operations", "Batch upload inflated-size cap",
                        "The most bytes one exploded archive's entries may inflate to in all. Streaming bounds memory, "
                                + "not the store: an entry compressed a thousand to one writes its inflated size. The "
                                + "entry that crosses it is refused whole, the walk stops, and the upload answers 413.",
                        Setting.Kind.LONG, Long.toString(RepositoryProperties.BATCH_UPLOAD_MAX_BYTES), true),
                new Setting("batch-upload-max-ratio", "Operations", "Batch upload compression-ratio cap",
                        "How many times the compressed bytes read an exploded archive may inflate to, once past a "
                                + "mebibyte - a ratio no archive of artifacts reaches and a zip bomb starts from. The "
                                + "entry that crosses it is refused whole, the walk stops, and the upload answers 413.",
                        Setting.Kind.INTEGER, Integer.toString(RepositoryProperties.BATCH_UPLOAD_MAX_RATIO), true),
                new Setting("store-families", "Operations", "Count store operations by key family",
                        "Count every store operation by the key family it touched as well as by its name, reported "
                                + "as jenreg.store.family.<operation>.<family> beside jenreg.store.ops.<operation>. "
                                + "Off by default, and worth switching on only while measuring: it costs a map "
                                + "lookup and a string concatenation on the store's hottest path. A count by "
                                + "operation alone cannot say which keys a pass is reading - whether a walk's "
                                + "versioned reads are the pointers it enumerates or a consumer riding it - which "
                                + "is the difference between a change that reduces a bill and one that does nothing.",
                        Setting.Kind.BOOLEAN, "false", false));
    }
}
