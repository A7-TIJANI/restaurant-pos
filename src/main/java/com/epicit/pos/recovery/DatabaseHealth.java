package com.epicit.pos.recovery;

import com.epicit.pos.config.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

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

    /** {@code releasePending}: the refusal looks like a previous run still closing the file, so waiting may help. */
    public record Result(State state, String detail, boolean releasePending) {
        public Result(State state, String detail) {
            this(state, detail, state == State.LOCKED);
        }

        static Result ok() {
            return new Result(State.OK, "", false);
        }
    }

    /** H2 ErrorCode.DATABASE_ALREADY_OPEN_1: "Database may be already in use". */
    static final int H2_DATABASE_ALREADY_OPEN = 90020;

    /** Another process is still closing the file (a quick close-and-reopen): worth waiting for. */
    static final int RETRIES = 8;
    static final long RETRY_DELAY_MILLIS = 1000;

    /** H2 codes seen while a previous run is still letting go of the file (see {@link #isReleasePending}). */
    private static final int H2_WRONG_USER_OR_PASSWORD = 28000;
    private static final int H2_DATABASE_IS_CLOSED = 90098;
    private static final int H2_DATABASE_CALLED_AT_SHUTDOWN = 90121;
    private static final int H2_LOCK_TIMEOUT = 50200;

    private DatabaseHealth() {
    }

    /**
     * {@link #check} that waits out a database the previous run is still releasing. The owner can press Close POS and
     * double-click the exe again within seconds; for a moment H2 then refuses the file, sometimes as "already in use"
     * and sometimes as 28000 "wrong user name or password" (the app only ever uses its own credentials, so that code
     * means "not released yet", not a bad password). Up to {@link #RETRIES} retries, one second apart. A database that is
     * still refused after that falls through to the normal handling (the lock message or the restore wizard).
     */
    public static Result checkWithRetry(AppPaths paths) {
        return checkWithRetry(() -> check(paths), RETRIES, RETRY_DELAY_MILLIS, DatabaseHealth::sleep);
    }

    static Result checkWithRetry(Supplier<Result> probe, int retries, long delayMillis, LongConsumer sleeper) {
        Result result = probe.get();
        for (int attempt = 1; attempt <= retries && !isOkOrFinal(result); attempt++) {
            System.err.println("The database is still being released by the previous run; retrying (" + attempt + "/" + retries + ").");
            sleeper.accept(delayMillis);
            result = probe.get();
        }
        return result;
    }

    private static boolean isOkOrFinal(Result result) {
        return result.state() == State.OK || (result.state() == State.BROKEN && !result.releasePending());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        return new Result(State.BROKEN, firstLine(e.getMessage()), isReleasePending(e));
    }

    /** True for the H2 refusals that a previous run still holding the file produces; never for real corruption. */
    static boolean isReleasePending(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                int code = sql.getErrorCode();
                if (code == H2_WRONG_USER_OR_PASSWORD || code == H2_DATABASE_IS_CLOSED
                        || code == H2_DATABASE_CALLED_AT_SHUTDOWN || code == H2_LOCK_TIMEOUT) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "The data file could not be opened.";
        }
        String line = message.strip().split("\\R", 2)[0];
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
