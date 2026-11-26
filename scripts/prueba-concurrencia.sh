#!/usr/bin/env bash
# Demostración de concurrencia de TicketYa, todo a través del API Gateway:
#   PARTE 1: N clientes piden EL MISMO asiento a la vez  -> solo uno lo reserva (MS2 Reservas).
#            El ganador compra y paga                    -> se emite una sola entrada (MS5 Entradas).
#   PARTE 2: el QR de esa entrada se valida N veces a la vez -> un solo ingreso (MS5 Entradas).
#
# Uso (Git Bash, desde la raíz del repo, con el compose general levantado y healthy):
#   bash scripts/prueba-concurrencia.sh          # 10 pedidos simultáneos
#   N=25 bash scripts/prueba-concurrencia.sh
#
# Cada corrida crea y publica un evento propio ("Prueba concurrencia <hora>"), así no toca los
# lugares de otros eventos y se puede repetir. Para sacarlo del catálogo: Panel admin -> Eventos.
# Termina con código 0 solo si en las dos partes hubo exactamente un éxito.

GW="${GW:-http://localhost:8086}"
N="${N:-10}"
CLAVE="Prueba123"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
RUN="$(date +%H%M%S)"

uuid() {
  printf '%04x%04x-%04x-4%03x-%04x-%04x%04x%04x\n' \
    $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $(((RANDOM % 16384) + 32768)) $RANDOM $RANDOM $RANDOM
}
# Primer valor de texto del campo indicado (lee de la entrada estándar).
campo() { grep -oE "\"$1\" *: *\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
fallar() { echo "ERROR: $1" >&2; exit 1; }

# pedir METODO RUTA TOKEN [BODY] [HEADER] -> imprime el cuerpo de la respuesta
pedir() {
  local args=(-s -m 60 -X "$1" "$GW$2" -H "X-Correlation-Id: conc-$RUN")
  [ -n "$3" ] && args+=(-H "Authorization: Bearer $3")
  [ -n "$4" ] && args+=(-H 'Content-Type: application/json' -d "$4")
  [ -n "$5" ] && args+=(-H "$5")
  curl "${args[@]}"
}
login() { pedir POST /auth/login "" "{\"correo\":\"$1\",\"password\":\"$CLAVE\"}" | campo token; }

# disparar ARCHIVO_PREFIJO RUTA: lanza N POST a la vez; usa token.$i y cuerpo.$i
disparar() {
  local i
  for i in $(seq 1 "$N"); do
    curl -s -m 90 -o "$TMP/$1.resp.$i" -w '%{http_code}' -X POST "$GW$2" \
      -H "X-Correlation-Id: conc-$RUN-$1-$i" \
      -H "Authorization: Bearer $(cat "$TMP/$1.token.$i")" \
      -H 'Content-Type: application/json' -d "$(cat "$TMP/$1.cuerpo.$i")" \
      > "$TMP/$1.codigo.$i" &
  done
  wait
}

# resumir PREFIJO ETIQUETA -> imprime cada resultado; deja EXITOS, RECHAZOS, OTROS y GANADOR
resumir() {
  local i codigo motivo
  EXITOS=0; RECHAZOS=0; OTROS=0; GANADOR=""
  for i in $(seq 1 "$N"); do
    codigo=$(cat "$TMP/$1.codigo.$i")
    motivo=$(campo mensaje < "$TMP/$1.resp.$i")
    case "$codigo" in
      201) EXITOS=$((EXITOS + 1)); GANADOR=$i; printf '  %s %2d -> %s  OK\n' "$2" "$i" "$codigo" ;;
      409) RECHAZOS=$((RECHAZOS + 1)); printf '  %s %2d -> %s  rechazado: %s\n' "$2" "$i" "$codigo" "$motivo" ;;
      *) OTROS=$((OTROS + 1)); printf '  %s %2d -> %s  INESPERADO: %s\n' "$2" "$i" "$codigo" "$motivo" ;;
    esac
  done
  echo "  RESULTADO: $EXITOS con éxito, $RECHAZOS rechazados con 409, $OTROS inesperados"
}

echo "== Prueba de concurrencia conc-$RUN: $N pedidos simultáneos contra $GW =="

echo "-- Preparación: evento propio, personal de acceso y $N clientes"
ORG=$(login organizador@ticketya.test); [ -n "$ORG" ] || fallar "no pude iniciar sesión como organizador"
LUGAR=$(pedir GET /lugares "$ORG" | tr '{' '\n' | grep '"nombre":"Teatro Central"' | head -1 | campo id)
[ -n "$LUGAR" ] || fallar "no existe 'Teatro Central'; corré antes scripts/cargar-datos-demo.sh"
FECHA=$(date -u -d "+90 days" +%Y-%m-%dT21:00:00Z)
CREADO=$(pedir POST /eventos "$ORG" "{\"nombre\":\"Prueba concurrencia $RUN\",\"descripcion\":\"Prueba de concurrencia\",\"tipo\":\"TEATRO\",\"participantes\":[\"QA\"],\"fechaHora\":\"$FECHA\",\"lugarId\":\"$LUGAR\",\"limiteEntradasPorCliente\":4}")
EVENTO=$(echo "$CREADO" | campo id); [ -n "$EVENTO" ] || fallar "no se pudo crear el evento: $CREADO"
for s in $(echo "$CREADO" | grep -oE '"sectorId" *: *"[^"]*"' | cut -d'"' -f4); do
  pedir PUT "/eventos/$EVENTO/sectores/$s/precio" "$ORG" '{"precio":1000}' > /dev/null
