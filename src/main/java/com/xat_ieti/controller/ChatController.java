package com.xat_ieti.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class ChatController {

    // Contenedores
    @FXML
    private VBox messagesContainer;

    @FXML
    private HBox imagePreviewContainer;

    // Componentes de entrada
    @FXML
    private Button attachButton;

    @FXML
    private TextArea messageInput;

    @FXML
    private Button sendButton;

    @FXML
    private Button stopButton;


    /**
     * Se ejecuta automáticamente cuando JavaFX
     * termina de cargar el archivo FXML.
     */
    @FXML
    public void initialize() {

        // El botón Stop empieza oculto
        stopButton.setVisible(false);
        stopButton.setManaged(false);
    }


    /**
     * Gestiona el envío de un mensaje.
     */
    @FXML
    private void handleSend() {

        String message = messageInput.getText().trim();

        // No enviamos mensajes vacíos
        if (message.isEmpty()) {
            return;
        }

        // Mostramos el mensaje del usuario
        addMessage(message, true);

        // Limpiamos el campo de entrada
        messageInput.clear();

    }


    /**
     * Añade un mensaje al historial de conversación.
     *
     * @param text texto del mensaje
     * @param user true si el mensaje es del usuario
     */
    private void addMessage(String text, boolean user) {

        Label messageLabel = new Label(text);

        // Permitir saltos de línea
        messageLabel.setWrapText(true);

        // El ancho mínimo puede reducirse
        messageLabel.setMinWidth(0);

        // El ancho preferido se calcula según el contenido
        // No lo vinculamos a un porcentaje fijo.

        // El ancho máximo será el 75% del chat
        messageLabel.maxWidthProperty().bind(
                messagesContainer.widthProperty()
                        .subtract(40)
                        .multiply(0.75)
        );

        // Contenedor de la burbuja
        HBox messageBox = new HBox(messageLabel);

        messageBox.setSpacing(10);
        messageBox.setMaxWidth(Double.MAX_VALUE);

        // Alineación del mensaje
        messageBox.setAlignment(
                user ? javafx.geometry.Pos.CENTER_RIGHT
                     : javafx.geometry.Pos.CENTER_LEFT
        );

        // Estilo visual
        messageLabel.setStyle(
                "-fx-background-color: " +
                (user ? "#DCEBFF" : "#EAEAEA") +
                ";" +
                "-fx-text-fill: #222222;" +
                "-fx-padding: 12;" +
                "-fx-background-radius: 10;"
        );

        // Añadir al historial
        messagesContainer.getChildren().add(messageBox);
    }



    /**
     * Gestiona el botón Stop.
     *
     * En una fase posterior cancelará la petición HTTP
     * que esté ejecutándose.
     */
    @FXML
    private void handleStop() {

        // La cancelación se implementará más adelante.

    }
}
