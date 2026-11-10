## Contexto

`Backend/APIGateWay/api.java` solo contiene una anotación de que el Gateway se incorporará más adelante. Los servicios ya documentan las APIs públicas y locales. El contrato de Eventos/Lugares especifica que sus rutas públicas se reenvían sin cambios y que los endpoints internos deben excluirse. MS2 expone públicamente la creación de reservas; MS3 expone compras y devoluciones; MS5 expone consultas de entradas.

La Entrega 1 no tiene autenticación. Los servicios reciben `X-User-Id` y, donde aplica, `X-User-Role`; esos valores identifican al usuario de prueba y los servicios realizan sus validaciones. Este Gateway no debe presentarse como una barrera de autenticación.

## Objetivos y no objetivos

**Objetivos:**

- Proveer un único origen HTTP local para las APIs públicas.
- Conservar los contratos existentes: método, path, parámetros de consulta, cuerpo, `Content-Type`, clave de idempotencia y headers de identidad.
- Enrutar por una lista explícita de rutas y métodos permitidos.
- Impedir que el Gateway se convierta en proxy abierto a endpoints internos.
- Permitir que un navegador en el origen local del frontend consuma la API con CORS configurable.

**No objetivos:**

- Autenticar usuarios, validar JWT ni confiar en un rol proporcionado por un cliente como prueba de identidad.
- Mediar tráfico entre servicios: las integraciones servicio-a-servicio siguen usando sus URLs directas.
- Unificar o transformar DTOs, errores, códigos HTTP o contratos.
- Exponer la API de Pagos o agregar funcionalidades del frontend.

## Rutas y destinos

La primera versión conserva los paths públicos backend, sin prefijo extra ni `/api/v1`, y escucha en el puerto `8086`. Cada ruta se registra explícitamente con los métodos documentados; una coincidencia más amplia no debe publicar rutas vecinas por accidente.

| Servicio | Destino local configurable | Rutas públicas a enrutar |
|---|---|---|
| Eventos/Lugares (MS1) | `http://localhost:8081` | Las rutas marcadas como públicas en `Backend/mcsv-lugares-sectores-eventos/docs/api-gateway-contract.md`: operaciones públicas de `/eventos` y `/lugares` para consulta, organizadores y administración. El path se reenvía sin cambios. |
| Disponibilidad/Reservas (MS2) | `http://localhost:8080` | `POST /disponibilidad/eventos/{eventoId}/reservas`. No enrutar la consulta de disponibilidad ni las operaciones de consulta, confirmación o liberación de reservas, que son internas. |
| Compras/Devoluciones (MS3) | `http://localhost:8083` | `POST /compras`, `GET /compras`, `GET /compras/{compraId}`, `POST /compras/{compraId}/pago`, `POST /devoluciones`, `GET /devoluciones`, `GET /devoluciones/{devolucionId}`, `POST /devoluciones/{devolucionId}/aprobacion`, `POST /devoluciones/{devolucionId}/rechazo`, `GET /reintegros` y `POST /reintegros/{reintegroId}/reintento`. Las operaciones administrativas solo pueden ejecutarse si el servicio valida el rol. `POST /eventos-cancelados` queda excluida por ser callback interno. |
| Entradas (MS5) | `http://localhost:8085` | `GET /entradas` y `GET /entradas/{id}`. |

**Rutas siempre excluidas:** cualquier `/internal/**`; `PUT /eventos/{eventoId}/disponibilidad`; `GET /disponibilidad/eventos/{eventoId}` y operaciones internas bajo `/disponibilidad/reservas/**`; `POST /eventos-cancelados`; emisión y administración interna de entradas; cualquier ruta de Pagos. La lista se mantiene explícita, no se implementa un proxy genérico por host o path.

La lista de MS3 corresponde a las rutas documentadas en su OpenAPI vigente. No se deben deducir permisos nuevos ni crear endpoints; el Gateway solo enruta y MS3 conserva la autorización de cada operación.

## Decisiones

1. **Spring Boot + Spring Cloud Gateway.** Es consistente con la arquitectura de la entrega y permite definir rutas, filtros y CORS en la aplicación. `Backend/APIGateWay` tendrá su propio build y configuración. Las versiones compatibles de Java, Spring Boot y Spring Cloud se elegirán al crear el build; no se necesita otro acuerdo del equipo para preparar esta propuesta.
2. **Paths conservados sin prefijo.** Acuerdo confirmado: no agregar `/api/v1`; el Gateway retransmite el path que los servicios ya publican.
3. **Rutas con allowlist.** Se declara método y patrón por operación pública, con destinos configurables. No se usa un fallback que reenvíe paths desconocidos a los servicios.
4. **Identidad en Entrega 1.** El Gateway retransmite `X-User-Id` y `X-User-Role` recibidos, sin autenticar, generar una identidad por defecto ni permitir que una futura política JWT se simule ahora. Cada backend sigue respondiendo sus errores de identidad/autorización. La posibilidad de modificar esos headers desde el cliente es una limitación conocida de la entrega sin autenticación.
5. **CORS.** Se habilitan los métodos y headers requeridos por los contratos (incluidos `Content-Type`, `X-User-Id`, `X-User-Role` e `Idempotency-Key`) solo para orígenes configurados. Se propone `http://localhost:5173` como origen local inicial, puerto predeterminado habitual de Vite; si el frontend usa otro origen, se cambia por configuración. No se combina `allowCredentials` con origen comodín.
6. **Errores y respuestas.** Se preservan las respuestas HTTP y cuerpos de los servicios. Si un destino no está disponible, el Gateway responde con error de servicio no disponible (503) en un formato documentado por el Gateway, sin transformar errores de negocio recibidos.
7. **Configuración local.** Puerto del Gateway y URLs de MS1, MS2, MS3 y MS5 se externalizan. Defaults locales: Gateway `8086` (acordado), frontend `http://localhost:5173` (propuesta configurable), y servicios en los puertos de la tabla. No se requiere Docker Compose en este cambio.

## Pruebas y verificación

- Pruebas con servidores backend falsos que confirmen destino, método, path, query, body, `Content-Type`, headers de identidad y `Idempotency-Key`.
- Prueba de cada patrón público y método admitido, incluida la separación de rutas que comparten prefijo.
- Pruebas negativas para rutas internas, métodos no permitidos y rutas desconocidas: el Gateway no debe alcanzar un destino backend.
- Prueba CORS de preflight para un origen permitido y rechazo/no concesión para uno no configurado.
- Prueba del comportamiento cuando un destino está caído, verificando respuesta 503.
- Prueba manual local del flujo público de lectura y reserva; el flujo de compra requiere que estén disponibles los servicios dependientes de MS3.

## Riesgos y preguntas abiertas

- **Identidad suplantable:** sin autenticación, el usuario puede proporcionar headers de identidad. Es una limitación aceptada por Entrega 1 y debe quedar documentada; no se debe reutilizar esta configuración como seguridad de producción.
- **Origen del frontend:** se propone `http://localhost:5173`; se conserva configurable para ajustarlo si el equipo usa otro puerto.
- **Versiones de plataforma:** seleccionar un conjunto compatible de JDK/Spring Boot/Spring Cloud al crear el build, sin modificar los builds de los microservicios ni introducir una dependencia de decisión grupal.
