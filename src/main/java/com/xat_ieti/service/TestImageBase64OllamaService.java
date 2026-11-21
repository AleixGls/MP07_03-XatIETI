package com.xat_ieti.service;



import java.io.File;
import java.io.IOException;



public class TestImageBase64OllamaService {

    public static void main(String[] args) {

        OllamaService service = new OllamaService();

        File image = new File("C:\\Users\\aleix\\Downloads\\sdas.png");

        try {
            String base64 = service.imageToBase64(image);

            System.out.println("Imagen convertida correctamente.");
            System.out.println("Longitud Base64: " + base64.length());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
