package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HTTP mock test for the client exception reporting endpoint. Tests the HTTP request/response format without requiring
 * a Service instance.
 */
class ExceptionReporterHttpMockTest {

    private HttpServer mockServer;
    private int serverPort;
    private String serverUrl;
    private ObjectMapper objectMapper;
    private OkHttpClient httpClient;

    // Track requests received by the mock server
    private List<ReceivedRequest> receivedRequests;
    private AtomicInteger requestCounter;

    // Mock responses
    private int mockResponseCode = 200;
    private String mockResponseBody = null;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        httpClient = new OkHttpClient();
        receivedRequests = new ArrayList<>();
        requestCounter = new AtomicInteger(0);

        // Start mock HTTP server
        mockServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverPort = mockServer.getAddress().getPort();
        serverUrl = "http://localhost:" + serverPort;

        // Set up endpoint handler
        mockServer.createContext("/api/client-exceptions/", this::handleExceptionReport);
        mockServer.setExecutor(null);
        mockServer.start();

        System.out.println("✓ Mock server started on port " + serverPort);
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
        mockResponseCode = 200;
        mockResponseBody = null;
    }

    /** HTTP handler for the /api/client-exceptions/ endpoint */
    private void handleExceptionReport(HttpExchange exchange) throws IOException {
        // Record the request
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        ReceivedRequest request = new ReceivedRequest(method, path, authHeader, requestBody);
        receivedRequests.add(request);

        // Generate response
        int responseCode = mockResponseCode;
        String responseBody = mockResponseBody;

        if (responseBody == null) {
            // Default success response (matching staging server format)
            int id = requestCounter.incrementAndGet();
            var jsonResponse = objectMapper.createObjectNode();
            jsonResponse.put("id", "exc-" + id);
            jsonResponse.put("created_at", "2025-10-14T15:30:00Z");
            jsonResponse.put("status", "received");
            responseBody = jsonResponse.toString();
        }

        // Send response
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    @Test
    @DisplayName("Should send POST request with correct format")
    void shouldSendPostRequestWithCorrectFormat() throws IOException {
        String testStacktrace = "java.lang.RuntimeException: Test exception\n\tat TestClass.method(TestClass.java:42)";
        String clientVersion = "1.0.0-test";
        String brokkKey = "brk+00000000-0000-0000-0000-000000000000+test-token";

        // Build request (mimicking Service.reportClientException)
        var jsonBody = objectMapper.createObjectNode();
        jsonBody.put("stacktrace", testStacktrace);
        jsonBody.put("client_version", clientVersion);

        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(serverUrl + "/api/client-exceptions/")
                .header("Authorization", "Bearer " + brokkKey)
                .post(body)
                .build();

        // Send request
        try (Response response = httpClient.newCall(request).execute()) {
            assertTrue(response.isSuccessful(), "Response should be successful");

            // Verify response
            String responseBody = response.body().string();
            JsonNode responseJson = objectMapper.readTree(responseBody);
            assertTrue(responseJson.has("id"));
            assertTrue(responseJson.has("created_at"));
            assertEquals("exc-1", responseJson.get("id").asText());
        }

        // Verify the request received by the mock server
        assertEquals(1, receivedRequests.size(), "Should have received exactly one request");

        ReceivedRequest receivedRequest = receivedRequests.get(0);
        assertEquals("POST", receivedRequest.method);
        assertEquals("/api/client-exceptions/", receivedRequest.path);
        assertEquals("Bearer " + brokkKey, receivedRequest.authHeader);

        // Verify request body
        JsonNode requestBody = objectMapper.readTree(receivedRequest.body);
        assertEquals(testStacktrace, requestBody.get("stacktrace").asText());
        assertEquals(clientVersion, requestBody.get("client_version").asText());

        System.out.println("✓ Verified POST request format");
    }

    @Test
    @DisplayName("Should include full Brokk key in Authorization header")
    void shouldIncludeFullBrokkKeyInAuthorizationHeader() throws IOException {
        String brokkKey = "brk+12345678-1234-1234-1234-123456789abc+my-secret-token";

        var jsonBody = objectMapper.createObjectNode();
        jsonBody.put("stacktrace", "test stacktrace");
        jsonBody.put("client_version", "1.0.0");

        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(serverUrl + "/api/client-exceptions/")
                .header("Authorization", "Bearer " + brokkKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertTrue(response.isSuccessful());
        }

        assertEquals(1, receivedRequests.size());
        ReceivedRequest receivedRequest = receivedRequests.get(0);

        // Verify full key is sent (not just the token part)
        assertEquals("Bearer " + brokkKey, receivedRequest.authHeader);
        assertTrue(receivedRequest.authHeader.contains("brk+"));
        assertTrue(receivedRequest.authHeader.contains("12345678-1234-1234-1234-123456789abc"));
        assertTrue(receivedRequest.authHeader.contains("my-secret-token"));

        System.out.println("✓ Verified full Brokk key in Authorization header");
    }

    @Test
    @DisplayName("Should send stacktrace and client version in request body")
    void shouldSendStacktraceAndClientVersionInRequestBody() throws IOException {
        String expectedStacktrace =
                "java.lang.NullPointerException: Cannot invoke method on null object\n\tat com.example.MyClass.doSomething(MyClass.java:123)";
        String expectedVersion = "2.5.1";

        var jsonBody = objectMapper.createObjectNode();
        jsonBody.put("stacktrace", expectedStacktrace);
        jsonBody.put("client_version", expectedVersion);

        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(serverUrl + "/api/client-exceptions/")
                .header("Authorization", "Bearer brk+test+token")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertTrue(response.isSuccessful());
        }

        assertEquals(1, receivedRequests.size());
        JsonNode requestBody = objectMapper.readTree(receivedRequests.get(0).body);

        assertEquals(expectedStacktrace, requestBody.get("stacktrace").asText());
        assertEquals(expectedVersion, requestBody.get("client_version").asText());

        System.out.println("✓ Verified request body contains stacktrace and client_version");
    }

    @Test
    @DisplayName("Should handle server error response")
    void shouldHandleServerErrorResponse() throws IOException {
        // Configure mock to return 500 error
        mockResponseCode = 500;
        mockResponseBody = "{\"detail\":\"Internal server error\"}";

        var jsonBody = objectMapper.createObjectNode();
        jsonBody.put("stacktrace", "test");
        jsonBody.put("client_version", "1.0.0");

        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(serverUrl + "/api/client-exceptions/")
                .header("Authorization", "Bearer brk+test+token")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertEquals(500, response.code());
            String errorBody = response.body().string();
            assertTrue(errorBody.contains("Internal server error"));
        }

        assertEquals(1, receivedRequests.size(), "Request should have been sent despite error");

        System.out.println("✓ Verified server error handling");
    }

    @Test
    @DisplayName("Should parse success response correctly")
    void shouldParseSuccessResponseCorrectly() throws IOException {
        var jsonBody = objectMapper.createObjectNode();
        jsonBody.put("stacktrace", "test stacktrace");
        jsonBody.put("client_version", "1.0.0");

        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(serverUrl + "/api/client-exceptions/")
                .header("Authorization", "Bearer brk+test+token")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            assertTrue(response.isSuccessful());

            String responseBody = response.body().string();
            JsonNode responseJson = objectMapper.readTree(responseBody);

            // Verify response structure matches staging server format
            assertTrue(responseJson.has("id"), "Response should have 'id' field");
            assertTrue(responseJson.has("created_at"), "Response should have 'created_at' field");
            assertTrue(responseJson.has("status"), "Response should have 'status' field");

            assertEquals("exc-1", responseJson.get("id").asText());
            assertEquals("2025-10-14T15:30:00Z", responseJson.get("created_at").asText());
            assertEquals("received", responseJson.get("status").asText());
        }

        System.out.println("✓ Verified response format");
    }

    @Test
    @DisplayName("Should handle multiple sequential requests")
    void shouldHandleMultipleSequentialRequests() throws IOException {
        for (int i = 0; i < 3; i++) {
            var jsonBody = objectMapper.createObjectNode();
            jsonBody.put("stacktrace", "test stacktrace " + i);
            jsonBody.put("client_version", "1.0.0");

            RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(serverUrl + "/api/client-exceptions/")
                    .header("Authorization", "Bearer brk+test+token")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                assertTrue(response.isSuccessful());
                JsonNode responseJson = objectMapper.readTree(response.body().string());
                assertEquals("exc-" + (i + 1), responseJson.get("id").asText());
            }
        }

        assertEquals(3, receivedRequests.size());

        for (int i = 0; i < 3; i++) {
            JsonNode requestBody = objectMapper.readTree(receivedRequests.get(i).body);
            assertEquals("test stacktrace " + i, requestBody.get("stacktrace").asText());
        }

        System.out.println("✓ Verified handling of multiple sequential requests");
    }

    /** Record of an HTTP request received by the mock server */
    private static class ReceivedRequest {
        final String method;
        final String path;
        final String authHeader;
        final String body;

        ReceivedRequest(String method, String path, String authHeader, String body) {
            this.method = method;
            this.path = path;
            this.authHeader = authHeader;
            this.body = body;
        }
    }
}