done
PUBLICADO=$(pedir PUT "/eventos/$EVENTO/publicar" "$ORG")
echo "$PUBLICADO" | grep -qi '"estado" *: *"publicado"' || fallar "no se pudo publicar: $PUBLICADO"
pedir POST "/eventos/$EVENTO/personal-acceso" "$ORG" '{"correo":"personal@ticketya.test"}' > /dev/null
SECTOR=$(pedir GET "/eventos/$EVENTO" "" | tr '{' '\n' | grep '"tipo":"NUMERADO"' | campo sectorId)
[ -n "$SECTOR" ] || fallar "el lugar no tiene un sector numerado"

for i in $(seq 1 "$N"); do
  CORREO="concurrencia$i@ticketya.test"
  # Si la cuenta ya existe de una corrida anterior, el registro responde 409 y se reutiliza.
  pedir POST /auth/registro "" "{\"nombre\":\"Cliente\",\"apellido\":\"Concurrencia\",\"correo\":\"$CORREO\",\"password\":\"$CLAVE\"}" > /dev/null
  TOKEN=$(login "$CORREO"); [ -n "$TOKEN" ] || fallar "no pude iniciar sesión como $CORREO"
  echo "$TOKEN" > "$TMP/reserva.token.$i"
  echo "{\"solicitudId\":\"$(uuid)\",\"detalles\":[{\"sectorId\":\"$SECTOR\",\"fila\":1,\"numero\":1}]}" > "$TMP/reserva.cuerpo.$i"
done
echo "   Evento 'Prueba concurrencia $RUN' publicado; $N clientes con sesión"

echo
echo "== PARTE 1: $N clientes reservan A LA VEZ la fila 1, asiento 1 =="
disparar reserva "/disponibilidad/eventos/$EVENTO/reservas"
resumir reserva "Cliente"
EXITOS_RESERVA=$EXITOS; OTROS_RESERVA=$OTROS
[ "$EXITOS" -eq 1 ] || fallar "se esperaba exactamente 1 reserva y hubo $EXITOS"

echo
echo "-- El ganador (cliente $GANADOR) compra y paga"
CLIENTE=$(cat "$TMP/reserva.token.$GANADOR")
RESERVA=$(campo reservaId < "$TMP/reserva.resp.$GANADOR")
COMPRA=$(pedir POST /compras "$CLIENTE" "{\"reservaId\":\"$RESERVA\"}" | campo id)
[ -n "$COMPRA" ] || fallar "no se pudo crear la compra"
PAGO=$(pedir POST "/compras/$COMPRA/pago" "$CLIENTE" '{"medioDePago":"CREDIT_CARD"}' "Idempotency-Key: $(uuid)")
echo "$PAGO" | grep -q '"estado" *: *"CONFIRMADA"' || fallar "el pago no se confirmó: $PAGO"
QR=""
for intento in 1 2 3 4 5 6; do
  ENTRADAS=$(pedir GET "/entradas?page=0&size=50" "$CLIENTE" | sed 's/{"idEntrada"/\n{"idEntrada"/g' | grep "\"idCompra\":\"$COMPRA\"")
  QR=$(echo "$ENTRADAS" | campo codigoQr)
  [ -n "$QR" ] && break
  sleep 2
done
[ -n "$QR" ] || fallar "no apareció la entrada emitida"
EMITIDAS=$(echo "$ENTRADAS" | grep -c '"codigoQr"')
echo "   Compra CONFIRMADA; entradas emitidas para ese asiento: $EMITIDAS"

echo
echo "== PARTE 2 (MS5 Entradas): el mismo QR se presenta $N veces A LA VEZ en la puerta =="
PERSONAL=$(login personal@ticketya.test); [ -n "$PERSONAL" ] || fallar "no pude iniciar sesión como personal de acceso"
for i in $(seq 1 "$N"); do
  echo "$PERSONAL" > "$TMP/ingreso.token.$i"
  echo "{\"codigoQr\":\"$QR\"}" > "$TMP/ingreso.cuerpo.$i"
done
disparar ingreso "/eventos/$EVENTO/ingresos"
resumir ingreso "Lectura"

echo
if [ "$EXITOS_RESERVA" -eq 1 ] && [ "$OTROS_RESERVA" -eq 0 ] && [ "$EMITIDAS" -eq 1 ] && [ "$EXITOS" -eq 1 ] && [ "$OTROS" -eq 0 ]; then
  echo "== PRUEBA SUPERADA: 1 reserva y 1 entrada para el asiento, 1 solo ingreso con el QR =="
  echo "   Seguí los pedidos en Grafana buscando conc-$RUN"
else
  fallar "la prueba NO se superó (ver los resultados de arriba)"
fi
