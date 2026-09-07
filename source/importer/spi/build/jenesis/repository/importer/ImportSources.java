package build.jenesis.repository.importer;

import module java.base;

import build.jenesis.repository.store.Providers;

/** The one discovery of the import sources on the module path, validated through {@link Providers#all} (a blank or
 *  duplicated name throws here, once) and cached for the process. A holder rather than a field on the interface, so
 *  the load happens on first use and not on the interface's own initialisation. */
final class ImportSources {

    static final List<ImportSourceProvider> DECLARED = Providers.all("import-source",
            ServiceLoader.load(ImportSourceProvider.class), ImportSourceProvider::name, _ -> true, Optional::of);

    private ImportSources() {
    }
}
