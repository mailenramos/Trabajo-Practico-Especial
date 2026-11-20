## 1. Documentación retrospectiva

- [x] 1.1 Registrar fuentes, alcance histórico no archivado y capacidad completa sin suponer especificación canónica (`proposal.md`, `design.md`).
- [x] 1.2 Trazar US-4.1, US-4.2, US-4.4 y US-5.1–US-5.4 y referencias a US-7.4/US-7.5 (`specs/availability-reservations/spec.md`).
- [x] 1.3 Registrar inventario, reservas, límite, vencimientos, confirmación y devoluciones con evidencia `ReservaServiceTests`, `ReservaConfirmacionServiceTests` y `DisponibilidadMySqlConcurrencyTests`.
- [x] 1.4 Registrar autenticación propagada, correlación, errores, resiliencia, outbox existente y restricciones de integración (`ClientesInternosTests`, `CorrelacionFilterTests`, `OutboxDisponibilidadTests`).
- [x] 1.5 Registrar 300 pruebas MS2 y 59 MS7 y Compose aislado con dos instancias por servicio y MS1 simulado; no atribuir las simulaciones a integración grupal.

## 2. Integración pendiente

- [ ] 2.1 Confirmar con MS1 y MS3 los nombres y el contrato de `cantidadReservada` y `cantidadVendida`.
- [ ] 2.2 Verificar con MS1 real la política, los cambios de venta y las notificaciones de disponibilidad.
- [ ] 2.3 Validar con MS3 los flujos reales de confirmación, liberación, devolución y cancelación; las operaciones de pago siguen a cargo de MS3/MS4 (US-5.3).
- [ ] 2.4 Validar JWT, roles, rutas internas y correlación a través de MS6 y Gateway reales; acordar el tratamiento de roles desconocidos.
- [ ] 2.5 Ejecutar los flujos grupales T-19, la carga JMeter T-21 y el despliegue y observabilidad comunes T-13/T-18/T-22.
