package com.xat_ieti.service;



import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;



public class OllamaService {

    // Atributos
    private static final String MODEL_NAME = "Qwen38";
    private static final String MODEL_URL = "https://agents.ieti.site/v1";
    private final String MODEL_KEY;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();



    // Constructor
    public OllamaService() {

        // Cargar las variables del archivo AgentsIETI.env
        Dotenv dotenv = Dotenv.configure().filename("AgentsIETI.env").load();

        // Cargar y comprobar que existe MODEL_KEY en el archivo AgentsIETI.env
        MODEL_KEY = dotenv.get("MODEL_KEY");

        if (MODEL_KEY == null || MODEL_KEY.isBlank()) {
            throw new IllegalStateException(
                    "No se ha encontrado MODEL_KEY en AgentsIETI.env."
            );
        }

        // Crear el cliente HTTP
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }



    // Metodos
    // Consultar las capacidades de los modelos
    public CompletableFuture<HttpResponse<String>> getModelCapabilities() {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODEL_URL + "/model-capabilities"))
                .header("Authorization", "Bearer " + MODEL_KEY)
                .header("Accept", "application/json")
                .GET()
                .build();

        return httpClient.sendAsync(
                request,
                BodyHandlers.ofString()
        );
    }

    // Enviar un mensaje al modelo
    public CompletableFuture<HttpResponse<String>> sendMessage(String message) {

        // Construir el contenido de la petición
        Map<String, Object> requestBody = Map.of(
                "model", MODEL_NAME,
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", message
                        )
                ),
                "stream", false,
                "reasoning_effort", "low"
        );

        try {
            // Convertir el objeto Java a JSON
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // Construir la petición HTTP
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MODEL_URL + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + MODEL_KEY)
                    .POST(BodyPublishers.ofString(jsonBody))
                    .build();

            // Enviar la petición de forma asíncrona
            return httpClient.sendAsync(
                    request,
                    BodyHandlers.ofString()
            );

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "No se ha podido construir el JSON de la petición.",
                    e
            );
        }
    }

    // Extraer el texto de la respuesta JSON
    private String extractResponse(String jsonResponse) {

        try {
            // Convertir el JSON recibido en un árbol de nodos
            JsonNode root = objectMapper.readTree(jsonResponse);

            // Acceder al texto de la respuesta
            JsonNode content = root
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content");

            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException(
                        "El servidor no ha devuelto ningún mensaje."
                );
            }

            return content.asText();

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "No se ha podido interpretar la respuesta del servidor.",
                    e
            );
        }
    }

    // Enviar un mensaje y obtener directamente el texto
    public CompletableFuture<String> ask(String message) {

        return sendMessage(message)
                .thenApply(response -> {

                    // Comprobar el código de respuesta HTTP
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException(
                                "Error del servidor. Código HTTP: "
                                        + response.statusCode()
                                        + "\n"
                                        + response.body()
                        );
                    }

                    // Extraer el texto generado por el modelo
                    return extractResponse(response.body());
                });
    }
}
