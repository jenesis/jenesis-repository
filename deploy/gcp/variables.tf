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
  default     = "docker.io/jenesisbuild/jenesis-repository:latest"
  description = <<-EOT
    The repository image. The default is the published one on Docker Hub, which Cloud Run pulls directly; pin a
    release (docker.io/jenesisbuild/jenesis-repository:1.2.3) for a deployment that should not move on its own,
    or name a copy in Artifact Registry (REGION-docker.pkg.dev/PROJECT/REPO/jenesis-repository:1.2.3).
  EOT
}

variable "cpu" {
  type        = string
  default     = "1"
  description = "CPU limit of the container."
}

variable "memory" {
  type        = string
  default     = "2Gi"
  description = <<-EOT
    Memory limit of the container. The image sets no -Xmx, so about a quarter of this becomes heap; two gibibytes
    is the smallest size a repository that serves wide listings should get.
  EOT
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
    Settings that are credentials, each stored in Secret Manager and handed to the container as the environment
    variable it is keyed by. The first two a deployment wants, since authentication is on by default:
    JENREG_BOOTSTRAP_KEY, the API key the server provisions at boot, and JENREG_UI_ADMIN_KEY, the key that signs
    into the console's first-run setup. A vendor feed's token goes here too; supplying it is what switches the
    feed on.
  EOT
}
