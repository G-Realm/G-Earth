package gearth.app.protocol.hostreplacer.hostsfile;

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
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

final class MacOSPrivilegedHostHelper implements MacOSHostReplacer.HostHelper {

    private static final Logger LOG = LoggerFactory.getLogger(MacOSPrivilegedHostHelper.class);
    private static final String LABEL = "com.gearth.hosts-helper";
    private static final String BUNDLED_HELPER_NAME = "G-Earth-Hosts-Helper";
    private static final String PLIST_NAME = LABEL + ".plist";
    private static final Path INSTALLED_HELPER = Path.of("/Library/PrivilegedHelperTools", LABEL);
    private static final Path INSTALLED_PLIST = Path.of("/Library/LaunchDaemons", PLIST_NAME);
    private static final Path SOCKET = Path.of("/var/run", LABEL + ".sock");

    @Override
    public boolean apply(String[] lines) {
        try {
            if (!ensureInstalled()) {
                return false;
            }
            return request("APPLY\n" + String.join("\n", lines) + "\n");
        } catch (Exception e) {
            LOG.error("Failed to apply host redirects through the privileged helper", e);
            showError();
            return false;
        }
    }

    @Override
    public boolean remove() {
        try {
            if (!Files.isExecutable(INSTALLED_HELPER)) {
                return false;
            }
            return request("REMOVE\n");
        } catch (Exception e) {
            LOG.error("Failed to remove host redirects through the privileged helper", e);
            return false;
        }
    }

    private synchronized boolean ensureInstalled() throws Exception {
        final Path resources = resourcesDirectory();
        final Path bundledHelper = resources.resolve(BUNDLED_HELPER_NAME);
        final Path bundledPlist = resources.resolve(PLIST_NAME);
        if (!Files.isRegularFile(bundledHelper) || !Files.isRegularFile(bundledPlist)) {
            throw new IOException("The bundled privileged helper resources are missing");
        }

        final boolean updateRequired = !sameFileContents(bundledHelper, INSTALLED_HELPER)
                || !sameFileContents(bundledPlist, INSTALLED_PLIST);
        if (!updateRequired && isResponsive()) {
            return true;
        }
        if (!confirmInstallation(Files.exists(INSTALLED_HELPER))) {
            return false;
        }
        if (!install(bundledHelper, bundledPlist)) {
            showError();
            return false;
        }

        for (int attempt = 0; attempt < 25; attempt++) {
            if (isResponsive()) {
                return true;
            }
            Thread.sleep(200);
        }
        throw new IOException("The privileged helper did not start after installation");
    }

    private static Path resourcesDirectory() throws Exception {
        final Path location = Path.of(MacOSPrivilegedHostHelper.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        final Path codeDirectory = Files.isDirectory(location) ? location : location.getParent();
        if (Files.isRegularFile(codeDirectory.resolve(BUNDLED_HELPER_NAME))) {
            return codeDirectory;
        }

        // Packaged native resources sit above the Java directory.
        final Path parent = codeDirectory.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve(BUNDLED_HELPER_NAME))) {
            return parent;
        }
        throw new IOException("Unable to locate the bundled privileged helper resources");
    }

    private static boolean sameFileContents(Path bundled, Path installed) {
        try {
            return Files.isRegularFile(installed) && Files.mismatch(bundled, installed) == -1;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isResponsive() {
        try {
            return request("PING\n");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean install(Path helper, Path plist) throws IOException, InterruptedException {
        final String command = "set -e; "
                + "/bin/launchctl bootout system/" + LABEL + " >/dev/null 2>&1 || true; "
                + "/usr/bin/install -d -o root -g wheel -m 0755 /Library/PrivilegedHelperTools; "
                + "/usr/bin/install -o root -g wheel -m 0755 " + shellQuote(helper.toString()) + " " + shellQuote(INSTALLED_HELPER.toString()) + "; "
                + "/usr/bin/install -o root -g wheel -m 0644 " + shellQuote(plist.toString()) + " " + shellQuote(INSTALLED_PLIST.toString()) + "; "
                + "/bin/launchctl enable system/" + LABEL + "; "
                + "/bin/launchctl bootstrap system " + shellQuote(INSTALLED_PLIST.toString());
        final String appleScript = "do shell script \"" + appleScriptEscape(command)
                + "\" with administrator privileges";
        final Process process = new ProcessBuilder("/usr/bin/osascript", "-e", appleScript)
                .redirectErrorStream(true)
                .start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            LOG.error("Privileged helper installation failed: {}", output.trim());
            return false;
        }
        return true;
    }

    private static boolean request(String request) throws IOException {
        final UnixDomainSocketAddress address = UnixDomainSocketAddress.of(SOCKET);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            final ByteBuffer requestBuffer = StandardCharsets.UTF_8.encode(request);
            while (requestBuffer.hasRemaining()) {
                channel.write(requestBuffer);
            }
            channel.shutdownOutput();

            final ByteBuffer responseBuffer = ByteBuffer.allocate(1024);
            while (responseBuffer.hasRemaining() && channel.read(responseBuffer) >= 0) {
                // Drain the response.
            }
            responseBuffer.flip();
            final String response = StandardCharsets.UTF_8.decode(responseBuffer).toString().trim();
            if (!response.startsWith("OK")) {
                LOG.error("Privileged helper rejected request: {}", response);
                return false;
            }
            return true;
        }
    }

    private static boolean confirmInstallation(boolean update) throws IOException, InterruptedException {
        final FutureTask<Boolean> dialog = new FutureTask<>(() -> {
            final Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.YES, ButtonType.NO);
            alert.setTitle(LanguageBundle.get("alert.macoshelper.title"));
            alert.setHeaderText(LanguageBundle.get("alert.macoshelper.title"));
            final String key = update ? "alert.macoshelper.update" : "alert.macoshelper.install";
            alert.getDialogPane().setContent(new Label(
                    LanguageBundle.get(key).replaceAll("\\\\n", System.lineSeparator())
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
            throw new IOException("Failed to show privileged helper confirmation", e.getCause());
        }
    }

    private static void showError() {
        Platform.runLater(() -> {
            final Alert alert = new Alert(Alert.AlertType.ERROR,
                    LanguageBundle.get("alert.macoshelper.failed"), ButtonType.OK);
            alert.setTitle(LanguageBundle.get("alert.macoshelper.title"));
            alert.setHeaderText(LanguageBundle.get("alert.macoshelper.title"));
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            try {
                TitleBarAlert.create(alert).showAlert();
            } catch (IOException e) {
                LOG.error("Failed to show privileged helper error", e);
            }
        });
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String appleScriptEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
