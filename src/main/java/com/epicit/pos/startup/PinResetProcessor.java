package com.epicit.pos.startup;

import com.epicit.pos.config.AppPaths;
import com.epicit.pos.service.PinService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * A14: if a file named RESET-PIN sits in the data folder at startup, the PIN is removed and the forced PIN
 * setup runs. Runs after the database is open (migrations done) and before the web server accepts requests,
 * so the UI can never be served in a half-reset state. The file is deleted so the reset happens once.
 */
@Component
public class PinResetProcessor implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PinResetProcessor.class);

    private final Path resetFile;
    private final PinService pinService;

    public PinResetProcessor(AppPaths paths, PinService pinService) {
        this.resetFile = paths.resetPinFile();
        this.pinService = pinService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        processIfPresent();
    }

    /** Returns true if a reset was performed. */
    public boolean processIfPresent() {
        if (!Files.exists(resetFile)) {
            return false;
        }
        pinService.resetViaFile();
        try {
            Files.deleteIfExists(resetFile);
        } catch (IOException e) {
            log.error("PIN was reset but {} could not be deleted - delete it by hand or the PIN is reset on every start", resetFile, e);
        }
        log.warn("Owner PIN reset via {}", resetFile);
        return true;
    }
}
