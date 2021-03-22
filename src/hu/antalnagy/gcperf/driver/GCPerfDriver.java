package hu.antalnagy.gcperf.driver;

import com.github.sh0nk.matplotlib4j.PythonExecutionException;
import hu.antalnagy.gcperf.CLI;
import hu.antalnagy.gcperf.GCType;
import hu.antalnagy.gcperf.plot.GCPerfPlot;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GCPerfDriver {
    public static final int INITIAL_HEAP_SIZE_DEFAULT = 4;
    public static final int INITIAL_HEAP_SIZE_DEFAULT_SHENANDOAH = 40;
    public static final int MAX_HEAP_SIZE_DEFAULT = 64;
    public static final int MAX_HEAP_SIZE_DEFAULT_SHENANDOAH = 640;
    private static final Path LOC_PATH = Paths.get("").toAbsolutePath();
    private static final Path LOC_OUT_PATH = Paths.get(LOC_PATH + "/res/out");
    private static final Path LOC_OUT_ERR_PATH = Paths.get(LOC_PATH + "/res/outErr").toAbsolutePath();
    private static final Path LOC_OUT_BIN_PATH = Paths.get(LOC_PATH + "/bin").toAbsolutePath();
    private static final Path LOC_OUT_CSV_PATH = Paths.get(LOC_PATH + "/res/csv").toAbsolutePath();
    private static final Logger LOGGER = Logger.getLogger(GCPerfDriver.class.getSimpleName());
    private static final int BUFFER_SIZE = 8 * 1024;

    private static final Pattern gcCpuPattern = Pattern.compile("gc,cpu");
    private static final Pattern gcPhasesPattern = Pattern.compile("gc,phases");
    private static final Pattern gcWhiteSpacePattern = Pattern.compile("gc\\s");
    private static final Pattern gcNumPattern = Pattern.compile("GC\\([0-9]+\\)");

    private static final Object mainLock = new Object();
    private static final Object watcherLock = new Object();

    private static int OUT_FILE_NO;
    private static String mainClass = null;

    private static List<GCType> gcTypes = null;
    private static boolean isShenandoahOnly = false;
    private static int prematureProcessInterrupts = 0;
    private static double lastGoodShenandoahRun = 0;

    public static void main(String[] args) {
        try {
            var list = new ArrayList<GCType>();
//            list.add(GCType.G1);
            list.add(GCType.ZGC);
            list.add(GCType.SHENANDOAH);
//            launch("App", 2, 64, 256, list);
            launch(new File("mass-deploy.jar"), 2, 640, 999,
                    list, true);
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "IO exception occurred");
            ex.printStackTrace();
        } catch (PythonExecutionException ex) {
            LOGGER.log(Level.SEVERE, "Python execution error occurred");
            ex.printStackTrace();
        } catch (InterruptedException ex) {
            LOGGER.log(Level.SEVERE, "Interrupted thread");
            ex.printStackTrace();
        }
    }

    public static void launch(File app, int numOfRuns, int initHeapIncrementSize,
                              int maxHeapIncrementSize, List<GCType> gcTypes, boolean exportToCSV) throws IOException,
            PythonExecutionException, InterruptedException {

        GCPerfDriver.gcTypes = new ArrayList<>(gcTypes);
        isShenandoahOnly = GCPerfDriver.gcTypes.contains(GCType.SHENANDOAH) && GCPerfDriver.gcTypes.size() == 1;
        extractBinariesAndSetMainClass(app);
        Map<GCType, List<Double>> gcTimeMeasurements = performGCAnalysis(numOfRuns, initHeapIncrementSize,
                maxHeapIncrementSize);
        plotResults(gcTimeMeasurements);
        Map<GCType, Double> avgRuns = calculateAvgRuns(gcTimeMeasurements);
        GCType suggestedGCType = selectSuggestedGC(avgRuns);
        if(exportToCSV) {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            Date date = new Date(System.currentTimeMillis());
            createCSVFile(gcTimeMeasurements, "results-" + formatter.format(date) + ".csv");
        }
        LOGGER.log(Level.INFO, "Suggested GC Type: " + suggestedGCType.name());
    }

    private static void extractBinariesAndSetMainClass(File file) throws IOException, InterruptedException {
        String fileName = file.getName();
        createBinDirectoryAndCopyFile(file);

        if(fileName.endsWith(".class")) {
            mainClass = fileName.substring(0, fileName.length() - 6);
        }
        else if (fileName.endsWith(".jar")) {
            ProcessBuilder processBuilder = new ProcessBuilder("jar", "xf", fileName);
            processBuilder.directory(LOC_OUT_BIN_PATH.toFile());
            Process process = processBuilder.start();
            process.waitFor();
            File[] files = Paths.get(LOC_OUT_BIN_PATH + "/META-INF").toFile().listFiles();
            if(files == null) {
                LOGGER.log(Level.SEVERE, "Empty binaries directory");
                throw new IllegalArgumentException("Empty binaries directory");
            }
            Optional<File> manifest = Arrays.stream(files).filter(f -> f.getName().contains("MANIFEST.MF")).findFirst();
            if(manifest.isEmpty()) {
                LOGGER.log(Level.SEVERE, "MANIFEST.MF file missing");
                throw new IllegalArgumentException("MANIFEST.MF file couldn't be found. Please check .jar file" +
                        "contents");
            }
            try (Scanner scanner = new Scanner(manifest.get())) {
                while (scanner.hasNext()) {
                    String line = scanner.nextLine();
                    if (line.contains("Main-Class:")) {
                        var split = line.split(" ");
                        mainClass = split[1];
                        break;
                    }
                }
                if (mainClass == null) {
                    LOGGER.log(Level.SEVERE, "Incorrect MANIFEST.MF file provided");
                    throw new IllegalArgumentException("Incorrect MANIFEST.MF file provided. Please check .jar file" +
                            "contents");
                }
            }
        }
        else {
            LOGGER.log(Level.SEVERE, "File format not supported");
            throw new IllegalArgumentException("File format not supported. Please provide either a .class file or a .jar file");
        }
    }

    private static void createBinDirectoryAndCopyFile(File file) throws IOException {
        if(Files.exists(LOC_OUT_BIN_PATH)) {
            if(!deleteFilesInDirectory(LOC_OUT_BIN_PATH.toFile())) {
                LOGGER.log(Level.SEVERE, "Couldn't delete files in binaries directory");
                throw new IOException("Couldn't delete files in binaries directory. " +
                        "Possible permission denial.");
            }
        }
        Files.createDirectory(LOC_OUT_BIN_PATH);
        Files.createDirectories(LOC_OUT_PATH);
        Files.createDirectories(LOC_OUT_ERR_PATH);
        Files.createDirectories(LOC_OUT_CSV_PATH);
        copyFileToBinDirectory(file);
    }

    private static boolean deleteFilesInDirectory(File directory) {
        File[] content = directory.listFiles();
        if (content != null) {
            for (File file : content) {
                deleteFilesInDirectory(file);
            }
        }
        return directory.delete();
    }

    private static void copyFileToBinDirectory(File file) throws IOException {
        File copy = new File(LOC_OUT_BIN_PATH.toString() + "/" + file.getName());
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(copy))) {
            byte[] buffer = new byte[1024];
            int lengthRead;
            while ((lengthRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, lengthRead);
                out.flush();
            }
        }
    }

    private static void createCSVFile(Map<GCType, List<Double>> gcTimeMeasurements, String fileName) {
        /* >>gcType<<,>>run no.<<,>>time<< */
        File outFile = new File(LOC_OUT_CSV_PATH + "/" + fileName);
        try (PrintWriter printWriter = new PrintWriter(outFile)) {
            for (GCType gcType : GCPerfDriver.gcTypes) {
                List<Double> runs = gcTimeMeasurements.get(gcType);
                StringBuilder stringBuilder = new StringBuilder();
                for (int i = 0; i < runs.size(); i++) {
                    stringBuilder.append(gcType.name()).append(",").append(i + 1).append(",").append(runs.get(i)).append("\n");
                }
                printWriter.write(stringBuilder.toString());
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "IO exception occurred");
            ex.printStackTrace();
        }
    }

    private static void plotResults(Map<GCType, List<Double>> measurements) throws IOException, PythonExecutionException {
        GCPerfPlot gcPerfPlot = new GCPerfPlot(GCType.SERIAL, new ArrayList<>());
        for (Map.Entry<GCType, List<Double>> entry : measurements.entrySet()) {
            gcPerfPlot.setGcType(entry.getKey());
            gcPerfPlot.setMeasurements(entry.getValue());
            gcPerfPlot.plotMeasurements();
        }
    }

    private static Map<GCType, Double> calculateAvgRuns(Map<GCType, List<Double>> performanceMap) {
        Map<GCType, Double> avgRuns = new HashMap<>();
        for (GCType gcType : GCPerfDriver.gcTypes) {
            List<Double> performanceTimes = performanceMap.get(gcType);
            double aggregatedTimes = performanceTimes.stream().reduce(Double::sum).orElse(0.0);
            avgRuns.put(gcType, aggregatedTimes / performanceTimes.size());
        }
        return avgRuns;
    }

    private static GCType selectSuggestedGC(Map<GCType, Double> avgRuns) {
        var resultEntry = avgRuns.entrySet().stream().min(Map.Entry.comparingByValue());
        if (resultEntry.isEmpty()) {
            throw new IllegalStateException("Result GC Type not available.");
        }
        return resultEntry.get().getKey();
    }

    private static Map<GCType, List<Double>> performGCAnalysis(int runs, int initHeapIncrementSize,
                                                               int maxHeapIncrementSize) throws IOException {
        Map<GCType, List<Double>> performanceMap = new HashMap<>();
        Map<GCType, Double> avgRuns = new HashMap<>();
        for (GCType gcType : GCPerfDriver.gcTypes) {
            List<Double> measuredTimes = new ArrayList<>();
            double totalTime = 0.0;
            int noOfRuns = runs;
            boolean mallocFailure = false;
            int lastRunWithNoMallocFailure = 0;
            prematureProcessInterrupts = 0;
            LOGGER.log(Level.INFO, "Initializing run with GC Type: " + gcType.name());
            LOGGER.log(Level.INFO, "Expected no. of runs: " + runs);
            for (int i = 0; i < noOfRuns; i++) {
                int xms, xmx;
                if (!mallocFailure && gcType == GCType.SHENANDOAH) {
                    xms = Integer.min(INITIAL_HEAP_SIZE_DEFAULT_SHENANDOAH + (lastRunWithNoMallocFailure * initHeapIncrementSize), 2048);
                    xmx = Integer.min(MAX_HEAP_SIZE_DEFAULT_SHENANDOAH + (lastRunWithNoMallocFailure * initHeapIncrementSize), 8192);
                } else {
                    if (gcType == GCType.SHENANDOAH) {
                        xms = Integer.min(INITIAL_HEAP_SIZE_DEFAULT_SHENANDOAH + (i * initHeapIncrementSize), 2048);
                        xmx = Integer.min(MAX_HEAP_SIZE_DEFAULT_SHENANDOAH + (i * initHeapIncrementSize), 8192);
                    } else {
                        xms = Integer.min(INITIAL_HEAP_SIZE_DEFAULT + (i * initHeapIncrementSize), 2048);
                        xmx = Integer.min(MAX_HEAP_SIZE_DEFAULT + (i * maxHeapIncrementSize), 8192);
                    }
                }
                if (xms == 2048 && xmx == 8192) {
                    i = noOfRuns;
                    LOGGER.log(Level.WARNING, "Maximum initial heap size (Xms) and maximum heap size (Xmx) reached," +
                            "initializing last run");
                }
                if (xms == 2048) {
                    LOGGER.log(Level.WARNING, "Maximum initial heap size (Xms) reached");
                }
                if (xmx == 8192) {
                    LOGGER.log(Level.WARNING, "Maximum heap size (Xmx) reached");
                }
                ProcessBuilder builder = new ProcessBuilder(buildExecutableCommandArray(buildCLI(gcType, xms,
                        xmx)));
                builder.directory(LOC_OUT_BIN_PATH.toFile());
                File outFile = createOutFile(builder, false);
                File outErrFile = createOutFile(builder, true);
                final AtomicReference<Process> process = new AtomicReference<>();
                final AtomicBoolean processSuspended = new AtomicBoolean();
                mallocFailure = createProcessThread(process, processSuspended, builder, gcType, avgRuns, outFile, mallocFailure);
                if (!mallocFailure && gcType == GCType.SHENANDOAH) {
                    lastRunWithNoMallocFailure = i;
                }
                waitForMainLock();
                final AtomicBoolean erroneousRun = new AtomicBoolean(false);
                final AtomicBoolean outOfMemoryError = new AtomicBoolean(false);
                noOfRuns = handleUnexpectedThreadEvents(noOfRuns, processSuspended, erroneousRun, outOfMemoryError,
                        process, outErrFile);
                totalTime = yieldRunTimes(erroneousRun, outFile, gcType, measuredTimes, totalTime, i);
            }
            avgRuns.put(gcType, totalTime / runs);
            performanceMap.put(gcType, measuredTimes);
        }
        return performanceMap;
    }

    private static int handleUnexpectedThreadEvents(int noOfRuns, final AtomicBoolean processSuspended,
                                                    final AtomicBoolean erroneousRun, final AtomicBoolean outOfMemoryError,
                                                    final AtomicReference<Process> process, File outErrFile) throws FileNotFoundException {
        if (processSuspended.get()) {
            erroneousRun.set(true);
            noOfRuns++;
        } else if (process.get().exitValue() != 0) {
            erroneousRun.set(true);
            LOGGER.log(Level.WARNING, "Process ended abnormally, exit code: " + process.get().exitValue());
            try (Scanner scanner = new Scanner(outErrFile)) {
                while (scanner.hasNext()) {
                    String line = scanner.nextLine();
                    if (line.contains("OutOfMemoryError")) {
                        outOfMemoryError.set(true);
                    }
                }
            }
            if (!outOfMemoryError.get()) {
                LOGGER.log(Level.SEVERE, "Unknown error occurred, ending analysis");
                throw new IllegalArgumentException("Unknown error occurred during application run. " +
                        "Please verify the integrity of the .class file or check the error output for more details");
            }
            noOfRuns++;
        }
        return noOfRuns;
    }

    private static void waitForMainLock() {
        synchronized (mainLock) {
            try {
                mainLock.wait();
            } catch (InterruptedException ex) {
                LOGGER.log(Level.SEVERE, Thread.currentThread().getName() + " interrupted");
                ex.printStackTrace();
            }
        }
    }

    private static double yieldRunTimes(final AtomicBoolean erroneousRun, File outFile, GCType gcType, List<Double> measuredTimes,
                                        double totalTime, int runNo) {
        double time = 0.0;
        if (!erroneousRun.get()) {
            time = yieldGCTimeFromSource(yieldOutputStringsFromFile(outFile), gcType);
            if (gcType == GCType.SHENANDOAH) {
                lastGoodShenandoahRun = time;
            }
            measuredTimes.add(time);
            totalTime += time;
        }
        LOGGER.log(Level.INFO, "Run no.: " + (runNo + 1) + " : time: " + time);
        return totalTime;
    }

    private static boolean createProcessThread(final AtomicReference<Process> process, final AtomicBoolean processSuspended,
                                               ProcessBuilder builder, GCType gcType, Map<GCType, Double> avgRuns, File outFile,
                                               boolean mallocFailure) {
        Thread processThread = new Thread(() -> {
            try {
                process.set(builder.start());
                synchronized (watcherLock) {
                    watcherLock.notify();
                }
                process.get().waitFor();
                if (gcType != GCType.SHENANDOAH) {
                    synchronized (mainLock) {
                        mainLock.notify();
                    }
                }
            } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, "IO exception occurred");
                ex.printStackTrace();
            } catch (InterruptedException ex) {
                LOGGER.log(Level.WARNING, "Java runtime process " + process.get().pid() + " timed out/interrupted");
            }
        });
        processThread.start();
        if (gcType != GCType.SHENANDOAH) {
            return false;
        }
        return startWatcherThread(process, processSuspended, avgRuns, processThread, outFile, mallocFailure);
    }

    private static boolean startWatcherThread(final AtomicReference<Process> process, final AtomicBoolean processSuspended,
                                              Map<GCType, Double> avgRunsMap, Thread processThread, File outFile, boolean mallocFailure) {
        LOGGER.log(Level.FINE, "Detected Shenandoah GC Type, initializing timeout watcher thread");
        AtomicBoolean supposedMemoryAllocationFailure = new AtomicBoolean(false);
        Thread watcherThread = new Thread(() -> {
            try {
                synchronized (watcherLock) {
                    watcherLock.wait();
                }
                Process processOnStart = process.get();
                if (mallocFailure) {
                    induceThreadSleep(avgRunsMap, 0);
                } else { //premature interrupt in last run
                    induceThreadSleep(avgRunsMap, GCPerfDriver.prematureProcessInterrupts);
                }
                if (process.get().pid() == processOnStart.pid() && processThread.isAlive()) {
                    processOnStart.destroy();
                    int continuousHandleAllocationCount = countContinuousHandleAllocations(outFile);
                    if (continuousHandleAllocationCount >= 3) { //pretty sure small heap size would cause this
                        LOGGER.log(Level.WARNING, "Possibly selected heap size was too small, rerunning with bigger heap");
                        supposedMemoryAllocationFailure.set(true);
                    } else {
                        LOGGER.log(Level.WARNING, "Probable premature process interrupt happened");
                        prematureProcessInterrupts++;
                    }
                    processThread.interrupt();
                    synchronized (mainLock) {
                        processSuspended.set(true);
                        LOGGER.log(Level.WARNING, "Shenandoah GC process suspended");
                        mainLock.notify();
                    }
                } else {
                    synchronized (mainLock) {
                        mainLock.notify();
                    }
                }
            } catch (InterruptedException ex) {
                LOGGER.log(Level.SEVERE, Thread.currentThread().getName() + " interrupted");
                ex.printStackTrace();
            }
        });
        watcherThread.start();
        return supposedMemoryAllocationFailure.get();
    }

    private static int countContinuousHandleAllocations(File outFile) {
        List<String> triggers = new ArrayList<>();
        try (Scanner scanner = new Scanner(outFile)) {
            while (scanner.hasNext()) {
                String line = scanner.nextLine();
                if (line.contains("Trigger: ")) {
                    triggers.add(line);
                }
            }
        } catch (FileNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Output file not found");
            e.printStackTrace();
        }
        int continuousHandleAllocationCount = 0;
        int counter = 0;
        for (String logLine : triggers) {
            if (logLine.contains("Trigger: Handle Allocation Failure")) {
                counter++;
                if (counter > continuousHandleAllocationCount) {
                    continuousHandleAllocationCount = counter;
                }
            } else if (!logLine.contains("Trigger: Free")) {
                counter = 0;
            }
        }
        return continuousHandleAllocationCount;
    }

    private static void induceThreadSleep(Map<GCType, Double> avgRunsMap, int prematureProcessInterrupts) throws InterruptedException {
        if (!isShenandoahOnly) {
            double totalRuns = 0.0;
            for (GCType gcType : GCPerfDriver.gcTypes) {
                if (gcType != GCType.SHENANDOAH) {
                    totalRuns += avgRunsMap.get(gcType);
                }
            }
            double avgRuns = totalRuns / GCPerfDriver.gcTypes.size();
            double shenandoahWaitThresholdInMs = 2 * avgRuns;
            Thread.sleep((long) Math.max(250, (shenandoahWaitThresholdInMs * 1000 * (prematureProcessInterrupts + 1))));
        } else {
            if (lastGoodShenandoahRun == 0) {
                Thread.sleep(2 * 1000L * (prematureProcessInterrupts + 1));
            } else {
                Thread.sleep((long) ((lastGoodShenandoahRun + 0.025) * 1000L * (prematureProcessInterrupts + 1)));
            }
        }
    }

    private static File createOutFile(ProcessBuilder builder, boolean isErrFile) {
        File outFile;
        if (!isErrFile) {
            outFile = new File(LOC_OUT_PATH + "/out" + ++OUT_FILE_NO + ".txt");
            builder.redirectOutput(outFile);
        } else {
            outFile = new File(LOC_OUT_ERR_PATH + "/outErr" + OUT_FILE_NO + ".txt");
            builder.redirectError(outFile);
        }
        return outFile;
    }

    /***
     * @param gcType gcType
     * @param startHeapSize Start heap size in MB
     * @param maxHeapSize Maximum heap size in MB
     * @return CLI
     */
    private static CLI buildCLI(GCType gcType, int startHeapSize, int maxHeapSize) {
        if (startHeapSize < 1) {
            throw new IllegalArgumentException("Invalid argument for initial heap size!");
        }
        if (maxHeapSize < 16) {
            throw new IllegalArgumentException("Invalid argument for maximum heap size!");
        }
        CLI.VMOptions.Xms.setSize(startHeapSize);
        CLI.VMOptions.Xmx.setSize(maxHeapSize);
        CLI cli = new CLI(gcType);
        switch (gcType) {
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

    private static String[] buildExecutableCommandArray(CLI cli) {
        List<String> stringList = new ArrayList<>();
        stringList.add("java");
        for (CLI.VMOptions vmOption : cli.getVmOptions()) {
            stringList.add(vmOption.stringifyHeapSizeOption());
        }
        for (CLI.VMOptions.XlogOptions logOption : cli.getXlogOptions()) {
            stringList.add(logOption.getOptionString());
        }
        for (CLI.VMOptions.GCOptions gcOption : cli.getGcOptions()) {
            stringList.add(gcOption.getOptionString());
        }
        stringList.add(cli.getGcType().getCliOption());
        stringList.add(mainClass);
        String[] resultArray = new String[stringList.size()];
        AtomicInteger ai = new AtomicInteger(-1);
        stringList.forEach(string -> resultArray[ai.incrementAndGet()] = string);
        return resultArray;
    }

    private static List<String> yieldOutputStringsFromFile(File file) {
        List<String> outputStrings = new ArrayList<>();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(file), BUFFER_SIZE)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                outputStrings.add(line);
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "IO exception occurred with file " + file.getName());
            ex.printStackTrace();
        }
        return outputStrings;
    }

    private static double yieldGCTimeFromSource(List<String> parsedStrings, GCType gcType) {
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
}
