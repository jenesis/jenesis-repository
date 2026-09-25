Deploying Jenesis Repository
============================

Everything here deploys the published image, `docker.io/jenesisbuild/jenesis-repository`, and nothing needs
building first. Each template takes the image as a parameter defaulting to `:latest`; pin a release for a
deployment that should not move on its own.

    helm/jenesis/         the Helm chart, published as oci://registry-1.docker.io/jenesisbuild/jenesis
    gcp/                  Terraform: Cloud Run over a Cloud Storage bucket (the gcs backend, keyless under ADC)
    scaleway/             Terraform: a Serverless Container over Object Storage (the s3 backend)
    aws/                  CloudFormation: ECS Fargate behind an ALB over an S3 bucket (the task role is the credential)
    azure/                Bicep: a Container App over Blob Storage (the azure-blob backend)

**Every template provisions its store and selects it**, so a deployment's durable state lives in that cloud's
own object store from the first start, and there is no database to provision beside it. Several instances over
the one store is the product's multi-node shape; the templates start one.

**Authentication is on by default**, so a deployment needs a credential to begin with. Each template takes two as
secrets: `JENREG_BOOTSTRAP_KEY`, the API key the server provisions at boot (a `jenk_<tenant>.<secret><checksum>`
key; `java -Djenesis.execute.module=source+server-spi build/jenesis/Execute.java` mints one), and
`JENREG_UI_ADMIN_KEY`, which signs into the console's first-run setup at `/ui/`. Issue real credentials from there
and unset both - they are re-provisioned on every boot for as long as they are set.

**Any other setting is an environment variable**: a `jenreg.*` key under its `JENREG_*` name, by Spring's relaxed
binding. The Terraform and Bicep templates take a map of plain settings and a map of secret ones; the
CloudFormation template, which cannot iterate, takes an environment file in S3. The generated settings reference
on the documentation site lists every key.

**Every service starts private.** Cloud Run and Scaleway require the cloud's own IAM token until
`allow_unauthenticated` is set, and the Container App's ingress is internal until `ingressExternal` is; the ALB is
the exception, and answers on plain HTTP on port 80 until a listener with a certificate is added. Access past the
network rests on the repository's own key authentication.

The templates are checked - `terraform validate`, `az bicep build`, `cfn-lint` - but none has been deployed to a
real account by this repository, so read one before relying on it.
