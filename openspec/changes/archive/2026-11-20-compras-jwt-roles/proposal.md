## Por qué

En la Entrega 2 la identidad deja de venir de encabezados declarados por el cliente y pasa a salir de un JWT validado por el API Gateway. El Gateway elimina los encabezados de identidad que envía el cliente y propaga `X-User-Id` (el `sub` del token) y `X-User-Roles` (lista de roles separados por coma). Un usuario puede tener varios roles.

Hoy este servicio lee un único `X-User-Role` en `UsuarioActualResolver`, y `Permisos` compara ese valor con el rol de administrador. Si no se adapta, un usuario con varios roles (por ejemplo `CLIENTE,ADMIN`) no sería reconocido como administrador, y el servicio no entendería el contrato nuevo del Gateway.

## Qué cambia

- `UsuarioActual` pasa de un rol a un conjunto de roles.
- `UsuarioActualResolver` lee `X-User-Roles` (separados por coma, sin distinguir mayúsculas ni espacios) y mantiene el comportamiento actual de `X-User-Id`.
- `Permisos` decide por pertenencia: es administrador si el conjunto incluye el rol configurado (`compras.rol-administrador`, por defecto `ADMIN`).
- Las comprobaciones de titularidad (el recurso pertenece a `X-User-Id`) no cambian.
- Se actualizan `openapi.yaml`, README e `historias-ms3.md` con el contrato nuevo.

## Fuera de alcance

- Validar la firma o la vigencia del JWT: lo hace el Gateway (Spring Security reactivo, HS256). Este servicio no recibe ni verifica el token.
- Los endpoints `/internal/**`: siguen usando `X-Service-Token` y no se exponen por el Gateway.
- Cambios en el Gateway, en Usuarios o en los otros microservicios.

## Decisiones

- **Compatibilidad:** si falta `X-User-Roles` pero llega el `X-User-Role` anterior, se lo toma como un rol único durante la transición. Se retira cuando el Gateway nuevo esté en uso en todo el grupo.
- **Confianza en los encabezados:** el servicio solo es seguro si únicamente el Gateway puede llegar a él. Por eso el puerto 8083 no se publica fuera de la red de Docker (`ticketya-net`) en la configuración de entrega.
