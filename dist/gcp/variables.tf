variable "project_id" {
  type        = string
  description = "Target GCP project."
}

variable "region" {
  type        = string
  default     = "us-central1"
  description = "Region for Cloud Run and the bucket."
}

variable "name" {
  type        = string
  default     = "jenesis"
  description = "Base name for the provisioned resources."
}

variable "bucket_name" {
  type        = string
  description = "Globally-unique GCS bucket name for the artifact store."
}

variable "image" {
  type        = string
  description = "The all-in-one repository image (the free edition differs only by tag), e.g. REGION-docker.pkg.dev/PROJECT/REPO/jenesis-repository:enterprise"
}

variable "store" {
  type        = string
  default     = "gcs"
  description = <<-EOT
    The exclusive store-backend selection (JENREG_STORE). Defaults to "gcs", the native
    Google Cloud Storage backend. Set "s3" to drive the same bucket through GCS's S3-compatible XML
    API instead: the two are interchangeable against GCS, and which one a deployment prefers is its
    own call. Every image carries all four backends and selects one at runtime, so this is a
    configuration choice rather than an image choice.
  EOT
  validation {
    condition     = contains(["gcs", "s3"], var.store)
    error_message = "store must be \"gcs\" or \"s3\"."
  }
}

variable "allow_unauthenticated" {
  type        = bool
  default     = false
  description = <<-EOT
    Whether to grant run.invoker to allUsers, making the Cloud Run service reachable by anonymous
    callers at the network layer. Secure default is false: a stock apply keeps the service PRIVATE
    (only IAM-authorized principals can invoke it), matching the server's auth-on default
    (jenreg.auth is enforced unless explicitly set to false). To expose the service on the
    public internet - relying on the repository's own per-credential key auth for access control - set
    allow_unauthenticated = true.
  EOT
}
