# Evidencia de concurrencia y no doble venta de MS2

Fecha: 23 de noviembre de 2026. Código de referencia: commit `6c3d858f6dbf5c7ddfc6564199a67dabd555a603`. MySQL Community Server 8.4.13, bases aisladas de las bases de desarrollo.

## Suite con MySQL real

Se ejecutó `DisponibilidadMySqlConcurrencyTests` con `-Dms2.mysql.integration=true`, `MS2_TEST_MYSQL_URL` apuntando a `127.0.0.1` en un puerto aleatorio y al esquema exclusivo `ms2_disposable`. Resultado Maven: **58 pruebas, 0 fallos, 0 errores, 0 omitidas**. Incluye las pruebas de reserva concurrente del mismo asiento y del último lugar no numerado, y verifica que solo una solicitud gana.

El [resultado JUnit](resultado-mysql-concurrencia.xml) conserva los nombres, tiempos y estados de los 58 casos del reporte Surefire. Se retiraron las propiedades de ejecución y las salidas de log para no publicar información del entorno. Los clientes MS1 y MS7 se sustituyen por dobles en esta suite; no es una prueba del flujo grupal completo.

## Dos instancias de MS2

Se ejecutó el caso existente `test_dos_instancias_mismo_asiento` de `tests/integracion/integracion_ms2_ms7.py`, con dos procesos MS2 reales y una base MySQL compartida, MS1 simulado y el evento sin fila. Resultado: **una reserva HTTP 201 y un conflicto HTTP 409**, con un solo asiento reservado.

Además se ejecutó [concurrencia_ms2.py](../tests/integracion/concurrencia_ms2.py) con diez clientes simultáneos repartidos entre las dos instancias para cada uno de dos escenarios: un mismo asiento numerado y el último lugar no numerado. En cada escenario hubo **una reserva 201, nueve conflictos 409, una confirmación 204 y un lugar vendido**. La [salida de los casos](resultado-dos-instancias.txt) fue recuperada de los contenedores de prueba; ambos finalizaron con código 0.

Estos casos ejercitan la reserva y su confirmación mediante HTTP directo a MS2; no simulan diez pagos ni acreditan el flujo grupal con fila activa. La prueba JMeter grupal del flujo de compra sin fila se documenta por separado en [informe.md](../../../docs/pruebas-carga/informe.md).

## Reproducción de los diez clientes

Desde `Backend/mcsv-disponibildad-reservas`, construir la imagen de MS2 y ejecutar el Compose aislado con un nombre de proyecto no utilizado:

```sh
docker build -t ticketya-reservas:prueba .
docker compose -p ticketya-ms2-prueba -f tests/integracion/compose.concurrencia.yaml up --abort-on-container-exit --exit-code-from tests
```

El entorno usa una base nueva en el proyecto de prueba y no publica puertos en el host. El comando no elimina los recursos al terminar; gestionar únicamente esos recursos de prueba según corresponda, sin borrar volúmenes de otros proyectos. El contenedor de prueba retorna código 0 solo si se cumplen todas las comprobaciones.
