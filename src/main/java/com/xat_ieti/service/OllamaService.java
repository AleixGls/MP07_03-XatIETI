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
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;



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
                "MODEL_KEY was not found in AgentsIETI.env."
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
    
        return sendRequest(requestBody);
    }

    // Extraer el texto de la respuesta JSON
    public String extractResponse(String jsonResponse) {

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
                    "The server did not return any message."
                );
            }

            return content.asText();

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Unable to parse the server response.",
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
                            "Server error. HTTP status code: "
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

        // Crear y guardar el mensaje del usuario
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", message
        );

        conversationHistory.add(userMessage);

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
                            "Server error. HTTP status code: "
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
                            if (!content.isMissingNode() && !content.isNull()) {

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

            }).whenComplete((result, error) -> {
                // Si la petición falla o se cancela, eliminar el mensaje del usuario
                if (error != null) {
                    conversationHistory.removeIf(
                            messageEntry -> messageEntry == userMessage
                    );
                }
            });

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                "Unable to build the request JSON.",
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

    // Convertir una imagen a Base64
    public String imageToBase64(File file) throws IOException {
    
        // Leer los bytes de la imagen
        byte[] bytes = Files.readAllBytes(file.toPath());
    
        // Convertir los bytes a Base64
        return Base64.getEncoder().encodeToString(bytes);
    }

    // Enviar un mensaje junto con una imagen
    public CompletableFuture<HttpResponse<String>> sendMessageWithImage(
            String message,
            String mimeType,
            String base64Image
    ) {
    
        // Eliminar del historial los mensajes que contienen imágenes
        conversationHistory.removeIf(this::containsImage);

        // Reservar espacio para el nuevo intercambio
        if (conversationHistory.size() >= MAX_HISTORY_MESSAGES - 1) {
            conversationHistory.remove(0);
            conversationHistory.remove(0);
        }
    
        // Construir el contenido del mensaje
        List<Map<String, Object>> content = List.of(
                Map.of(
                        "type", "text",
                        "text", message
                ),
                Map.of(
                        "type", "image_url",
                        "image_url", Map.of(
                                "url", "data:" + mimeType + ";base64," + base64Image
                        )
                )
        );
    
        // Crear y guardar el mensaje del usuario con la imagen
        Map<String, Object> userMessage = Map.of(
                "role", "user",
                "content", content
        );
        
        conversationHistory.add(userMessage);
    
        // Construir la petición con todo el historial
        Map<String, Object> requestBody = Map.of(
                "model", MODEL_NAME,
                "messages", new ArrayList<>(conversationHistory),
                "stream", false,
                "reasoning_effort", "low"
        );
    
        // Enviar la petición y guardar la respuesta del asistente
        return sendRequest(requestBody)
            .thenApply(response -> {
            
                // Guardar la respuesta únicamente si la petición ha funcionado
                if (response.statusCode() == 200) {
                
                    String assistantResponse =
                        extractResponse(response.body());
                
                    conversationHistory.add(
                        Map.of(
                            "role", "assistant",
                            "content", assistantResponse
                        )
                    );
                
                } else {
                
                    // Eliminar el mensaje del usuario si el servidor devuelve un error
                    conversationHistory.removeIf(
                        messageEntry -> messageEntry == userMessage
                    );
                }
            
                return response;
            
            }).whenComplete((response, error) -> {
            
                // Eliminar el mensaje del usuario si la petición falla o se cancela
                if (error != null) {
                    conversationHistory.removeIf(
                        messageEntry -> messageEntry == userMessage
                    );
                }
            });
    }

    // Enviar una petición HTTP con un cuerpo JSON
    private CompletableFuture<HttpResponse<String>> sendRequest( Map<String, Object> requestBody) {

        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODEL_URL + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + MODEL_KEY)
                .POST(BodyPublishers.ofString(jsonBody))
                .build();

            CompletableFuture<HttpResponse<String>> future =
                    httpClient.sendAsync(
                            request,
                            BodyHandlers.ofString()
                    );
                
            // Guardar la petición actual para poder cancelarla
            currentRequest.set(future);
                
            // Limpiar la referencia cuando termine
            future.whenComplete((response, error) ->
                    currentRequest.compareAndSet(future, null)
            );

            return future;

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Unable to build the request JSON.",
                e
            );
        }
    }

    // Obtener el tipo MIME de una imagen
    public String getImageMimeType(File file) {

        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".png")) {
            return "image/png";
        } else if (fileName.endsWith(".jpg")
                || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (fileName.endsWith(".gif")) {
            return "image/gif";
        } else if (fileName.endsWith(".webp")) {
            return "image/webp";
        }

        throw new IllegalArgumentException(
            "Unsupported image format."
        );
    }

    /**
     * Comprueba si un mensaje del historial contiene una imagen.
     *
     * @param message mensaje que queremos comprobar
     * @return true si contiene una imagen
     */
    private boolean containsImage(Map<String, Object> message) {

        Object contentObject = message.get("content");

        if (contentObject instanceof List<?> contentList) {

            for (Object item : contentList) {

                if (item instanceof Map<?, ?> contentItem) {

                    if ("image_url".equals(contentItem.get("type"))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}