package com.ticketya.gestionlugares.api;

/** Ejemplos JSON reutilizados en la documentaciÃ³n OpenAPI. */
public final class ApiExamples {

    private ApiExamples() { }

    public static final String EVENTO_NUEVO = """
            {"id":"672a1f77bcf86cd799439011","organizadorId":"organizador-1","lugarId":"507f1f77bcf86cd799439011","ciudad":"Buenos Aires","nombre":"Festival Central","descripcion":"Música en vivo","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"borrador","ventaSuspendida":false,"limiteEntradasPorCliente":4,"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null,"precio":null}],"precioMinimo":null,"version":0}
            """;

    public static final String EVENTO_BORRADOR = """
            {"id":"672a1f77bcf86cd799439011","organizadorId":"organizador-1","lugarId":"507f1f77bcf86cd799439011","ciudad":"Buenos Aires","nombre":"Festival Central","descripcion":"MÃºsica en vivo","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"borrador","ventaSuspendida":false,"limiteEntradasPorCliente":4,"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null,"precio":15000.00}],"precioMinimo":null,"version":0}
            """;

    public static final String EVENTO_PUBLICADO = """
            {"id":"672a1f77bcf86cd799439011","organizadorId":"organizador-1","lugarId":"507f1f77bcf86cd799439011","ciudad":"Buenos Aires","nombre":"Festival Central","descripcion":"MÃºsica en vivo","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"publicado","ventaSuspendida":false,"limiteEntradasPorCliente":4,"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null,"precio":15000.00}],"precioMinimo":15000.00,"version":1}
            """;

    public static final String EVENTO_CON_LIMITE = """
            {"id":"672a1f77bcf86cd799439011","organizadorId":"organizador-1","lugarId":"507f1f77bcf86cd799439011","ciudad":"Buenos Aires","nombre":"Festival Central","descripcion":"Música en vivo","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"borrador","ventaSuspendida":false,"limiteEntradasPorCliente":8,"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null,"precio":15000.00}],"precioMinimo":null,"version":1}
            """;

    public static final String EVENTO_VENTA_SUSPENDIDA = """
            {"id":"672a1f77bcf86cd799439011","organizadorId":"organizador-1","lugarId":"507f1f77bcf86cd799439011","ciudad":"Buenos Aires","nombre":"Festival Central","descripcion":"MÃºsica en vivo","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"publicado","ventaSuspendida":true,"limiteEntradasPorCliente":4,"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null,"precio":15000.00}],"precioMinimo":15000.00,"version":2}
            """;

    public static final String EVENTO_CANCELADO = """
            {"id":"672a1f77bcf86cd799439011","organizadorId":"organizador-1","lugarId":"507f1f77bcf86cd799439011","ciudad":"Buenos Aires","nombre":"Festival Central","descripcion":"MÃºsica en vivo","tipo":"MUSICA","participantes":["Artista A"],"fechaHora":"2027-05-16T00:00:00Z","estado":"cancelado","ventaSuspendida":false,"limiteEntradasPorCliente":4,"sectores":[{"sectorId":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null,"precio":15000.00}],"precioMinimo":15000.00,"version":2}
            """;

    public static final String ERROR = """
            {"timestamp":"2026-11-07T12:00:00Z","status":400,"error":"Bad Request","mensaje":"Solicitud invÃ¡lida"}
            """;
}
