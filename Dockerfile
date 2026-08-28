# Builds the all-in-one image: every free SPI implementation on one module path, trimmed by configuration
# instead of rebuilt. The launchable module is source/bundle (build.jenesis.repository.bundle), whose packaging
# bundle=true makes the build tool's Bundle step emit bundle.zip - the module's full resolved runtime closure,
# already split into descriptor-bearing jars on the module path (modulepath/) and plain jars on the class path
# (classpath/) - and since the bundle module `requires` every implementation, nothing needs to be layered on
# after the fact: the image consumes that one zip.
#
#   docker build -t jenesis-repository:free .
#
# The image boots the repository server (port 8080) through build.jenesis.repository.bundle.AllInOne; the same
# image runs the web console instead (port 8081) with
#
#   docker run -e MAINCLASS=build.jenesis.repository.bundle.Console -e PORT=8081 -p 8081:8081 \
#       -v jenesis-data:/data jenesis-repository:free
#
# Configuration is entirely environment variables: every jenreg.* key is settable through Spring's
# relaxed binding (JENREG_MAVEN=false disables the Maven layout exactly as if its module were absent,
# JENREG_STORE=s3 selects the store backend), plus the documented JENREG_STORE / JENREG_FILESYSTEM_ROOT /
# cloud-credential variables. Everything on the module path is enabled until configured off.
#
# A JDK runtime image, not jlink/distroless: the non-modular Spring closure cannot be linked but runs fine on the
# module path, and the console's OAuth2 stack (nimbus JOSE) needs jdk.crypto.ec, which JRE images trim.
ARG BASE=eclipse-temurin:25-jdk
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY .jenesis ./.jenesis
COPY build ./build
COPY source ./source
RUN java build/jenesis/Project.java +source+bundle build \
 && mkdir -p /opt/app/mp /opt/app/cp \
 && bundle="$(find target/build/modules/compose/module/package/module-source%2Fbundle -name bundle.zip | head -1)" \
 && [ -n "$bundle" ] \
 && unzip -q "$bundle" -d /opt/app/bundle \
 && cp /opt/app/bundle/modulepath/*.jar /opt/app/mp/ \
 && (cp /opt/app/bundle/classpath/*.jar /opt/app/cp/ 2>/dev/null || true) \
 && rm -rf /opt/app/bundle \
 && rm -f /opt/app/mp/*tomcat-annotations-api* \
 && echo "module-path jars: $(ls /opt/app/mp | wc -l), class-path jars: $(ls /opt/app/cp | wc -l)"

FROM ${BASE}
COPY --from=build /opt/app /opt/app
# JENREG_STORE names the backend, which is a safe default because it can no longer be a silent one: the
# filesystem backend requires JENREG_FILESYSTEM_ROOT and there is deliberately no default for it here. An image
# told nothing about where its data goes must fail to start rather than write into the container's own writable
# layer, which is what /data was before the volume is mounted - and which is gone on the next `docker rm`, with
# every published artifact in it. Whoever runs the image says where the store is: the chart sets it beside the
# PersistentVolumeClaim it mounts, and a plain `docker run` passes -e JENREG_FILESYSTEM_ROOT=/data with -v.
ENV JENREG_STORE=filesystem \
    MAINMODULE=build.jenesis.repository.bundle \
    MAINCLASS=build.jenesis.repository.bundle.AllInOne \
    PORT=8080
EXPOSE 8080 8081
VOLUME ["/data"]
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 --module-path /opt/app/mp --class-path '/opt/app/cp/*' --add-modules ALL-MODULE-PATH -m ${MAINMODULE}/${MAINCLASS}"]
