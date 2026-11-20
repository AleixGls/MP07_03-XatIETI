package com.xat_ieti.controller;



import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import com.xat_ieti.service.OllamaService;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;



public class ChatController {

    // Componentes
    @FXML
    private VBox messagesContainer;
    @FXML
    private HBox imagePreviewContainer;
    @FXML
    private Button attachButton;
    @FXML
    private TextArea messageInput;
    @FXML
    private Button sendButton;
    @FXML
    private Button stopButton;

    // Atributos
    // Servicio de comunicación con Qwen38
    private final OllamaService ollamaService = new OllamaService();
    // Tarea de la petición actual
    private Task<Void> currentTask;



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

        if (message.isEmpty()) {
            return;
        }

        // Mostrar el mensaje del usuario
        addMessage(message, true);
        messageInput.clear();

        // Crear una burbuja vacía para la respuesta del asistente
        Label responseLabel = addMessage("Pensando...", false);

        setRequestRunning(true);

        // Crear una tarea que ejecutará la petición en segundo plano
        currentTask = new Task<>() {

            @Override
            protected Void call() {

                ollamaService.askStream(
                    message,
                    fragment -> Platform.runLater(() -> {
                    
                        // Si todavía aparece el indicador, sustituirlo
                        // por el primer fragmento recibido
                        if (responseLabel.getText().equals("Pensando...")) {
                            responseLabel.setText(fragment);
                        } else {
                            // Añadir los siguientes fragmentos
                            responseLabel.setText(
                                    responseLabel.getText() + fragment
                            );
                        }
                    
                    })
                ).join();

                return null;
            }
        };

        // Cuando termina correctamente
        currentTask.setOnSucceeded(event -> {
            setRequestRunning(false);
            currentTask = null;
        });

        // Si se produce un error
        currentTask.setOnFailed(event -> {

            Throwable error = currentTask.getException();

            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("No se ha podido obtener una respuesta");
            alert.setContentText(
                    error != null ? error.getMessage() : "Error desconocido."
            );
            alert.showAndWait();

            setRequestRunning(false);
            currentTask = null;
        });

        // Si el usuario cancela la tarea
        currentTask.setOnCancelled(event -> {
            setRequestRunning(false);
            currentTask = null;
        });

        // Ejecutar la tarea en un hilo independiente
        Thread thread = new Thread(currentTask);
        thread.setDaemon(true);
        thread.start();
    }


    /**
     * Añade un mensaje al historial de conversación.
     *
     * @param text texto del mensaje
     * @param user true si el mensaje es del usuario
    */
    private Label addMessage(String text, boolean user) {
        Label messageLabel = new Label(text);
        messageLabel.setWrapText(true);
        messageLabel.setMinWidth(0);

        messageLabel.maxWidthProperty().bind(
                messagesContainer.widthProperty()
                        .subtract(40)
                        .multiply(0.75)
        );

        HBox messageBox = new HBox(messageLabel);
        messageBox.setSpacing(10);
        messageBox.setMaxWidth(Double.MAX_VALUE);

        messageBox.setAlignment(
                user ? javafx.geometry.Pos.CENTER_RIGHT
                     : javafx.geometry.Pos.CENTER_LEFT
        );

        messageLabel.setStyle(
                "-fx-background-color: " +
                (user ? "#DCEBFF" : "#EAEAEA") +
                ";" +
                "-fx-text-fill: #222222;" +
                "-fx-padding: 12;" +
                "-fx-background-radius: 10;"
        );

        messagesContainer.getChildren().add(messageBox);

        return messageLabel;
    }



    /**
     * Gestiona el botón Stop.
     *
     * En una fase posterior cancelará la petición HTTP
     * que esté ejecutándose.
     */
    @FXML
    private void handleStop() {

        // Cancelar la petición HTTP al servidor
        ollamaService.cancelCurrentRequest();

        // Cancelar la tarea de JavaFX
        if (currentTask != null) {
            currentTask.cancel();
        }
    }

    // Metodo para intercambiar los botones de Send y Stop entre si
    private void setRequestRunning(boolean running) {

        sendButton.setVisible(!running);
        sendButton.setManaged(!running);

        stopButton.setVisible(running);
        stopButton.setManaged(running);
    }
}
