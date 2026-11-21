package com.xat_ieti.controller;



import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import com.xat_ieti.service.OllamaService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;



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
    private Task<?> currentTask;
    // Imagen seleccionada por el usuario
    private File selectedImage;



    /**
     * Se ejecuta automáticamente cuando JavaFX
     * termina de cargar el archivo FXML.
     */
    @FXML
    public void initialize() {

        // El botón Stop empieza oculto
        stopButton.setVisible(false);
        stopButton.setManaged(false);

        // El contenedor de imágenes empieza oculto
        imagePreviewContainer.setVisible(false);
        imagePreviewContainer.setManaged(false);
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

        // Si hay una imagen seleccionada, procesarla y enviarla
        if (selectedImage != null) {
        
            File imageFile = selectedImage;
        
            // Mostrar el mensaje del usuario
            addMessageWithImage(message, imageFile);
            messageInput.clear();
            // Quitar la imagen de la zona de selección
            selectedImage = null;
            imagePreviewContainer.getChildren().clear();
            imagePreviewContainer.setVisible(false);
            imagePreviewContainer.setManaged(false);
        
            // Mostrar el indicador mientras se procesa la imagen
            Label responseLabel = addMessage("Thinking...", false);
        
            setRequestRunning(true);
        
            // Convertir la imagen a Base64 en segundo plano
            Task<String> imageTask = convertImageToBase64(imageFile);
        
            currentTask = imageTask;
        
            imageTask.setOnSucceeded(event -> {
            
                String base64 = imageTask.getValue();
            
                // Obtener el MIME de la imagen
                String mimeType = ollamaService.getImageMimeType(imageFile);
            
                // Crear una tarea para enviar la imagen y recibir la respuesta
                Task<String> requestTask = new Task<>() {
                
                    @Override
                    protected String call() {
                    
                        return ollamaService.sendMessageWithImage(
                                message,
                                mimeType,
                                base64
                        ).thenApply(response -> {
                        
                            if (response.statusCode() != 200) {
                                throw new CompletionException(
                                        new IllegalStateException(
                                                "Error HTTP: " + response.statusCode()
                                                        + "\n" + response.body()
                                        )
                                );
                            }
                        
                            return ollamaService.extractResponse(response.body());
                        
                        }).join();
                    }
                };
            
                currentTask = requestTask;
            
                requestTask.setOnSucceeded(requestEvent -> {
                
                    responseLabel.setText(requestTask.getValue());
                
                    setRequestRunning(false);
                    currentTask = null;
                });
            
                requestTask.setOnFailed(requestEvent -> {
                
                    Throwable error = requestTask.getException();
                
                    // Comprobar si el error se debe a una cancelación
                    if (error instanceof CancellationException
                            || (error != null
                            && error.getCause() instanceof CancellationException)) {
                            
                        setRequestRunning(false);
                        currentTask = null;
                        responseLabel.setText("Se ha cancelado la peticion.");
                        return;
                    }
                
                    responseLabel.setText("No se ha podido obtener una respuesta.");
                
                    Alert alert = new Alert(AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("Error al enviar la imagen");
                    alert.setContentText(
                            error != null ? error.getMessage() : "Error desconocido."
                    );
                    alert.showAndWait();
                
                    setRequestRunning(false);
                    currentTask = null;
                });
            
                requestTask.setOnCancelled(requestEvent -> {
                    setRequestRunning(false);
                    currentTask = null;
                });
            
                Thread requestThread = new Thread(requestTask);
                requestThread.setDaemon(true);
                requestThread.start();
            });
        
            imageTask.setOnFailed(event -> {
            
                Throwable error = imageTask.getException();
            
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("No se ha podido procesar la imagen");
                alert.setContentText(
                        error != null ? error.getMessage() : "Error desconocido."
                );
                alert.showAndWait();
            
                setRequestRunning(false);
                currentTask = null;
            });
        
            imageTask.setOnCancelled(event -> {
                setRequestRunning(false);
                currentTask = null;
            });
        
            Thread imageThread = new Thread(imageTask);
            imageThread.setDaemon(true);
            imageThread.start();
        
            return;
        }

        // Mostrar el mensaje del usuario
        addMessage(message, true);
        messageInput.clear();

        // Crear una burbuja vacía para la respuesta del asistente
        Label responseLabel = addMessage("Thinking...", false);

        setRequestRunning(true);

        // Crear una tarea que ejecutará la petición en segundo plano
        currentTask = new Task<Void>() {

            @Override
            protected Void call() {

                ollamaService.askStream(
                    message,
                    fragment -> Platform.runLater(() -> {
                    
                        // Si todavía aparece el indicador, sustituirlo
                        // por el primer fragmento recibido
                        if (responseLabel.getText().equals("Thinking...")) {
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

    // Metodo para seleccionar imagenes 
    @FXML
    private void handleAttach() {

        // Crear el selector de archivos
        FileChooser fileChooser = new FileChooser();

        // Título de la ventana
        fileChooser.setTitle("Seleccionar imagen");

        // Permitir únicamente archivos de imagen
        FileChooser.ExtensionFilter imageFilter =
                new FileChooser.ExtensionFilter(
                        "Imágenes",
                        "*.png",
                        "*.jpg",
                        "*.jpeg",
                        "*.gif",
                        "*.webp"
                );

        fileChooser.getExtensionFilters().add(imageFilter);

        // Abrir el explorador de archivos
        File file = fileChooser.showOpenDialog(
                attachButton.getScene().getWindow()
        );

        // Comprobar si se ha seleccionado un archivo
        if (file != null) {

            // Guardar la imagen seleccionada
            selectedImage = file;

            // Mostrat la miniatura de la imagen
            showImagePreview(selectedImage);
        }
    }

    // Mostrar la miniatura de la imagen encima de el input del texto
    private void showImagePreview(File file) {

        // Crear la imagen
        Image image = new Image(file.toURI().toString());

        // Crear la vista previa
        ImageView imageView = new ImageView(image);

        imageView.setFitWidth(100);
        imageView.setFitHeight(100);
        imageView.setPreserveRatio(true);

        // Añadir la miniatura al contenedor
        imagePreviewContainer.getChildren().clear();
        imagePreviewContainer.getChildren().add(imageView);

        // Mostrar el contenedor y reservar su espacio
        imagePreviewContainer.setVisible(true);
        imagePreviewContainer.setManaged(true);
    }

    // Convertir una imagen a Base64 en segundo plano
    private Task<String> convertImageToBase64(File file) {

        return new Task<>() {

            @Override
            protected String call() throws Exception {

                return ollamaService.imageToBase64(file);
            }
        };
    }

    /**
     * Añade un mensaje del usuario con texto e imagen al historial.
     *
     * @param text texto del mensaje
     * @param imageFile archivo de imagen
     */
    private void addMessageWithImage(String text, File imageFile) {

        // Crear la etiqueta del mensaje
        Label messageLabel = new Label(text);
        messageLabel.setWrapText(true);
        messageLabel.setMinWidth(0);

        messageLabel.maxWidthProperty().bind(
                messagesContainer.widthProperty()
                        .subtract(40)
                        .multiply(0.75)
        );

        // Crear la miniatura
        Image image = new Image(imageFile.toURI().toString());

        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(150);
        imageView.setFitHeight(150);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        // Contenedor vertical para el texto y la imagen
        VBox contentBox = new VBox(8);
        contentBox.getChildren().addAll(messageLabel, imageView);

        // Aplicar el mismo estilo que el mensaje normal
        contentBox.setStyle(
                "-fx-background-color: #DCEBFF;" +
                "-fx-padding: 12;" +
                "-fx-background-radius: 10;"
        );

        // Contenedor y alineación del mensaje
        HBox messageBox = new HBox(contentBox);
        messageBox.setSpacing(10);
        messageBox.setMaxWidth(Double.MAX_VALUE);
        messageBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        messagesContainer.getChildren().add(messageBox);
    }
}