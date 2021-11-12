package ru.mail.polis.service.eldar_tim;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.PathMapper;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Cluster;
import ru.mail.polis.lsm.DAO;
import ru.mail.polis.service.Service;
import ru.mail.polis.service.eldar_tim.handlers.RequestHandler;
import ru.mail.polis.service.eldar_tim.handlers.EntityRequestHandler;
import ru.mail.polis.service.eldar_tim.handlers.StatusRequestHandler;
import ru.mail.polis.service.exceptions.ServerRuntimeException;
import ru.mail.polis.service.exceptions.ServiceOverloadException;
import ru.mail.polis.sharding.HashRouter;

import java.io.IOException;

/**
 * Service implementation for 2021-highload-dht.
 *
 * @author Eldar Timraleev
 */
public class HttpServerImpl extends HttpServer implements Service {
    private static final Logger LOG = LoggerFactory.getLogger(HttpServerImpl.class);

    private final DAO dao;
    private final Cluster.Node self;
    private final Cluster.ReplicasHolder replicasHolder;
    private final HashRouter<Cluster.Node> router;
    private final ServiceExecutor workers;
    private final ServiceExecutor proxies;

    private final PathMapper pathMapper;
    private final one.nio.http.RequestHandler statusHandler;

    public HttpServerImpl(
            DAO dao, Cluster.Node self,
            Cluster.ReplicasHolder replicasHolder, HashRouter<Cluster.Node> router,
            ServiceExecutor workers, ServiceExecutor proxies
    ) throws IOException {
        super(buildHttpServerConfig(self.port));
        this.dao = dao;
        this.self = self;
        this.replicasHolder = replicasHolder;
        this.router = router;
        this.workers = workers;
        this.proxies = proxies;

        pathMapper = new PathMapper();
        statusHandler = new StatusRequestHandler(self, router, replicasHolder);
        mapPaths();
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

    private void mapPaths() {
        pathMapper.add("/v0/status", new int[]{Request.METHOD_GET}, statusHandler);

        pathMapper.add("/v0/entity",
                new int[]{Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE},
                new EntityRequestHandler(self, router, replicasHolder, dao));
    }

    @Override
    public synchronized void stop() {
        super.stop();
        workers.awaitAndShutdown();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        RequestHandler requestHandler =
                (RequestHandler) pathMapper.find(request.getPath(), request.getMethod());

        if (requestHandler == statusHandler) {
            workers.run(session, this::exceptionHandler, () -> requestHandler.handleRequest(request, session));
        } else if (requestHandler != null) {
            Cluster.Node targetNode = requestHandler.getTargetNode(request);
            if (requestHandler.shouldHandleLocally(targetNode, request)) {
                workers.execute(session, this::exceptionHandler, () ->
                        requestHandler.handleRequest(targetNode, request, session));
            } else {
                proxies.execute(session, this::exceptionHandler, () ->
                        requestHandler.redirect(targetNode, request, session));
            }
        } else {
            handleDefault(request, session);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        sendResponse(response, session);
    }

    private void exceptionHandler(Session session, ServerRuntimeException e) {
        sendError(e.description(), e.httpCode(), (HttpSession) session, e);
    }

    private void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException ex) {
            LOG.error("Unable to send response", ex);
        }
    }

    private void sendError(String description, String httpCode, HttpSession session, Exception e) {
        if (e != ServiceOverloadException.INSTANCE) {
            LOG.warn("Error: {}", description, e); // Влияет на результаты профилирования
        }

        try {
            String code = httpCode == null ? Response.INTERNAL_ERROR : httpCode;
            session.sendError(code, description);
        } catch (IOException ex) {
            LOG.error("Unable to send error: {} {}", description, e.getMessage(), ex);
        }
    }
}
