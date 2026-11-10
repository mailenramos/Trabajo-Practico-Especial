## Why

El API Gateway ya cuenta con pruebas automatizadas contra servicios HTTP falsos, pero todavía falta una forma sencilla y repetible de comprobarlo con los microservicios locales reales. El equipo necesita validar desde Postman que las rutas públicas llegan al servicio correcto y que los flujos principales funcionan a través del puerto `8086`, además de distinguir fallas del Gateway de fallas o datos faltantes en los servicios dependientes.

## What Changes

- Completar la cobertura automatizada del Gateway, separando las pruebas unitarias de las pruebas de integración con backends falsos.
- Agregar una colección de Postman y un environment local para verificar rutas públicas, propagación de identidad, consulta de eventos, reserva y recorrido de compra/consulta de entradas cuando los servicios y datos necesarios estén disponibles.
- Documentar el orden de arranque, las precondiciones, las respuestas esperadas y el diagnóstico de errores para ejecutar las pruebas manuales en Windows.
- Mantener una lista explícita de rutas internas/no publicadas y comprobar que las solicitudes de prueba no alcanzan los servicios.

## Capabilities

### New Capabilities

- `verificacion-api-gateway`: procedimientos y artefactos automatizados y manuales para verificar el enrutamiento del API Gateway de TicketYa.

### Modified Capabilities

Ninguna.

## Fuera de alcance

- Cambiar rutas públicas, contratos, reglas de negocio o autorización de los microservicios.
- Publicar rutas internas o el servicio de Pagos para facilitar las pruebas.
- Agregar un endpoint de health check, autenticación, JWT o una interfaz gráfica.
- Reemplazar las pruebas automatizadas existentes por llamadas a servicios reales; las pruebas normales deben seguir ejecutándose sin levantar toda la plataforma.
- Crear datos de negocio persistentes automáticamente al importar la colección. Los requests que crean eventos, reservas o compras se ejecutarán de forma explícita con datos configurados para un entorno local.
- Probar CORS con Postman: Postman no aplica las restricciones CORS del navegador; CORS se verificará automatizadamente y, si hace falta, desde un navegador.
- Incorporar Newman o cambiar la pipeline CI en esta propuesta.

## Impact

- Pruebas y documentación del proyecto `Backend/APIGateWay`.
- Nueva colección y environment de Postman sin secretos, en una carpeta `Backend/APIGateWay/postman/`.
- Las pruebas unitarias e integración usan backends falsos y no requieren que MS1–MS5 estén ejecutándose.
- La validación manual de compra completa requiere los servicios involucrados, sus dependencias documentadas y datos válidos; MS4 sigue siendo interno y no se invoca a través del Gateway.
