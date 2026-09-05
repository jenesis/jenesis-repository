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

variable "license_key" {
  type        = string
  default     = ""
  sensitive   = true
  description = <<-EOT
    The enterprise licence key (JENREG_LICENSE_KEY). Leave empty for the free edition, and for an
    enterprise deployment that has not been licensed yet: an unlicensed enterprise image warns on every
    start and degrades nothing - no capability is gated and it does not refuse to serve - so this is
    never the reason a deployment will not come up.

    It is stored in Secret Manager rather than passed as a plain environment value, because it is a
    credential. Where the key comes from depends on how the deployment was bought: a marketplace purchase
    assigns one, and a direct purchase is handed one.
  EOT
}
