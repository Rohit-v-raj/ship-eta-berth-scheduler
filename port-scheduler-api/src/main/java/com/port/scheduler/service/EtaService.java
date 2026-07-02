package com.port.scheduler.service;

import com.port.scheduler.model.EtaRequest;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.ScheduleRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Calls the Python FastAPI model service to get a vessel's ETA.
 */
@Service
public class EtaService {

    private final RestClient restClient;

    public EtaService(@Value("${eta.api.url}") String etaApiUrl) {
        // Force HTTP/1.1: the JDK HttpClient defaults to HTTP/2 and attempts an
        // h2c upgrade that uvicorn rejects, which drops the request body.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(etaApiUrl)
                .build();
    }

    public EtaResponse predict(ScheduleRequest req) {
        EtaRequest body = new EtaRequest(
                req.lat(), req.lon(), req.sog(), req.cog(),
                req.destLat(), req.destLon());
        return restClient.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(EtaResponse.class);
    }
}
