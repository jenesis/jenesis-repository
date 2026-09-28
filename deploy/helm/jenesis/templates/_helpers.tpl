{{- define "jenrepo.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "jenrepo.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "jenrepo.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "jenrepo.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "jenrepo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "jenrepo.selectorLabels" -}}
app.kubernetes.io/name: {{ include "jenrepo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "jenrepo.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "jenrepo.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "jenrepo.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- include "jenrepo.fullname" . -}}
{{- end -}}
{{- end -}}

{{/* A jenrepo.* key as its environment variable (Spring relaxed binding): dots and
     dashes become underscores, uppercased - "scheduled-scan" -> JENREPO_SCHEDULED_SCAN. */}}
{{- define "jenrepo.repositoryEnvName" -}}
{{- printf "JENREPO_%s" (regexReplaceAll "[.-]" . "_" | upper) -}}
{{- end -}}

{{/* The store-backend selection and its settings, shared by the server and the console container
     (both read the same store). Credentials ride the Secret (envFrom) or a pod identity. */}}
{{/* Where the gcs service-account key is mounted when secrets.gcsServiceAccountKey is set; the backend reads
     the file the setting names. */}}
{{- define "jenrepo.gcsCredentialsPath" -}}
/var/run/secrets/jenesis/gcs/credentials.json
{{- end }}

{{- define "jenrepo.storeEnv" -}}
- name: JENREPO_STORE
  value: {{ .Values.store.backend | quote }}
{{- if eq .Values.store.backend "filesystem" }}
- name: JENREPO_FILESYSTEM_ROOT
  value: /data
{{- else if eq .Values.store.backend "s3" }}
- name: JENREPO_S3_BUCKET
  value: {{ .Values.store.s3.bucket | quote }}
- name: JENREPO_S3_REGION
  value: {{ .Values.store.s3.region | quote }}
{{- with .Values.store.s3.endpoint }}
- name: JENREPO_S3_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- else if eq .Values.store.backend "gcs" }}
- name: JENREPO_GCS_BUCKET
  value: {{ .Values.store.gcs.bucket | quote }}
{{- with .Values.store.gcs.endpoint }}
- name: JENREPO_GCS_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- if .Values.secrets.gcsServiceAccountKey }}
- name: JENREPO_GCS_CREDENTIALS
  value: {{ include "jenrepo.gcsCredentialsPath" . | quote }}
{{- end }}
{{- else if eq .Values.store.backend "azure-blob" }}
- name: JENREPO_AZURE_BLOB_CONTAINER
  value: {{ .Values.store.azureBlob.container | quote }}
{{- end }}
{{- end -}}

{{/* Console / SSO settings (JENREPO_UI_*), read by the console the image runs in-process. */}}
{{- define "jenrepo.uiEnv" -}}
{{- with .Values.ui.admins }}
- name: JENREPO_UI_ADMINS
  value: {{ . | quote }}
{{- end }}
{{- with .Values.ui.github.clientId }}
- name: JENREPO_UI_GITHUB_CLIENT_ID
  value: {{ . | quote }}
{{- end }}
{{- with .Values.ui.oidc.issuerUri }}
- name: JENREPO_UI_OIDC_ISSUER_URI
  value: {{ . | quote }}
- name: JENREPO_UI_OIDC_CLIENT_ID
  value: {{ $.Values.ui.oidc.clientId | quote }}
- name: JENREPO_UI_OIDC_NAME
  value: {{ $.Values.ui.oidc.name | quote }}
{{- end }}
{{- end -}}
