#!/usr/bin/env bash
# Carga eventos de demostración a través del API Gateway, con el mismo camino que usa un organizador:
#   login (ADMIN y ORGANIZADOR) -> lugar y sectores (ADMIN) -> evento, precio por sector y
#   publicación (ORGANIZADOR).
# Publicar es lo que inicializa la disponibilidad en MS2 (Reservas): por eso los eventos quedan
# listos para reservar y comprar desde la interfaz.
#
# Uso (Git Bash, desde la raíz del repo, con el compose general levantado y healthy):
#   bash scripts/cargar-datos-demo.sh
#
# Es repetible: si un evento con el mismo nombre ya existe, se omite.

GW="${GW:-http://localhost:8086}"
ORG_CORREO="${ORG_CORREO:-organizador@ticketya.test}"
ORG_PASSWORD="${ORG_PASSWORD:-Prueba123}"
RESP="$(mktemp)"

fecha() { date -u -d "+$1 days" +%Y-%m-%dT21:00:00Z; }

# Todos los valores de texto del campo indicado en la última respuesta, uno por línea.
campos() { grep -oE "\"$1\" *: *\"[^\"]*\"" "$RESP" | cut -d'"' -f4; }
campo() { campos "$1" | head -1; }

# llamar METODO RUTA "CODIGOS_ESPERADOS" BODY
llamar() {
  local codigo args=(-s -o "$RESP" -w '%{http_code}' -X "$1" "$GW$2")
  [ -n "$TOKEN" ] && args+=(-H "Authorization: Bearer $TOKEN")
  # El cuerpo va desde un archivo: en Windows curl recodifica los argumentos con tildes y MS1 los rechaza.
  [ -n "$4" ] && { printf '%s' "$4" > "$RESP.body"; args+=(-H 'Content-Type: application/json' --data-binary "@$RESP.body"); }
  codigo=$(curl "${args[@]}")
  if [[ " $3 " != *" $codigo "* ]]; then
    echo "    ERROR: $1 $2 -> $codigo (se esperaba $3)" >&2
    cat "$RESP" >&2; echo >&2
    exit 1
  fi
}

# Por defecto entra con el admin de prueba de MS6. Se puede cambiar con ADMIN_CORREO / ADMIN_CLAVE.
ADMIN_CORREO="${ADMIN_CORREO:-admin@ticketya.test}"
ADMIN_CLAVE="${ADMIN_CLAVE:-Prueba123}"

# iniciar_sesion CORREO CLAVE -> deja el token en TOKEN
iniciar_sesion() {
  TOKEN=""
  llamar POST /auth/login "200" "{\"correo\":\"$1\",\"password\":\"$2\"}"
  TOKEN=$(campo token)
  [ -n "$TOKEN" ] || { echo "No se pudo iniciar sesión como $1"; exit 1; }
}

TOKEN=""
echo "== Datos de demo contra $GW =="
iniciar_sesion "$ADMIN_CORREO" "$ADMIN_CLAVE"
TOKEN_ADMIN=$TOKEN
iniciar_sesion "$ORG_CORREO" "$ORG_PASSWORD"
TOKEN_ORG=$TOKEN
echo "    Sesiones de ADMIN (lugares y sectores) y ORGANIZADOR (eventos) iniciadas"
TOKEN=$TOKEN_ORG

# Nombres de eventos que ya existen.
llamar GET "/eventos?page=0&size=100" "200" ""
EXISTENTES=$(campos nombre)

existe() { printf '%s\n' "$EXISTENTES" | grep -qxF "$1"; }

# crear_lugar NOMBRE DIRECCION CIUDAD LAT LON -> LUGAR_ID
# Si ya existe un lugar con ese nombre y dirección se reutiliza (con sus sectores) en vez de duplicarlo.
crear_lugar() {
  TOKEN=$TOKEN_ADMIN
  LUGAR_EXISTE=""
  llamar GET /lugares "200" ""
  LUGAR_ID=$(tr '{' '\n' < "$RESP" | grep "\"nombre\":\"$1\",\"direccion\":\"$2\"" | head -1 \
    | grep -oE '"id" *: *"[^"]*"' | head -1 | cut -d'"' -f4)
  if [ -n "$LUGAR_ID" ]; then
    LUGAR_EXISTE=1
    echo "-- Lugar $1: ya existe, se reutiliza"
  else
    llamar POST /lugares "201" \
      "{\"nombre\":\"$1\",\"direccion\":\"$2\",\"ciudad\":\"$3\",\"latitud\":$4,\"longitud\":$5}"
    LUGAR_ID=$(campo id)
  fi
  TOKEN=$TOKEN_ORG
}

# crear_sector NOMBRE TIPO FILAS ASIENTOS_POR_FILA CAPACIDAD (los que no aplican, "null")
crear_sector() {
  [ -n "$LUGAR_EXISTE" ] && return
  TOKEN=$TOKEN_ADMIN
  llamar POST "/lugares/$LUGAR_ID/sectores" "201" \
    "{\"nombre\":\"$1\",\"tipo\":\"$2\",\"filas\":$3,\"asientosPorFila\":$4,\"capacidad\":$5}"
  TOKEN=$TOKEN_ORG
}

# crear_evento NOMBRE DESCRIPCION TIPO PARTICIPANTE DIAS LIMITE PRECIO1 [PRECIO2 ...]
# Los precios se asignan a los sectores del lugar en el orden en que se crearon.
crear_evento() {
  local nombre=$1 descripcion=$2 tipo=$3 participante=$4 dias=$5 limite=$6
  shift 6
  if existe "$nombre"; then echo "-- $nombre: ya existe, se omite"; return; fi
  llamar POST /eventos "201" \
    "{\"nombre\":\"$nombre\",\"descripcion\":\"$descripcion\",\"tipo\":\"$tipo\",\"participantes\":[\"$participante\"],\"fechaHora\":\"$(fecha "$dias")\",\"lugarId\":\"$LUGAR_ID\",\"limiteEntradasPorCliente\":$limite}"
  local evento sectores=() i=0 precio
  evento=$(campo id)
  mapfile -t sectores < <(campos sectorId)
  for precio in "$@"; do
    llamar PUT "/eventos/$evento/sectores/${sectores[$i]}/precio" "200" "{\"precio\":$precio}"
    i=$((i + 1))
  done
  llamar PUT "/eventos/$evento/publicar" "200" ""
  echo "-- $nombre: publicado ($evento, $i sectores con precio)"
}

crear_lugar "Teatro Central" "Av. Corrientes 1234" "Buenos Aires" -34.6037 -58.3816
crear_sector "Platea" NUMERADO 6 10 null
crear_sector "Pullman" NO_NUMERADO null null 120
crear_evento "El zorzal" "Una obra para recordar la voz de un clásico." TEATRO "Compañía Gamma" 40 4 7000 4500
crear_evento "Noche de Jazz" "Un ciclo de jazz en vivo con invitados especiales." MUSICA "Cuarteto Delta" 25 4 9000 5000

crear_lugar "Estadio del Parque" "Av. del Deporte 2500" "Córdoba" -31.4201 -64.1888
crear_sector "Tribuna Norte" NUMERADO 8 12 null
crear_sector "Campo de pie" NO_NUMERADO null null 300
crear_evento "Festival de Rock Nacional" "Una noche de rock nacional en Córdoba." MUSICA "Banda Alfa" 30 6 20000 8000
crear_evento "Final del Torneo" "La gran final del torneo apertura." DEPORTE "Club Delta" 55 4 15000 6000

echo "== Listo. Eventos visibles en: $GW/eventos =="
