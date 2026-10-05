#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Semana 8 · Configura Azure Event Grid para el Sistema de Usuarios y Roles
#
#   1. Crea el Custom Topic "evt-usuarios-roles".
#   2. Guarda su endpoint y clave en la configuración de la Function App
#      (las funciones productoras publican con ellos).
#   3. Crea el contenedor de dead-letter en el Storage de la Function App.
#   4. Crea una suscripción por cada función consumidora, filtrando por
#      tipo de evento.
#
# Requisitos: Azure CLI con sesión iniciada (az login) o Azure Cloud Shell,
# y las funciones YA desplegadas (mvn clean package azure-functions:deploy),
# porque las suscripciones apuntan a funciones existentes.
#
# Uso:   bash infra/event-grid/configurar-event-grid.sh
# Se puede ejecutar varias veces: todos los comandos crean o actualizan.
# ---------------------------------------------------------------------------
set -euo pipefail

RESOURCE_GROUP="${RESOURCE_GROUP:-prueba_funcion}"
FUNCTION_APP="${FUNCTION_APP:-funcionusuariosroles1}"
TOPIC="${TOPIC:-evt-usuarios-roles}"
DEADLETTER_CONTAINER="${DEADLETTER_CONTAINER:-eventos-deadletter}"
ROLES_CRITICOS="${ROLES_CRITICOS:-ADMINISTRADOR}"
SEGURIDAD_CORREO="${SEGURIDAD_CORREO:-seguridad@empresa.cl}"

echo "==> Suscripción activa: $(az account show --query name -o tsv)"

if ! az functionapp show -g "$RESOURCE_GROUP" -n "$FUNCTION_APP" --query id -o tsv > /dev/null 2>&1; then
  echo "No se encontró la Function App $FUNCTION_APP en el grupo $RESOURCE_GROUP de esta suscripción." >&2
  echo "Revise 'az account list -o table' y seleccione la correcta con 'az account set --subscription <id>'." >&2
  exit 1
fi

# Por defecto el topic se crea en la misma región de la Function App: las
# suscripciones de estudiante solo permiten algunas regiones y esa ya está
# permitida. Se puede forzar otra con LOCATION=<region>.
LOCATION="${LOCATION:-$(az functionapp show -g "$RESOURCE_GROUP" -n "$FUNCTION_APP" --query location -o tsv)}"
LOCATION=$(echo "$LOCATION" | tr -d ' ' | tr '[:upper:]' '[:lower:]')   # "Brazil South" -> "brazilsouth"
echo "==> Región: $LOCATION"

echo "==> 1/5 Registrando el proveedor Microsoft.EventGrid (solo la primera vez tarda)"
az provider register --namespace Microsoft.EventGrid --wait

echo "==> 2/5 Creando el topic $TOPIC en $RESOURCE_GROUP"
az eventgrid topic create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$TOPIC" \
  --location "$LOCATION" \
  --input-schema eventgridschema \
  --output none

TOPIC_ID=$(az eventgrid topic show -g "$RESOURCE_GROUP" -n "$TOPIC" --query id -o tsv)
TOPIC_ENDPOINT=$(az eventgrid topic show -g "$RESOURCE_GROUP" -n "$TOPIC" --query endpoint -o tsv)
TOPIC_KEY=$(az eventgrid topic key list -g "$RESOURCE_GROUP" -n "$TOPIC" --query key1 -o tsv)

echo "==> 3/5 Configurando la Function App $FUNCTION_APP"
az functionapp config appsettings set \
  --resource-group "$RESOURCE_GROUP" \
  --name "$FUNCTION_APP" \
  --settings \
    "EVENTGRID_TOPIC_ENDPOINT=$TOPIC_ENDPOINT" \
    "EVENTGRID_TOPIC_KEY=$TOPIC_KEY" \
    "ROLES_CRITICOS=$ROLES_CRITICOS" \
    "SEGURIDAD_CORREO=$SEGURIDAD_CORREO" \
  --output none

echo "==> 4/5 Creando el contenedor de dead-letter $DEADLETTER_CONTAINER"
STORAGE_CONNECTION=$(az functionapp config appsettings list \
  -g "$RESOURCE_GROUP" -n "$FUNCTION_APP" \
  --query "[?name=='AzureWebJobsStorage'].value" -o tsv)
STORAGE_ACCOUNT=$(echo "$STORAGE_CONNECTION" | sed -n 's/.*AccountName=\([^;]*\).*/\1/p')

if [ -z "$STORAGE_ACCOUNT" ]; then
  echo "No se pudo obtener el Storage de la Function App (AzureWebJobsStorage)." >&2
  exit 1
fi

STORAGE_ID=$(az storage account show --name "$STORAGE_ACCOUNT" --query id -o tsv)
az storage container create \
  --name "$DEADLETTER_CONTAINER" \
  --connection-string "$STORAGE_CONNECTION" \
  --output none

FUNCTION_APP_ID=$(az functionapp show -g "$RESOURCE_GROUP" -n "$FUNCTION_APP" --query id -o tsv)

# suscribir <nombre-suscripcion> <nombre-funcion> [tipos de evento...]
# Sin tipos de evento la suscripción recibe todos.
suscribir() {
  local nombre="$1"
  local funcion="$2"
  shift 2

  local argumentos=(
    --name "$nombre"
    --source-resource-id "$TOPIC_ID"
    --endpoint-type azurefunction
    --endpoint "$FUNCTION_APP_ID/functions/$funcion"
    --max-delivery-attempts 10
    --event-ttl 1440
    --deadletter-endpoint "$STORAGE_ID/blobServices/default/containers/$DEADLETTER_CONTAINER"
    --output none
  )

  if [ "$#" -gt 0 ]; then
    argumentos+=(--included-event-types "$@")
  fi

  echo "    - $nombre -> $funcion ${*:-(todos los eventos)}"
  az eventgrid event-subscription create "${argumentos[@]}"
}

echo "==> 5/5 Creando las suscripciones"
suscribir sub-auditoria auditoria-eventos

suscribir sub-notificaciones notificaciones-eventos \
  Usuario.Creado Usuario.Desactivado UsuarioRol.Asignado UsuarioRol.Revocado Alerta.Seguridad

suscribir sub-seguridad procesador-seguridad \
  UsuarioRol.Asignado

suscribir sub-desactivacion procesador-desactivacion \
  Usuario.Desactivado

echo
az eventgrid event-subscription list --source-resource-id "$TOPIC_ID" \
  --query "[].{suscripcion:name, estado:provisioningState}" \
  -o table

echo
echo "Listo. Topic: $TOPIC_ENDPOINT"
