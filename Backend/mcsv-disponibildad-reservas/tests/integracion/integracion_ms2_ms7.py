"""Pruebas HTTP contra dos MS2 y un MS7 reales, con MS1 simulado y bases descartables."""
import concurrent.futures
import datetime
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def endpoint(nombre):
    valor = os.environ[nombre]
    url = urllib.parse.urlsplit(valor)
    compose_hosts = {"ms2-a", "ms2-b", "ms7-a", "ms7-b"}
    isolated_compose = os.environ.get("TICKETYA_TEST_COMPOSE") == "true" and url.hostname in compose_hosts
    if url.scheme != "http" or not url.port or not (isolated_compose or url.hostname == "127.0.0.1" and url.port not in (3307, 8080, 8088)):
        raise ValueError(nombre + " debe apuntar a un puerto local aislado y explícito")
    return valor.rstrip("/")


def call(base, method, path, body=None, usuario=None, roles="CLIENTE"):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if usuario is not None:
        headers.update({"X-User-Id": usuario, "X-User-Roles": roles})
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


def expect(result, status):
    if result[0] != status:
        raise AssertionError(f"Se esperaba HTTP {status}, se recibió {result}")
    return result[1]


def initialize(ms2, evento, sector):
    path = "/disponibilidad/eventos/" + evento
    expect(call(ms2, "PUT", path, {"limiteEntradasPorCliente": 4, "sectores": [sector]}), 204)
    expect(call(ms2, "PUT", path + "/venta", {"estado": "ACTIVA"}), 204)


def reserve(base, evento, usuario, solicitud, detalle):
    return call(base, "POST", "/disponibilidad/eventos/" + evento + "/reservas",
                {"solicitudId": solicitud, "detalles": [detalle]}, usuario)


def test_consumo_fallo_y_reingreso(ms2, ms7, suffix):
    evento = "con-fila-" + suffix
    initialize(ms2, evento, {"sectorId": "GEN", "tipo": "NO_NUMERADO", "capacidadTotal": 1, "asientos": []})
    path = "/filas/eventos/" + evento
    expect(call(ms7, "PUT", path + "/configuracion", {"cupo": 1, "duracionMinutos": 5}, "admin", "ADMIN"), 204)
    expect(call(ms7, "PUT", path + "/estado", {"estado": "ACTIVA"}, "admin", "ADMIN"), 204)
    expect(call(ms7, "POST", path + "/ingresos", usuario="primer-" + suffix), 201)
    expect(reserve(ms2, evento, "primer-" + suffix, "primera-" + suffix, {"sectorId": "GEN", "cantidad": 1}), 201)
    anterior = expect(call(ms7, "POST", path + "/ingresos", usuario="segundo-" + suffix), 201)
    assert anterior["estado"] == "HABILITADO"
    error = expect(reserve(ms2, evento, "segundo-" + suffix, "sin-lugar-" + suffix,
                           {"sectorId": "GEN", "cantidad": 1}), 409)
    assert "Lugares disponibles: 0" in error["mensaje"]
    consumido = expect(call(ms7, "GET", path + "/mi-turno", usuario="segundo-" + suffix), 200)
    assert consumido["estado"] == "CONSUMIDO" and consumido["turnoId"] == anterior["turnoId"]
    nuevo = expect(call(ms7, "POST", path + "/ingresos", usuario="segundo-" + suffix), 201)
    assert nuevo["turnoId"] != anterior["turnoId"] and nuevo["estado"] == "HABILITADO"
    print("OK: consumo real, rechazo por inventario y reingreso con turno nuevo")


def test_dos_instancias_mismo_asiento(ms2a, ms2b, suffix):
    evento = "sin-fila-" + suffix
    initialize(ms2a, evento, {"sectorId": "NUM", "tipo": "NUMERADO", "capacidadTotal": 1,
                              "asientos": [{"fila": 1, "numero": 1}]})
    barrier = threading.Barrier(2)

    def competir(index, base):
        barrier.wait(timeout=10)
        return reserve(base, evento, "cliente-" + str(index) + suffix, "sol-" + str(index) + suffix,
                       {"sectorId": "NUM", "fila": 1, "numero": 1})

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a = pool.submit(competir, 1, ms2a)
        b = pool.submit(competir, 2, ms2b)
        results = [a.result(timeout=30), b.result(timeout=30)]
    assert sorted(result[0] for result in results) == [201, 409], results
    resumen = expect(call(ms2a, "GET", "/disponibilidad/eventos/" + evento), 200)["sectores"][0]
    assert resumen["cantidadReservada"] == 1 and resumen["cantidadDisponible"] == 0
    print("OK: dos instancias MS2, un mismo asiento numerado, una sola reserva")


