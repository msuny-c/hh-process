package ru.itmo.hhprocess.camunda;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;

class CamundaRestClientTest {

    @Test
    void businessKeyUpdateDoesNotThrowWhenFailOnErrorIsEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://camunda:8080/engine-rest/process-instance/process-1/business-key"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withResourceNotFound()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"NotFoundException\",\"message\":\"HTTP 404 Not Found\"}"));

        CamundaRestClient client = new CamundaRestClient(restTemplate, strictProperties());

        assertFalse(client.updateProcessInstanceBusinessKey("process-1", "vacancy:1"));
        server.verify();
    }

    @Test
    void otherCamundaRestFailuresStillThrowWhenFailOnErrorIsEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://camunda:8080/engine-rest/process-definition/key/hhVacancyProcess/start"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withResourceNotFound()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"NotFoundException\",\"message\":\"missing process\"}"));

        CamundaRestClient client = new CamundaRestClient(restTemplate, strictProperties());

        assertThrows(HttpClientErrorException.NotFound.class,
                () -> client.startProcessByKey("hhVacancyProcess", "vacancy-request:1", Map.of()));
        server.verify();
    }

    private static CamundaProperties strictProperties() {
        CamundaProperties properties = new CamundaProperties();
        properties.setEnabled(true);
        properties.setFailOnError(true);
        properties.setBaseUrl("http://camunda:8080/engine-rest");
        return properties;
    }
}
