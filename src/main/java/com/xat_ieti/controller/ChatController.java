package com.xat_ieti.controller;



import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import com.xat_ieti.service.OllamaService;
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
    private Task<String> currentTask;



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

        // Mostrar Stop y ocultar Send
        setRequestRunning(true);

        // Crear la tarea
        currentTask = new Task<>() {

            @Override
            protected String call() {

                return ollamaService.ask(message).join();
            }
        };

        // Cuando termina correctamente
        currentTask.setOnSucceeded(event -> {

            String response = currentTask.getValue();

            addMessage(response, false);

            setRequestRunning(false);
            currentTask = null;
        });

        // Cuando ocurre un error
        currentTask.setOnFailed(event -> {

            Throwable error = currentTask.getException();

            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("No se ha podido obtener una respuesta");
            alert.setContentText(error.getMessage());
            alert.showAndWait();

            setRequestRunning(false);
            currentTask = null;
        });

        // Cuando se cancela la tarea
        currentTask.setOnCancelled(event -> {

            setRequestRunning(false);
            currentTask = null;
        });

        // Ejecutar en segundo plano
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
