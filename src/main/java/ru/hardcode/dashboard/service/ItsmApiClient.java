package ru.hardcode.dashboard.service;

import java.time.Duration;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import ru.hardcode.dashboard.exception.*;

@Component
public class ItsmApiClient {
    private static final ParameterizedTypeReference<List<Map<String,Object>>> ROWS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String,Object>> ROW = new ParameterizedTypeReference<>() {};
    private final RestClient http;

    public ItsmApiClient(@Value("${dashboard.itsm.base-url:http://127.0.0.1:8082}") String url,
            @Value("${dashboard.itsm.connect-timeout:PT2S}") Duration connect,
            @Value("${dashboard.itsm.read-timeout:PT5S}") Duration read) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public List<Map<String,Object>> users() { return call(() -> http.get().uri("/user").retrieve().body(ROWS)); }
    public List<Map<String,Object>> incidents() { return call(() -> http.get().uri("/itsm_incident").retrieve().body(ROWS)); }
    public Map<String,Object> incident(String id) { return call(() -> http.get().uri("/itsm_incident/{id}",id).retrieve().body(ROW)); }
    public Map<String,Object> patch(String id,Map<String,Object> changes) {
        return call(() -> http.patch().uri("/itsm_incident/{id}",id).body(changes).retrieve().body(ROW));
    }
    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            T body = request.get();
            if (body == null) throw new AppException(AppErrors.ITSM_BAD_RESPONSE);
            return body;
        } catch (RestClientResponseException e) {
            throw new AppException(switch (e.getStatusCode().value()) {
                case 400 -> AppErrors.INCIDENT_PATCH_INVALID;
                case 404 -> AppErrors.INCIDENT_NOT_FOUND;
                case 409 -> AppErrors.INCIDENT_CONFLICT;
                case 503 -> AppErrors.ITSM_OFFLINE;
                default -> AppErrors.ITSM_BAD_RESPONSE;
            },e);
        } catch (ResourceAccessException e) { throw new AppException(AppErrors.ITSM_OFFLINE,e); }
        catch (RestClientException e) { throw new AppException(AppErrors.ITSM_BAD_RESPONSE,e); }
    }
}
