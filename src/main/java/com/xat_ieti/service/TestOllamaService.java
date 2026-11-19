package com.xat_ieti.service;



public class TestOllamaService {

    public static void main(String[] args) {

        // Crear el servicio
        OllamaService service = new OllamaService();

        // Consultar las capacidades del modelo
        System.out.println("Consultando capacidades de Qwen38...");

        service.getModelCapabilities()
                .thenAccept(response -> {

                    System.out.println(
                            "Código HTTP: " + response.statusCode()
                    );

                    System.out.println("Catálogo de modelos:");
                    System.out.println(response.body());

                })
                .thenCompose(ignored -> {

                    // Enviar un mensaje al modelo
                    System.out.println("\nEnviando mensaje a Qwen38...");

                    return service.ask(
                            "Hola, ¿puedes presentarte y explicar qué puedes hacer?"
                    );
                })
                .thenAccept(response -> {

                    System.out.println("\nRespuesta de Qwen38:");
                    System.out.println(response);

                })
                .exceptionally(error -> {

                    System.out.println(
                            "\nError al comunicarse con Qwen38:"
                    );

                    error.printStackTrace();

                    return null;
                })
                .join();
    }
}