// Jenesis Repository on Azure Container Apps, backed by Azure Blob Storage (the cloud's native object store).
//
// Provisions a Storage Account + blob container (the store), a Container Apps environment, and a
// Container App running the image with JENREG_STORE=azure-blob wired to the provisioned account. The
// connection string is composed from the account the template itself creates (the azure-blob backend
// authenticates by connection string), so no key is hand-set by the operator. Every further setting is an
// entry of `environment` (plain) or `secrets` (a container-app secret), under its JENREG_* name.
//
//   az deployment group create -g <group> --template-file main.bicep --parameters secrets='{...}'
//
// NOTE: authored from the API conventions and checked with `az bicep build`; NOT deploy-validated (no Azure
// subscription was at hand). Review names, SKUs and quotas before relying on it.

@description('Base name for the provisioned resources.')
param name string = 'jenesis'

@description('Location for all resources.')
param location string = resourceGroup().location

@description('The repository image. The default is the published one on Docker Hub, which Container Apps pulls directly; pin a release (docker.io/jenesisbuild/jenesis-repository:1.2.3) for a deployment that should not move on its own.')
param image string = 'docker.io/jenesisbuild/jenesis-repository:latest'

@description('Blob container that holds the artifact store.')
param blobContainer string = 'jenesis-repository'

@description('vCPU for the repository container.')
param cpu string = '0.5'

@description('Memory for the repository container.')
param memory string = '2Gi'

@description('Minimum and maximum replicas (the app is stateless over the one blob store).')
param minReplicas int = 1
param maxReplicas int = 3

@description('Expose the repository on a PUBLIC ingress. Secure default is false: ingress is INTERNAL (reachable only from within the Container Apps environment / VNet), matching the server auth-on default. Set to true to publish it on the public internet, relying on the repository\'s own per-credential key auth for access control.')
param ingressExternal bool = false

@description('Further settings, as plain environment variables: any jenreg.* key under its JENREG_* name (Spring\'s relaxed binding), e.g. {"JENREG_MAVEN": "false"} to drop a format. The store selection is the template\'s and wins over an entry here.')
param environment object = {}

@description('Settings that are credentials, each a container-app secret handed to the container as the environment variable it is keyed by. The first two a deployment wants, since authentication is on by default: JENREG_BOOTSTRAP_KEY, the API key the server provisions at boot, and JENREG_UI_ADMIN_KEY, the key that signs into the console\'s first-run setup.')
@secure()
param secrets object = {}

// The store selection is the template's, so it replaces an entry of the same name in `environment`.
var storeEnv = {
  JENREG_STORE: 'azure-blob'
  JENREG_AZURE_BLOB_CONTAINER: blobContainer
  PORT: '8080'
}

// A container-app secret's name is lowercase letters, digits and dashes, so each setting's secret is named after
// its variable in that alphabet.
var secretSettings = [for setting in items(secrets): {
  name: 'setting-${toLower(replace(setting.key, '_', '-'))}'
  value: setting.value
}]
var secretEnv = [for setting in items(secrets): {
  name: setting.key
  secretRef: 'setting-${toLower(replace(setting.key, '_', '-'))}'
}]
var plainEnv = [for setting in items(union(environment, storeEnv)): { name: setting.key, value: setting.value }]

var storageName = toLower(take('${name}${uniqueString(resourceGroup().id)}', 24))

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageName
  location: location
  sku: { name: 'Standard_LRS' }
  kind: 'StorageV2'
  properties: {
    minimumTlsVersion: 'TLS1_2'
    allowBlobPublicAccess: false
  }
}

resource blob 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  name: '${storage.name}/default/${blobContainer}'
}

resource env 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${name}-env'
  location: location
  properties: {}
}

resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: name
  location: location
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      ingress: {
        // Internal by default (ingressExternal=false); set the parameter to true for a public endpoint.
        external: ingressExternal
        targetPort: 8080
        transport: 'auto'
      }
      secrets: concat([
        {
          name: 'storage-connection-string'
          value: 'DefaultEndpointsProtocol=https;AccountName=${storage.name};AccountKey=${storage.listKeys().keys[0].value};EndpointSuffix=${az.environment().suffixes.storage}'
        }
      ], secretSettings)
    }
    template: {
      containers: [
        {
          name: 'jenesis-repository'
          image: image
          resources: {
            cpu: json(cpu)
            memory: memory
          }
          env: concat(plainEnv, [
            { name: 'JENREG_AZURE_BLOB_CONNECTION_STRING', secretRef: 'storage-connection-string' }
          ], secretEnv)
          probes: [
            {
              type: 'Liveness'
              httpGet: { path: '/actuator/health/liveness', port: 8080 }
              initialDelaySeconds: 5
              periodSeconds: 15
            }
            {
              type: 'Readiness'
              httpGet: { path: '/actuator/health/readiness', port: 8080 }
              initialDelaySeconds: 5
              periodSeconds: 10
            }
          ]
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
      }
    }
  }
}

@description('HTTPS endpoint of the repository server (the console is at /ui/). With the default internal ingress this FQDN resolves only inside the Container Apps environment / VNet; it is publicly reachable only when ingressExternal is true.')
output endpoint string = 'https://${app.properties.configuration.ingress.fqdn}'