def test_fifo_idempotencia_y_cupo(ms2a, ms2b, ms7a, ms7b, suffix):
    evento = "con-fila-fifo-" + suffix
    initialize(ms2a, evento, {"sectorId": "GEN", "tipo": "NO_NUMERADO", "capacidadTotal": 10, "asientos": []})
    path = "/filas/eventos/" + evento
    expect(call(ms7a, "PUT", path + "/configuracion", {"cupo": 1, "duracionMinutos": 5}, "admin", "ADMIN"), 204)
    expect(call(ms7a, "PUT", path + "/estado", {"estado": "ACTIVA"}, "admin", "ADMIN"), 204)
    uno = expect(call(ms7a, "POST", path + "/ingresos", usuario="uno-" + suffix), 201)
    dos = expect(call(ms7b, "POST", path + "/ingresos", usuario="dos-" + suffix), 201)
    assert uno["estado"] == "HABILITADO" and dos["estado"] == "ESPERANDO" and dos["posicion"] == 1
    body = {"sectorId": "GEN", "cantidad": 1}
    first = expect(reserve(ms2a, evento, "uno-" + suffix, "misma-" + suffix, body), 201)
    retry = expect(reserve(ms2b, evento, "uno-" + suffix, "misma-" + suffix, body), 200)
    assert first["reservaId"] == retry["reservaId"]
    assert expect(call(ms7b, "GET", path + "/mi-turno", usuario="dos-" + suffix), 200)["estado"] == "HABILITADO"
    print("OK: FIFO, habilitación tras consumo e idempotencia entre instancias")


def test_limite_y_recuperacion_comunicacion(ms2a, ms2b, suffix):
    evento = "sin-fila-fallos-" + suffix
    initialize(ms2a, evento, {"sectorId": "GEN", "tipo": "NO_NUMERADO", "capacidadTotal": 10, "asientos": []})
    ms1 = "http://ms1:8081"
    expect(call(ms1, "PUT", "/_test/falla/activar"), 204)
    try:
        expect(reserve(ms2a, evento, "c-" + suffix, "fallo-" + suffix, {"sectorId": "GEN", "cantidad": 1}), 503)
    finally:
        expect(call(ms1, "PUT", "/_test/falla/desactivar"), 204)
    expect(reserve(ms2a, evento, "c-" + suffix, "fallo-" + suffix, {"sectorId": "GEN", "cantidad": 1}), 201)
    expect(reserve(ms2b, evento, "c-" + suffix, "limite-" + suffix, {"sectorId": "GEN", "cantidad": 4}), 409)
    print("OK: fallo temporal de MS1, recuperación y límite de cliente")


def test_vencimiento_turno(ms2, ms7a, ms7b, suffix):
    evento = "con-fila-vencimiento-" + suffix
    initialize(ms2, evento, {"sectorId": "GEN", "tipo": "NO_NUMERADO", "capacidadTotal": 4, "asientos": []})
    path = "/filas/eventos/" + evento
    expect(call(ms7a, "PUT", path + "/configuracion", {"cupo": 1, "duracionMinutos": 1}, "admin", "ADMIN"), 204)
    expect(call(ms7a, "PUT", path + "/estado", {"estado": "ACTIVA"}, "admin", "ADMIN"), 204)
    primero = expect(call(ms7a, "POST", path + "/ingresos", usuario="vence-" + suffix), 201)
    expect(call(ms7b, "POST", path + "/ingresos", usuario="espera-" + suffix), 201)
    expira = datetime.datetime.fromisoformat(primero["expiraEn"].replace("Z", "+00:00"))
    demora = (expira - datetime.datetime.now(datetime.timezone.utc)).total_seconds()
    if demora > 0: time.sleep(demora + 0.1)
    assert expect(call(ms7b, "GET", path + "/mi-turno", usuario="vence-" + suffix), 200)["estado"] == "VENCIDO"
    assert expect(call(ms7a, "GET", path + "/mi-turno", usuario="espera-" + suffix), 200)["estado"] == "HABILITADO"
    expect(reserve(ms2, evento, "vence-" + suffix, "vencido-" + suffix, {"sectorId": "GEN", "cantidad": 1}), 409)
    print("OK: vencimiento real del turno y habilitación del siguiente")


def test_dos_instancias_ms7_concurrentes(ms7a, ms7b, suffix):
    evento = "con-fila-concurrencia-" + suffix
    path = "/filas/eventos/" + evento
    expect(call(ms7a, "PUT", path + "/configuracion", {"cupo": 1, "duracionMinutos": 5}, "admin", "ADMIN"), 204)
    expect(call(ms7a, "PUT", path + "/estado", {"estado": "ACTIVA"}, "admin", "ADMIN"), 204)
    barrier = threading.Barrier(2)
    def ingresar(base, usuario):
        barrier.wait(timeout=10)
        return expect(call(base, "POST", path + "/ingresos", usuario=usuario), 201)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a = pool.submit(ingresar, ms7a, "concurrente-a-" + suffix)
        b = pool.submit(ingresar, ms7b, "concurrente-b-" + suffix)
        turnos = [a.result(timeout=30), b.result(timeout=30)]
    assert sorted(t["estado"] for t in turnos) == ["ESPERANDO", "HABILITADO"]
    assert turnos[0]["turnoId"] != turnos[1]["turnoId"]
    print("OK: dos instancias MS7, ingresos concurrentes sin superar cupo")


