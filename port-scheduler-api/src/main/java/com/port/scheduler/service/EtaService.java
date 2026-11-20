package com.port.scheduler.service;

import com.port.scheduler.exceptions.EtaServiceUnavailableException;
import com.port.scheduler.model.EtaRequest;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.EtaQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Calls the Python FastAPI model service to get a vessel's ETA. Connecting and
 * waiting for the answer are both time-limited, so a model service that is
 * down or hung is reported at once instead of holding the request.
 */
@Service
public class EtaService {

    private final RestClient restClient;

    public EtaService(@Value("${eta.api.url}") String etaApiUrl,
                      @Value("${eta.api.connect-timeout-seconds:3}") long connectTimeoutSeconds,
                      @Value("${eta.api.read-timeout-seconds:10}") long readTimeoutSeconds) {
        // Force HTTP/1.1: the JDK HttpClient defaults to HTTP/2 and attempts an
        // h2c upgrade that uvicorn rejects, which drops the request body.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(etaApiUrl)
                .build();
    }

    public EtaResponse predict(EtaQuery req) {
        EtaRequest body = new EtaRequest(
                req.lat(), req.lon(), req.sog(), req.cog(),
                req.destLat(), req.destLon());
        try {
            return restClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(EtaResponse.class);
        } catch (ResourceAccessException e) {
            throw new EtaServiceUnavailableException("The ETA model service is not answering; try again shortly", e);
        }
    }
}
