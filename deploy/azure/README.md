Azure
=====

Deploys Jenesis Repository on **Azure Container Apps** over **Blob Storage**: `JENREG_STORE=azure-blob` against a
Storage Account the template provisions. The `azure-blob` backend authenticates by connection string, so the
template composes one from the account it creates and hands it over as a container-app secret - no key is set by
hand.

    az group create -n jenesis -l westeurope
    az deployment group create -g jenesis --template-file main.bicep \
      --parameters secrets='{"JENREG_BOOTSTRAP_KEY":"jenk_...","JENREG_UI_ADMIN_KEY":"..."}' ingressExternal=true
    # the output `endpoint` is the repository; check <endpoint>/actuator/health, and open <endpoint> for the console

| Parameter | Default | |
|---|---|---|
| `image` | `docker.io/jenesisbuild/jenesis-repository:latest` | Container Apps pulls from Docker Hub directly |
| `environment` | `{}` | further settings, `JENREG_*` names |
| `secrets` | `{}` | settings that are credentials, one container-app secret each |
| `ingressExternal` | `false` | internal ingress reaches only the Container Apps environment; `true` publishes it |
| `cpu`, `memory` | `0.5`, `2Gi` | the image sets no `-Xmx`, so about a quarter of the memory becomes heap |
| `minReplicas`, `maxReplicas` | `1`, `3` | several replicas over the one store is the multi-node shape |
