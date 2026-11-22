#!/usr/bin/env bash
# Prueba conjunta de TicketYa, todo a través del API Gateway:
#   login -> lugar y sector (ADMIN) -> evento, precio y publicación (ORGANIZADOR)
#   -> reserva, compra y pago (CLIENTE) -> modificación del evento (ORGANIZADOR)
#   -> mails de "compra confirmada" y "evento modificado" en Mailpit.
#
# Uso (Git Bash, desde la raíz del repo, con el compose general levantado y healthy):
#   bash scripts/prueba-conjunta.sh
#
# Cada pedido lleva X-Correlation-Id "pc-<hora>-<paso>" para seguirlo en Grafana.

GW="${GW:-http://localhost:8086}"
RUN="$(date +%H%M%S)"
RESP="$(mktemp)"
PASO=0

uuid() {
  printf '%04x%04x-%04x-4%03x-%04x-%04x%04x%04x\n' \
    $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $(((RANDOM % 16384) + 32768)) $RANDOM $RANDOM $RANDOM
}

# Primer valor de texto del campo indicado en la última respuesta.
campo() { grep -oE "\"$1\" *: *\"[^\"]*\"" "$RESP" | head -1 | cut -d'"' -f4; }

# llamar METODO RUTA TOKEN "CODIGOS_ESPERADOS" BODY [HEADER...]
llamar() {
  local metodo=$1 ruta=$2 token=$3 esperado=$4 body=$5
  shift 5
  PASO=$((PASO + 1))
  local args=(-s -o "$RESP" -w '%{http_code}' -X "$metodo" "$GW$ruta" -H "X-Correlation-Id: pc-$RUN-$PASO")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local h
  for h in "$@"; do args+=(-H "$h"); done
  local codigo
  codigo=$(curl "${args[@]}")
  echo "[$PASO] $metodo $ruta -> $codigo" >&2
  if [[ " $esperado " != *" $codigo "* ]]; then
    { echo "    Se esperaba: $esperado. Respuesta:"; cat "$RESP"; echo
      echo "    Correlation-Id del paso: pc-$RUN-$PASO"; } >&2
    exit 1
  fi
}

login() {
  llamar POST /auth/login "" "200" "{\"correo\":\"$1\",\"password\":\"Prueba123\"}"
  campo token
}

echo "== Prueba conjunta pc-$RUN contra $GW =="

echo "-- Login"
# login corre en una subshell: el contador de pasos se ajusta a mano.
ADMIN=$(login admin@ticketya.test) || exit 1; PASO=1
ORG=$(login organizador@ticketya.test) || exit 1; PASO=2
CLI=$(login cliente@ticketya.test) || exit 1; PASO=3
[ -n "$ADMIN" ] && [ -n "$ORG" ] && [ -n "$CLI" ] || { echo "No se obtuvieron los tres tokens"; exit 1; }
echo "    Tokens de ADMIN, ORGANIZADOR y CLIENTE obtenidos"

echo "-- Lugar y sector (ADMIN)"
llamar POST /lugares "$ADMIN" "201" \
  "{\"nombre\":\"Estadio Prueba $RUN\",\"direccion\":\"Calle Falsa 123\",\"ciudad\":\"Tandil\",\"latitud\":-37.32,\"longitud\":-59.13}"
LUGAR=$(campo id); echo "    lugarId=$LUGAR"

llamar POST "/lugares/$LUGAR/sectores" "$ADMIN" "201" \
  '{"nombre":"Campo","tipo":"NO_NUMERADO","capacidad":100}'
SECTOR=$(campo id); echo "    sectorId=$SECTOR"

echo "-- Evento, precio y publicación (ORGANIZADOR)"
llamar POST /eventos "$ORG" "201" \
  "{\"nombre\":\"Recital Prueba $RUN\",\"descripcion\":\"Prueba conjunta\",\"tipo\":\"MUSICA\",\"participantes\":[\"Banda de Prueba\"],\"fechaHora\":\"2027-03-15T23:00:00Z\",\"lugarId\":\"$LUGAR\",\"limiteEntradasPorCliente\":4}"
EVENTO=$(campo id); echo "    eventoId=$EVENTO"
SECTOR_EV=$(campo sectorId); SECTOR_EV=${SECTOR_EV:-$SECTOR}; echo "    sectorId del evento=$SECTOR_EV"

llamar PUT "/eventos/$EVENTO/sectores/$SECTOR_EV/precio" "$ORG" "200" '{"precio":10000}'
llamar PUT "/eventos/$EVENTO/publicar" "$ORG" "200" ""
echo "    estado=$(campo estado)"

llamar GET "/eventos/$EVENTO" "" "200" ""
echo "    Detalle público OK (estado=$(campo estado))"

echo "-- Reserva, compra y pago (CLIENTE)"
llamar POST "/disponibilidad/eventos/$EVENTO/reservas" "$CLI" "201" \
  "{\"solicitudId\":\"$(uuid)\",\"detalles\":[{\"sectorId\":\"$SECTOR_EV\",\"cantidad\":2}]}"
RESERVA=$(campo reservaId); echo "    reservaId=$RESERVA"

llamar POST /compras "$CLI" "200 201" "{\"reservaId\":\"$RESERVA\"}"
COMPRA=$(campo compraId); COMPRA=${COMPRA:-$(campo id)}; echo "    compraId=$COMPRA"
echo "    respuesta: $(cat "$RESP")"

llamar POST "/compras/$COMPRA/pago" "$CLI" "200 201 202" '{"medioDePago":"CREDIT_CARD"}' "Idempotency-Key: $(uuid)"
echo "    respuesta: $(cat "$RESP")"

llamar GET "/compras/$COMPRA" "$CLI" "200" ""
echo "    estado de la compra=$(campo estado)"

echo "-- Modificación del evento (ORGANIZADOR)"
llamar PUT "/eventos/$EVENTO" "$ORG" "200" '{"fechaHora":"2027-03-22T23:00:00Z"}'
echo "    nueva fechaHora=$(campo fechaHora)"

rm -f "$RESP"
cat <<FIN

== Listo. Verificación manual ==
1. Mailpit (http://localhost:8025): en 1 a 2 minutos deben llegar a cliente@ticketya.test
   el mail de compra confirmada y, después, el de evento modificado
   (MS1 despacha el aviso cada 60 s, Compras y Notificaciones cada 10 s).
2. Grafana (http://localhost:3000, dashboard "TicketYa - Logs"): buscá "pc-$RUN"
   para ver cada paso atravesando los servicios.
   eventoId=$EVENTO  compraId=$COMPRA
FIN
