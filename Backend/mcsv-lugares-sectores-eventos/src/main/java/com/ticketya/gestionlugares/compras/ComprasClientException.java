package com.ticketya.gestionlugares.compras;

public class ComprasClientException extends RuntimeException {
    public ComprasClientException(String message) { super(message); }
    public ComprasClientException(String message, Throwable cause) { super(message, cause); }
}
