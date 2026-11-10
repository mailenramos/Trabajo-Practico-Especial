# Tasks

## 1. Prueba de integración con PostgreSQL real

- [x] 1.1 Agregar una prueba de integración Spring Boot Test que inicie PostgreSQL con Testcontainers, configure el datasource del contexto para usar ese contenedor y compruebe que Flyway ejecutó las migraciones existentes.
- [x] 1.2 Preparar solicitudes válidas con distintos `idCompra` y el mismo evento, sector, fila y número; sincronizar su inicio y ejecutar las emisiones en paralelo usando el servicio real del contexto Spring. Verificar que la prueba no use repositorios ni transacciones simulados.
- [x] 1.3 Comprobar los resultados concurrentes: exactamente una emisión creada, todas las demás con `BusinessConflictException` y código `ENTRADA_VIGENTE_DUPLICADA`; consultar PostgreSQL y verificar exactamente una fila `VIGENTE` para ese asiento.
- [x] 1.4 Ejecutar el test de integración contra Testcontainers con Docker disponible y confirmar que pasa utilizando las migraciones Flyway reales.

## 2. Regresión

- [x] 2.1 Ejecutar los 15 tests existentes de `EntradaServiceImplTest` sin modificarlos y confirmar que siguen pasando junto con el nuevo test de integración.
