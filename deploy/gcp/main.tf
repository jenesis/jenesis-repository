# GCP deployment for the all-in-one Jenesis repository, backed by Google Cloud Storage (the cloud's
# native object store). Provisions a GCS bucket and a Cloud Run service running the universal image
# with JENREG_STORE=gcs pointed at the bucket. The service runs as a service account that holds
# objectAdmin on the bucket, and that is the whole credential: the gcs backend speaks the JSON API
# through Application Default Credentials, so on Cloud Run the metadata server hands it a token and
# no key is minted, stored or rotated. With nothing further set every non-credentialed capability
# the image carries is on - trim it with JENREG_* env entries (any jenreg.* key via Spring relaxed
# binding) instead of changing images.
#
# NOTE: authored from the documented resource shapes; NOT deploy-validated here (no GCP project in
# this environment). Review before publishing.

terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# --- Storage: the bucket, and the runtime service account's right to it ---

resource "google_storage_bucket" "repository" {
  name                        = var.bucket_name
  location                    = var.region
  uniform_bucket_level_access = true
}

# The one identity of the deployment: Cloud Run runs as it, and it may read and write the bucket. The
# gcs backend finds the credential through the metadata server, so nothing else is provisioned for it.
resource "google_service_account" "run" {
  account_id   = "${var.name}-run"
  display_name = "jenesis-repository Cloud Run runtime"
}

resource "google_storage_bucket_iam_member" "run" {
  bucket = google_storage_bucket.repository.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.run.email}"
}

# The licence, in Secret Manager, created only when one was supplied - a free deployment provisions no secret
# it will never read.
resource "google_secret_manager_secret" "license" {
  count     = var.license_key == "" ? 0 : 1
  secret_id = "${var.name}-license-key"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "license" {
  count       = var.license_key == "" ? 0 : 1
  secret      = google_secret_manager_secret.license[0].id
  secret_data = var.license_key
}

# The store selection and its settings: the native backend, the bucket, and nothing else - the
# credential is the service account the service runs as.
locals {
  store_env = {
    JENREG_STORE      = "gcs"
    JENREG_GCS_BUCKET = google_storage_bucket.repository.name
  }
}

# --- Runtime: Cloud Run service ---

# The service account can read the licence too - without this the container starts and the secret is
# unreadable, which Cloud Run reports as a startup failure rather than as "unlicensed".
resource "google_secret_manager_secret_iam_member" "license" {
  count     = var.license_key == "" ? 0 : 1
  secret_id = google_secret_manager_secret.license[0].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.run.email}"
}

resource "google_cloud_run_v2_service" "repository" {
  name     = var.name
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.run.email
    containers {
      image = var.image
      ports {
        container_port = 8080
      }
      # Cloud Run injects PORT, so it is not set here. Add further env entries to trim features or
      # pin settings deployment-wide.
      dynamic "env" {
        for_each = local.store_env
        content {
          name  = env.key
          value = env.value
        }
      }
      # The licence, when there is one. Absent it, the container simply starts unlicensed - which warns
      # and gates nothing - so this block is conditional rather than the deployment being.
      dynamic "env" {
        for_each = toset(var.license_key == "" ? [] : ["JENREG_LICENSE_KEY"])
        content {
          name = env.value
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.license[0].secret_id
              version = "latest"
            }
          }
        }
      }
    }
  }

  depends_on = [google_storage_bucket_iam_member.run, google_secret_manager_secret_iam_member.license]
}

# Public exposure is OPT-IN. allow_unauthenticated defaults to false, so a stock apply leaves the
# service private (IAM-invoker-only). Set allow_unauthenticated = true to grant run.invoker to
# allUsers and expose it on the public internet (access then rests on the repository's own auth).
resource "google_cloud_run_v2_service_iam_member" "invoker" {
  count    = var.allow_unauthenticated ? 1 : 0
  name     = google_cloud_run_v2_service.repository.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}

output "url" {
  description = "Repository server URL (check <url>/actuator/health; the enterprise console is at <url>/console)."
  value       = google_cloud_run_v2_service.repository.uri
}
