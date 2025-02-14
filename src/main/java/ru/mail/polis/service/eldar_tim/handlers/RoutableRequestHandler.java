package ru.mail.polis.service.eldar_tim.handlers;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Cluster;
import ru.mail.polis.service.eldar_tim.HttpUtils;
import ru.mail.polis.service.eldar_tim.ServiceExecutor;
import ru.mail.polis.service.eldar_tim.ServiceResponse;
import ru.mail.polis.service.eldar_tim.ServiceResponseBodySubscriber;
import ru.mail.polis.sharding.HashRouter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public abstract class RoutableRequestHandler implements BaseRequestHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RoutableRequestHandler.class);

    protected final Cluster.Node self;
    private final HashRouter<Cluster.Node> router;
    protected final ServiceExecutor workers;
    protected final ServiceExecutor proxies;

    public RoutableRequestHandler(Context context) {
        this.self = context.self;
        this.router = context.router;
        this.workers = context.workers;
        this.proxies = context.proxies;
    }

    /**
     * Defines the key for detecting the owner node.
     *
     * @param request target request
     * @return string representation of the key
     */
    @Nullable
    protected abstract String getRouteKey(Request request);

    /**
     * Detects the node to redirect request.
     *
     * @param request request to redirect
     * @return node to redirect request
     */
    @Nonnull
    protected final Cluster.Node getTargetNode(Request request) {
        String key = getRouteKey(request);
        if (key == null) {
            return self;
        }

        return router.route(key);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        Cluster.Node targetNode = getTargetNode(request);
        final ServiceResponse response;
        if (targetNode == self) {
            response = handleRequest(request);
        } else {
            response = redirectRequest(request, targetNode);
        }
        session.sendResponse(response.raw());
    }

    /**
     * Synchronously redirects the request to the specified host.
     *
     * @param request request to redirect
     * @param target  target node to redirect request
     * @return response
     */
    protected final ServiceResponse redirectRequest(Request request, Cluster.Node target) {
        return redirectRequestAsync(request, target).join();
    }

    /**
     * Asynchronously redirects the request to the specified host.
     *
     * @param request request to redirect
     * @param target  target node to redirect request
     * @return response future, which is always not finish exceptionally
     */
    protected final CompletableFuture<ServiceResponse> redirectRequestAsync(Request request, Cluster.Node target) {
        HttpRequest mappedRequest = HttpUtils.mapRequest(request, target);
        CompletableFuture<ServiceResponse> result = new CompletableFuture<>();

        HttpClient httpClient = target.getClient();
        if (httpClient == null) {
            LOG.warn("Attempt to use unavailable service");
            result.complete(ServiceResponse.of(new Response(Response.SERVICE_UNAVAILABLE)));
            return result;
        }

        httpClient.sendAsync(mappedRequest, ServiceResponseBodySubscriber.INSTANCE)
                .thenApplyAsync(HttpResponse::body, workers)
                .whenComplete((response, t) -> {
                    if (response != null) {
                        result.complete(response);
                    } else {
                        LOG.debug("Proxy error: {}", t.getMessage());
                        var r = new Response(Response.INTERNAL_ERROR, t.getMessage().getBytes(StandardCharsets.UTF_8));
                        result.complete(ServiceResponse.of(r));
                    }
                });
        return result;
    }

    public static class Context {
        public final Cluster.Node self;
        public final HashRouter<Cluster.Node> router;
        public final ServiceExecutor workers;
        public final ServiceExecutor proxies;

        public Context(
                Cluster.Node self, HashRouter<Cluster.Node> router,
                ServiceExecutor workers, ServiceExecutor proxies
        ) {
            this.self = self;
            this.router = router;
            this.workers = workers;
            this.proxies = proxies;
        }
    }
}
