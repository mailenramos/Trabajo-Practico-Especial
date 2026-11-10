## Why

La Entrega 1 requiere que el cliente acceda a las capacidades públicas del sistema a través de un API Gateway. En el repositorio, `Backend/APIGateWay/api.java` es solo un placeholder: no existe una aplicación ejecutable que enrute solicitudes. Sin esa pieza no se puede integrar el frontend con los microservicios de forma consistente ni demostrar el recorrido de compra a través de la arquitectura prevista.

## What Changes

- Implementar en `Backend/APIGateWay` una aplicación ejecutable de API Gateway para la Entrega 1, usando Spring Boot y Spring Cloud Gateway.
- Publicar únicamente las rutas HTTP públicas acordadas por Eventos/Lugares, Disponibilidad/Reservas, Compras/Devoluciones y Entradas, reenviando método, path, query, body y headers necesarios sin cambiar los contratos de los servicios.
- Mantener las rutas backend sin prefijo adicional: no agregar `/api/v1`.
- Escuchar en el puerto `8086` y propagar `X-User-Id` y `X-User-Role` cuando estén presentes. En esta entrega el Gateway no autentica ni crea identidades: la identificación de prueba sigue el mecanismo acordado para Entrega 1 y cada servicio conserva sus validaciones y permisos.
- Configurar las URL de los servicios por propiedades/variables de entorno, con valores locales para desarrollo, y CORS configurable para el frontend local. Se propone `http://localhost:5173` como origen inicial de desarrollo.
- Mantener fuera del Gateway las rutas internas de comunicación entre microservicios, incluidas las rutas `/internal/**`, callbacks y operaciones de Reservas usadas por Eventos o Compras.
- Agregar pruebas que verifiquen el enrutamiento y la exclusión de rutas internas.

## Capabilities

### New Capabilities

- `api-gateway`: enrutamiento de las APIs públicas de TicketYa para la Entrega 1, propagación de identidad de desarrollo y acceso desde el frontend local.

### Modified Capabilities

Ninguna.

## Fuera de alcance

- Autenticación, emisión o validación de JWT, login real, gestión de usuarios o autorización centralizada: corresponden a la Entrega 2.
- Cambios en contratos, reglas de negocio, persistencia o endpoints de los microservicios.
- Enrutar el microservicio de Pagos (MS4), cuyas rutas son internas para Compras.
- Frontend, datos de demostración, Docker Compose completo, despliegue en contenedores, observabilidad centralizada, caché o resiliencia avanzada.
- Agregar rutas no documentadas o exponer operaciones internas solo para facilitar pruebas.
- Fijar ahora las versiones de Java, Spring Boot o Spring Cloud; se elegirán al crear el build, manteniendo compatibilidad entre ellas.

## Servicios externos

- Eventos y Lugares (MS1), local por defecto en `http://localhost:8081`.
- Disponibilidad y Reservas (MS2), local por defecto en `http://localhost:8080`.
- Compras y Devoluciones (MS3), local por defecto en `http://localhost:8083`.
- Entradas (MS5), local por defecto en `http://localhost:8085`.
- El microservicio de Pagos (MS4) no es destino del Gateway en este cambio.

## Impact

- Código y configuración: `Backend/APIGateWay`, que debe pasar de un archivo placeholder a un proyecto ejecutable con rutas y configuración externa.
- Pruebas: cobertura de rutas públicas, preservación de la solicitud, CORS local y rechazo/no publicación de rutas internas.
- Documentación: instrucciones para iniciar el Gateway, tabla de rutas, variables de entorno y límites de seguridad de la Entrega 1.
- Sin cambios en los servicios, sus bases de datos ni sus APIs.
