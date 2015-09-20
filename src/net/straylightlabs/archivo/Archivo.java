/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of Archivo.
 *
 * Archivo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Archivo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Archivo.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.straylightlabs.archivo;

import ch.qos.logback.classic.Level;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import net.straylightlabs.archivo.controller.ArchiveQueueManager;
import net.straylightlabs.archivo.model.Recording;
import net.straylightlabs.archivo.model.Tivo;
import net.straylightlabs.archivo.view.RecordingDetailsController;
import net.straylightlabs.archivo.view.RecordingListController;
import net.straylightlabs.archivo.view.RootLayoutController;
import net.straylightlabs.archivo.view.SetupDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Archivo extends Application {
    private Stage primaryStage;
    private String mak;
    private final StringProperty statusText;
    private final ExecutorService rpcExecutor;
    private final UserPrefs prefs;
    private RootLayoutController rootController;
    private RecordingListController recordingListController;
    private RecordingDetailsController recordingDetailsController;
    private final ArchiveQueueManager archiveQueueManager;

    public final static Logger logger;

    public static final String APPLICATION_NAME = "Archivo";
    public static final String APPLICATION_RDN = "net.straylightlabs.archivo";
    public static final String APPLICATION_VERSION = "0.1.0";
    public static final String USER_AGENT = String.format("%s/%s", APPLICATION_NAME, APPLICATION_VERSION);
    public static final int WINDOW_MIN_HEIGHT = 400;
    public static final int WINDOW_MIN_WIDTH = 555;

    static {
        logger = LoggerFactory.getLogger(Archivo.class.toString());
    }

    public Archivo() {
        super();
        prefs = new UserPrefs();
        statusText = new SimpleStringProperty();
        rpcExecutor = Executors.newSingleThreadExecutor();
        archiveQueueManager = new ArchiveQueueManager(this);
    }

    private void setLogLevel() {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        if (prefs.isLogVerbose()) {
            root.setLevel(Level.DEBUG);
        } else {
            root.setLevel(Level.ERROR);
        }
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        if (!prefs.parseParameters(getParameters())) {
            cleanShutdown();
        }
        setLogLevel();

        logger.info("Starting up...");

        this.primaryStage = primaryStage;
        this.primaryStage.setTitle(APPLICATION_NAME);
        this.primaryStage.setMinHeight(WINDOW_MIN_HEIGHT);
        this.primaryStage.setMinWidth(WINDOW_MIN_WIDTH);
        restoreWindowDimensions();

        initRootLayout();

        mak = prefs.getMAK();
        if (mak == null) {
            try {
                SetupDialog dialog = new SetupDialog(primaryStage);
                mak = dialog.promptUser();
                prefs.setMAK(mak);
            } catch (IllegalStateException e) {
                logger.error("Error getting MAK from user: ", e);
                cleanShutdown();
            }
        }
        List<Tivo> initialTivos = prefs.getKnownDevices(mak);
        initRecordingDetails();
        initRecordingList(initialTivos);

        archiveQueueManager.addObserver(recordingListController);

        primaryStage.setOnCloseRequest(e -> {
            if (!confirmTaskCancellation()) {
                e.consume();
            } else {
                archiveQueueManager.cancelAllArchiveTasks();
                cleanShutdown();
            }
        });
    }

    public void cleanShutdown() {
        if (!confirmTaskCancellation()) {
            return;
        }

        saveWindowDimensions();

        int waitTimeMS = 100;
        int msLimit = 5000;
        if (archiveQueueManager.hasTasks()) {
            try {
                int msWaited = 0;
                archiveQueueManager.cancelAllArchiveTasks();
                while (archiveQueueManager.hasTasks() && msWaited < msLimit) {
                    Thread.sleep(waitTimeMS);
                    msWaited += waitTimeMS;
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for archive tasks to shutdown: ", e);
            }
        }
        logger.info("Shutting down.");
        Platform.exit();
        System.exit(0);
    }

    /**
     * If there are active tasks, prompt the user before exiting.
     * Returns true if the user wants to cancel all tasks and exit.
     */
    private boolean confirmTaskCancellation() {
        if (archiveQueueManager.hasTasks()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Cancel All Tasks?");
            alert.setHeaderText("Really cancel all tasks and exit?");
            alert.setContentText("You are currently archiving recordings from your TiVo. Are you sure you want to " +
                    "close Archivo and cancel these tasks?");

            ButtonType cancelButtonType = new ButtonType("Cancel tasks and exit", ButtonBar.ButtonData.NO);
            ButtonType keepButtonType = new ButtonType("Keep archiving", ButtonBar.ButtonData.CANCEL_CLOSE);

            alert.getButtonTypes().setAll(cancelButtonType, keepButtonType);
            ((Button) alert.getDialogPane().lookupButton(cancelButtonType)).setDefaultButton(false);
            ((Button) alert.getDialogPane().lookupButton(keepButtonType)).setDefaultButton(true);

            Optional<ButtonType> result = alert.showAndWait();
            return (result.get() == cancelButtonType);
        }
        return true;
    }

    private void saveWindowDimensions() {
        if (primaryStage.isMaximized()) {
            getUserPrefs().setWindowMaximized(true);
        } else {
            getUserPrefs().setWindowMaximized(false);
            getUserPrefs().setWindowWidth((int) primaryStage.getWidth());
            getUserPrefs().setWindowHeight((int) primaryStage.getHeight());
        }
    }

    private void restoreWindowDimensions() {
        primaryStage.setWidth(getUserPrefs().getWindowWidth());
        primaryStage.setHeight(getUserPrefs().getWindowHeight());
        primaryStage.setMaximized(getUserPrefs().isWindowMaximized());
    }

    private void initRootLayout() {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(Archivo.class.getResource("view/RootLayout.fxml"));
            BorderPane rootLayout = loader.load();

            rootController = loader.getController();
            rootController.setMainApp(this);

            Scene scene = new Scene(rootLayout);
            scene.getStylesheets().add("style.css");
            primaryStage.setScene(scene);
            primaryStage.show();
        } catch (IOException e) {
            logger.error("Error initializing main window: ", e);
        }
    }

    private void initRecordingList(List<Tivo> initialTivos) {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(Archivo.class.getResource("view/RecordingList.fxml"));

            recordingListController = new RecordingListController(this, initialTivos);
            loader.setController(recordingListController);

            Pane recordingList = loader.load();
            rootController.getMainGrid().add(recordingList, 0, 0);

            recordingListController.addRecordingChangedListener(
                    (observable, oldValue, newValue) -> recordingDetailsController.showRecording(newValue)
            );
            recordingListController.startTivoSearch();
        } catch (IOException e) {
            logger.error("Error initializing recording list: ", e);
        }
    }

    private void initRecordingDetails() {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(Archivo.class.getResource("view/RecordingDetails.fxml"));

            recordingDetailsController = new RecordingDetailsController(this);
            loader.setController(recordingDetailsController);

            Pane recordingDetails = loader.load();
            rootController.getMainGrid().add(recordingDetails, 0, 1);
        } catch (IOException e) {
            logger.error("Error initializing recording details: ", e);
        }
    }

    public void enqueueRecordingForArchiving(Recording recording) {
        if (!archiveQueueManager.enqueueArchiveTask(recording, getActiveTivo(), getMak())) {
            logger.error("Error adding recording to queue");
        }
    }

    public void cancelArchiving(Recording recording) {
        archiveQueueManager.cancelArchiveTask(recording);
    }

    public Stage getPrimaryStage() {
        return primaryStage;
    }

    public Tivo getActiveTivo() {
        return recordingListController.getSelectedTivo();
    }

    public ExecutorService getRpcExecutor() {
        return rpcExecutor;
    }

    public StringProperty statusTextProperty() {
        return statusText;
    }

    public void setStatusText(String status) {
        logger.info("Setting status to '{}'", status);
        statusText.set(status);
        rootController.showStatus();
    }

    public void clearStatusText() {
        logger.info("TaskStatus cleared");
        rootController.hideStatus();
    }

    public void showErrorMessage(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Something went wrong...");
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public void updateMAK(String newMak) {
        this.mak = newMak;
        prefs.setMAK(newMak);
        recordingListController.updateMak(newMak);
    }

    public RecordingDetailsController getRecordingDetailsController() {
        return recordingDetailsController;
    }

    public UserPrefs getUserPrefs() {
        return prefs;
    }

    public String getMak() {
        return mak;
    }

    public void setLastDevice(Tivo tivo) {
        prefs.setLastDevice(tivo);
    }

    public Tivo getLastDevice() {
        return prefs.getLastDevice(mak);
    }

    public void setKnownDevices(List<Tivo> tivos) {
        prefs.setKnownDevices(tivos);
    }

    public Path getLastFolder() {
        return prefs.getLastFolder();
    }

    public void setLastFolder(Path lastFolder) {
        prefs.setLastFolder(lastFolder);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
