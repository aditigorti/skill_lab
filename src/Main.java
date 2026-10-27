import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Shared Equipment Fair-Use Scheduler - command-line program.
 *
 * Start it with:   java -cp out Main
 * Optional:        java -cp out Main --data my-folder   (keep the data somewhere else)
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        Path dataDir = Paths.get("data");
        for (int i = 0; i < args.length; i++) {
            if ("--data".equals(args[i]) && i + 1 < args.length) {
                dataDir = Paths.get(args[++i]);
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                System.out.println("Usage: java -cp out Main [--data <folder>]");
                System.out.println("All data is saved in the folder 'data' unless another folder is given.");
                return;
            }
        }

        Scheduler scheduler = new Scheduler();
        boolean firstRun = !Storage.hasSavedData(dataDir);
        if (!firstRun && !loadSavedData(scheduler, dataDir)) {
            return;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        new ConsoleUI(scheduler, dataDir, in, System.out).run(firstRun);
    }

    /** Loads saved data. If any line is damaged it is skipped and a backup of the originals is kept. */
    private static boolean loadSavedData(Scheduler scheduler, Path dataDir) {
        List<String> problems;
        try {
            problems = Storage.load(scheduler, dataDir);
        } catch (IOException e) {
            System.out.println("Could not read the saved data in '" + dataDir + "': " + e.getMessage());
            System.out.println("Nothing was changed. Close any program that has those files open and try again.");
            return false;
        }
        if (!problems.isEmpty()) {
            for (String p : problems) {
                System.out.println("[!] " + p);
            }
            try {
                Path backup = Storage.backup(dataDir);
                System.out.println("[!] A copy of the original files was kept in '" + backup + "'.");
            } catch (IOException e) {
                System.out.println("[!] Could not make a backup copy (" + e.getMessage()
                        + "), so the program stops here to avoid losing data.");
                return false;
            }
        }
        return true;
    }
}
