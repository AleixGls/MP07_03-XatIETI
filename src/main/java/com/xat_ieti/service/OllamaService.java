package com.xat_ieti.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

public class OllamaService {

    
    private static final String MODEL_NAME = "qwen3-vl";
    private static final String MODEL_URL = "https://agents.ieti.site/v1\"";
    private static final String MODEL_KEY = "...";

    private final HttpClient httpClient;

    public OllamaService() {

        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }
}
