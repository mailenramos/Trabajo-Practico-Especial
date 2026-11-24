# API Gateway: rutas de reportes de MS3 (Compras y devoluciones)

MS3 expone los cuatro reportes para organizadores (cambio OpenSpec `reportes-organizadores`). Este
fragmento ya está aplicado en `Backend/APIGateWay` (ruta `ms3-reportes` y matcher de seguridad, con
tests en `ApiGatewayIntegrationTests`); se conserva como referencia de lo que se agregó.

## Endpoints de MS3

| Método | Ruta | Acceso |
|---|---|---|
| GET | `/reportes/eventos/{eventoId}/ventas` | ORGANIZADOR titular o ADMIN |
| GET | `/reportes/eventos/{eventoId}/devoluciones` | ORGANIZADOR titular o ADMIN |
| GET | `/reportes/eventos/{eventoId}/recaudacion` | ORGANIZADOR titular o ADMIN |
| GET | `/reportes/eventos/{eventoId}/ocupacion` | ORGANIZADOR titular o ADMIN |

El Gateway solo filtra por rol. La titularidad (que el `organizadorId` del evento sea el `sub` del
JWT) la verifica MS3 contra Eventos y responde 403 `EVENTO_AJENO`; un usuario sin ORGANIZADOR ni
ADMIN que llegue igual recibe 403 `SIN_PERMISO_REPORTES`.

## 1. Ruta (`src/main/resources/application.yml`)

Agregar junto a las demás rutas `ms3-*` (por ejemplo, después de `ms3-reintegro-reintentar`).
`{reporte}` es un solo segmento: `/reportes/eventos/{eventoId}/ventas/otra-cosa` no se enruta.

```yaml
            - id: ms3-reportes
              uri: ${gateway.services.ms3-url:${MS3_URL:http://localhost:8083}}
              predicates:
                - Path=/reportes/eventos/{eventoId}/{reporte}
                - Method=GET
```

## 2. Seguridad (`GatewaySecurityConfiguration`)

Agregar en el bloque de MS3, antes de `.anyExchange().permitAll()`:

```java
                        // MS3 - Reportes para organizadores (la titularidad del evento la valida MS3)
                        .pathMatchers(HttpMethod.GET, "/reportes/eventos/*/*")
                        .hasAnyRole("ORGANIZADOR", "ADMIN")
```

`*` cubre un solo segmento, igual que el predicado de la ruta.

## Cómo verificarlo

Con el sistema levantado y los usuarios de prueba (`USUARIOS_DATOS_PRUEBA=true`):

- Organizador titular: `GET {{gatewayUrl}}/reportes/eventos/{eventoId}/ventas` con su token → 200.
- ADMIN, cualquier evento → 200.
- CLIENTE → 403 del Gateway (`ACCESO_DENEGADO`).
- Sin token → 401 (`NO_AUTENTICADO`).
- Organizador que no es el titular → 403 `EVENTO_AJENO` (lo responde MS3).

La colección de Postman de MS3 (`Backend/mcsv-compras-devoluciones/postman`) trae estos casos en la
carpeta "07 - Reportes (organizador y admin)".
