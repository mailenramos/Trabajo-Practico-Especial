"""Prueba aislada de competencia HTTP con dos MS2 y MySQL compartido."""
import concurrent.futures
import threading
import uuid

from integracion_ms2_ms7 import call, expect, initialize, reserve


def competir_por_un_lugar(tipo):
    suffix = uuid.uuid4().hex
    evento = "sin-fila-competencia-" + suffix
    sector = {"sectorId": "UNICO", "tipo": tipo, "capacidadTotal": 1,
              "asientos": [{"fila": 1, "numero": 1}] if tipo == "NUMERADO" else []}
    detalle = {"sectorId": "UNICO", "fila": 1, "numero": 1} if tipo == "NUMERADO" else {"sectorId": "UNICO", "cantidad": 1}
    bases = ("http://ms2-a:8080", "http://ms2-b:8080")
    initialize(bases[0], evento, sector)
    inicio = threading.Barrier(10)

    def cliente(i):
        inicio.wait(timeout=15)
        return reserve(bases[i % 2], evento, "cliente-" + suffix + "-" + str(i),
                       "sol-" + suffix + "-" + str(i), detalle)

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        resultados = list(pool.map(cliente, range(10)))
    codigos = sorted(r[0] for r in resultados)
    assert codigos == [201] + [409] * 9, codigos
    reserva = next(r[1]["reservaId"] for r in resultados if r[0] == 201)
    expect(call(bases[1], "POST", "/disponibilidad/reservas/" + reserva + "/confirmacion"), 204)
    resumen = expect(call(bases[0], "GET", "/disponibilidad/eventos/" + evento), 200)["sectores"][0]
    assert resumen["cantidadVendida"] == 1 and resumen["cantidadReservada"] == 0 and resumen["cantidadDisponible"] == 0, resumen
    print(f"OK: {tipo}: 10 clientes, 2 instancias, 1 reserva HTTP 201, 9 conflictos HTTP 409, 1 confirmación y 1 lugar vendido", flush=True)


if __name__ == "__main__":
    competir_por_un_lugar("NUMERADO")
    competir_por_un_lugar("NO_NUMERADO")
