package com.musicbus.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;

public final class MusicBusLog {

    private static final Path LOG = Path.of(
            System.getProperty("java.io.tmpdir"),
            "musicbus-agent.log"
    );

    private MusicBusLog() {}

    public static synchronized void log(String message) {
        String line = LocalDateTime.now()
                + " [MusicBus] " + message
                + System.lineSeparator();

        System.out.print(line);

        try {
            Files.writeString(
                    LOG,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Throwable error) {
            System.err.println(
                    "[MusicBus] Cannot write log: " + error
            );
        }
    }

    public static void error(String message, Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));

        log(message + System.lineSeparator() + writer);
    }
}