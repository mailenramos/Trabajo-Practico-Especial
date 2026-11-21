# TicketYa - Trabajo Practico Especial

## Levantar todo con Docker

El `docker-compose.yml` de la raíz incluye el compose de cada microservicio (MS1 a MS9, con Entradas y Usuarios), más el Gateway y Mailpit. Las instrucciones están al principio de ese archivo: crear la red `ticketya-net` una sola vez, copiar `.env.example` a `.env`, completar los valores y ejecutar `docker compose up -d --build`. El Gateway queda en `http://localhost:8086`.

## Inicio local completo en Windows

Esta guia permite levantar la aplicacion desde PowerShell. No hace falta instalar Maven: los proyectos Java incluyen `mvnw.cmd`, que descarga Maven y sus dependencias la primera vez. Se necesita Internet.

### Requisitos

- Docker Desktop iniciado.
- JDK 21 (necesario para MS1, MS2, MS3, MS5 y MS6; sirve tambien para MS4 y el Gateway).
- Node.js 20.19+ o 22.12+ para el frontend.
- Las bases de MS5 y MS6 se levantan con el docker-compose de cada servicio (ver abajo). Los README de cada servicio describen cómo iniciar sus bases.

Si PowerShell abre en `C:\Windows\System32`, primero cambia a la carpeta donde clonaste el repositorio. Reemplaza esta ruta por la ubicacion de tu copia:

```powershell
cd "D:\ruta\Trabajo-Practico-Especial"
```

Inicia las bases de datos antes que sus microservicios:

1. **MongoDB de MS1** (incluido en el repositorio):

   ```powershell
   cd .\Backend\mcsv-lugares-sectores-eventos
   docker compose up -d mongodb
   cd ..\..
   ```

2. **MySQL de MS2**: en una instalación nueva, levanta la base con el Compose del repositorio y las mismas credenciales que lee la aplicación:

   ```powershell
   cd .\Backend\mcsv-disponibildad-reservas
   $passwordLine = Select-String -Path .\src\main\resources\application.properties -Pattern '^spring\.datasource\.password='
   $env:MS2_DB_PASSWORD = $passwordLine.Line -replace '^spring\.datasource\.password=', ''
   docker compose up -d mysql
   cd ..\..
   ```

   Esto publica el puerto del host `3307` hacia el `3306` interno de MySQL. MS3 usa el puerto de host `3306`, por lo que ambos pueden ejecutarse a la vez. Si ya tenés un contenedor MySQL de MS2 escuchando en `3307`, dejalo en uso y no levantes Compose en ese puerto. Más detalles en [Backend/mcsv-disponibildad-reservas/README.md](Backend/mcsv-disponibildad-reservas/README.md).
3. **MySQL de MS3**: en una instalación nueva, crea un contenedor local (cambia la contraseña por una propia):

   ```powershell
   docker run --name ticketya-mysql --restart unless-stopped -e MYSQL_ROOT_PASSWORD='tu-contraseña' -e MYSQL_DATABASE=ticketya_compras -p 3306:3306 -d mysql:8.0
   ```

   En siguientes ejecuciones, usa `docker start ticketya-mysql`. La contraseña de `DB_PASSWORD` al iniciar MS3 debe coincidir con `MYSQL_ROOT_PASSWORD`. Los detalles están en [Backend/mcsv-compras-devoluciones/README.md](Backend/mcsv-compras-devoluciones/README.md).
4. **PostgreSQL de MS4 (Pagos)**: en una terminal:

   ```powershell
   cd .\Backend\mcvs-pagos
   if (-not (Test-Path .env)) { Copy-Item .env.example .env }
   docker compose up -d postgres
   ```

   El `.env.example` usa credenciales de demostracion solo para desarrollo local. Si ya hay otro PostgreSQL ocupando `5435`, no inicies otro contenedor en ese puerto; revisa [Backend/mcvs-pagos/README.md](Backend/mcvs-pagos/README.md) para configurar el servicio con la base existente.
5. **PostgreSQL de MS5 (Entradas)** y 6. **PostgreSQL de MS6 (Usuarios)**: cada uno se levanta con su propio compose. Requieren la red compartida (una sola vez) y un `.env` en cada carpeta, copiado de `.env.example`, con al menos `DB_PASSWORD`:

   ```powershell
   docker network create ticketya-net
   ```

   ```powershell
   cd .\Backend\msvc-entradas
   docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres-entradas
   cd ..\mcsv-usuarios-autenticacion
   docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres-usuarios
   cd ..\..
   ```

   Publican los puertos `5433` (Entradas) y `5436` (Usuarios) en `127.0.0.1`. Para levantar cada servicio completo en Docker (base + aplicación), ver [Backend/msvc-entradas/Documentacion/README.md](Backend/msvc-entradas/Documentacion/README.md) y [Backend/mcsv-usuarios-autenticacion/README.md](Backend/mcsv-usuarios-autenticacion/README.md).

Espera a que Docker indique que las bases están iniciadas antes de lanzar los microservicios.

### Inicia los servicios

Abre **una ventana de PowerShell por cada bloque**, desde la raiz del repositorio (o ejecuta primero el `cd` a la raiz mostrado arriba en cada ventana). Deja cada terminal abierta mientras uses la aplicacion. Inicia MS2, MS4 y MS5 antes de MS1; inicia MS3 después de sus dependencias y el Gateway al final.

