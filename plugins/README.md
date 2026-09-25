# plugins/

Build logic that runs *as part of* the build, not as a test of it. The build tool compiles each plugin from
source, resolves its declared dependencies and loads the `BuildExecutorModule` it provides. The `.jenesis.skip`
marker in this directory keeps them out of the project's own module scan: they are the build, not the product.

| plugin | what it does |
|---|---|
| `images` | builds an image from each staged Docker context its configuration names, packages the Helm charts it names, and publishes both when a registry is named - `java build/jenesis/Make.java export/custom/images`, with `-Djenesis.make.profiles=hub` to publish to Docker Hub |

## Using `images` from a build

`images` is an exporter of the whole project, named in `jenesis.plugins.properties`:

```properties
images+export=./plugins/images
```

so it runs with `export`, handed everything `stage` wrote - the Docker contexts among it - and `export/custom/images`
runs it alone. What it makes is its values, in `jenesis.plugins.arguments.properties`: what becomes of each staged
context, which chart to package, and the folder the chart source is read from, bound as an input. A published chart
is versioned with the release (`PUSH_VERSION`) and points at the image published with it, at the registry it was
pushed to, so `helm install jenesis oci://<registry>/jenesis --version <release>` needs nothing set to find its image:

```properties
images.image.source/bundle=jenesis-repository   # the module's context, built as jenesis-repository:latest
images.chart.jenesis=deploy/helm/jenesis        # a chart packaged from the input bound as "deploy"
images.chart.jenesis.image=jenesis-repository   # ... running that image by default
images.@deploy/sources=deploy                   # this repository's deploy/ folder, bound as that input
```

Where to publish is a value too, `images.push`, and it is a profile's rather than the file's, since building the
images must not publish them: `jenesis.plugins.arguments-hub.properties` holds `images.push=hub`, and
`-Djenesis.make.profiles=hub` brings it. The version published is the one value set per invocation, and it comes
from `PUSH_VERSION` beside the registry's credentials.

Another repository that builds on this one - with this one as a Git submodule - names `plugins/images` here in its
own `jenesis.plugins.properties` and gives it its own values, binding this deploy tree too where it packages a chart
from the chart source here. A staged context the configuration does not name is an error rather than a skip;
`images.image.<module>=` says a context is not an image that build makes, and `images.publish.<repository>=false`
builds an image without ever pushing it.
