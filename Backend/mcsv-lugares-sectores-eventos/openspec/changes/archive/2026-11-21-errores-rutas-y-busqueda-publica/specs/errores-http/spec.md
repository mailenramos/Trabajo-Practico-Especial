# Spec Delta

## Purpose

Definir respuestas HTTP de error coherentes para rutas y contenido no admitidos en MS1.

## ADDED Requirements

### Requirement: Responder errores de rutas y contenido con el formato común
MS1 SHALL responder los errores de recurso inexistente, método no permitido y tipo de contenido no soportado con su código HTTP correspondiente y el formato JSON común.

#### Scenario: Ruta inexistente
- **GIVEN** una ruta sin recurso ni endpoint correspondiente
- **WHEN** se solicita `GET /ruta-que-no-existe`
- **THEN** responde `404 Not Found` con JSON que contiene `timestamp`, `status: 404`, `error` y `mensaje`, y omite `campos` si no aplica

#### Scenario: Método no permitido
- **GIVEN** una ruta existente que no admite el método solicitado
- **WHEN** se envía la solicitud con ese método
- **THEN** responde `405 Method Not Allowed` con JSON que contiene `timestamp`, `status: 405`, `error` y `mensaje`, y omite `campos` si no aplica

#### Scenario: Tipo de contenido no soportado
- **GIVEN** un endpoint existente con restricciones de tipo de contenido
- **WHEN** se envía un cuerpo con un `Content-Type` no soportado
- **THEN** responde `415 Unsupported Media Type` con JSON que contiene `timestamp`, `status: 415`, `error` y `mensaje`, y omite `campos` si no aplica

### Requirement: Conservar correlación en errores HTTP
MS1 SHALL incluir `X-Correlation-Id` en las respuestas de error de ruta, método y contenido.

#### Scenario: Correlación recibida en errores HTTP
- **GIVEN** una solicitud con un `X-Correlation-Id` válido que produce un `404`, `405` o `415`
- **WHEN** MS1 responde
- **THEN** devuelve ese mismo identificador en `X-Correlation-Id`

#### Scenario: Correlación generada en errores HTTP
- **GIVEN** una solicitud sin `X-Correlation-Id` que produce un `404`, `405` o `415`
- **WHEN** MS1 responde
- **THEN** devuelve un UUID generado en `X-Correlation-Id`
