package hu.antalnagy.gcperf;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Analysis {
    private static final Path LOC_PATH = Paths.get("").toAbsolutePath();
    private static final Path LOC_OUT_PATH = Paths.get(LOC_PATH + "/res/out");
    private static final Path LOC_OUT_ERR_PATH = Paths.get(LOC_PATH + "/res/outErr").toAbsolutePath();
    private static final Path LOC_OUT_BIN_PATH = Paths.get(LOC_PATH + "/bin").toAbsolutePath();

    private static int OUT_FILE_NO = 0;

    private static final int MAX_INIT_HEAP_SIZE = 2048;
    private static final int MAX_MAX_HEAP_SIZE = 8192;

    private static final Logger LOGGER = Logger.getLogger(Analysis.class.getSimpleName());
    private static final int BUFFER_SIZE = 8 * 1024;

    private static final Pattern osThreadPattern = Pattern.compile("\\[os,thread");
    private static final Pattern gcCpuPattern = Pattern.compile("\\[gc,cpu");
    private static final Pattern gcStartPattern = Pattern.compile("\\[gc,start ");
    private static final Pattern gcPhasesPattern = Pattern.compile("\\[gc,phases");
    private static final Pattern gcStatsPattern = Pattern.compile("\\[gc,stats");
    private static final Pattern gcPausePattern = Pattern.compile("\\[gc *].*Pause");
    private static final Pattern gcConcurrentPattern = Pattern.compile("\\[gc *].*Concurrent Cycle \\d+");
    private static final Pattern gcWhiteSpacePattern = Pattern.compile("gc\\s");
    private static final Pattern gcNumPattern = Pattern.compile("GC\\([0-9]+\\)");
    private static final Pattern gcNumAndPausePattern = Pattern.compile("GC\\([0-9]+\\)\sPause");
    private static final Pattern pausePattern = Pattern.compile("Pause");
    private static final Pattern pauseFullPattern = Pattern.compile("Pause Full");

    private static final Object mainLock = new Object();
    private static final Object watcherLock = new Object();

    private final String mainClass;
    private final List<GCType> gcTypes;
    private final Metrics[] metrics;
    private final Progress progress;

    private final Map<GCType, Double> avgRuns = new HashMap<>();
    private final Map<GCType, Double> avgGCRuns = new HashMap<>();
    private final Map<GCType, List<Double>> gcRuntimes = new HashMap<>();
    private final Map<GCType, List<Double>> throughputsMap = new HashMap<>();
    private final Map<GCType, List<Integer>> pausesMap = new HashMap<>();

    private final boolean isShenandoahOnly;
    private final AtomicInteger prematureProcessInterrupts = new AtomicInteger(0);
    private final AtomicInteger prematureRunIncrement = new AtomicInteger(0);
    private final AtomicBoolean memoryAllocationFailureOnLastRun = new AtomicBoolean(false);
    private double lastSuccessfulShenandoahRunTime = 0.0;

    private Leaderboard leaderboard;

    public enum Metrics {
        BestGCRuntime,
        AvgGCRuntime,
        Throughput,
        Latency,
        MinorPauses,
        FullPauses
    }

    public static class Progress {
        private final LinkedHashMap<Integer, String> progressMap = new LinkedHashMap<>();
        boolean failed = false;
        boolean done = false;
        int progressLevel = 1;

        private Progress(GCType... gcTypes) {
            int i = 1;
            progressMap.put(i++, "Setting up analysis environment ...");
            for(GCType gcType : gcTypes) {
                progressMap.put(i++, "Running analysis on GC Type: " + gcType.name() + " ...");
            }
            progressMap.put(i, "Finishing analysis and calculating results ...");
        }

        //returns progressLevel in percentage points
        public double getProgressLevel() {
            return progressLevel / ((double) progressMap.size());
        }

        public String getProgressMessage() {
            return progressMap.get(progressLevel);
        }

        public boolean isFailed() {
            return failed;
        }

        public boolean isDone() {
            return done;
        }

        public void setDone(boolean done) {
            this.done = done;
        }
    }

    public Analysis(String mainClass, List<GCType> gcTypes, Metrics[] metrics) {
        this.mainClass = mainClass;
        this.gcTypes = new ArrayList<>(gcTypes);
        this.metrics = List.of(metrics).toArray(Metrics[]::new);
        progress = new Progress(gcTypes.toArray(GCType[]::new));
        isShenandoahOnly = gcTypes.contains(GCType.SHENANDOAH) && gcTypes.size() == 1;
    }

    public String getMainClass() {
        return mainClass;
    }

    public List<GCType> getGcTypes() {
        return new ArrayList<>(gcTypes);
    }

    public Metrics[] getMetrics() {
        return Arrays.asList(metrics).toArray(Metrics[]::new);
    }

    public Progress getProgress() {
        return progress;
    }

    public Map<GCType, Double> getAvgRuns() {
        return new HashMap<>(avgRuns);
    }

    public Map<GCType, Double> getAvgGCRuns() {
        return new HashMap<>(avgGCRuns);
    }

    public Map<GCType, List<Double>> getGcRuntimes() {
        return new HashMap<>(gcRuntimes);
    }

    public Map<GCType, List<Double>> getThroughputsMap() {
        return throughputsMap;
    }

    public Map<GCType, List<Integer>> getPausesMap() {
        return new HashMap<>(pausesMap);
    }

    public List<GCType> getLeaderboard() {
        return leaderboard.getLeaderboard();
    }

    public static Logger getLOGGER() {
        return LOGGER;
    }

    public void performGCAnalysis(int runs, int initStartHeapSize, int initMaxHeapSize,
                                  int startHeapIncrementSize, int maxHeapIncrementSize) throws IOException {
        validateInputParameters(runs, initStartHeapSize, initMaxHeapSize, startHeapIncrementSize, maxHeapIncrementSize);
        for (GCType gcType : gcTypes) {
            if(progress.failed) {
                LOGGER.log(Level.SEVERE, "Stopping analysis");
                waitABit(); //for gui progress bar
                break;
            }
            List<Double> measuredRuntimes = new ArrayList<>();
            List<Double> measuredGCTimes = new ArrayList<>();
            List<Double> measuredSTWTimes = new ArrayList<>();
            List<Double> throughputs = new ArrayList<>();
            List<Integer> pauses = new ArrayList<>();
            double totalGCTime = 0.0;
            int noOfRuns = runs;
            int lastRunWithNoMallocFailure = 0;
            prematureProcessInterrupts.set(0);
            prematureRunIncrement.set(0);
            progress.progressLevel++;
            LOGGER.log(Level.INFO, "Initializing run with GC Type: " + gcType.name());
            LOGGER.log(Level.INFO, "Expected no. of runs: " + runs);
            for (int i = 0; i < noOfRuns; i++) {
                if(Math.abs(i - lastRunWithNoMallocFailure) > 20) {
                    LOGGER.log(Level.SEVERE, "Analysis suspended for GC Type: " + gcType.name() +
                            "\nReason: 20 consecutive failed runs\n" +
                            "Possible problems include too small general heap size or too small heap size increments");
                    progress.failed = true;
                    break;
                }
                int[] xm = calculateHeapSize(initStartHeapSize, initMaxHeapSize, startHeapIncrementSize, maxHeapIncrementSize,
                        i, prematureProcessInterrupts.get());
                int xms = xm[0];
                int xmx = xm[1];
                noOfRuns = checkLimits(noOfRuns, i, xms, xmx);
                ProcessBuilder builder = new ProcessBuilder(buildExecutableCommandArray(buildCLI(gcType, xms,
                        xmx)));
                builder.directory(LOC_OUT_BIN_PATH.toFile());
                File outFile = createOutFile(false);
                File outErrFile = createOutFile(true);
                builder.redirectOutput(outFile);
                builder.redirectError(outErrFile);
                final AtomicReference<Process> process = new AtomicReference<>();
                final AtomicBoolean processSuspended = new AtomicBoolean();
                createProcessThread(process, processSuspended, builder, gcType, avgRuns, outFile);
                waitForMainLock();
                if (!memoryAllocationFailureOnLastRun.get()) {
                    lastRunWithNoMallocFailure = i;
                }
                final AtomicBoolean erroneousRun = new AtomicBoolean(false);
                noOfRuns = getNumOfRunsAndHandleUnexpectedThreadEvents(noOfRuns, processSuspended, erroneousRun, process,
                        outErrFile);
                if(!erroneousRun.get()) {
                    List<String> parsedStrings = yieldOutputStringsFromFile(outFile);
                    totalGCTime = yieldGCRuntimes(parsedStrings, gcType, measuredGCTimes, measuredSTWTimes, totalGCTime, i);
                    double runtime = yieldLastThreadExitFromSource(parsedStrings);
                    if (gcType == GCType.SHENANDOAH) {
                        lastSuccessfulShenandoahRunTime = runtime;
                    }
                    double throughput = calculateThroughput(runtime, measuredSTWTimes.get(measuredSTWTimes.size()-1));
                    int fullPauses = yieldNoOfPauses(parsedStrings, gcType)[0];
                    int minorPauses = yieldNoOfPauses(parsedStrings, gcType)[1];
                    measuredRuntimes.add(runtime);
                    throughputs.add(throughput);
                    pauses.add(fullPauses);
                    pauses.add(minorPauses);
                }
                else {
                    LOGGER.log(Level.WARNING, "Run no.: " + (i + 1) + " failed");
                }
            }
            avgRuns.put(gcType, measuredRuntimes.stream().reduce(Double::sum).orElse(0.0) / measuredRuntimes.size());
            avgGCRuns.put(gcType, totalGCTime / runs);
            gcRuntimes.put(gcType, measuredGCTimes);
            throughputsMap.put(gcType, throughputs);
            pausesMap.put(gcType, pauses);
        }
        if(!progress.failed) {
            progress.progressLevel++;
            leaderboard = new Leaderboard(avgGCRuns, gcRuntimes, throughputsMap, pausesMap, gcTypes);
            leaderboard.setLeaderboard(metrics);
        }
    }

    private void validateInputParameters(int runs, int initStartHeapSize, int initMaxHeapSize,
                                               int startHeapIncrementSize, int maxHeapIncrementSize) {
        if(runs < 1 || runs > 100) {
            LOGGER.log(Level.SEVERE, "Invalid argument for number of runs");
            progress.failed = true;
            throw new IllegalArgumentException("Number of runs should be between 1 and 100!");
        }
        if (initStartHeapSize < 1 || initStartHeapSize > 2048) {
            LOGGER.log(Level.SEVERE, "Invalid argument for initial heap size");
            progress.failed = true;
            throw new IllegalArgumentException("Please provide an initial heap size between 1MB and 2048MB!");
        }
        if (initMaxHeapSize < 16 || initMaxHeapSize > 8192) {
            LOGGER.log(Level.SEVERE, "Invalid argument for maximum heap size");
            progress.failed = true;
            throw new IllegalArgumentException("Please provide a maximum heap size between 1MB and 8192MB!");
        }
        if (startHeapIncrementSize < 1 || startHeapIncrementSize > 1024) {
            LOGGER.log(Level.SEVERE, "Invalid argument for initial heap increment size");
            progress.failed = true;
            throw new IllegalArgumentException("Initial heap increment size must be between 1MB and 1024MB");
        }
        if (maxHeapIncrementSize < 1 || maxHeapIncrementSize > 1024) {
            LOGGER.log(Level.SEVERE, "Invalid argument for maximum heap increment size");
            progress.failed = true;
            throw new IllegalArgumentException("Maximum heap increment size must be between 1MB and 1024MB");
        }
    }

    private void waitABit() {
        try {
            Thread.sleep(700);
        } catch (InterruptedException ignored) {}
    }

    private int checkLimits(int noOfRuns, int i, int xms, int xmx) {
        LOGGER.log(Level.INFO, "Initializing run no.: " + (i+1) + "; xms: " + xms + "(M); xmx: " + xmx + "(M)");
        if (xms == 2048 && xmx == 8192) {
            noOfRuns = i + 1;
            LOGGER.log(Level.WARNING, "Maximum initial heap size (Xms) and maximum heap size (Xmx) reached\n" +
                    "Initializing last run");
        }
        if (xms == 2048) {
            LOGGER.log(Level.INFO, "Maximum initial heap size (Xms) reached");
        }
        if (xmx == 8192) {
            LOGGER.log(Level.INFO, "Maximum heap size (Xmx) reached");
        }
        return noOfRuns;
    }

    public static double calculateThroughput(double lastThreadExitTime, double gcTime) {
        double gcTimeInPercentage = gcTime * 100.0 / lastThreadExitTime;
        return 100.0 - gcTimeInPercentage;
    }

    public static int[] calculateHeapSize(int initStartHeapSize, int initMaxHeapSize, int startHeapIncrementSize, int maxHeapIncrementSize,
                                           int i, int prematureProcessInterrupts) {
        int[] xm = new int[2];
        xm[0] = Integer.min(initStartHeapSize + ((i - prematureProcessInterrupts) * startHeapIncrementSize), MAX_INIT_HEAP_SIZE);
        xm[1] = Integer.min(initMaxHeapSize + ((i - prematureProcessInterrupts) * maxHeapIncrementSize), MAX_MAX_HEAP_SIZE);
        return xm;
    }

    private String[] buildExecutableCommandArray(CLI cli) {
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

    /***
     * @param gcType gcType
     * @param startHeapSize Start heap size in MB
     * @param maxHeapSize Maximum heap size in MB
     * @return CLI
     */
    public CLI buildCLI(GCType gcType, int startHeapSize, int maxHeapSize) {
        if (startHeapSize < 1 || startHeapSize > 2048) {
            LOGGER.log(Level.SEVERE, "Invalid argument for initial heap size");
            progress.failed = true;
            throw new IllegalArgumentException("Please provide an initial heap size between 1MB and 2048MB!");
        }
        if (maxHeapSize < 16 || maxHeapSize > 8192) {
            LOGGER.log(Level.SEVERE, "Invalid argument for maximum heap size");
            progress.failed = true;
            throw new IllegalArgumentException("Please provide a maximum heap size between 1MB and 8192MB!");
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
                            CLI.VMOptions.XlogOptions.GCPhasesStart, CLI.VMOptions.XlogOptions.OSThread);

            case G1 -> cli.withVMOptions(CLI.VMOptions.Xms, CLI.VMOptions.Xmx)
                    .withGCOptions(CLI.VMOptions.GCOptions.VerboseGC)
                    .withXlogOptions(CLI.VMOptions.XlogOptions.GCStart, CLI.VMOptions.XlogOptions.GCHeap, CLI.VMOptions.XlogOptions.GCMetaspace,
                            CLI.VMOptions.XlogOptions.GCCpu, CLI.VMOptions.XlogOptions.GCHeapExit, CLI.VMOptions.XlogOptions.GCHeapCoops,
                            CLI.VMOptions.XlogOptions.GCPhases, CLI.VMOptions.XlogOptions.GCPhasesStart, CLI.VMOptions.XlogOptions.GCTask,
                            CLI.VMOptions.XlogOptions.GCCds, CLI.VMOptions.XlogOptions.OSThread);

            case ZGC -> cli.withVMOptions(CLI.VMOptions.Xms, CLI.VMOptions.Xmx)
                    .withGCOptions(CLI.VMOptions.GCOptions.VerboseGC, CLI.VMOptions.GCOptions.UnlockExperimental)
                    .withXlogOptions(CLI.VMOptions.XlogOptions.GCStart, CLI.VMOptions.XlogOptions.GCHeap, CLI.VMOptions.XlogOptions.GCMetaspace,
                            CLI.VMOptions.XlogOptions.GCCpu, CLI.VMOptions.XlogOptions.GCHeapExit, CLI.VMOptions.XlogOptions.GCHeapCoops,
                            CLI.VMOptions.XlogOptions.GCPhases, CLI.VMOptions.XlogOptions.GCPhasesStart, CLI.VMOptions.XlogOptions.GCInit,
                            CLI.VMOptions.XlogOptions.GCLoad, CLI.VMOptions.XlogOptions.GCMMU, CLI.VMOptions.XlogOptions.GCMarking,
                            CLI.VMOptions.XlogOptions.GCReloc, CLI.VMOptions.XlogOptions.GCNMethod, CLI.VMOptions.XlogOptions.GCRef,
                            CLI.VMOptions.XlogOptions.OSThread);

            case SHENANDOAH -> cli.withVMOptions(CLI.VMOptions.Xms, CLI.VMOptions.Xmx)
                    .withGCOptions(CLI.VMOptions.GCOptions.VerboseGC, CLI.VMOptions.GCOptions.UnlockExperimental)
                    .withXlogOptions(CLI.VMOptions.XlogOptions.GC, CLI.VMOptions.XlogOptions.GCInit, CLI.VMOptions.XlogOptions.GCStats,
                            CLI.VMOptions.XlogOptions.GCHeapExit, CLI.VMOptions.XlogOptions.GCMetaspace, CLI.VMOptions.XlogOptions.GCErgo,
                            CLI.VMOptions.XlogOptions.OSThread);
        }
        return cli;
    }

    private File createOutFile(boolean isErrFile) {
        File outFile;
        if (!isErrFile) {
            outFile = new File(LOC_OUT_PATH + "/out" + ++OUT_FILE_NO + ".txt");
        } else {
            outFile = new File(LOC_OUT_ERR_PATH + "/outErr" + OUT_FILE_NO + ".txt");
        }
        return outFile;
    }

    private void createProcessThread(final AtomicReference<Process> process, final AtomicBoolean processSuspended,
                                            ProcessBuilder builder, GCType gcType, Map<GCType, Double> avgRuns, File outFile) {
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
                progress.failed = true;
                ex.printStackTrace();
            } catch (InterruptedException ex) {
                LOGGER.log(Level.WARNING, "Java runtime process " + process.get().pid() + " timed out/interrupted");
            }
        });
        processThread.start();
        if (gcType == GCType.SHENANDOAH) {
            startWatcherThread(process, processSuspended, avgRuns, processThread, outFile);
        }
    }

    private void startWatcherThread(final AtomicReference<Process> process, final AtomicBoolean processSuspended,
                                           Map<GCType, Double> avgRunsMap, Thread processThread, File outFile) {
        LOGGER.log(Level.INFO, "Detected Shenandoah GC Type, initializing timeout watcher thread");
        Thread watcherThread = new Thread(() -> {
            try {
                synchronized (watcherLock) {
                    watcherLock.wait();
                }
                Process processOnStart = process.get();
                if (memoryAllocationFailureOnLastRun.get()) {
                    induceThreadSleep(avgRunsMap, 0);
                } else { //premature interrupt in last run or successful run
                    induceThreadSleep(avgRunsMap, prematureRunIncrement.get());
                }
                if (process.get().pid() == processOnStart.pid() && processThread.isAlive()) {
                    processOnStart.destroy();
                    int continuousHandleAllocationCount = countContinuousHandleAllocations(outFile);
                    if (continuousHandleAllocationCount >= 3) { //pretty sure small heap size would cause this
                        LOGGER.log(Level.WARNING, "Possibly selected heap size was too small, rerunning with bigger heap");
                        memoryAllocationFailureOnLastRun.set(true);
                    } else {
                        LOGGER.log(Level.WARNING, "Potential premature process interrupt");
                        memoryAllocationFailureOnLastRun.set(false);
                        prematureProcessInterrupts.incrementAndGet();
                        prematureRunIncrement.incrementAndGet();
                    }
                    processThread.interrupt();
                    synchronized (mainLock) {
                        processSuspended.set(true);
                        LOGGER.log(Level.WARNING, "Shenandoah GC process suspended");
                        mainLock.notify();
                    }
                } else {
                    memoryAllocationFailureOnLastRun.set(false);
                    prematureRunIncrement.set(1); //to not wait the before compounded amount of time after a successful run
                    synchronized (mainLock) {
                        mainLock.notify();
                    }
                }
            } catch (InterruptedException ex) {
                LOGGER.log(Level.SEVERE, Thread.currentThread().getName() + " interrupted");
                progress.failed = true;
                ex.printStackTrace();
            }
        });
        watcherThread.start();
    }

    private void waitForMainLock() {
        synchronized (mainLock) {
            try {
                mainLock.wait();
            } catch (InterruptedException ex) {
                LOGGER.log(Level.SEVERE, Thread.currentThread().getName() + " interrupted");
                progress.failed = true;
                ex.printStackTrace();
            }
        }
    }

    private int countContinuousHandleAllocations(File outFile) {
        List<String> triggers = new ArrayList<>();
        try (Scanner scanner = new Scanner(outFile)) {
            while (scanner.hasNext()) {
                String line = scanner.nextLine();
                if (line.contains("Trigger: ")) {
                    triggers.add(line);
                }
            }
        } catch (FileNotFoundException ex) {
            LOGGER.log(Level.SEVERE, "Output file not found");
            progress.failed = true;
            ex.printStackTrace();
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

    private void induceThreadSleep(Map<GCType, Double> avgRunsMap, int magnifier) throws InterruptedException {
        if (lastSuccessfulShenandoahRunTime != 0) {
            Thread.sleep((long) (lastSuccessfulShenandoahRunTime * 1000L * (magnifier + 1)));
        }
        else {
            if (!isShenandoahOnly) {
                double totalRuns = 0.0;
                for (GCType gcType : gcTypes) {
                    if (gcType != GCType.SHENANDOAH) {
                        totalRuns += avgRunsMap.get(gcType);
                    }
                }
                double avgRuns = totalRuns / gcTypes.size();
                double shenandoahWaitThresholdInMs = 1.25 * avgRuns;
                Thread.sleep((long) Math.max(1000 * (magnifier + 1), (shenandoahWaitThresholdInMs * 1000 * (magnifier + 1))));
            } else {
                double constant = Math.pow(2, magnifier);
                int sleep = (int) (constant * 1000L * (magnifier + 1));
                Thread.sleep(sleep);
            }
        }
    }

    private int getNumOfRunsAndHandleUnexpectedThreadEvents(int noOfRuns, final AtomicBoolean processSuspended,
                                                                   final AtomicBoolean erroneousRun, final AtomicReference<Process> process,
                                                                   File outErrFile) throws FileNotFoundException {
        if (processSuspended.get()) {
            erroneousRun.set(true);
            noOfRuns++;
        } else if (process.get().exitValue() != 0) {
            boolean outOfMemoryError = false;
            erroneousRun.set(true);
            LOGGER.log(Level.WARNING, "Process ended abnormally, exit code: " + process.get().exitValue());
            try (Scanner scanner = new Scanner(outErrFile)) {
                while (scanner.hasNext()) {
                    String line = scanner.nextLine();
                    if (line.contains("OutOfMemoryError")) {
                        outOfMemoryError = true;
                    }
                }
            }
            if (!outOfMemoryError) {
                LOGGER.log(Level.SEVERE, "Unknown error/exception occurred during application run, ending analysis. " +
                        "Verify the integrity of the .class file or check the error output for more details");
                progress.failed = true;
                throw new IllegalArgumentException("Unknown error occurred during application run. " +
                        "Please verify the integrity of the .class file or check the error output for more details");
            }
            noOfRuns++;
        }
        return noOfRuns;
    }

    private double yieldGCRuntimes(List<String> parsedStrings, GCType gcType, List<Double> measuredGCTimes, List<Double> measuredSTWTimes,
                                        double totalTime, int runNo) {
        double time = yieldGCTimeFromSource(parsedStrings, gcType);
        measuredGCTimes.add(time);
        if(gcType == GCType.SERIAL || gcType == GCType.PARALLEL) {
            measuredSTWTimes.add(time);
        }
        else {
            measuredSTWTimes.add(yieldSTWTimeFromSource(parsedStrings, gcType));
        }
        totalTime += time;
        LOGGER.log(Level.INFO, "Run no.: " + (runNo + 1) + " : time: " + time);

        return totalTime;
    }

    public static Double yieldSTWTimeFromSource(List<String> parsedStrings, GCType gcType) {
        double totalTimeRounded = 0.0;
        switch (gcType) {
            case G1 -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcPausePattern, null, "ms", true);
            case ZGC -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcPhasesPattern,
                    pausePattern, "ms", true);
            case SHENANDOAH -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcWhiteSpacePattern,
                    gcNumAndPausePattern, "ms", true);
        }
        return totalTimeRounded;
    }

    public static double yieldGCTimeFromSource(List<String> parsedStrings, GCType gcType) {
        double totalTimeRounded = 0.0;
        switch (gcType) {
            case SERIAL, PARALLEL -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcCpuPattern,
                    null, "Real=", false);
            case G1 -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcPausePattern, null, "ms", true)
                    + calculateTotalTimeRounded(parsedStrings, gcConcurrentPattern, null, "ms", true);
            case ZGC -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcPhasesPattern,
                    null, "ms", true);
            case SHENANDOAH -> totalTimeRounded = calculateTotalTimeRounded(parsedStrings, gcWhiteSpacePattern,
                    gcNumPattern, "ms", true);
        }
        return totalTimeRounded;
    }

    public static int[] yieldNoOfPauses(List<String> parsedStrings, GCType gcType) {
        int[] pauses = new int[2];
        int fullPauses = gcType == GCType.SHENANDOAH ? yieldNoOfFullPausesShenandoah(parsedStrings) :
                yieldNoOfPausesHelper(parsedStrings, false, pauseFullPattern, null) / 2;
        int totalPauses = (gcType == GCType.SERIAL || gcType == GCType.PARALLEL) ? yieldNoOfPausesHelper(parsedStrings, false, pausePattern, null) / 2 :
                (gcType == GCType.G1 ? yieldNoOfPausesHelper(parsedStrings, false, gcPausePattern, null) :
                        (gcType == GCType.ZGC ? yieldNoOfPausesHelper(parsedStrings, false, gcStartPattern, null) :
                                yieldNoOfPausesHelper(parsedStrings, true, pausePattern, gcStatsPattern)));
        pauses[0] = fullPauses;
        pauses[1] = totalPauses - fullPauses;
        return pauses;
    }

    private static int yieldNoOfPausesHelper(List<String> parsedStrings, boolean filter, Pattern regex1, Pattern regex2) {
        if(filter && regex2 == null) {
            throw new IllegalArgumentException("Second regex is missing");
        }
        int counter = 0;
        for(String line : parsedStrings) {
            if(!filter) {
                Matcher matcher = regex1.matcher(line);
                if(matcher.find()) {
                    counter++;
                }
            }
            else {
                Matcher matcher1 = regex1.matcher(line);
                Matcher matcher2 = regex2.matcher(line);
                if(matcher1.find() && !matcher2.find()) {
                    counter++;
                }
            }
        }
        return counter;
    }

    private static int yieldNoOfFullPausesShenandoah(List<String> parsedStrings) {
        Collections.reverse(parsedStrings);
        int fullPauses = 0;
        for(String line : parsedStrings) {
            if(line.contains("Full GCs")) {
                String[] split = line.split(" ");
                int i = 0;
                while(i < split.length) {
                    String prev = split[i];
                    String actual = split[++i];
                    if(actual.equals("Full")) {
                        try {
                            fullPauses = Integer.parseInt(prev);
                            break;
                        } catch(NumberFormatException ex) {
                            LOGGER.log(Level.SEVERE, "Couldn't parse full pauses: " + ex.getMessage());
                        }
                    }
                }
                break;
            }
        }
        return fullPauses;
    }

    private static double yieldLastThreadExitFromSource(List<String> parsedStrings) {
        Collections.reverse(parsedStrings);
        double timeStamp = 0.0;
        for(String line : parsedStrings) {
            Matcher matcher = osThreadPattern.matcher(line);
            if(matcher.find()) {
                String[] split = line.split("s]");
                try {
                    timeStamp = Double.parseDouble(split[0].substring(1));
                } catch (NumberFormatException ex) {
                    LOGGER.log(Level.SEVERE, "Couldn't parse timestamp: " + ex.getMessage());
                }
                break;
            }
        }
        return timeStamp;
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
            try {
                return Double.parseDouble(realSecondsString);
            } catch(NumberFormatException ex) {
                LOGGER.log(Level.SEVERE, "Couldn't parse real seconds: " + ex.getMessage());
                return 0.0;
            }
        }).reduce(Double::sum).orElse(0.0);
        DecimalFormat df = new DecimalFormat("#####.###");
        if (!inMs) {
            try {
                return Double.parseDouble(df.format(realTimeTotal));
            } catch(NumberFormatException ex) {
                LOGGER.log(Level.SEVERE, "Couldn't parse real total time: " + ex.getMessage());
                return 0.0;
            }
        }
        double totalTimeRoundedInMs = 0.0;
        try {
            totalTimeRoundedInMs = Double.parseDouble(df.format(realTimeTotal));
        } catch (NumberFormatException ex) {
            LOGGER.log(Level.SEVERE, "Couldn't parse real total time: " + ex.getMessage());
        }
        return totalTimeRoundedInMs / 1000;
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
}
