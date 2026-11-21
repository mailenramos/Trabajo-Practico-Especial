## 1. Documentación retrospectiva

- [x] 1.1 Documentar alcance simplificado, modelo y contratos, sin describirlo como diseño previo a la implementación (`proposal.md`, `design.md`).
- [x] 1.2 Trazar US-7.1–US-7.9 en `specs/virtual-queue/spec.md`.
- [x] 1.3 Registrar FIFO, posiciones, cupo, vencimientos, concurrencia e idempotencia con evidencia `FilaVirtualMySqlTests`, `RepositoriosMySqlTests` y `TurnoSchedulerTests`.
- [x] 1.4 Registrar coordinación, cancelación, seguridad, errores y circuitos con evidencia `Ms1IntegracionMySqlTests`, `FilaControllerTests` y `Ms1EventoClientTests`.
- [x] 1.5 Registrar las 59 pruebas MS7 y 300 MS2 y Compose aislado con servicios propios reales, MS1 simulado y proxy de fallos.

## 2. Integración pendiente

- [ ] 2.1 Validar `GET /internal/eventos/{eventoId}`, `PUT /internal/eventos/{eventoId}/politica-fila`, titularidad y cancelación con MS1 real.
- [ ] 2.2 Verificar JWT, identidad, roles y aislamiento de rutas internas con MS6 y Gateway reales; acordar el tratamiento de roles desconocidos.
- [ ] 2.3 Validar los flujos con MS3 y el sistema grupal completo, incluyendo fallos de comunicación e idempotencia.
- [ ] 2.4 Ejecutar T-19 grupal, prueba de carga JMeter T-21 y validaciones de despliegue, observabilidad y documentación T-13/T-18/T-22.
