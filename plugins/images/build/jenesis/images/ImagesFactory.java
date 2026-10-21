package build.jenesis.images;

import module java.base;

import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildModuleFactory;
import build.jenesis.Environment;
import build.jenesis.Permits;
import build.jenesis.Repository;
import build.jenesis.Resolver;

/**
 * Creates the {@link Images} releaser from the lines of {@code plugin-images.properties}, publishing only when the
 * loading build releases with {@code -Djenesis.release.publish=true}, which only a command line may set.
 */
public class ImagesFactory implements BuildModuleFactory {

    @Override
    public BuildExecutorModule create(Environment environment,
                                      SequencedMap<String, String> properties,
                                      Permits permits,
                                      String group,
                                      Map<String, Repository> repositories,
                                      Map<String, Resolver> resolvers) {
        return new Images(properties, environment.flag("release.publish"));
    }
}
