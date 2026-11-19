# Propuesta: token de servicio para outbox

## Why

MS8 y Notificaciones exigen `X-Service-Token`, pero el cliente HTTP común de outbox solo envía `X-Aviso-Id`. Sin el token, los destinos que lo exigen rechazan el aviso con `403`, error definitivo según la política actual.

## What Changes

- Enviar `X-Service-Token` en todos los POST de la outbox, con `servicio.token=${SERVICIO_TOKEN:}`, sin almacenar ni registrar el secreto.
- Con el perfil `docker`, impedir el arranque si el token está vacío. En otros perfiles, advertir al arrancar y no procesar avisos hasta que haya token, sin consumir intentos.
- Pasar `SERVICIO_TOKEN` al servicio `lugares` en Compose y documentar su configuración sin añadir secretos al repositorio.
- Permitir que el stub de Preferencias verifique opcionalmente el token cuando se configure `SERVICIO_TOKEN`; conservar su uso sin verificación cuando no se configure.
- Cubrir configuración, envío, ausencia de token, reintentos, confidencialidad en logs y regresiones de outbox/publicación.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `eventos`: autenticación de los avisos outbox y pausa del procesamiento cuando falta la configuración del token.

## Impact

- Cliente HTTP y dispatcher de outbox, configuración Spring/Compose, pruebas, stub de Preferencias y README de MS1.
- **Cambio operativo:** el perfil `docker` requiere `SERVICIO_TOKEN`; los demás perfiles no entregan avisos si falta.
- No se modifican los endpoints entrantes, su autenticación ni el Gateway. No se encontró validación entrante de `X-Service-Token` en el código actual de MS1; esa protección queda fuera de alcance y para `personal-acceso-eventos`.
