{{- define "jenreg.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "jenreg.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "jenreg.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "jenreg.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "jenreg.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "jenreg.selectorLabels" -}}
app.kubernetes.io/name: {{ include "jenreg.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "jenreg.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "jenreg.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "jenreg.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- include "jenreg.fullname" . -}}
{{- end -}}
{{- end -}}

{{/* A jenreg.* key as its environment variable (Spring relaxed binding): dots and
     dashes become underscores, uppercased - "scheduled-scan" -> JENREG_SCHEDULED_SCAN. */}}
{{- define "jenreg.repositoryEnvName" -}}
{{- printf "JENREG_%s" (regexReplaceAll "[.-]" . "_" | upper) -}}
{{- end -}}

{{/* The store-backend selection and its settings, shared by the server and the console container
     (both read the same store). Credentials ride the Secret (envFrom) or a pod identity. */}}
{{- define "jenreg.storeEnv" -}}
- name: JENREG_STORE
  value: {{ .Values.store.backend | quote }}
{{- if eq .Values.store.backend "filesystem" }}
- name: JENREG_FILESYSTEM_ROOT
  value: /data
{{- else if eq .Values.store.backend "s3" }}
- name: JENREG_S3_BUCKET
  value: {{ .Values.store.s3.bucket | quote }}
- name: JENREG_S3_REGION
  value: {{ .Values.store.s3.region | quote }}
{{- with .Values.store.s3.endpoint }}
- name: JENREG_S3_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- else if eq .Values.store.backend "gcs" }}
- name: JENREG_GCS_BUCKET
  value: {{ .Values.store.gcs.bucket | quote }}
{{- with .Values.store.gcs.endpoint }}
- name: JENREG_GCS_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- with .Values.store.gcs.region }}
- name: JENREG_GCS_REGION
  value: {{ . | quote }}
{{- end }}
{{- else if eq .Values.store.backend "azure-blob" }}
- name: JENREG_AZURE_BLOB_CONTAINER
  value: {{ .Values.store.azureBlob.container | quote }}
{{- end }}
{{- end -}}

{{/* Console / SSO settings (JENREG_UI_*), read by the enterprise in-process console and the free
     console container alike. */}}
{{- define "jenreg.uiEnv" -}}
- name: JENREG_UI_SECURE_COOKIE
  value: {{ .Values.ui.secureCookie | quote }}
{{- with .Values.ui.admins }}
- name: JENREG_UI_ADMINS
  value: {{ . | quote }}
{{- end }}
{{- with .Values.ui.github.clientId }}
- name: JENREG_UI_GITHUB_CLIENT_ID
  value: {{ . | quote }}
{{- end }}
{{- with .Values.ui.oidc.issuerUri }}
- name: JENREG_UI_OIDC_ISSUER_URI
  value: {{ . | quote }}
- name: JENREG_UI_OIDC_CLIENT_ID
  value: {{ $.Values.ui.oidc.clientId | quote }}
- name: JENREG_UI_OIDC_NAME
  value: {{ $.Values.ui.oidc.name | quote }}
{{- end }}
{{- end -}}
