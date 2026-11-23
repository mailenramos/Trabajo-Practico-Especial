# Prueba de carga del flujo de compra

Fecha: 23 de noviembre de 2026, 18:28 ART. Herramienta: Apache JMeter 5.6.3 en modo no gráfico. Sistema: servicios locales mediante Docker Compose y API Gateway en `127.0.0.1:8086`; pagos simulados localmente.

## Escenario

Se configuraron **5 clientes concurrentes**, con incorporación gradual durante 2 segundos y una compra por cliente. Una barrera sincronizó a los cinco antes de reservar. Cada hilo registró un usuario distinto, inició sesión para obtener su JWT, reservó una entrada, creó la compra y solicitó el pago. Se exigieron los códigos HTTP esperados y el estado `CONFIRMADA` en la respuesta del pago. Si un paso fallaba, el hilo se detenía.

Se usó un evento local creado para esta prueba, con sector no numerado de capacidad 100 y una entrada por cliente. No se compitió por asientos específicos. Las escrituras de prueba quedaron en las bases locales; no se borraron datos ni volúmenes.

## Resultados

| Operación | Solicitudes | Media (ms) | Mínimo (ms) | Máximo (ms) | Errores |
|---|---:|---:|---:|---:|---:|
| Registro | 5 | 100,4 | 76 | 140 | 0 |
| Login | 5 | 70,4 | 60 | 97 | 0 |
| Reserva | 5 | 335,6 | 294 | 377 | 0 |
| Compra | 5 | 280,0 | 239 | 323 | 0 |
| Pago y confirmación | 5 | 661,2 | 660 | 662 | 0 |
| **Total HTTP** | **25** | **289,5** | **60** | **662** | **0 (0 %)** |

Las cinco reservas y compras respondieron 201; los cinco pagos respondieron 200 y cumplieron la verificación de compra confirmada. JMeter registró un máximo de 5 hilos activos. La ejecución incluyó además cinco muestras internas para preparar identidades, sin llamadas HTTP; se excluyen de la tabla. El resultado completo está en [resultados.jtl](resultados.jtl).

## Conclusión

En esta ejecución local y moderada, los cinco clientes completaron el flujo de compra sin errores; el pago fue la operación más lenta, con una media de 661,2 ms. Por el tamaño y la duración de la muestra, estos resultados no determinan la capacidad máxima ni garantizan tiempos para una carga mayor.

## Reproducción

Con los servicios locales y sus dependencias funcionando, preparar un evento publicado con sector no numerado y capacidad suficiente. Ejecutar desde la raíz, usando sus identificadores y una ruta de resultado nueva:

```sh
jmeter -n -t docs/pruebas-carga/compra.jmx \
  -Jhost=127.0.0.1 -Jport=8086 \
  -JeventoId=ID_EVENTO -JsectorId=ID_SECTOR \
  -l docs/pruebas-carga/nuevos-resultados.jtl
```

El plan genera usuarios y claves de solicitud únicos por ejecución. Los registros, reservas y compras de prueba persisten en el entorno utilizado. Para esta ejecución el evento fue `6b04afdb3288ce2e952c1e73` y el sector `6786fcf6-7539-4c61-b992-458fc88ee9ab`.
