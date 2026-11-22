# resiliencia-clientes-sincronicos Specification

## Purpose
Limitar las esperas ante fallas repetidas de servicios remotos y permitir observar la recuperación de las llamadas síncronas de MS1.

## Requirements

### Requirement: Aislar llamadas síncronas por servicio
MS1 SHALL mantener circuitos independientes `reservas` y `usuarios` para las llamadas síncronas definidas en este cambio.

#### Scenario: Compartir circuito de Reservas
- **GIVEN** el circuito `reservas` acumula fallas al consultar disponibilidad
- **WHEN** alcanza el umbral configurado
- **THEN** también rechaza los pasos remotos de publicación y las llamadas de suspensión y reactivación mientras permanece abierto

#### Scenario: Independencia entre servicios
- **GIVEN** el circuito `reservas` está abierto y `usuarios` está cerrado
- **WHEN** se consulta MS6 para asignar personal
- **THEN** MS1 realiza la consulta a MS6 normalmente y el estado de `reservas` no cambia

#### Scenario: Trabajos fuera de alcance
- **GIVEN** el circuito `reservas` está abierto
- **WHEN** el dispatcher procesa avisos o el trabajo de cancelación recuperable, o el scheduler intenta finalizar un evento
- **THEN** esas operaciones conservan su política de llamadas y reintentos y no consultan ni modifican el circuito

### Requirement: Abrir y recuperar circuitos configurables
MS1 SHALL configurar por propiedades una ventana por cantidad, mínimo de llamadas, umbral de fallas, duración de apertura y número de llamadas permitidas en semiabierto para cada circuito.

#### Scenario: Abrir tras fallas
- **GIVEN** una configuración de prueba con mínimo y tamaño de ventana de N llamadas y umbral del 100 %
- **WHEN** N llamadas protegidas al mismo servicio fallan por timeout, transporte o `5xx`
- **THEN** su circuito se abre y la siguiente operación protegida no llama al remoto ni espera su timeout

#### Scenario: Recuperación en semiabierto
- **GIVEN** un circuito abierto con una llamada permitida en semiabierto
- **WHEN** transcurre la duración de apertura y una llamada de prueba al servicio tiene éxito
- **THEN** el circuito se cierra y las siguientes llamadas vuelven a llegar al remoto

#### Scenario: Fallo en semiabierto
- **GIVEN** un circuito semiabierto configurado con una llamada de prueba y umbral del 100 %
- **WHEN** la llamada de prueba falla por error de transporte o `5xx`
- **THEN** el circuito vuelve a abrirse durante la duración configurada

### Requirement: Excluir respuestas 4xx del cómputo de fallas
MS1 SHALL excluir del cómputo del circuito todas las respuestas HTTP `4xx` remotas y conservar su tratamiento existente.

#### Scenario: Conflicto de venta de Reservas
- **GIVEN** el circuito `reservas` está cerrado y el evento permite suspender o reactivar
- **WHEN** Reservas responde repetidamente `409` a esas operaciones
- **THEN** MS1 sigue respondiendo `409`, no modifica el evento, no contabiliza esas respuestas en la ventana y el circuito no se abre

#### Scenario: Conflicto de inicialización de Reservas
- **GIVEN** un evento listo para publicación
- **WHEN** Reservas responde `409` al inicializar
- **THEN** MS1 responde `503`, mantiene el borrador y no contabiliza el `409` en la ventana

#### Scenario: Usuario inexistente o error de autenticación
- **GIVEN** MS6 responde `404`, `401` o `403` a la consulta por correo
- **WHEN** se intenta asignar personal
- **THEN** MS1 conserva respectivamente la respuesta `404` o `503`, no guarda la asignación y excluye cada respuesta del cómputo

### Requirement: Degradar sin invocar un servicio con circuito abierto
MS1 SHALL conservar el resultado de falla remota existente cuando el circuito rechaza una llamada protegida.

#### Scenario: Detalle con Reservas abierto
- **GIVEN** el circuito `reservas` está abierto y un evento publicado o agotado tiene sectores
- **WHEN** se consulta su detalle
- **THEN** responde `200` con `disponibilidad: null` en cada sector sin llamar a Reservas

#### Scenario: Publicación con Reservas abierto
- **GIVEN** el circuito `reservas` está abierto y el evento está listo para publicar
- **WHEN** se solicita la publicación
- **THEN** responde `503` con el error estándar, el evento permanece en borrador y no se realiza ninguna llamada a Reservas

#### Scenario: Apertura entre los pasos de publicación
- **GIVEN** Reservas confirmó la inicialización de disponibilidad y el circuito se abre antes de activar la venta
- **WHEN** se intenta activar la venta para publicar
- **THEN** MS1 no realiza esa llamada, responde `503` y conserva el evento en borrador; no supone que la inicialización remota fue revertida

#### Scenario: Suspensión o reactivación con Reservas abierto
- **GIVEN** el circuito `reservas` está abierto y la operación administrativa es válida
- **WHEN** se solicita suspender o reactivar la venta
- **THEN** responde `503` con el error estándar sin llamar a Reservas ni cambiar el evento

#### Scenario: Asignación con Usuarios abierto
- **GIVEN** el circuito `usuarios` está abierto y la solicitud de asignación es válida
- **WHEN** se solicita asignar personal de acceso
- **THEN** responde `503` con el error estándar sin llamar a MS6 ni crear o reactivar la asignación

### Requirement: Observar transiciones de circuitos
MS1 SHALL registrar en nivel WARN cada transición de estado de los circuitos y exponer sus estados en Actuator.

#### Scenario: Transiciones observables
- **WHEN** cualquiera de los circuitos cambia de estado
- **THEN** el log identifica el circuito y los estados anterior y nuevo sin revelar datos sensibles

#### Scenario: Consultar circuitos en health
- **GIVEN** `reservas` está abierto y `usuarios` está cerrado
- **WHEN** se consulta `/actuator/health`
- **THEN** la sección `circuitbreakers` permite distinguir `OPEN` para `reservas` y `CLOSED` para `usuarios`
