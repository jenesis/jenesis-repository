package build.jenesis.repository.server.kernel;

import module java.base;
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
                        "The most members one exploded archive may publish; the walk stops at this cap. Bounds the "
                                + "zip-bomb axis - entry size is harmless because every entry streams.",
                        Setting.Kind.INTEGER, "10000", true),
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
