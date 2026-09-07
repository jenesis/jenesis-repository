variable "project_id" {
  type        = string
  description = "Target Scaleway project ID."
}

variable "region" {
  type        = string
  default     = "fr-par"
  description = "Region for the container and the bucket (fr-par, nl-ams, pl-waw)."
}

variable "name" {
  type        = string
  default     = "jenesis"
  description = "Base name for the provisioned resources."
}

variable "bucket_name" {
  type        = string
  description = "Bucket name for the artifact store; unique within the region."
}

variable "image" {
  type        = string
  description = <<-EOT
    The all-in-one repository image (the free edition differs only by tag), e.g.
    rg.fr-par.scw.cloud/NAMESPACE/jenesis-repository:enterprise. A public image on Docker Hub works too
    (jenesis/jenesis-repository:free), but Scaleway advises against it: Docker Hub's rate limits can fail a
    container start, so push the image to the Scaleway Container Registry for a deployment that matters.
  EOT
}

variable "cpu_millicores" {
  type        = number
  default     = 1000
  description = "CPU limit of the container, in millicores."
}

variable "memory_bytes" {
  type        = number
  default     = 2147483648
  description = <<-EOT
    Memory limit of the container, in bytes. The image sets no -Xmx, so about a quarter of this becomes heap;
    two gibibytes is the smallest size a repository that serves wide listings should get.
  EOT
}

variable "max_scale" {
  type        = number
  default     = 1
  description = "Upper bound on instances; more than one over the same bucket is the product's multi-node shape."
}

variable "request_timeout_seconds" {
  type        = number
  default     = 600
  description = "How long one request may take; a large artifact download over a slow link needs minutes, not the default."
}

variable "allow_unauthenticated" {
  type        = bool
  default     = false
  description = <<-EOT
    Whether the container is public. Secure default is false: a stock apply keeps it PRIVATE (callers present a
    Scaleway IAM token), matching the server's auth-on default (jenreg.auth is enforced unless explicitly set to
    false). To expose the repository on the public internet - relying on the repository's own per-credential key
    auth for access control - set allow_unauthenticated = true.
  EOT
}

variable "license_key" {
  type        = string
  default     = ""
  sensitive   = true
  description = <<-EOT
    The enterprise licence key (JENREG_LICENSE_KEY). Leave empty for the free edition, and for an enterprise
    deployment that has not been licensed yet: an unlicensed enterprise image warns on every start and degrades
    nothing - no capability is gated and it does not refuse to serve - so this is never the reason a deployment will
    not come up.

    It reaches the container as a secret environment variable rather than a plain one, because it is a credential.
    Where the key comes from depends on how the deployment was bought: a marketplace purchase assigns one, and a
    direct purchase is handed one.
  EOT
}
