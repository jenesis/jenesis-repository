GCP (Google Cloud Marketplace) packaging
========================================

Deploys the **all-in-one repository image** (`jenesis-repository:free` or `:enterprise` - one template,
the tag selects the edition; built by
`script/build-images.sh` in the enterprise repository, which builds and tags both editions; the free edition is the same image shape from the
free repo, only the tag differs) on **Cloud Run**, defaulting the exclusive store selection to GCP's
native object store: `JENREG_STORE=gcs` against a bucket the Terraform provisions. The
`gcs` backend speaks GCS's XML API, which authenticates with an **HMAC key pair** — Terraform issues
one for a dedicated service account and stores the secret in Secret Manager, so no key is hand-set.
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

A GCS bucket; a service account with `storage.objectAdmin` on it and an **HMAC key** (secret stored
in Secret Manager); and a Cloud Run service running the image with `JENREG_STORE=gcs`,
`JENREG_GCS_BUCKET`, and the HMAC pair (`JENREG_GCS_ACCESS_KEY_ID` + the Secret-Manager-backed
`JENREG_GCS_SECRET_ACCESS_KEY`).

Store fallback: `-var store=s3` drives the **same bucket** through GCS's S3-compatible endpoint
(`JENREG_S3_*` keys, same HMAC pair) — the configuration shape is identical, only the selection
value and key prefix change. It exists because the two are interchangeable against GCS, not because
either image lacks a backend: every image carries all four and selects one at runtime.
carrying the `gcs` backend aboard (the free image already carries it).

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
