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
  default     = "docker.io/jenesisbuild/jenesis-repository:latest"
  description = <<-EOT
    The repository image. The default is the published one on Docker Hub; pin a release
    (docker.io/jenesisbuild/jenesis-repository:1.2.3) for a deployment that should not move on its own. Scaleway
    advises against pulling from Docker Hub for anything that matters - its rate limits can fail a container
    start - so copy the image to the Scaleway Container Registry and name it here
    (rg.fr-par.scw.cloud/NAMESPACE/jenesis-repository:1.2.3).
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

variable "environment" {
  type        = map(string)
  default     = {}
  description = <<-EOT
    Further settings, as plain environment variables: any jenreg.* key under its JENREG_* name (Spring's relaxed
    binding), e.g. { JENREG_MAVEN = "false" } to drop a format. The store selection is the template's and wins
    over an entry here.
  EOT
}

variable "secrets" {
  type        = map(string)
  default     = {}
  sensitive   = true
  description = <<-EOT
    Settings that are credentials, handed to the container as secret environment variables under the names they
    are keyed by. The first two a deployment wants, since authentication is on by default: JENREG_BOOTSTRAP_KEY,
    the API key the server provisions at boot, and JENREG_UI_ADMIN_KEY, the key that signs into the console's
    first-run setup. A vendor feed's token goes here too; supplying it is what switches the feed on.
  EOT
}
