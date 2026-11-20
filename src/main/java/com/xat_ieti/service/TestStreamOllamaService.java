package com.xat_ieti.service;



public class TestStreamOllamaService {

    public static void main(String[] args) {

        OllamaService service = new OllamaService();

        System.out.println("Iniciando streaming de Qwen38...\n");

        service.askStream(
                "Explícame qué es Java en cinco frases.",
                fragment -> {
                    System.out.print(fragment);
                    System.out.flush();
                }
        )
        .exceptionally(error -> {
            System.out.println("\nError durante el streaming:");
            error.printStackTrace();
            return null;
        })
        .join();

        System.out.println("\n\nStreaming finalizado.");
    }
}