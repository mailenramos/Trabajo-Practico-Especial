package com.ticketya.gestionlugares.api;

public record ApiError(int status, String error, String message) {
}
