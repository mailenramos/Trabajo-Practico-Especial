# Tasks

## 1. Dependencia y configuración

- [x] 1.1 Agregar `resilience4j-spring-boot3` en una versión verificada con Spring Boot 3.4 y Java 21, sin cambiar Java; comprobar resolución y compilación Maven.
- [x] 1.2 Configurar las dos instancias y defaults del diseño, con propiedades sobrescribibles; probar que cada instancia aplica sus valores y que una sobrescritura no afecta a la otra.

## 2. Integración de Reservas

- [x] 2.1 Conservar la clasificación HTTP necesaria para ignorar todos los `4xx` sin cambiar el manejo actual; probar `409` de venta e inicialización, otros `4xx`, `5xx` y errores de transporte.
- [x] 2.2 Proteger por separado disponibilidad, inicialización, activación de publicación, suspensión y reactivación con el circuito compartido; probar apertura tras N fallas y cada resultado degradado sin llamadas adicionales ni cambios locales.
- [x] 2.3 Probar la apertura entre inicialización y activación, la recuperación en semiabierto y el aislamiento de finalización, dispatcher y cancelación recuperable incluso con el circuito abierto.
- [x] 2.4 Documentar en el README el alcance y las respuestas de Reservas, incluida la publicación parcial y la exclusión de los trabajos; verificar que se retire la afirmación de que no existe circuit breaker.

## 3. Integración de Usuarios

- [x] 3.1 Proteger la consulta por correo de MS6, conservar el status para excluir `4xx` y traducir el rechazo del circuito al error existente; probar `404`, `401`, `403`, fallas de transporte y `5xx`.
- [x] 3.2 Probar que tras N fallas el circuito se abre y una asignación válida responde `503` sin consultar MS6 ni crear o reactivar registros; probar cierre tras una llamada exitosa en semiabierto e independencia de Reservas.
- [x] 3.3 Documentar en el README el comportamiento de Usuarios y las propiedades y defaults de ambas instancias; contrastar nombres y valores documentados con la configuración efectiva.

## 4. Observabilidad y verificación integral

- [x] 4.1 Registrar cada transición de ambos circuitos en WARN sin secretos; comprobar con pruebas la identidad del circuito y los estados anterior y nuevo.
- [x] 4.2 Exponer los estados reales en `circuitbreakers` de `/actuator/health` sin detalles internos; probar por HTTP que con un circuito abierto el health general es `UP` y responde `200`, y que otro indicador `DOWN` sigue degradándolo.
- [x] 4.3 Documentar en el README la visibilidad de componentes, el healthcheck y los logs; comprobar que el Gateway sigue sin exponer Actuator.
- [x] 4.4 Ejecutar las pruebas relevantes de clientes, servicios, endpoints, outbox y health, y la compilación Maven; corregir regresiones causadas por el cambio.
