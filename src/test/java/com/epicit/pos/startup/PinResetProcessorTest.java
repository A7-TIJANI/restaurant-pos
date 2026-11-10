package com.epicit.pos.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.config.AppPaths;
import com.epicit.pos.service.PinService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** A14: the RESET-PIN file triggers exactly one reset, then removes itself. */
@IntegrationTest
class PinResetProcessorTest {

    @Autowired PinService pins;
    @TempDir Path dir;

    @Test
    void noFileMeansNothingHappens() {
        pins.setInitialPin("4821");

        boolean reset = new PinResetProcessor(new AppPaths(dir), pins).processIfPresent();

        assertThat(reset).isFalse();
        assertThat(pins.state().pinSet()).isTrue();
    }

    @Test
    void fileResetsThePinOnceAndIsDeleted() throws IOException {
        pins.setInitialPin("4821");
        AppPaths paths = new AppPaths(dir);
        Files.createFile(paths.resetPinFile());
        PinResetProcessor processor = new PinResetProcessor(paths, pins);

        assertThat(processor.processIfPresent()).isTrue();

        assertThat(Files.exists(paths.resetPinFile())).isFalse();
        assertThat(pins.state().pinSet()).isFalse();
        assertThat(pins.state().resetAt()).isNotNull();

        // a second startup finds no file, so a PIN set in between is not wiped again
        pins.setInitialPin("5555");
        assertThat(processor.processIfPresent()).isFalse();
        assertThat(pins.state().pinSet()).isTrue();
    }

    @Test
    void resetPinFileLivesInTheDataFolderNextToTheDatabase() {
        AppPaths paths = new AppPaths(dir);
        assertThat(paths.resetPinFile().getParent()).isEqualTo(dir);
        assertThat(paths.resetPinFile().getFileName().toString()).isEqualTo("RESET-PIN");
    }
}
