Scaleway
========

Deploys Jenesis Repository as a **Serverless Container** over **Object Storage** through the `s3` backend:
`JENREG_STORE=s3` against a bucket the Terraform provisions, at the regional endpoint `https://s3.<region>.scw.cloud`.
The store credential is one IAM API key on an application holding `ObjectStorageFullAccess` on the project; its
access key and secret reach the container as secret environment and nothing else is minted or stored.

Why Scaleway among the European clouds: it documents both write preconditions the `s3` backend's compare-and-set
rests on - `If-None-Match` on a create and `If-Match` on a replace. The backend does not take that on trust: at every
boot it writes one probe key four ways and refuses to start if the endpoint accepts what it had to refuse, so a
store that ignored a precondition fails at the first start rather than losing two instances' writes silently.
OVHcloud's object storage ignores both headers and Exoscale's takes no ETag on `If-Match`; neither passes the probe.

    export SCW_ACCESS_KEY=... SCW_SECRET_KEY=... SCW_DEFAULT_PROJECT_ID=...
    terraform init
    terraform apply -var project_id=PROJECT -var bucket_name=UNIQUE-IN-REGION \
      -var 'secrets={JENREG_BOOTSTRAP_KEY="jenk_...", JENREG_UI_ADMIN_KEY="..."}'
    # the `url` output is the repository; check <url>/actuator/health, the console is at <url>/ui/

The default image is the one on Docker Hub, and Scaleway advises against pulling from there for anything that
matters: Docker Hub's rate limits can fail a container start. Copy it into the Scaleway Container Registry and name
the copy:

    docker pull docker.io/jenesisbuild/jenesis-repository:1.2.3
    docker tag docker.io/jenesisbuild/jenesis-repository:1.2.3 rg.fr-par.scw.cloud/NAMESPACE/jenesis-repository:1.2.3
    docker push rg.fr-par.scw.cloud/NAMESPACE/jenesis-repository:1.2.3
    terraform apply ... -var image=rg.fr-par.scw.cloud/NAMESPACE/jenesis-repository:1.2.3

| Variable | Default | |
|---|---|---|
| `image` | `docker.io/jenesisbuild/jenesis-repository:latest` | see above |
| `environment` | `{}` | further settings, `JENREG_*` names |
| `secrets` | `{}` | settings that are credentials, as secret environment |
| `allow_unauthenticated` | `false` | make the container public; access then rests on the repository's own auth |
| `max_scale` | `1` | more than one instance over the bucket is the multi-node shape |
| `cpu_millicores`, `memory_bytes` | `1000`, 2 GiB | the image sets no `-Xmx`, so about a quarter of the memory becomes heap |
| `request_timeout_seconds` | `600` | a large download over a slow link needs minutes |

One instance is always up (`min_scale = 1`): the repository keeps in-process caches and holds its maintenance
leases, and a cold start on the day's first request is not what a build expects.