**MS2 - Disponibilidad y reservas** (inícialo antes de MS1):

```powershell
cd .\Backend\mcsv-disponibildad-reservas
.\mvnw.cmd spring-boot:run
```

**MS1 - Eventos y lugares** (inícialo después de MS2; su perfil de desarrollo sincroniza con MS2 la disponibilidad de los eventos de ejemplo):

```powershell
cd .\Backend\mcsv-lugares-sectores-eventos
.\mvnw.cmd spring-boot:run
```

**MS3 - Compras y devoluciones** (inícialo después de MS2, MS4 y MS5; reemplaza la contraseña por la de tu MySQL):

```powershell
cd .\Backend\mcsv-compras-devoluciones
$env:DB_USER = 'root'
$env:DB_PASSWORD = 'tu-contraseña'
.\mvnw.cmd spring-boot:run
```

**MS4 - Pagos** (desde la carpeta `Backend\mcvs-pagos`, usando el `.env` creado antes):

```powershell
cd .\Backend\mcvs-pagos
$config = Get-Content .env | ConvertFrom-StringData
$env:JAVA_TOOL_OPTIONS = '-Duser.timezone=UTC'
$env:DB_HOST = 'localhost'
$env:DB_PORT = $config.DB_PORT
$env:DB_NAME = $config.DB_NAME
$env:DB_USER = $config.DB_USER
$env:DB_PASSWORD = $config.DB_PASSWORD
.\mvnw.cmd spring-boot:run
```

Si Pagos ya usa una base existente en `localhost:5435`, configura `DB_PASSWORD` con la contraseña real de esa base en vez de copiar las credenciales de ejemplo.

**MS5 - Entradas** (`DB_PASSWORD` es la del `.env` de su carpeta):

```powershell
cd .\Backend\msvc-entradas
$env:DB_PASSWORD = '<la contraseña del .env>'
.\mvnw.cmd spring-boot:run
```

**MS6 - Usuarios y autenticación** (emite los JWT; `JWT_SECRET` debe ser el mismo valor en MS6 y en el Gateway, de al menos 32 caracteres; `ADMIN_EMAIL` y `ADMIN_PASSWORD` crean el administrador inicial y son obligatorias; `DB_PASSWORD` es la del `.env` de su carpeta):

```powershell
cd .\Backend\mcsv-usuarios-autenticacion
$env:DB_PASSWORD = '<la contraseña del .env>'
$env:JWT_SECRET = '<clave compartida con el Gateway, al menos 32 caracteres>'
$env:ADMIN_EMAIL = 'admin@ticketya.local'
$env:ADMIN_PASSWORD = '<contraseña con 8+ caracteres, letras y números>'
.\mvnw.cmd spring-boot:run
```

En desarrollo, MS6 crea un usuario de prueba por rol (`cliente@ticketya.test`, `organizador@ticketya.test`, `admin@ticketya.test` y `personal@ticketya.test`, contraseña `Prueba123`). Para obtener un token: `POST http://localhost:8086/auth/login` con `{"correo": "...", "password": "..."}`.

**API Gateway** (con el mismo `JWT_SECRET` que MS6):

```powershell
cd .\Backend\APIGateWay
$env:JWT_SECRET = '<la misma clave que en MS6>'
.\mvnw.cmd spring-boot:run
```

El Gateway escucha en `http://localhost:8086`.

### Abre el frontend

Abre otra PowerShell y ve a la carpeta del frontend:

```powershell
cd .\Frontend
```

La primera vez que se descarga el proyecto, instala las dependencias de Node.js (no instala Node.js):

```powershell
npm ci
```

Luego inicia Vite:

```powershell
npm run dev
```

Deja esa terminal abierta y abre `http://localhost:5173` en el navegador. En siguientes ejecuciones, con Node.js ya instalado y las dependencias descargadas, alcanza con `npm run dev`. El frontend usa el Gateway en `http://localhost:8086`.

### Puertos locales

| Componente | Puerto |
|---|---:|
| MS1 - Eventos y lugares | 8081 |
| MS2 - Disponibilidad y reservas | 8080 |
| MS3 - Compras y devoluciones | 8083 |
| MS4 - Pagos | 8084 |
| MS5 - Entradas | 8085 |
| API Gateway | 8086 |
| MS6 - Usuarios y autenticación | 8087 |
| Frontend | 5173 |
| MongoDB de MS1 | 27017 |
| MySQL de MS2 | 3307 |
| MySQL de MS3 | 3306 |
| PostgreSQL de MS5 | 5433 |
| PostgreSQL de MS4 | 5435 |
| PostgreSQL de MS6 | 5436 |

Si un puerto ya esta ocupado, detén el proceso o contenedor que lo usa antes de iniciar el servicio. No elimines volúmenes de Docker para resolver problemas de conexión: pueden contener datos persistentes.

## Interfaz grafica

La interfaz de cliente esta desarrollada con Vue 3 y Vite. Permite buscar eventos, consultar sectores y disponibilidad, reservar lugares, iniciar compras, probar el pago simulado y consultar las entradas emitidas. El pago es una simulacion local de MS4; no realiza cobros reales.

Para instrucciones del flujo de usuario, consultar [Frontend/README.md](Frontend/README.md). Para ejecutar cada microservicio por separado y conocer su configuracion, consultar los README dentro de `Backend`.
