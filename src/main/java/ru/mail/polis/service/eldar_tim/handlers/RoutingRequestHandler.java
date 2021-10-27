package ru.mail.polis.service.eldar_tim.handlers;

import one.nio.http.HttpException;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.RequestHandler;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Cluster;
import ru.mail.polis.sharding.HashRouter;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public abstract class RoutingRequestHandler implements RequestHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingRequestHandler.class);

    private final Cluster.Node self;
    private final HashRouter<Cluster.Node> router;

    public RoutingRequestHandler(Cluster.Node self, HashRouter<Cluster.Node> router) {
        this.self = self;
        this.router = router;
    }

    @Nullable
    protected abstract String getRouteKey(Request request);

    /**
     * Detects the node to redirect request.
     *
     * @param request request to redirect
     * @return null if the request must be handled by the current node, otherwise node to redirect
     */
    public final Cluster.Node getTarget(Request request) {
        String key = getRouteKey(request);
        if (key == null) {
            return null;
        }

        Cluster.Node target = router.route(key);
        return target == self ? null : target;
    }

    /**
     * Redirects the request to the specified host.
     *
     * @param target target node to redirect request
     * @param request request to redirect
     * @param session session for the current connection
     */
    public final void redirect(Cluster.Node target, Request request, HttpSession session) throws IOException {
        Response response;
        try {
            response = target.httpClient.invoke(request);
        } catch (InterruptedException e) {
            String errorText = "Proxy error: interrupted";
            LOG.debug(errorText, e);
            response = new Response(Response.INTERNAL_ERROR, errorText.getBytes(StandardCharsets.UTF_8));
            Thread.currentThread().interrupt();
        } catch (PoolException | IOException | HttpException e) {
            String errorText = "Proxy error";
            LOG.debug(errorText, e);
            response = new Response(Response.INTERNAL_ERROR, errorText.getBytes(StandardCharsets.UTF_8));
        }
        session.sendResponse(response);
    }

    protected final byte[] extractBytes(ByteBuffer buffer) {
        final byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
