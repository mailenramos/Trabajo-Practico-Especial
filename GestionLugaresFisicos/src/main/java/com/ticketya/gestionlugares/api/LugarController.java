package com.ticketya.gestionlugares.api;

import java.util.List;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticketya.gestionlugares.domain.Lugar;
import com.ticketya.gestionlugares.domain.Sector;
import com.ticketya.gestionlugares.service.ForbiddenException;
import com.ticketya.gestionlugares.service.LugarService;
import com.ticketya.gestionlugares.service.UnauthorizedException;

@RestController
@RequestMapping("/lugares")
@Tag(name = "Lugares y sectores", description = "Registro y consulta de lugares físicos y sus sectores.")
@ApiResponse(responseCode = "500", description = "Error interno",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
public class LugarController {

    private final LugarService lugarService;

    public LugarController(LugarService lugarService) {
        this.lugarService = lugarService;
    }

    @PostMapping
    @Operation(summary = "Registrar un lugar", description = "Crea un lugar con dirección y coordenadas geográficas. Requiere X-User-Role: ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Lugar creado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Lugar.class),
                            examples = @ExampleObject(name = "Lugar creado", value = """
                                    {"id":"507f1f77bcf86cd799439011","nombre":"Teatro Central","direccion":"Av. Corrientes 1234","ciudad":"Buenos Aires","latitud":-34.6037,"longitud":-58.3816,"sectores":[],"version":0}
                                    """))),
            @ApiResponse(responseCode = "400", description = "Campos obligatorios vacíos o coordenadas fuera de rango",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "Coordenada inválida", value = """
                                    {"timestamp":"2026-11-07T12:00:00Z","status":400,"error":"Bad Request","mensaje":"latitud: must be less than or equal to 90.0"}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Falta X-User-Id o X-User-Role",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":401,\"error\":\"Unauthorized\",\"mensaje\":\"Se requieren X-User-Id y X-User-Role\"}"))),
            @ApiResponse(responseCode = "403", description = "Se requiere X-User-Role: ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":403,\"error\":\"Forbidden\",\"mensaje\":\"Solo un administrador puede gestionar lugares y sectores\"}"))),
            @ApiResponse(responseCode = "409", description = "Conflicto de persistencia",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":409,\"error\":\"Conflict\",\"mensaje\":\"El recurso cambió en paralelo; vuelva a intentar la operación\"}")))
    })
    public ResponseEntity<Lugar> crearLugar(
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true,
                    description = "Identificador no vacío del usuario que realiza la solicitud.", example = "admin-1")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    description = "Enviar ADMIN (sin distinguir mayúsculas; se recortan espacios).",
                    example = "ADMIN", schema = @Schema(allowableValues = {"ADMIN"}))
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Datos del lugar. La latitud debe estar entre -90 y 90, y la longitud entre -180 y 180.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreateLugarRequest.class),
                            examples = @ExampleObject(name = "Lugar", value = """
                                    {"nombre":"Teatro Central","direccion":"Av. Corrientes 1234","ciudad":"Buenos Aires","latitud":-34.6037,"longitud":-58.3816}
                                    """)))
            @Valid @RequestBody CreateLugarRequest request) {
        validarAdministrador(userId, userRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(lugarService.crearLugar(request));
    }

    @GetMapping
    @Operation(summary = "Listar lugares", description = "Devuelve todos los lugares. Requiere X-User-Role: ORGANIZADOR o ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Colección de lugares, posiblemente vacía",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = Lugar.class)),
                            examples = @ExampleObject(name = "Lista vacía", value = "[]"))),
            @ApiResponse(responseCode = "401", description = "Falta X-User-Id o X-User-Role",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":401,\"error\":\"Unauthorized\",\"mensaje\":\"Se requieren X-User-Id y X-User-Role\"}"))),
            @ApiResponse(responseCode = "403", description = "Se requiere X-User-Role: ORGANIZADOR o ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":403,\"error\":\"Forbidden\",\"mensaje\":\"Solo un organizador o administrador puede consultar lugares y sectores\"}")))
    })
    public List<Lugar> listarLugares(
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true,
                    description = "Identificador no vacío del usuario.", example = "organizador-1")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    description = "Admite ORGANIZADOR o ADMIN, sin distinguir mayúsculas y recortando espacios.",
                    example = "ORGANIZADOR", schema = @Schema(allowableValues = {"ORGANIZADOR", "ADMIN"}))
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        validarLector(userId, userRole);
        return lugarService.listarLugares();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar lugar", description = "Devuelve un lugar con sus sectores. Requiere X-User-Role: ORGANIZADOR o ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lugar encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Lugar.class),
                            examples = @ExampleObject(name = "Lugar con sectores", value = """
                                    {"id":"507f1f77bcf86cd799439011","nombre":"Teatro Central","direccion":"Av. Corrientes 1234","ciudad":"Buenos Aires","latitud":-34.6037,"longitud":-58.3816,"sectores":[{"id":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null}],"version":0}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Falta X-User-Id o X-User-Role",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":401,\"error\":\"Unauthorized\",\"mensaje\":\"Se requieren X-User-Id y X-User-Role\"}"))),
            @ApiResponse(responseCode = "403", description = "Se requiere X-User-Role: ORGANIZADOR o ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":403,\"error\":\"Forbidden\",\"mensaje\":\"Solo un organizador o administrador puede consultar lugares y sectores\"}"))),
            @ApiResponse(responseCode = "404", description = "No existe un lugar con ese ID",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":404,\"error\":\"Not Found\",\"mensaje\":\"No existe el lugar 507f1f77bcf86cd799439011\"}")))
    })
    public Lugar obtenerLugar(
            @Parameter(name = "id", in = ParameterIn.PATH, required = true,
                    description = "ID ObjectId del lugar, codificado como hexadecimal de 24 caracteres.",
                    example = "507f1f77bcf86cd799439011")
            @PathVariable String id,
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true,
                    description = "Identificador no vacío del usuario.", example = "organizador-1")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    description = "Admite ORGANIZADOR o ADMIN, sin distinguir mayúsculas y recortando espacios.",
                    example = "ORGANIZADOR", schema = @Schema(allowableValues = {"ORGANIZADOR", "ADMIN"}))
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        validarLector(userId, userRole);
        return lugarService.buscarLugar(id);
    }

    @GetMapping("/{id}/sectores")
    @Operation(summary = "Listar sectores de un lugar", description = "Devuelve los sectores y sus configuraciones. Requiere X-User-Role: ORGANIZADOR o ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sectores del lugar, posiblemente una lista vacía",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = Sector.class)),
                            examples = @ExampleObject(name = "Sectores", value = """
                                    [{"id":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null}]
                                    """))),
            @ApiResponse(responseCode = "401", description = "Falta X-User-Id o X-User-Role",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":401,\"error\":\"Unauthorized\",\"mensaje\":\"Se requieren X-User-Id y X-User-Role\"}"))),
            @ApiResponse(responseCode = "403", description = "Se requiere X-User-Role: ORGANIZADOR o ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":403,\"error\":\"Forbidden\",\"mensaje\":\"Solo un organizador o administrador puede consultar lugares y sectores\"}"))),
            @ApiResponse(responseCode = "404", description = "No existe un lugar con ese ID",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":404,\"error\":\"Not Found\",\"mensaje\":\"No existe el lugar 507f1f77bcf86cd799439011\"}")))
    })
    public List<Sector> listarSectores(
            @Parameter(name = "id", in = ParameterIn.PATH, required = true,
                    description = "ID ObjectId del lugar, codificado como hexadecimal de 24 caracteres.",
                    example = "507f1f77bcf86cd799439011")
            @PathVariable String id,
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true,
                    description = "Identificador no vacío del usuario.", example = "organizador-1")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    description = "Admite ORGANIZADOR o ADMIN, sin distinguir mayúsculas y recortando espacios.",
                    example = "ORGANIZADOR", schema = @Schema(allowableValues = {"ORGANIZADOR", "ADMIN"}))
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {
        validarLector(userId, userRole);
        return lugarService.listarSectores(id);
    }

    @PostMapping("/{id}/sectores")
    @Operation(summary = "Agregar sector", description = "Agrega un sector numerado o no numerado a un lugar existente. Requiere X-User-Role: ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sector creado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Sector.class),
                            examples = {
                                    @ExampleObject(name = "Sector numerado", value = """
                                            {"id":"550e8400-e29b-41d4-a716-446655440000","nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30,"capacidad":null}
                                            """),
                                    @ExampleObject(name = "Sector no numerado", value = """
                                            {"id":"7d444840-9dc0-11d1-b245-5ffdce74fad2","nombre":"Campo","tipo":"NO_NUMERADO","filas":null,"asientosPorFila":null,"capacidad":500}
                                            """)
                            })),
            @ApiResponse(responseCode = "400", description = "Configuración inválida para el tipo de sector",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":400,\"error\":\"Bad Request\",\"mensaje\":\"Un sector numerado requiere filas y asientosPorFila positivos, sin capacidad\"}"))),
            @ApiResponse(responseCode = "401", description = "Falta X-User-Id o X-User-Role",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":401,\"error\":\"Unauthorized\",\"mensaje\":\"Se requieren X-User-Id y X-User-Role\"}"))),
            @ApiResponse(responseCode = "403", description = "Se requiere X-User-Role: ADMIN",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":403,\"error\":\"Forbidden\",\"mensaje\":\"Solo un administrador puede gestionar lugares y sectores\"}"))),
            @ApiResponse(responseCode = "404", description = "No existe un lugar con ese ID",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":404,\"error\":\"Not Found\",\"mensaje\":\"No existe el lugar 507f1f77bcf86cd799439011\"}"))),
            @ApiResponse(responseCode = "409", description = "Conflicto de actualización concurrente o clave duplicada",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"timestamp\":\"2026-11-07T12:00:00Z\",\"status\":409,\"error\":\"Conflict\",\"mensaje\":\"El recurso cambió en paralelo; vuelva a intentar la operación\"}")))
    })
    public ResponseEntity<Sector> agregarSector(
            @Parameter(name = "id", in = ParameterIn.PATH, required = true,
                    description = "ID ObjectId del lugar, codificado como hexadecimal de 24 caracteres.",
                    example = "507f1f77bcf86cd799439011")
            @PathVariable String id,
            @Parameter(name = "X-User-Id", in = ParameterIn.HEADER, required = true,
                    description = "Identificador no vacío del usuario que realiza la solicitud.", example = "admin-1")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(name = "X-User-Role", in = ParameterIn.HEADER, required = true,
                    description = "Enviar ADMIN (sin distinguir mayúsculas; se recortan espacios).",
                    example = "ADMIN", schema = @Schema(allowableValues = {"ADMIN"}))
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Sector numerado: completar filas y asientosPorFila, sin capacidad. Sector no numerado: completar capacidad, sin filas ni asientosPorFila.",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreateSectorRequest.class),
                            examples = {
                                    @ExampleObject(name = "Sector numerado", value = """
                                            {"nombre":"Platea","tipo":"NUMERADO","filas":20,"asientosPorFila":30}
                                            """),
                                    @ExampleObject(name = "Sector no numerado", value = """
                                            {"nombre":"Campo","tipo":"NO_NUMERADO","capacidad":500}
                                            """)
                            }))
            @Valid @RequestBody CreateSectorRequest request) {
        validarAdministrador(userId, userRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(lugarService.agregarSector(id, request));
    }

    private void validarAdministrador(String userId, String userRole) {
        validarIdentidad(userId, userRole);
        if (!esAdministrador(userRole)) {
            throw new ForbiddenException("Solo un administrador puede gestionar lugares y sectores");
        }
    }

    private void validarLector(String userId, String userRole) {
        validarIdentidad(userId, userRole);
        String rol = userRole.trim();
        if (!esAdministrador(rol) && !"ORGANIZADOR".equalsIgnoreCase(rol)) {
            throw new ForbiddenException("Solo un organizador o administrador puede consultar lugares y sectores");
        }
    }

    private void validarIdentidad(String userId, String userRole) {
        if (userId == null || userId.isBlank() || userRole == null || userRole.isBlank()) {
            throw new UnauthorizedException("Se requieren X-User-Id y X-User-Role");
        }
    }

    private boolean esAdministrador(String userRole) {
        String rol = userRole.trim();
        return "ADMIN".equalsIgnoreCase(rol) || "administrador".equalsIgnoreCase(rol);
    }
}
