Scaleway packaging
==================

Deploys the **all-in-one repository image** (`jenesis-repository:free` or `:enterprise` - one template, the tag
selects the edition) as a **Serverless Container**, defaulting the exclusive store selection to Scaleway Object Storage
through the `s3` backend: `JENREG_STORE=s3` against a bucket the Terraform provisions, reached at the regional
endpoint `https://s3.<region>.scw.cloud`. The credential is one IAM API key on an application that holds
`ObjectStorageFullAccess` on the project; the key's access key and secret reach the container as secret
environment variables and nothing else is minted or stored.

Why Scaleway among the European clouds: it documents both write preconditions the `s3` backend's compare-and-set
rests on - `If-None-Match` on a create and `If-Match` on a replace - on `PutObject` and `CompleteMultipartUpload`.
The backend does not take that on trust: at every boot it writes one probe key four ways (create, create again,
replace under the reported token, replace under the now-stale token) and refuses to start if the endpoint accepts
what it had to refuse, so a store that ignored a precondition would fail loudly at the first start rather than lose
two nodes' writes silently. OVHcloud's object storage ignores both headers and Exoscale's takes no ETag on
`If-Match`; neither passes that probe, which is why there is no template for them.

With nothing further set, every non-credentialed capability the image carries is on and the credential-switched ones
(licensed feeds, the AI gateway) self-disable with a one-line log. The image carries every SPI implementation and is
trimmed at runtime instead of rebuilt: add `JENREG_*` entries to `environment_variables` (any `jenreg.*` key via
Spring relaxed binding) to toggle a feature off, pick an exclusive implementation, or pin a setting deployment-wide.

> The Terraform is authored from the provider's documented resource shapes and passes `terraform validate`, but was
> **not deploy-validated** here (no Scaleway project in this environment). Review before publishing.

What it provisions (`main.tf`)
------------------------------

An Object Storage bucket; an IAM application with `ObjectStorageFullAccess` on the project and one API key on it;
a Containers namespace; and a Serverless Container running the image with `JENREG_STORE=s3`, the bucket, the
regional endpoint and region as plain environment, and the key pair - plus the licence, when one is supplied - as
secret environment. One instance is always up (`min_scale = 1`): the repository keeps in-process caches and holds
its maintenance leases, and a cold start on the day's first request is not what a build expects. `max_scale`
defaults to one; more than one instance over the same bucket is the product's multi-node shape.

1) Push the image to the Scaleway Container Registry
-----------------------------------------------------

    # from the repo root: stage the all-in-one module's Docker context, then build the image over it
    java -Djenesis.test.skip=true build/jenesis/Project.java stage
    docker build -t jenesis-repository:free 'target/stage/docker/output/module-source%2Fbundle'
    docker login rg.fr-par.scw.cloud/NAMESPACE -u nologin --password-stdin <<< "$SCW_SECRET_KEY"
    docker tag jenesis-repository:free rg.fr-par.scw.cloud/NAMESPACE/jenesis-repository:free
    docker push rg.fr-par.scw.cloud/NAMESPACE/jenesis-repository:free

An image on Docker Hub deploys too, but Scaleway advises against it: Docker Hub's rate limits can fail a
container start. The enterprise repository's `images` goal builds both editions this way and tags the other one
`:enterprise`; it is the same image shape and deploys through this same Terraform - only the tag differs.

2) Deploy / test
----------------

    export SCW_ACCESS_KEY=... SCW_SECRET_KEY=... SCW_DEFAULT_PROJECT_ID=...
    terraform init
    terraform apply \
      -var project_id=PROJECT -var bucket_name=UNIQUE-IN-REGION \
      -var image=rg.fr-par.scw.cloud/NAMESPACE/jenesis-repository:free
    # the `url` output is the repository endpoint; check <url>/actuator/health
    # (the console is at <url>/console)

The container is private by default (callers present a Scaleway IAM token); `-var allow_unauthenticated=true`
exposes it, and access then rests on the repository's own key auth, which is on by default.

3) Scaleway Marketplace
-----------------------

Scaleway's marketplace lists partner products with pay-per-use, fixed-price or subscription pricing on the
customer's Scaleway bill. The listing points customers at this module and the registry image. **Requires your
accounts:** a Scaleway Marketplace partner account and a Container Registry namespace for the image.

Licence
-------

The enterprise edition reads its licence from `JENREG_LICENSE_KEY`. This template takes it as an input and passes it
through as a secret environment variable; the free edition needs none.

**An unlicensed enterprise deployment still runs.** It warns on every start and gates nothing - no capability is
switched off and it does not refuse to serve - so a missing or expired key is never why a deployment fails to come
up. That is deliberate: enforcement is commercial rather than technical.

Where the key comes from depends on how the deployment was bought: a marketplace purchase assigns one, and a direct
purchase is handed one.
