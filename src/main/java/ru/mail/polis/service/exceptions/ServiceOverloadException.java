package ru.mail.polis.service.exceptions;

import one.nio.http.Response;

public class ServiceOverloadException extends ServerRuntimeException {

    public ServiceOverloadException() {
        super();
    }

    public ServiceOverloadException(Exception e) {
        super(e);
    }

    @Override
    public String description() {
        return "Service is overloaded";
    }

    @Override
    public String httpCode() {
        return Response.SERVICE_UNAVAILABLE;
    }
}
