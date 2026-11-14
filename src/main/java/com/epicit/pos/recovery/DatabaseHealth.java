package com.epicit.pos.recovery;

import com.epicit.pos.config.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * NFR-6: "opening the database <em>is</em> the integrity check". H2 runs its own recovery when a file is opened
 * and throws if the file cannot be used, so this probe simply opens it with plain JDBC, runs one cheap query and
 * closes it again, <strong>before</strong> Spring starts. A database that will not open never reaches the UI;
 * the caller launches the restore wizard instead of letting Spring die with a stack trace nobody can read.
 *
 * <p>Two outcomes must never be confused: {@link State#LOCKED} (another process, normally another copy of this
 * app, holds the file; the data is fine and offering a restore would be wrong) and {@link State#BROKEN}.
 */
public final class DatabaseHealth {

    public enum State { OK, LOCKED, BROKEN }

    public record Result(State state, String detail) {
        static Result ok() {
            return new Result(State.OK, "");
        }
    }

    /** H2 ErrorCode.DATABASE_ALREADY_OPEN_1: "Database may be already in use". */
    static final int H2_DATABASE_ALREADY_OPEN = 90020;

    private DatabaseHealth() {
    }

    public static Result check(AppPaths paths) {
        if (!Files.exists(paths.dbFile())) {
            return Result.ok();   // first run: H2 creates the file and Flyway fills it
        }
        try {
            if (Files.size(paths.dbFile()) == 0) {
                // H2 would treat a zero-byte file as a brand-new empty database and the app would start
                // "fine" with all the data gone. An empty file is what a crash on a full disk leaves behind.
                return new Result(State.BROKEN, "The data file is empty.");
            }
        } catch (IOException e) {
            return new Result(State.BROKEN, "The data file cannot be read: " + firstLine(e.getMessage()));
        }
        try {
            // Without the driver on the classpath "No suitable driver" would look like corruption: fail loudly instead.
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The H2 database driver is missing from the application.", e);
        }
        // IFEXISTS: never create a file as a side effect of probing. No other options: the probe closes fully.
        String url = paths.jdbcUrl() + ";IFEXISTS=TRUE";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES")) {
            rs.next();
            return Result.ok();
        } catch (SQLException e) {
            return classify(e);
        }
    }

    static Result classify(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == H2_DATABASE_ALREADY_OPEN) {
                return new Result(State.LOCKED, "Another copy of Epic IT POS is probably still running.");
            }
        }
        return new Result(State.BROKEN, firstLine(e.getMessage()));
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "The data file could not be opened.";
        }
        String line = message.strip().split("\\R", 2)[0];
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
