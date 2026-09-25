# plugins/

Build logic that runs *as part of* the build, not as a test of it. The build tool compiles each plugin from
source with its `InternalModule`, resolves its declared dependencies and loads the `BuildExecutorModule` it
provides. The `.jenesis.skip` marker in this directory keeps them out of the project's own module scan: they
are the build, not the product.

| plugin | what it does |
|---|---|
| `images` | builds an image from each staged Docker context its configuration names, packages the Helm charts it names, and publishes both when a registry is named - `java build/Build.java images`, with `-Djenesis.images.push=<registries>` to publish |

## Using `images` from a build

A plugin of the stock build runs in one of its slots, and none of them runs after `stage`, which writes the
Docker contexts. So `build/Build.java` wires the module in code: it is the stock build plus one module hanging
off `stage`,

```java
executor.addModule("images", (images, _) -> {
    images.addSource("deploy", Bind.asSources(), project.root().resolve("deploy"));
    images.addModule("docker", new InternalModule("module", "tool", project.root().resolve("plugins/images"))
                    .properties(configuration(project.root().resolve("deploy/images.properties"))),
            new LinkedHashSet<>(List.of("deploy", BuildExecutorModule.PREVIOUS + Project.STAGE + "/docker")));
}, Project.STAGE);
```

and hands it `deploy/images.properties`, which says what becomes of each staged context and which chart to
package:

```properties
image.source/bundle=jenesis-repository      # the module's context, built as jenesis-repository:latest
chart.jenesis=deploy/helm/jenesis           # a chart packaged from the deploy tree bound as "deploy"
chart.jenesis.image=jenesis-repository      # ... running that image by default
```

Another repository that builds on this one - with this one as a Git submodule - points its own
`InternalModule` at `plugins/images` here and hands it its own configuration, binding this deploy tree too
where it packages a chart from the chart source here. A staged context the configuration does not name is an
error rather than a skip; `image.<module>=` says a context is not an image that build makes, and
`publish.<repository>=false` builds an image without ever pushing it.
