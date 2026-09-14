package gearth.app.protocol.connection.proxy.flash.macos;

import gearth.app.ui.titlebar.TitleBarAlert;
import gearth.app.ui.translations.LanguageBundle;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class MacOSFlashClientPreparation {

    private static final Logger LOG = LoggerFactory.getLogger(MacOSFlashClientPreparation.class);

    private MacOSFlashClientPreparation() {
    }

    public static boolean prepare() {
        try {
            final Optional<MacOSFlashClientResigner> located = MacOSFlashClientResigner.locate();
            if (located.isEmpty()) {
                LOG.warn("Could not find an installed Habbo Flash client to prepare");
                return true;
            }

            final MacOSFlashClientResigner resigner = located.get();
            if (resigner.isPrepared()) {
                return true;
            }
            if (resigner.isRunning()) {
                showError(LanguageBundle.get("alert.macosflash.running"));
                return false;
            }
            if (!confirmPreparation()) {
                return false;
            }

            final Path backup = resigner.resign();
            LOG.info("Prepared Habbo Flash client. Original bundle saved to {}", backup);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while preparing the Habbo Flash client", e);
            showError(LanguageBundle.get("alert.macosflash.failed"));
        } catch (IOException e) {
            LOG.error("Failed to prepare the Habbo Flash client", e);
            showError(LanguageBundle.get("alert.macosflash.failed") + System.lineSeparator() + System.lineSeparator() + e.getMessage());
        }

        return false;
    }

    private static boolean confirmPreparation() throws IOException, InterruptedException {
        final FutureTask<Boolean> dialog = new FutureTask<>(() -> {
            final Alert alert = new Alert(Alert.AlertType.WARNING, "", ButtonType.YES, ButtonType.NO);
            alert.setTitle(LanguageBundle.get("alert.macosflash.title"));
            alert.setHeaderText(LanguageBundle.get("alert.macosflash.title"));
            alert.getDialogPane().setContent(new Label(
                    LanguageBundle.get("alert.macosflash.content").replaceAll("\\\\n", System.lineSeparator())
            ));
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            return TitleBarAlert.create(alert).showAlertAndWait()
                    .filter(ButtonType.YES::equals)
                    .isPresent();
        });

        if (Platform.isFxApplicationThread()) {
            dialog.run();
        } else {
            Platform.runLater(dialog);
        }

        try {
            return dialog.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to show Habbo preparation confirmation", e.getCause());
        }
    }

    private static void showError(String message) {
        Platform.runLater(() -> {
            final Alert alert = new Alert(Alert.AlertType.ERROR, "", ButtonType.OK);
            alert.setTitle(LanguageBundle.get("alert.macosflash.title"));
            alert.setHeaderText(LanguageBundle.get("alert.macosflash.title"));
            alert.getDialogPane().setContent(new Label(message.replaceAll("\\\\n", System.lineSeparator())));
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            try {
                TitleBarAlert.create(alert).showAlert();
            } catch (IOException e) {
                LOG.error("Failed to show Habbo preparation error", e);
            }
        });
    }
}
