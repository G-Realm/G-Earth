package gearth.app.ui;

import gearth.app.GEarth;
import gearth.app.protocol.HConnection;
import gearth.app.protocol.connection.HState;
import gearth.services.extension_handler.extensions.GEarthExtension;
import gearth.app.services.extension_handler.extensions.extensionproducers.ExtensionProducerFactory;
import gearth.app.services.extension_handler.extensions.implementations.network.NetworkExtensionServer;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Adds a {@link TrayIcon} to the {@link SystemTray} for this G-Earth instance.
 *
 * @author Dorving
 */
public final class GEarthTrayIcon {

    private static final String TO_FRONT_LABEL = "To front";
    private static final String TO_BACK_LABEL = "To back";

    private static final int DOT_DIAMETER = 15;
    private static final int DOT_MARGIN = 1;

    private static PopupMenu menu;
    private static Image baseImage;
    private static TrayIcon currentTrayIcon;
    private static HState currentState = HState.NOT_CONNECTED;

    public static void setHConnection(HConnection hConnection) {
        hConnection.getStateObservable().addListener((oldState, newState) ->
                Platform.runLater(() -> onStateChanged(newState)));
    }

    public static void updateOrCreate(Image image) {

        if (!SystemTray.isSupported())
            return;

        baseImage = image;

        final NetworkExtensionServer server = ExtensionProducerFactory.getExtensionServer();
        final String appTitle = "G-Earth " + GEarth.version + " (" + server.getPort() + ")";

        final Optional<TrayIcon> trayIcon = Stream.of(SystemTray.getSystemTray().getTrayIcons())
                .filter(other -> Objects.equals(other.getToolTip(), appTitle))
                .findFirst();
        if (trayIcon.isPresent()) {
            currentTrayIcon = trayIcon.get();
            EventQueue.invokeLater(() -> currentTrayIcon.setImage(composeIconWithDot(baseImage, currentState)));
        } else {
            menu = new PopupMenu();
            menu.add(createToFrontOrBackMenuItem());
            menu.addSeparator();
            menu.addSeparator();
            menu.add(createInstallMenuItem());
            try {
                currentTrayIcon = new TrayIcon(composeIconWithDot(baseImage, currentState), appTitle, menu);
                currentTrayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(currentTrayIcon);
            } catch (AWTException e) {
                e.printStackTrace();
                menu = null;
                currentTrayIcon = null;
            }
        }
    }

    private static void onStateChanged(HState newState) {
        currentState = newState;
        if (currentTrayIcon != null && baseImage != null) {
            final BufferedImage composed = composeIconWithDot(baseImage, newState);
            EventQueue.invokeLater(() -> currentTrayIcon.setImage(composed));
        }
    }

    private static BufferedImage composeIconWithDot(Image fxImage, HState state) {
        final BufferedImage awtImage = SwingFXUtils.fromFXImage(fxImage, null);
        if (awtImage == null) return null;

        final Color dotColor;
        switch (state) {
            case CONNECTED:     dotColor = Color.GREEN;  break;
            case NOT_CONNECTED: dotColor = Color.RED;    break;
            default:            dotColor = Color.ORANGE;  break;
        }

        drawStatusDot(awtImage, dotColor);
        return awtImage;
    }

    private static void drawStatusDot(BufferedImage image, Color color) {
        final Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        double x = image.getWidth() - DOT_DIAMETER - DOT_MARGIN;
        double y = image.getHeight() - DOT_DIAMETER - DOT_MARGIN;
        g2.fill(new Ellipse2D.Double(x, y, DOT_DIAMETER, DOT_DIAMETER));
        g2.dispose();
    }

    private static MenuItem createToFrontOrBackMenuItem() {
        final MenuItem showMenuItem = new MenuItem(TO_FRONT_LABEL);
        showMenuItem.addActionListener(e -> {
            if (Objects.equals(showMenuItem.getLabel(), TO_FRONT_LABEL)) {
                showMenuItem.setLabel(TO_BACK_LABEL);
                Platform.runLater(() -> GEarth.main.getController().getStage().toFront());
            } else {
                showMenuItem.setLabel(TO_FRONT_LABEL);
                Platform.runLater(() -> GEarth.main.getController().getStage().toBack());
            }
        });
        return showMenuItem;
    }

    private static MenuItem createInstallMenuItem() {
        final MenuItem showMenuItem = new MenuItem("Install Extension...");
        showMenuItem.addActionListener(e ->
                Optional.ofNullable(GEarth.main.getController())
                        .map(c -> c.extensionsController)
                        .ifPresent(c -> Platform.runLater(() -> {
                            final Stage stage = c.parentController.getStage();
                            final boolean isOnTop = stage.isAlwaysOnTop();
                            stage.setAlwaysOnTop(true); // bit of a hack to force stage to front
                            c.installBtnClicked(null);
                            stage.setAlwaysOnTop(isOnTop);
                        })));
        return showMenuItem;
    }

    /**
     * Adds the argued extension as a menu item to {@link #menu}.
     *
     * @param extension the {@link GEarthExtension} to add to the {@link #menu}.
     */
    public static void addExtension(GEarthExtension extension) {

        if (menu == null)
            return;

        final MenuItem menuItem = new MenuItem("Show "+extension.getTitle());
        EventQueue.invokeLater(() -> menu.insert(menuItem, 2));
        menuItem
                .addActionListener(e -> Platform.runLater(() -> extension.getClickedObservable().fireEvent()));
        extension.getDeletedObservable()
                .addListener(() -> EventQueue.invokeLater(() -> menu.remove(menuItem)));
    }
}
