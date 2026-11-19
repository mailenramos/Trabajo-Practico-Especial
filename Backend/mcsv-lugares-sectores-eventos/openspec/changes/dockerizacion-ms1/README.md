# dockerizacion-ms1

Dockerizar MS1 con MongoDB replica set y red interna. El default de `MONGODB_URI` para desarrollo en host será `mongodb://localhost:27017/ticketya_lugares?directConnection=true`; Compose sobrescribe la URI para conectarse a `mongodb:27017` dentro de Docker. El Compose base no publica puertos; el override local publica solo la aplicación en `8081` y MongoDB en `127.0.0.1:27017`.
