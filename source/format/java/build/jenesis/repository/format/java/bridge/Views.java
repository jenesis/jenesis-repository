package build.jenesis.repository.format.java.bridge;

import module java.base;

/**
 * The one discovery of the module views, at this holder's load, held for the process and shared by the Maven publish
 * path and its rebuild consumer. Discovery order; views have no names to sort by.
 */
final class Views {

    static final List<ModuleView> ALL = ServiceLoader.load(ModuleView.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    private Views() {
    }
}
