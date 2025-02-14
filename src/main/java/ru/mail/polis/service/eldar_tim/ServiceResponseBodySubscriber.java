package ru.mail.polis.service.eldar_tim;

import javax.annotation.Nullable;
import java.net.http.HttpResponse;
import java.util.Optional;

public final class ServiceResponseBodySubscriber implements HttpResponse.BodyHandler<ServiceResponse> {

    public static final ServiceResponseBodySubscriber INSTANCE = new ServiceResponseBodySubscriber();

    private ServiceResponseBodySubscriber() {
        // No need.
    }

    @Override
    public HttpResponse.BodySubscriber<ServiceResponse> apply(HttpResponse.ResponseInfo responseInfo) {
        Optional<String> headerTimestampOpt = responseInfo.headers().firstValue(ServiceResponse.HEADER_TIMESTAMP);

        String headerTimestamp = headerTimestampOpt.orElse(null);
        long timestamp = parseTimestampHeader(headerTimestamp);

        return HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofByteArray(),
                body -> ServiceResponse.of(HttpUtils.mapResponse(responseInfo, body), timestamp));
    }

    private long parseTimestampHeader(@Nullable String timestampHeader) {
        if (timestampHeader == null) {
            return -1;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader, 0, timestampHeader.length(), 10);
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            timestamp = -1;
        }
        return timestamp;
    }
}
