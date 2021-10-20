package ru.mail.polis.service.eldar_tim;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.lsm.DAO;
import ru.mail.polis.lsm.Record;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Service implementation for Stage 1-2 within 2021-highload-dht.
 *
 * @author Eldar Timraleev
 */
public class HttpServerImpl extends HttpServer implements Service {
    private static final Logger LOG = LoggerFactory.getLogger(HttpServerImpl.class);

    private final DAO dao;

    public HttpServerImpl(final int port, final DAO dao) throws IOException {
        super(buildHttpServerConfig(port));
        this.dao = dao;
    }

    private static HttpServerConfig buildHttpServerConfig(final int port) {
        final HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.threads = 2;
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        acceptorConfig.deferAccept = true;
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        return httpServerConfig;
    }

    @Override
    public synchronized void stop() {
        super.stop();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        super.handleRequest(request, session);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Path("/v0/status")
    public void status(HttpSession session) {
        try {
            Response response = Response.ok(Response.OK);
            session.sendResponse(response);
        } catch (IOException e) {
            sendError("Something went wrong", Response.INTERNAL_ERROR, session, e);
        }
    }

    @Path("/v0/entity")
    public void entity(Request request, HttpSession session, @Param(value = "id", required = true) final String id) {
        try {
            if (id.isBlank()) {
                Response response = new Response(Response.BAD_REQUEST, "Bad id".getBytes(StandardCharsets.UTF_8));
                session.sendResponse(response);
                return;
            }

            final Response response;
            switch (request.getMethod()) {
                case Request.METHOD_GET:
                    response = get(id);
                    break;
                case Request.METHOD_PUT:
                    response = put(id, request.getBody());
                    break;
                case Request.METHOD_DELETE:
                    response = delete(id);
                    break;
                default:
                    response = new Response(Response.METHOD_NOT_ALLOWED);
                    break;
            }
            session.sendResponse(response);
        } catch (IOException e) {
            sendError("Something went wrong", Response.INTERNAL_ERROR, session, e);
        }
    }

    private Response get(String id) {
        ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final Iterator<Record> iterator = dao.range(key, DAO.nextKey(key));
        if (iterator.hasNext()) {
            return new Response(Response.OK, extractBytes(iterator.next().getValue()));
        } else {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
    }

    private Response put(String id, byte[] body) {
        ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        ByteBuffer value = ByteBuffer.wrap(body);
        dao.upsert(Record.of(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response delete(String id) {
        ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        dao.upsert(Record.tombstone(key));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static byte[] extractBytes(final ByteBuffer buffer) {
        final byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private void sendError(String description, String httpCode, HttpSession session, Exception e) {
        LOG.error("Error: {}", description, e);
        try {
            String code = httpCode == null ? Response.INTERNAL_ERROR : httpCode;
            session.sendError(code, e.getMessage());
        } catch (IOException ex) {
            LOG.error("Unable to send error: {}", description, e);
        }
    }
}
