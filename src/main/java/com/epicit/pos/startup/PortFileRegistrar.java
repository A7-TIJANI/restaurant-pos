package com.epicit.pos.startup;

import com.epicit.pos.config.AppPaths;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Once Tomcat is up on its OS-assigned port: write {@code port.txt} (for the single-instance guard)
 * and open the browser. Removes the file on shutdown; a stale file after a crash is harmless because
 * the guard verifies the port with /ping.
 */
@Component
public class PortFileRegistrar implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(PortFileRegistrar.class);

    private final Path portFile;
    private final boolean openBrowser;

    public PortFileRegistrar(AppPaths paths, @Value("${epicit.open-browser:true}") boolean openBrowser) {
        this.portFile = paths.portFile();
        this.openBrowser = openBrowser;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        try {
            Files.createDirectories(portFile.toAbsolutePath().getParent());
            Files.writeString(portFile, Integer.toString(port));
        } catch (IOException e) {
            log.warn("Could not write {}: single-instance guard will not work", portFile, e);
        }
        log.info("Epic IT POS listening on http://127.0.0.1:{}/", port);
        if (openBrowser) {
            BrowserLauncher.open("http://127.0.0.1:" + port + "/");
        }
    }

    @PreDestroy
    void removePortFile() {
        try {
            Files.deleteIfExists(portFile);
        } catch (IOException e) {
            log.debug("Could not delete {}", portFile, e);
        }
    }
}
