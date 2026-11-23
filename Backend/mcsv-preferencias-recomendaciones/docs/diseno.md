# Diseño de MS8: preferencias y recomendaciones

## Módulos e integraciones

```mermaid
flowchart LR
    Cliente -->|"JWT: /preferencias/me y /recomendaciones/me"| Gateway["API Gateway :8086"]
    Gateway -->|"X-User-Id, X-User-Roles"| API["MS8: API e interceptores"]
    API --> Preferencias["PreferenciasService"]
    API --> Recomendaciones["RecomendacionesService"]
    API --> Avisos["AvisoEventosService"]
    Preferencias --> Repo["PreferenciasRepository"]
    Avisos --> Repo
    Repo --> Mongo[("MongoDB: preferencias")]
    Recomendaciones --> Preferencias
    Recomendaciones -->|"Catálogo publicado, paginado"| MS1["MS1: eventos"]
    Recomendaciones -->|"Historial y co-compras, X-Service-Token"| Compras["Compras"]
    MS1 -->|"Evento publicado, X-Service-Token; sin Gateway"| API
    Avisos -->|"Avisos, X-Service-Token"| Notificaciones["Notificaciones"]
```

El Gateway valida el JWT y autoriza el rol `CLIENTE`. Las llamadas
`/internal/**` son entre servicios y no pasan por el Gateway. MS8 confía en
los headers de identidad inyectados por el Gateway, no valida JWT; el acceso
directo debe limitarse a redes confiables. Si Compras falla se usan solo las
preferencias; si falla el catálogo de MS1, la consulta responde `503`.

## Modelo de datos MongoDB

La única clase `@Document` de MS8 es `Preferencia`, en la colección
`preferencias`. Se almacena un documento por usuario; las listas están
embebidas y no son colecciones independientes. El ID del usuario referencia
externamente a MS6, sin relación ni clave foránea MongoDB.

```mermaid
erDiagram
    PREFERENCIAS {
        string _id PK "usuarioId"
        string_array tiposEvento "tipos favoritos"
        string_array artistas "artistas favoritos"
        string_array ciudades "ciudades preferidas"
        datetime actualizadoEn "Instant"
    }
```

MS8 no persiste eventos, compras ni avisos enviados: los consulta o entrega a
los servicios responsables.
