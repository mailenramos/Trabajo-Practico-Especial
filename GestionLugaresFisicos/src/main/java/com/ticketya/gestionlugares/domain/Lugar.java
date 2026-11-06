package com.ticketya.gestionlugares.domain;

import java.util.ArrayList;
import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "lugares")
@CompoundIndex(name = "idx_lugares_ciudad_nombre", def = "{'ciudad': 1, 'nombre': 1}")
public class Lugar {

    @Id
    private String id;

    @Indexed(name = "idx_lugares_nombre")
    private String nombre;

    private String direccion;
    private String ciudad;
    private double latitud;
    private double longitud;
    private List<Sector> sectores = new ArrayList<>();

    @Version
    private Long version;

    public Lugar() {
    }

    public Lugar(String id, String nombre, String direccion, String ciudad, double latitud, double longitud, List<Sector> sectores) {
        this.id = id == null ? new ObjectId().toHexString() : id;
        this.nombre = nombre;
        this.direccion = direccion;
        this.ciudad = ciudad;
        this.latitud = latitud;
        this.longitud = longitud;
        this.sectores = sectores == null ? new ArrayList<>() : new ArrayList<>(sectores);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public String getCiudad() {
        return ciudad;
    }

    public void setCiudad(String ciudad) {
        this.ciudad = ciudad;
    }

    public double getLatitud() {
        return latitud;
    }

    public void setLatitud(double latitud) {
        this.latitud = latitud;
    }

    public double getLongitud() {
        return longitud;
    }

    public void setLongitud(double longitud) {
        this.longitud = longitud;
    }

    public List<Sector> getSectores() {
        return sectores;
    }

    public void setSectores(List<Sector> sectores) {
        this.sectores = sectores == null ? new ArrayList<>() : new ArrayList<>(sectores);
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
