# Jenesis Repository on Cloud Run, backed by Google Cloud Storage (the cloud's native object store).
# Provisions a GCS bucket and a Cloud Run service running the image with JENREG_STORE=gcs pointed at
# the bucket. The service runs as a service account that holds objectAdmin on the bucket, and that is
# the whole store credential: the gcs backend speaks the JSON API through Application Default
# Credentials, so on Cloud Run the metadata server hands it a token and no key is minted, stored or
# rotated. Every further setting is an entry of `environment` (plain) or `secrets` (Secret Manager),
# under the setting's JENREG_* name.
#
# NOTE: authored from the documented resource shapes and checked with `terraform validate`; NOT
# deploy-validated (no GCP project was at hand). Review before relying on it.

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
  display_name = "Jenesis Repository Cloud Run runtime"
}

resource "google_storage_bucket_iam_member" "run" {
  bucket = google_storage_bucket.repository.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.run.email}"
}

# --- Secrets: one Secret Manager secret per entry of var.secrets ---

# The names of the secret settings are not themselves secret, and a for_each needs them in the clear; the
# values stay sensitive throughout.
locals {
  secret_names = nonsensitive(toset(keys(var.secrets)))
}

resource "google_secret_manager_secret" "setting" {
  for_each  = local.secret_names
  secret_id = "${var.name}-${lower(replace(each.key, "_", "-"))}"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "setting" {
  for_each    = local.secret_names
  secret      = google_secret_manager_secret.setting[each.key].id
  secret_data = var.secrets[each.key]
}

# Without this the container starts and the secret is unreadable, which Cloud Run reports as a startup
# failure rather than as a missing setting.
resource "google_secret_manager_secret_iam_member" "setting" {
  for_each  = local.secret_names
  secret_id = google_secret_manager_secret.setting[each.key].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.run.email}"
}

# --- Runtime: Cloud Run service ---

# The store selection is the template's, so it is applied over whatever `environment` says: the bucket
# this template provisions is the one the service must use.
locals {
  plain_env = merge(var.environment, {
    JENREG_STORE      = "gcs"
    JENREG_GCS_BUCKET = google_storage_bucket.repository.name
  })
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
      resources {
        limits = {
          cpu    = var.cpu
          memory = var.memory
        }
      }
      # The image listens on 8080, the container_port above. Cloud Run injects PORT with that same value, which
      # the server deliberately does not read: a property is set by its own name, and Spring reads SERVER_PORT
      # for server.port. Changing container_port therefore means setting SERVER_PORT to match.
      dynamic "env" {
        for_each = local.plain_env
        content {
          name  = env.key
          value = env.value
        }
      }
      dynamic "env" {
        for_each = local.secret_names
        content {
          name = env.value
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.setting[env.value].secret_id
              version = "latest"
            }
          }
        }
      }
    }
  }

  depends_on = [
    google_storage_bucket_iam_member.run,
    google_secret_manager_secret_iam_member.setting,
    google_secret_manager_secret_version.setting,
  ]
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
  description = "Repository server URL (check <url>/actuator/health; the console is at <url>/ui/)."
  value       = google_cloud_run_v2_service.repository.uri
}
