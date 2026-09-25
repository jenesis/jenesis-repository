Google Cloud
============

Deploys Jenesis Repository on **Cloud Run** over a **Cloud Storage** bucket the Terraform provisions:
`JENREG_STORE=gcs`. The service runs as a service account holding `storage.objectAdmin` on the bucket, and that
is the whole store credential - the `gcs` backend speaks the JSON API through Application Default Credentials, so
the metadata server hands it a token and no key is minted, stored or rotated.

There is deliberately no `s3` option through Cloud Storage's S3-compatible XML API: that surface evaluates
`If-Match` on reads only, so the `s3` backend's compare-and-set would succeed unconditionally against it and two
instances would lose updates silently. The `gcs` backend's precondition is Cloud Storage's own.

    terraform init
    terraform apply -var project_id=PROJECT -var bucket_name=GLOBALLY-UNIQUE-NAME \
      -var 'secrets={JENREG_BOOTSTRAP_KEY="jenk_...", JENREG_UI_ADMIN_KEY="..."}'
    # the `url` output is the repository; check <url>/actuator/health, and open <url> for the console

| Variable | Default | |
|---|---|---|
| `image` | `docker.io/jenesisbuild/jenesis-repository:latest` | Cloud Run pulls from Docker Hub directly |
| `environment` | `{}` | further settings, `JENREG_*` names |
| `secrets` | `{}` | settings that are credentials, one Secret Manager secret each |
| `allow_unauthenticated` | `false` | grant `run.invoker` to `allUsers`; access then rests on the repository's own auth |
| `cpu`, `memory` | `1`, `2Gi` | the image sets no `-Xmx`, so about a quarter of the memory becomes heap |
