# Jenesis Repository on Scaleway, backed by Scaleway Object Storage through the
# s3 backend. Provisions a bucket, an IAM application whose API key is the bucket's only credential, and a
# Serverless Container running the image with JENREG_STORE=s3 pointed at the bucket. Scaleway
# documents both write preconditions the s3 backend's compare-and-set rests on (If-None-Match on a create,
# If-Match on a replace), and the backend confirms them at every boot with a four-write probe before it
# serves - a store that ignored either would refuse to start rather than lose writes silently. Every further
# setting is an entry of `environment` (plain) or `secrets` (secret environment), under its JENREG_* name.
#
# NOTE: authored from the provider's documented resource shapes and checked with `terraform validate`; NOT
# deploy-validated (no Scaleway project was at hand). Review before relying on it.

terraform {
  required_providers {
    scaleway = {
      source  = "scaleway/scaleway"
      version = ">= 2.40"
    }
  }
}

provider "scaleway" {
  project_id = var.project_id
  region     = var.region
}

# --- Storage: the bucket, and the one identity that may read and write it ---

resource "scaleway_object_bucket" "repository" {
  name   = var.bucket_name
  region = var.region
}

# The deployment's identity: an IAM application holding Object Storage on the project, and one API key on it. The
# key's access key and secret are the S3 credential the container boots with; nothing else is provisioned for it.
resource "scaleway_iam_application" "repository" {
  name        = "${var.name}-repository"
  description = "Jenesis Repository runtime: reads and writes the artifact bucket"
}

resource "scaleway_iam_policy" "storage" {
  name           = "${var.name}-storage"
  application_id = scaleway_iam_application.repository.id
  rule {
    project_ids          = [var.project_id]
    permission_set_names = ["ObjectStorageFullAccess"]
  }
}

resource "scaleway_iam_api_key" "repository" {
  application_id     = scaleway_iam_application.repository.id
  default_project_id = var.project_id
  description        = "Jenesis Repository S3 credential for ${scaleway_object_bucket.repository.name}"
}

# The store selection and its settings: the s3 backend against the regional endpoint and the bucket, with the
# credential as secret environment. The key names are the jenreg.* settings under Spring's relaxed binding
# (jenreg.s3.access-key-id is JENREG_S3_ACCESSKEYID). Both are the template's, so they are applied over whatever
# `environment` and `secrets` say: the bucket this template provisions is the one the container must use.
locals {
  plain_env = merge(var.environment, {
    JENREG_STORE       = "s3"
    JENREG_S3_BUCKET   = scaleway_object_bucket.repository.name
    JENREG_S3_ENDPOINT = "https://s3.${var.region}.scw.cloud"
    JENREG_S3_REGION   = var.region
  })
  secret_env = merge(var.secrets, {
    JENREG_S3_ACCESSKEYID     = scaleway_iam_api_key.repository.access_key
    JENREG_S3_SECRETACCESSKEY = scaleway_iam_api_key.repository.secret_key
  })
}

# --- Runtime: Serverless Container ---

resource "scaleway_container_namespace" "repository" {
  name        = var.name
  description = "Jenesis Repository"
}

resource "scaleway_container" "repository" {
  name         = var.name
  namespace_id = scaleway_container_namespace.repository.id
  image        = var.image
  port         = 8080
  protocol     = "http1"

  cpu_limit          = var.cpu_millicores
  memory_limit_bytes = var.memory_bytes
  # One instance is always up: the repository keeps in-process caches and holds its maintenance leases, and a cold
  # start on the first request of the day is not what a build expects. More than one over the same bucket is the
  # multi-node shape the product supports; raise max_scale for it.
  min_scale = 1
  max_scale = var.max_scale
  timeout   = var.request_timeout_seconds

  # Public exposure is OPT-IN. allow_unauthenticated defaults to false, so a stock apply leaves the container
  # PRIVATE (callers present a Scaleway IAM token at the network layer). Set allow_unauthenticated = true to expose
  # it on the public internet, where access then rests on the repository's own per-credential key auth.
  privacy                = var.allow_unauthenticated ? "public" : "private"
  https_connections_only = true

  environment_variables        = local.plain_env
  secret_environment_variables = local.secret_env

  depends_on = [scaleway_iam_policy.storage]
}

output "url" {
  description = "Repository server URL (check <url>/actuator/health; the console is at <url>/ui/)."
  value       = scaleway_container.repository.public_endpoint
}
