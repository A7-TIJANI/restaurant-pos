package com.epicit.pos.startup;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.Locale;

/** Opens the POS in the default browser. Best-effort: failure must never stop the app. */
public final class BrowserLauncher {

    private BrowserLauncher() {
    }

    public static void open(String url) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            }
        } catch (Exception e) {
            System.err.println("Could not open the browser automatically. Open " + url + " manually.");
        }
    }
}
