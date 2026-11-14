package gearth.app.misc;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets other processes bring their own window to the front.
 *
 * <p>Windows only accepts {@code SetForegroundWindow} from the foreground process (or from a
 * process the foreground process explicitly authorized). Every other call is rejected, and
 * Windows notifies the user by flashing the taskbar button instead of raising the window.
 * This matters for extensions: they run in their own JVM, so the request to show their window
 * is performed by a background process while G-Earth is the foreground one.</p>
 */
public final class ForegroundWindowAuthorizer {

    private static final Logger LOG = LoggerFactory.getLogger(ForegroundWindowAuthorizer.class);

    /** Windows {@code ASFW_ANY}, meaning the authorization is not restricted to a single process. */
    private static final int ANY_PROCESS = -1;

    private ForegroundWindowAuthorizer() {}

    /**
     * Authorizes every process to take the foreground until the next user input event.
     * Does nothing on operating systems other than Windows.
     */
    public static void allowAnyProcess() {
        if (!OSValidator.isWindows()) return;

        try {
            if (!Native.load("user32", User32.class).AllowSetForegroundWindow(ANY_PROCESS)) {
                LOG.debug("Could not authorize other processes to take the foreground, "
                        + "G-Earth is probably not the foreground process");
            }
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LOG.debug("AllowSetForegroundWindow is unavailable", e);
        }
    }

    private interface User32 extends StdCallLibrary {
        boolean AllowSetForegroundWindow(int dwProcessId);
    }
}
