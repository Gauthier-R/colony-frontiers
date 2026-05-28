package fr.gauthier.colonyfrontiers.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logger dédié Colony Frontiers → run/logs/colonyfrontiers.log
 *
 * Écrit uniquement les événements importants du mod dans un fichier séparé,
 * facilement filtrable sans avoir à chercher dans latest.log.
 *
 * Usage : CfLogger.log("CITY_CREATED colonyId={} pos={}", id, pos);
 */
public class CfLogger {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static Path logFile = null;

    public static void init(Path gameDir) {
        try {
            Path logDir = gameDir.resolve("logs");
            Files.createDirectories(logDir);
            logFile = logDir.resolve("colonyfrontiers.log");
            // Repart d'un fichier vide à chaque démarrage
            Files.writeString(logFile, "=== Colony Frontiers Log — " +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    + " ===\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[CF:Logger] Impossible de créer le fichier log : " + e.getMessage());
        }
    }

    public static void log(String pattern, Object... args) {
        if (logFile == null) return;
        String msg = format(pattern, args);
        String line = "[" + LocalDateTime.now().format(FMT) + "] " + msg + "\n";
        try {
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private static String format(String pattern, Object[] args) {
        if (args == null || args.length == 0) return pattern;
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int i = 0;
        while (i < pattern.length()) {
            if (i < pattern.length() - 1
                    && pattern.charAt(i) == '{'
                    && pattern.charAt(i + 1) == '}') {
                sb.append(argIdx < args.length ? args[argIdx++] : "{}");
                i += 2;
            } else {
                sb.append(pattern.charAt(i++));
            }
        }
        return sb.toString();
    }
}
