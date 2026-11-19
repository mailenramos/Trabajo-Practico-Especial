# Proposal

## Why

MS1 actualmente se ejecuta en el host y su Compose solo levanta MongoDB, publica Mongo en el host y anuncia el miembro de `rs0` como `localhost:27017`. Para desplegar el servicio de forma reproducible en Docker se necesita contenerizar la aplicación y MongoDB, conservar las transacciones del replica set y mantener el acceso externo limitado a la red interna.

## What Changes

- Agregar un Dockerfile multi-etapa con build Maven/JDK 21 y runtime JRE 21, ejecutado como usuario no root.
- Extender el Compose de MS1 para levantar la aplicación y MongoDB `rs0` de un solo nodo, con volumen persistente, inicialización automática, miembro `mongodb:27017` y healthchecks, sin publicar puertos en el Compose base.
- Configurar la aplicación en el contenedor mediante las variables de entorno existentes; usar un perfil no `dev` por defecto y nombres DNS de servicios Docker para MongoDB, Reservas y Compras.
- Cambiar el default local de `MONGODB_URI` a `mongodb://localhost:27017/ticketya_lugares?directConnection=true`; el Compose define la URI interna con `replicaSet=rs0`.
- Mantener la aplicación y sus endpoints internos solo en redes Docker. Un override opcional publica `8081` y `127.0.0.1:27017:27017` para desarrollo.
- Documentar el uso aislado, la integración con el Compose general y la conexión desde el host al replica set.
- No cambiar el comportamiento funcional de MS1 ni el Compose general de los otros servicios.

## Capabilities

### New Capabilities

- `despliegue-contenedores`: construcción, ejecución, salud y exposición de MS1 y su MongoDB en Docker.

### Modified Capabilities

Ninguna.

## Impact

Afecta el despliegue de MS1, `application.yml`, su Dockerfile, Compose local, override y README. Actuator ya forma parte de las dependencias; se reutilizará `/actuator/health` sin detalles para el healthcheck de la aplicación. La configuración actual de Compose anuncia el replica set como `localhost:27017`; para usar datos existentes se documentará su eliminación explícita con `docker compose down -v`, advirtiendo que borra el volumen y sus datos.

Los nombres Docker de los otros microservicios quedan pendientes de confirmar con el grupo, pero no bloquean la implementación: se proponen los nombres `reservas` y `compras` como aliases/servicios. No se modificarán archivos Compose de otros servicios.
