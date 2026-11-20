package com.xat_ieti.service;



import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;



public class OllamaService {

    // Atributos
    private static final String MODEL_NAME = "Qwen38";
    private static final String MODEL_URL = "https://agents.ieti.site/v1";
    private final String MODEL_KEY;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Petición HTTP que se está ejecutando actualmente
    private final AtomicReference<CompletableFuture<?>> currentRequest = new AtomicReference<>();
    // Flujo de datos de la respuesta actual
    private final AtomicReference<InputStream> currentInputStream = new AtomicReference<>();
    // Historial de la conversación
    private final List<Map<String, Object>> conversationHistory = new ArrayList<>();
    private static final int MAX_HISTORY_MESSAGES = 10;



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


    // Enviar un mensaje y recibir la respuesta progresivamente
    public CompletableFuture<Void> askStream(
            String message,
            Consumer<String> onText
    ) {

        // Reservar espacio para un nuevo intercambio
        if (conversationHistory.size() >= MAX_HISTORY_MESSAGES - 1) {
            conversationHistory.remove(0);
            conversationHistory.remove(0);
        }

        // Añadir el mensaje del usuario
        conversationHistory.add(
                Map.of(
                        "role", "user",
                        "content", message
                )
        );

        // Construir la petición con el historial
        Map<String, Object> requestBody = Map.of(
                "model", MODEL_NAME,
                "messages", new ArrayList<>(conversationHistory),
                "stream", true,
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

            // Enviar la petición y recibir un InputStream
            CompletableFuture<HttpResponse<InputStream>> requestFuture =
                    httpClient.sendAsync(
                            request,
                            BodyHandlers.ofInputStream()
                    );

            // Guardar la petición HTTP actual
            currentRequest.set(requestFuture);

            // Acumular la respuesta completa del asistente
            StringBuilder assistantResponse = new StringBuilder();

            return requestFuture.thenAcceptAsync(response -> {

                InputStream responseStream = response.body();

                // Guardar el flujo de respuesta
                currentInputStream.set(responseStream);

                // Comprobar si se ha cancelado antes de empezar a leer
                if (requestFuture.isCancelled()
                        || currentRequest.get() != requestFuture) {

                    try {
                        responseStream.close();
                    } catch (IOException ignored) {
                    }

                    return;
                }

                try (InputStream input = responseStream) {

                    // Comprobar el código HTTP
                    if (response.statusCode() != 200) {

                        String errorBody = new String(
                                input.readAllBytes(),
                                StandardCharsets.UTF_8
                        );

                        throw new IllegalStateException(
                                "Error del servidor. Código HTTP: "
                                        + response.statusCode()
                                        + "\n"
                                        + errorBody
                        );
                    }

                    // Leer los fragmentos de la respuesta
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    input,
                                    StandardCharsets.UTF_8
                            )
                    )) {

                        String line;

                        while ((line = reader.readLine()) != null) {

                            // Comprobar si la línea contiene datos
                            if (!line.startsWith("data:")) {
                                continue;
                            }

                            String data = line.substring(5).trim();

                            // Final de la respuesta
                            if (data.equals("[DONE]")) {
                                break;
                            }

                            if (data.isEmpty()) {
                                continue;
                            }

                            // Interpretar el JSON del fragmento
                            JsonNode root = objectMapper.readTree(data);

                            JsonNode content = root
                                    .path("choices")
                                    .path(0)
                                    .path("delta")
                                    .path("content");

                            // Enviar el fragmento recibido
                            if (!content.isMissingNode()
                                    && !content.isNull()) {

                                String text = content.asText();

                                if (!text.isEmpty()) {
                                
                                    // Guardar el fragmento para construir la respuesta completa
                                    assistantResponse.append(text);
                                
                                    // Mostrar el fragmento en la interfaz
                                    onText.accept(text);
                                }
                            }
                        }
                    }

                    // Guardar la respuesta del asistente cuando termina el streaming
                    if (!assistantResponse.isEmpty()) {
                        conversationHistory.add(
                                Map.of(
                                        "role", "assistant",
                                        "content", assistantResponse.toString()
                                )
                        );
                    }

                } catch (IOException e) {
                    throw new CompletionException(e);

                } finally {
                    // Limpiar las referencias de la petición terminada
                    currentInputStream.compareAndSet(responseStream, null);
                    currentRequest.compareAndSet(requestFuture, null);
                }

            });

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "No se ha podido construir el JSON de la petición.",
                    e
            );
        }
    }

    // Cancelar la petición HTTP y cerrar el flujo de respuesta
    public void cancelCurrentRequest() {

        // Obtener y cancelar la petición HTTP
        CompletableFuture<?> request = currentRequest.getAndSet(null);

        if (request != null) {
            request.cancel(true);
        }

        // Cerrar el flujo de datos si ya se ha recibido
        InputStream input = currentInputStream.getAndSet(null);

        if (input != null) {
            try {
                input.close();
            } catch (IOException e) {
                // El flujo ya puede estar cerrado
            }
        }
    }
}
