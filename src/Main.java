import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    static final Pattern gcCpuPattern = Pattern.compile("gc,cpu");
    static final Pattern gcPhasesPattern = Pattern.compile("gc,phases");
    static final Pattern gcWhiteSpacePattern = Pattern.compile("gc\\s");
    static final Pattern gcNumPattern = Pattern.compile("GC\\([0-9]+\\)");
    private static final int BUFFER_SIZE = 8 * 1024;
    private static int OUT_FILE_NO;

    public static void main(String[] args) {
        try {
            launch();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private static void launch() throws IOException {
        for (GCType gcType : GCType.values()) {
            performGCAnalysis(gcType);
        }
    }

    private static void performGCAnalysis(GCType gcType) throws IOException {
        String command = buildExecutableCommand(buildCLI(gcType, 300, 3000), false);
        System.out.println(command);
        Process process = invokeRuntime(command);
        System.out.println(yieldGCTimeFromSource(yieldOutputStrings(process), null, gcType));
    }

    /***
     * @param gcType gcType
     * @param startHeapSize Start heap size in MB
     * @param maxHeapSize Maximum heap size in MB
     * @return CLI
     */
    private static CLI buildCLI(GCType gcType, int startHeapSize, int maxHeapSize) {
        CLI.VMOptions.Xms.setSize(startHeapSize);
        CLI.VMOptions.Xmx.setSize(maxHeapSize);
        CLI cli = new CLI(gcType);
        switch(gcType) {
            case SERIAL, PARALLEL -> cli.withVMOptions(CLI.VMOptions.Xms, CLI.VMOptions.Xmx)
                    .withGCOptions(CLI.VMOptions.GCOptions.VerboseGC)
                    .withXlogOptions(CLI.VMOptions.XlogOptions.GCStart, CLI.VMOptions.XlogOptions.GCHeap,
                            CLI.VMOptions.XlogOptions.GCMetaspace, CLI.VMOptions.XlogOptions.GCCpu,
                            CLI.VMOptions.XlogOptions.GCHeapExit, CLI.VMOptions.XlogOptions.GCHeapCoops, CLI.VMOptions.XlogOptions.GCPhases,
                            CLI.VMOptions.XlogOptions.GCPhasesStart);

            case G1 -> cli.withVMOptions(CLI.VMOptions.Xms, CLI.VMOptions.Xmx)
                    .withGCOptions(CLI.VMOptions.GCOptions.VerboseGC)
                    .withXlogOptions(CLI.VMOptions.XlogOptions.GCStart, CLI.VMOptions.XlogOptions.GCHeap, CLI.VMOptions.XlogOptions.GCMetaspace,
                            CLI.VMOptions.XlogOptions.GCCpu, CLI.VMOptions.XlogOptions.GCHeapExit, CLI.VMOptions.XlogOptions.GCHeapCoops,
                            CLI.VMOptions.XlogOptions.GCPhases, CLI.VMOptions.XlogOptions.GCPhasesStart, CLI.VMOptions.XlogOptions.GCTask,
                            CLI.VMOptions.XlogOptions.GCCds);

            case ZGC -> cli.withVMOptions(CLI.VMOptions.Xms, CLI.VMOptions.Xmx)
                    .withGCOptions(CLI.VMOptions.GCOptions.VerboseGC, CLI.VMOptions.GCOptions.UnlockExperimental)
                    .withXlogOptions(CLI.VMOptions.XlogOptions.GCStart, CLI.VMOptions.XlogOptions.GCHeap, CLI.VMOptions.XlogOptions.GCMetaspace,
                            CLI.VMOptions.XlogOptions.GCCpu, CLI.VMOptions.XlogOptions.GCHeapExit, CLI.VMOptions.XlogOptions.GCHeapCoops,
                            CLI.VMOptions.XlogOptions.GCPhases, CLI.VMOptions.XlogOptions.GCPhasesStart, CLI.VMOptions.XlogOptions.GCInit,
                            CLI.VMOptions.XlogOptions.GCLoad, CLI.VMOptions.XlogOptions.GCMMU, CLI.VMOptions.XlogOptions.GCMarking,
                            CLI.VMOptions.XlogOptions.GCReloc, CLI.VMOptions.XlogOptions.GCNMethod, CLI.VMOptions.XlogOptions.GCRef);

            case SHENANDOAH -> cli.withVMOptions(CLI.VMOptions.Xms, CLI.VMOptions.Xmx)
                     .withGCOptions(CLI.VMOptions.GCOptions.VerboseGC, CLI.VMOptions.GCOptions.UnlockExperimental)
                     .withXlogOptions(CLI.VMOptions.XlogOptions.GC, CLI.VMOptions.XlogOptions.GCInit, CLI.VMOptions.XlogOptions.GCStats,
                             CLI.VMOptions.XlogOptions.GCHeapExit, CLI.VMOptions.XlogOptions.GCMetaspace, CLI.VMOptions.XlogOptions.GCErgo);
        }
        return cli;
    }

    private static String buildExecutableCommand(CLI cli, boolean outputToFile) {
        StringBuilder stringBuilder = new StringBuilder("java");
        for (CLI.VMOptions vmOption : cli.getVmOptions()) {
            stringBuilder.append(" ").append(vmOption.stringifyHeapSizeOption());
        }
        for (CLI.VMOptions.XlogOptions logOption : cli.getXlogOptions()) {
            stringBuilder.append(" ").append(logOption.getOptionString());
        }
        for (CLI.VMOptions.GCOptions gcOption : cli.getGcOptions()) {
            stringBuilder.append(" ").append(gcOption.getOptionString());
        }
        stringBuilder.append(" ").append(cli.getGcType().getCliOption()).append(" ").append("App");
        if (outputToFile) {
            stringBuilder.append(" > out").append(++OUT_FILE_NO).append(".txt");
        }
        return stringBuilder.toString();
    }

    private static List<String> yieldOutputStrings(Process process) {
        List<String> outputStrings = new ArrayList<>();
        Scanner scanner = new Scanner(process.getInputStream());
        while (scanner.hasNext()) {
            outputStrings.add(scanner.nextLine());
        }
        return outputStrings;
    }

    private static Process invokeRuntime(String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }

    private static double yieldGCTimeFromSource(List<String> parsedStrings, final String fileName, GCType gcType) {
        if (parsedStrings == null && fileName == null) {
            throw new IllegalArgumentException("Parsed strings cannot be null if no file name was provided");
        }
        if (fileName != null) {
            parsedStrings = readFile(fileName);
        }
        double totalTimeRounded = 0.0;
        switch (gcType) {
            case SERIAL, PARALLEL, G1 -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcCpuPattern,
                    null, "Real=", false);
            case ZGC -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcPhasesPattern,
                    null, "ms", true);
            case SHENANDOAH -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcWhiteSpacePattern,
                    gcNumPattern, "ms", true);
        }
        return totalTimeRounded;
    }

    private static double calculateTotalTimeRounded(List<String> parsedStrings, Pattern pattern1, Pattern pattern2,
                                                    String separator, boolean inMs) {
        List<String> realTimeStringList = new ArrayList<>();
        for (String line : parsedStrings) {
            Matcher matcher = pattern1.matcher(line);
            Matcher matcher2 = null;
            if (pattern2 != null) {
                matcher2 = pattern2.matcher(line);
            }
            if (matcher.find() && (matcher2 == null || matcher2.find())) {
                String[] splitByWhiteSpace = line.split(" ");
                Arrays.stream(splitByWhiteSpace).filter(str -> str.contains(separator)).forEach(realTimeStringList::add);
            }
        }
        double realTimeTotal = realTimeStringList.stream().map(str -> {
            String realSecondsString;
            if (!inMs) {
                realSecondsString = str.substring(5, str.length() - 1);
            } else {
                realSecondsString = str.substring(0, str.length() - 2);
            }
            return Double.parseDouble(realSecondsString);
        }).reduce(Double::sum).orElse(0.0);
        DecimalFormat df = new DecimalFormat("#####.###");
        if (!inMs) {
            return Double.parseDouble(df.format(realTimeTotal));
        }
        double totalTimeRoundedInMs = Double.parseDouble(df.format(realTimeTotal));
        return totalTimeRoundedInMs / 1000;
    }

    private static List<String> readFile(String fileName) {
        List<String> stringList = new ArrayList<>();
        try (BufferedReader bufferedReader = new BufferedReader(
                new FileReader(fileName),
                BUFFER_SIZE
        )) {
            String line = bufferedReader.readLine();
            while (line != null) {
                stringList.add(line);
                line = bufferedReader.readLine();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return stringList;
    }
}
