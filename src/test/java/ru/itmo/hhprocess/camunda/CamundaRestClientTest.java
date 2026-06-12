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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

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

    @Test
    void messageCorrelationMissReturnsFalseWhenFailOnErrorIsEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://camunda:8080/engine-rest/message"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"RestException\",\"message\":\"org.camunda.bpm.engine.MismatchingMessageCorrelationException: Cannot correlate message 'MSG_INTERVIEW_CANCELLED': No process definition or execution matches the parameters\",\"code\":0}"));

        CamundaRestClient client = new CamundaRestClient(restTemplate, strictProperties());

        assertFalse(client.correlateMessage("MSG_INTERVIEW_CANCELLED", "application:1", Map.of()));
        server.verify();
    }

    @Test
    void concurrentTaskCompletionReturnsFalseWhenFailOnErrorIsEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://camunda:8080/engine-rest/task/task-1/complete"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"RestException\",\"message\":\"Cannot complete task task-1: ENGINE-03005 Execution of 'DELETE TaskEntity[task-1]' failed. Entity was updated by another transaction concurrently.\",\"code\":1}"));

        CamundaRestClient client = new CamundaRestClient(restTemplate, strictProperties());

        assertFalse(client.completeTask("task-1", Map.of()));
        server.verify();
    }

    @Test
    void missingTaskIdentityLinksReturnsFalseWhenFailOnErrorIsEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://camunda:8080/engine-rest/task/task-1/identity-links"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"NullValueException\",\"message\":\"Cannot find task with id task-1: task is null\",\"code\":0}"));

        CamundaRestClient client = new CamundaRestClient(restTemplate, strictProperties());

        assertFalse(client.taskHasCandidateGroup("task-1", "RECRUITER"));
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
