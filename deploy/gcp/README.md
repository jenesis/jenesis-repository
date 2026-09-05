GCP (Google Cloud Marketplace) packaging
========================================

Deploys the **all-in-one repository image** (`jenesis-repository:free` or `:enterprise` - one template,
the tag selects the edition; built by
`java build/Build.java images` in the enterprise repository, which builds and tags both editions; the free edition is the same image shape from the
free repo, only the tag differs) on **Cloud Run**, defaulting the exclusive store selection to GCP's
native object store: `JENREG_STORE=gcs` against a bucket the Terraform provisions. The `gcs` backend
speaks GCS's JSON API through **Application Default Credentials**: the Cloud Run service runs as a
service account that holds `objectAdmin` on the bucket, the metadata server hands the backend its
token, and no key is minted, stored or rotated.
With nothing further set, every non-credentialed capability the image carries is on and the
credential-switched ones (licensed feeds, the AI gateway) self-disable with a one-line log.

The image carries every SPI implementation and is trimmed at runtime instead of rebuilt: add env
entries to the Cloud Run container (any `jenreg.*` key as `JENREG_*` via
Spring relaxed binding) to toggle a feature off, pick an exclusive implementation, or pin a setting
deployment-wide. See the configuration reference on the docs site for the full key catalogue.

> The Terraform is authored from the documented resource shapes but was **not deploy-validated**
> here (no GCP project in this environment). Review before publishing.

What it provisions (`main.tf`)
------------------------------

A GCS bucket; a runtime service account with `storage.objectAdmin` on it; and a Cloud Run service
running the image as that account with `JENREG_STORE=gcs` and `JENREG_GCS_BUCKET`. The licence, when
one is supplied, lives in Secret Manager and reaches the container as `JENREG_LICENSE_KEY`.

There is deliberately no `s3` fallback through GCS's S3-compatible XML API: that surface evaluates
`If-Match` on reads only, so the `s3` backend's compare-and-set would succeed unconditionally against it
and two nodes would lose updates silently. The `gcs` backend's precondition is GCS's own.

1) Build and push the image to Artifact Registry
-------------------------------------------------

    # from the repo root; builds both editions' images
    ./build-images.sh
    gcloud auth configure-docker REGION-docker.pkg.dev
    docker tag jenesis-repository:enterprise REGION-docker.pkg.dev/PROJECT/REPO/jenesis-repository:enterprise
    docker push REGION-docker.pkg.dev/PROJECT/REPO/jenesis-repository:enterprise

(The free edition is the same image, built by the same script and tagged `:free` — jenesis-repository:free .` there — and deploys through this same Terraform; only the tag differs.)

2) Deploy / test
----------------

    terraform init
    terraform apply \
      -var project_id=PROJECT -var bucket_name=GLOBALLY-UNIQUE-NAME \
      -var image=REGION-docker.pkg.dev/PROJECT/REPO/jenesis-repository:enterprise
    # the `url` output is the repository endpoint; check <url>/actuator/health
    # (the enterprise console is at <url>/console)

3) Google Cloud Marketplace
---------------------------

In **Producer Portal**, create a product with a **Terraform-based** deployment (upload this module)
or a Cloud Run / GKE delivery. Pricing rides on the customer's Google Cloud bill and draws down their
committed use. **Requires your accounts:** a Google Cloud Marketplace producer/seller account and an
Artifact Registry repo for the image.

Licence
-------

The enterprise edition reads its licence from `JENREG_LICENSE_KEY`. This template takes it as an input and
passes it through; the free edition needs none.

**An unlicensed enterprise deployment still runs.** It warns on every start and gates nothing - no capability
is switched off and it does not refuse to serve - so a missing or expired key is never why a deployment fails
to come up. That is deliberate: enforcement is commercial rather than technical.

Where the key comes from depends on how the deployment was bought: a marketplace purchase assigns one, and a
direct purchase is handed one.
