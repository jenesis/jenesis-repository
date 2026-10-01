package build.jenesis.repository.importer;

import module java.base;

import build.jenesis.repository.store.Providers;

/**
 * The one discovery of the import sources, validated through {@link Providers#all} (a blank or duplicate name throws
 * once) and held for the process; a holder, so the load happens on first use.
 */
final class ImportSources {

    static final List<ImportSourceProvider> DECLARED = Providers.all("import-source",
            ServiceLoader.load(ImportSourceProvider.class), ImportSourceProvider::name, _ -> true, Optional::of);

    private ImportSources() {
    }
}
