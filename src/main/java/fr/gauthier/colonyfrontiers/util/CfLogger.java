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
        // Cherche le bon répertoire de logs dans cet ordre :
        // 1. gameDir/logs/ (FMLPaths.GAMEDIR = répertoire de travail Forge)
        // 2. ./logs/        (répertoire courant du processus Java)
        Path resolved = tryInitDir(gameDir.resolve("logs"));
        if (resolved == null) resolved = tryInitDir(Paths.get("logs"));
        if (resolved == null) resolved = tryInitDir(Paths.get("run", "logs"));
        if (resolved == null) {
            System.err.println("[CF:Logger] Aucun répertoire de logs trouvé. gameDir=" + gameDir);
        }
    }

    private static Path tryInitDir(Path logDir) {
        try {
            Files.createDirectories(logDir);
            Path f = logDir.resolve("colonyfrontiers.log");
            Files.writeString(f,
                    "=== Colony Frontiers Log — "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    + " ===\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            logFile = f;
            System.out.println("[CF:Logger] Log initialisé : " + f.toAbsolutePath());
            return logDir;
        } catch (IOException e) {
            return null;
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
