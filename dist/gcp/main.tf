# GCP deployment for the all-in-one Jenesis repository, backed by Google Cloud Storage (the cloud's
# native object store). Provisions a GCS bucket, a service account whose HMAC key grants bucket
# access (the gcs backend speaks GCS's XML API, which authenticates with an HMAC pair - Terraform
# issues it, so no key is hand-set), the HMAC secret in Secret Manager, and a Cloud Run service
# running the universal image with JENREG_STORE=gcs pointed at the bucket. With nothing
# further set every non-credentialed capability the image carries is on - trim it with
# JENREG_* env entries (any jenreg.* key via Spring relaxed binding) instead
# of changing images.
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

# --- Storage: bucket + a service account whose HMAC key the store uses ---

resource "google_storage_bucket" "repository" {
  name                        = var.bucket_name
  location                    = var.region
  uniform_bucket_level_access = true
}

resource "google_service_account" "storage" {
  account_id   = "${var.name}-storage"
  display_name = "jenesis-repository storage access"
}

resource "google_storage_bucket_iam_member" "storage" {
  bucket = google_storage_bucket.repository.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.storage.email}"
}

resource "google_storage_hmac_key" "storage" {
  service_account_email = google_service_account.storage.email
}

resource "google_secret_manager_secret" "hmac" {
  secret_id = "${var.name}-hmac-secret"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "hmac" {
  secret      = google_secret_manager_secret.hmac.id
  secret_data = google_storage_hmac_key.storage.secret
}

# The store selection and its settings. The default "gcs" backend reads the JENREG_GCS_* keys; the
# "s3" fallback drives the same bucket through GCS's S3-compatible endpoint with the same HMAC pair.
locals {
  store_env = var.store == "gcs" ? {
    JENREG_STORE  = "gcs"
    JENREG_GCS_BUCKET        = google_storage_bucket.repository.name
    JENREG_GCS_ACCESS_KEY_ID = google_storage_hmac_key.storage.access_id
    } : {
    JENREG_STORE  = "s3"
    JENREG_S3_ENDPOINT      = "https://storage.googleapis.com"
    JENREG_S3_BUCKET        = google_storage_bucket.repository.name
    JENREG_S3_REGION        = var.region
    JENREG_S3_ACCESS_KEY_ID = google_storage_hmac_key.storage.access_id
  }
  store_secret_key = var.store == "gcs" ? "JENREG_GCS_SECRET_ACCESS_KEY" : "JENREG_S3_SECRET_ACCESS_KEY"
}

# --- Runtime: Cloud Run service ---

resource "google_service_account" "run" {
  account_id   = "${var.name}-run"
  display_name = "jenesis-repository Cloud Run runtime"
}

resource "google_secret_manager_secret_iam_member" "run" {
  secret_id = google_secret_manager_secret.hmac.id
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
      env {
        name = local.store_secret_key
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.hmac.secret_id
            version = "latest"
          }
        }
      }
    }
  }

  depends_on = [google_secret_manager_secret_iam_member.run]
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
