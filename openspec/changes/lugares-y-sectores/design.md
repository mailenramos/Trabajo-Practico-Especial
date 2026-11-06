# Design

## Context

El proyecto es un servicio Spring Boot independiente. MongoDB se utiliza desde la Entrega 1 y cada lugar junto con sus sectores se almacena en un documento de la colección `lugares`. El API Gateway es el punto de entrada público y propaga `X-User-Id` y `X-User-Role`; la validación de permisos de esta capacidad corresponde al microservicio. No hay implementación ni contratos preexistentes en el directorio del servicio.

## Goals / Non-Goals

**Goals:**
- Implementar los contratos de lugares y sectores de `specs/lugares-y-sectores/spec.md`.
- Usar `spring-boot-starter-data-mongodb` y documentos jerárquicos con sectores embebidos.
- Derivar la identidad de cada asiento numerado sin persistir documentos de asiento.

**Non-Goals:**
- Eventos, búsqueda, disponibilidad de entradas y comunicación síncrona con otros microservicios.
- Definir mecanismos de autenticación del Gateway; el servicio valida los encabezados propagados.

## Decisions

- **Persistencia MongoDB desde Entrega 1.** El servicio usa `spring-boot-starter-data-mongodb`; MongoDB se levanta con Docker mediante `docker-compose`. Las pruebas unitarias mockean los repositorios. Las pruebas de integración usan Testcontainers con una instancia real de MongoDB.
- **Un documento por lugar.** La colección `lugares` almacena los datos del lugar y su arreglo `sectores`; cada sector embebido contiene `id`, `nombre`, `tipo`, `filas`, `asientosPorFila` y `capacidad`. Los valores que no corresponden al tipo de sector quedan nulos o ausentes. No se crea una colección de asientos.
- **Asientos derivados.** Para un sector numerado, se deriva el conjunto de pares `(fila, numero)` para `1 <= fila <= filas` y `1 <= numero <= asientosPorFila`. La unicidad queda garantizada por construcción al usar esos rangos acotados; no se guardan registros por asiento ni se aplica una restricción de unicidad de base de datos. El servicio de Disponibilidad y Reservas identifica cada asiento con `(sectorId, fila, numero)`.
- **Identificadores.** Todos los IDs se manejan y exponen como `String`. El `_id` de MongoDB para lugar es un ObjectId serializado como hexadecimal de 24 caracteres. Cada `sector.id` es un UUID en formato String, generado al crear el sector y luego inmutable. Las referencias de otros servicios usan los IDs String.
- **Ejemplos JSON.** Un lugar con sectores embebidos puede representarse así (los IDs se muestran como String):

```json
{
  "_id": "507f1f77bcf86cd799439011",
  "nombre": "Teatro Central",
  "direccion": "Av. Corrientes 1234",
  "ciudad": "Buenos Aires",
  "latitud": -34.6037,
  "longitud": -58.3816,
  "sectores": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "nombre": "Platea",
      "tipo": "NUMERADO",
      "filas": 20,
      "asientosPorFila": 30,
      "capacidad": null
    },
    {
      "id": "7d444840-9dc0-11d1-b245-5ffdce74fad2",
      "nombre": "Campo",
      "tipo": "NO_NUMERADO",
      "filas": null,
      "asientosPorFila": null,
      "capacidad": 500
    }
  ]
}
```

  En MongoDB, `_id` se almacena como ObjectId; en JSON se representa por su cadena hexadecimal. Las respuestas HTTP exponen el ID del lugar y los UUID de sectores como cadenas. Los ejemplos de entrada no deben usar IDs numéricos.
- **Índices de consulta.** Crear índices de MongoDB para los campos consultables con frecuencia, por ejemplo `ciudad` y `nombre` del lugar (incluyendo índice compuesto si las consultas lo justifican). Los índices no cambian las rutas ni los filtros definidos por el contrato.
- **Operaciones de un solo documento.** Cada operación modifica un único documento de lugar; no se usan transacciones multi-documento. La actualización del arreglo de sectores y los datos del lugar se realiza atómicamente sobre ese documento.
- **API REST y autorización.** Mantener las rutas, cuerpos y códigos HTTP definidos por la especificación. Exigir ambos encabezados en altas y rechazar roles que no sean administrador. La lectura de lugares y sectores queda disponible para consumidores sin privilegio administrativo.
- **Mapeo explícito de errores HTTP.** Convertir errores de validación en `400`, falta de identidad en `401`, rol insuficiente en `403`, ausencia del lugar en `404` y conflictos de escritura aplicables en `409`, evitando filtrar detalles internos de MongoDB.

## Risks / Trade-offs

- [El formato concreto para nombrar las filas no está fijado por los requisitos] → Mantener fila y número como identificadores distintos y usar índices enteros consecutivos desde 1, de acuerdo con la identidad derivada definida en este diseño.
- [Los encabezados podrían llegar alterados si la red interna no es confiable] → Confiar en ellos solo en la topología prevista con API Gateway y restringir el acceso de red al servicio según la configuración de despliegue.

## Migration Plan

No hay datos ni esquema preexistentes en el servicio. Levantar MongoDB con Docker y configurar la aplicación para conectarse a la base antes de habilitar las rutas. Como no hay migraciones relacionales ni transacciones multi-documento, el rollback consiste en volver a la versión anterior de la aplicación; los documentos pueden conservarse o eliminarse según el entorno.
