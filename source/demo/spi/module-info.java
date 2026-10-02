/**
 * The demo's contributor seam: what a module adds to the sample content the first-run guide loads into an empty
 * deployment. A {@code DemoContributor} says up front what it will do - the repositories it creates, the settings it
 * switches on, the public registries it reaches and the vulnerable code it loads - so the offer can warn about all of
 * it before anything is done, and then loads its content through {@code Demo}, the run's own way to create, configure,
 * publish and fetch, which records every outcome. The contract carries nothing but {@code java.base}; the console
 * module that offers the demo and runs it is a module of its own.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.demo {
    exports build.jenesis.repository.demo;
}
