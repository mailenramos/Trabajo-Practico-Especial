# Tareas

- [x] 1. Configurar Spring Cache y Caffeine con TTL `30s` y máximo `1000` configurables y validados.
- [x] 2. Cachear únicamente la búsqueda pública con todos los parámetros efectivos en la clave, preservando validaciones y sin cachear errores.
- [x] 3. Centralizar la invalidación y conectarla tras las escrituras indicadas y después del commit transaccional; dejar el componente disponible para futuras modificaciones.
- [x] 4. Probar caché, claves, TTL, tamaño, exclusiones, invalidación y rollback con pruebas aisladas y MongoDB de Testcontainers, nunca MongoDB local.
- [x] 5. Actualizar README y ejecutar `mvnw.cmd test`, `openspec validate --strict` y `git diff --check`.
