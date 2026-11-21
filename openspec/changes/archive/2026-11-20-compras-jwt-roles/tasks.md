## 1. Modelo e identidad
- [x] 1.1 Buscar todos los usos de `usuario.rol()` y `UsuarioActual` (main y tests) y listarlos en el commit o en el resumen
- [x] 1.2 `UsuarioActual` con conjunto de roles normalizado (mayúsculas, sin espacios ni vacíos) + test unitario
- [x] 1.3 `UsuarioActualResolver`: leer `X-User-Roles`, con `X-User-Role` como respaldo y prioridad del nuevo + tests de los seis escenarios

## 2. Permisos
- [x] 2.1 `Permisos.esAdministrador` y `exigirAdministrador` por pertenencia al conjunto + tests (varios roles, un rol, rol configurado distinto)
- [x] 2.2 Reemplazar los usos restantes de `rol()` y actualizar los tests existentes

## 3. Regresión
- [x] 3.1 Tests web: titular con varios roles, administrador con varios roles y usuario sin rol de administrador en una operación de administrador
- [x] 3.2 Correr la suite completa y confirmar que no cambia ningún código de respuesta existente

## 4. Documentación
- [x] 4.1 Actualizar `openapi.yaml` (encabezado `X-User-Roles`, marcar `X-User-Role` como obsoleto)
- [x] 4.2 Actualizar README e `historias-ms3.md` (contrato de identidad, compatibilidad, puerto 8083 no publicado en la configuración de entrega)
