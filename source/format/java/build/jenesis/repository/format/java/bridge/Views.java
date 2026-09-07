package build.jenesis.repository.format.java.bridge;

import module java.base;

/** The one discovery of the module views on the module path, at this holder's load, cached for the process and
 *  shared by the Maven format's publish path and its rebuild consumer - one instance of each view rather than one
 *  per consumer. Discovery order; views have no names to validate or sort by. */
final class Views {

    static final List<ModuleView> ALL = ServiceLoader.load(ModuleView.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    private Views() {
    }
}