def test_fallos_consumo_circuito_y_recuperacion(ms2, ms7, suffix):
    proxy, ms1 = "http://proxy-ms7:8082", "http://ms1:8081"
    evento = "con-fila-fallos-" + suffix
    path = "/filas/eventos/" + evento
    initialize(ms2, evento, {"sectorId": "GEN", "tipo": "NO_NUMERADO", "capacidadTotal": 10, "asientos": []})
    expect(call(ms7, "PUT", path + "/configuracion", {"cupo": 4, "duracionMinutos": 5}, "admin", "ADMIN"), 204)
    expect(call(ms7, "PUT", path + "/estado", {"estado": "ACTIVA"}, "admin", "ADMIN"), 204)
    detalle = {"sectorId": "GEN", "cantidad": 1}
    u1, u2, u3 = "existente-" + suffix, "perdida-" + suffix, "caido-" + suffix
    for usuario in (u1, u2, u3):
        expect(call(ms7, "POST", path + "/ingresos", usuario=usuario), 201)
    existente = expect(reserve(ms2, evento, u1, "sol-existente", detalle), 201)

    expect(call(proxy, "PUT", "/_test/perder-respuesta"), 200)
    antes = expect(call(proxy, "GET", "/_test/estado"), 200)
    resultado = reserve(ms2, evento, u2, "sol-incierta", detalle)
    assert resultado[0] == 503, resultado
    estado = expect(call(proxy, "GET", "/_test/estado"), 200)
    assert estado['dropped'] == antes['dropped'] + 1 and estado['forwarded'] == antes['forwarded'] + 1, estado
    previo = expect(call(ms7, "GET", path + "/mi-turno", usuario=u2), 200)
    assert previo['estado'] == 'CONSUMIDO'
    recuperada = expect(reserve(ms2, evento, u2, "sol-incierta", detalle), 201)
    repetida = expect(reserve(ms2, evento, u2, "sol-incierta", detalle), 200)
    assert repetida['reservaId'] == recuperada['reservaId']
    assert expect(call(ms7, "GET", path + "/mi-turno", usuario=u2), 200)['turnoId'] == previo['turnoId']
    resumen = expect(call(ms2, "GET", "/disponibilidad/eventos/" + evento), 200)['sectores'][0]
    assert resumen['cantidadReservada'] == 2, resumen

    expect(call(proxy, "PUT", "/_test/no-disponible"), 200)
    abierto = False
    for i in range(16):
        n = expect(call(proxy, "GET", "/_test/estado"), 200)['rejected']
        expect(reserve(ms2, evento, u3, "sol-caido", detalle), 503)
        if expect(call(proxy, "GET", "/_test/estado"), 200)['rejected'] == n:
            abierto = True
            break
    assert abierto, 'El circuito no se abrió después de los fallos'
    assert expect(call(ms7, "GET", path + "/mi-turno", usuario=u3), 200)['estado'] == 'HABILITADO'
    expect(call(ms1, "PUT", "/_test/falla/activar"), 204)
    try:
        mismo = expect(reserve(ms2, evento, u1, "sol-existente", detalle), 200)
        assert mismo['reservaId'] == existente['reservaId']
    finally:
        expect(call(ms1, "PUT", "/_test/falla/desactivar"), 204)
        expect(call(proxy, "PUT", "/_test/normal"), 200)
    time.sleep(31)
    expect(reserve(ms2, evento, u3, "sol-caido", detalle), 201)
    print("OK: respuesta perdida, consumo idempotente, MS7 no disponible, circuito abierto y recuperación")


if __name__ == "__main__":
    if os.environ.get("TICKETYA_TEST_ISOLATED") != "true":
        raise SystemExit("Solo ejecutar con servicios y bases descartables; TICKETYA_TEST_ISOLATED=true es obligatorio")
    ms2a, ms2b, ms7 = (endpoint(name) for name in ("MS2_TEST_URL_A", "MS2_TEST_URL_B", "MS7_TEST_URL"))
    if ms2a == ms2b:
        raise SystemExit("Se requieren dos instancias MS2 diferentes")
    suffix = uuid.uuid4().hex
    test_consumo_fallo_y_reingreso(ms2a, ms7, suffix)
    test_dos_instancias_mismo_asiento(ms2a, ms2b, suffix)
    if os.environ.get("MS7_TEST_URL_B"):
        test_fifo_idempotencia_y_cupo(ms2a, ms2b, ms7, endpoint("MS7_TEST_URL_B"), suffix)
        test_limite_y_recuperacion_comunicacion(ms2a, ms2b, suffix)
        test_dos_instancias_ms7_concurrentes(ms7, endpoint("MS7_TEST_URL_B"), suffix)
        test_fallos_consumo_circuito_y_recuperacion(ms2a, ms7, suffix)
        test_vencimiento_turno(ms2a, ms7, endpoint("MS7_TEST_URL_B"), suffix)
