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
    private static final Path LOC_OUT_BIN_PATH = Paths.get(LOC_PATH + "/bin").toAbsolutePath();

    private static final int MAX_INIT_HEAP_SIZE = 2048;
    private static final int MAX_MAX_HEAP_SIZE = 8192;

    private static final Logger LOGGER = Logger.getLogger(Analysis.class.getSimpleName());
    private static final int BUFFER_SIZE = 8 * 1024;

    private final File outFile;
    private final File outErrFile;

    private static final Pattern gcCpuPattern = Pattern.compile("gc,cpu");
    private static final Pattern gcPhasesPattern = Pattern.compile("gc,phases");
    private static final Pattern gcWhiteSpacePattern = Pattern.compile("gc\\s");
    private static final Pattern gcNumPattern = Pattern.compile("GC\\([0-9]+\\)");

    private static final Object mainLock = new Object();
    private static final Object watcherLock = new Object();

    private final String mainClass;

    private final List<GCType> gcTypes;
    private final Map<GCType, Double> avgRuns = new HashMap<>();
    private final Map<GCType, List<Double>> performanceMap = new HashMap<>();
    private final Map<GCType, List<Integer>> pausesMap = new HashMap<>();

    private static boolean isShenandoahOnly;
    private static final AtomicInteger prematureProcessInterrupts = new AtomicInteger(0);
    private static final AtomicInteger prematureRunIncrement = new AtomicInteger(0);
    private static final AtomicBoolean memoryAllocationFailureOnLastRun = new AtomicBoolean(false);
    private static double lastSuccessfulShenandoahRunTime = 0;

    public Analysis(String mainClass, List<GCType> gcTypes, File outFile, File outErrFile) {
        this.mainClass = mainClass;
        this.gcTypes = new ArrayList<>(gcTypes);
        isShenandoahOnly = gcTypes.contains(GCType.SHENANDOAH) && gcTypes.size() == 1;
        this.outFile = outFile;
        this.outErrFile = outErrFile;
    }

    public Map<GCType, Double> getAvgRuns() {
        return new HashMap<> (avgRuns);
    }

    public Map<GCType, List<Double>> getPerformanceMap() {
        return new HashMap<>(performanceMap);
    }

    public Map<GCType, List<Integer>> getPausesMap() {
        return new HashMap<>(pausesMap);
    }

    private void performGCAnalysis(int runs, int initStartHeapSize, int initMaxHeapSize,
                                   int startHeapIncrementSize, int maxHeapIncrementSize) throws IOException {
        for (GCType gcType : gcTypes) {
            List<Double> measuredTimes = new ArrayList<>();
            List<Integer> pauses = new ArrayList<>();
            double totalTime = 0.0;
            int fullPauses;
            int minorPauses;
            int noOfRuns = runs;
            int lastRunWithNoMallocFailure = 0;
            prematureProcessInterrupts.set(0);
            prematureRunIncrement.set(0);
            LOGGER.log(Level.INFO, "Initializing run with GC Type: " + gcType.name());
            LOGGER.log(Level.INFO, "Expected no. of runs: " + runs);
            for (int i = 0; i < noOfRuns; i++) {
                if(Math.abs(noOfRuns - lastRunWithNoMallocFailure) > 20) {
                    LOGGER.log(Level.SEVERE, "Analysis suspended for GC Type: " + gcType.name() +
                            "\nReason: 10 consecutive failed runs\n" +
                            "Possible problems include too small general heap size or too small heap size increments");
                    break;
                }
                int[] xm = calculateHeapSize(initStartHeapSize, initMaxHeapSize, startHeapIncrementSize, maxHeapIncrementSize,
                        i, prematureProcessInterrupts.get());
                int xms = xm[0];
                int xmx = xm[1];
                LOGGER.log(Level.INFO, "Initializing run no.: " + (i+1) + "; xms: " + xms + "(M); xmx: " + xmx + "(M)");
                if (xms == 2048 && xmx == 8192) {
                    i = noOfRuns;
                    LOGGER.log(Level.WARNING, "Maximum initial heap size (Xms) and maximum heap size (Xmx) reached\n" +
                            "Initializing last run");
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
                totalTime = yieldRunTimes(erroneousRun, outFile, gcType, measuredTimes, totalTime, i);
                fullPauses = yieldNoOfPausesFromSource(yieldOutputStringsFromFile(outFile), gcType)[0];
                minorPauses = yieldNoOfPausesFromSource(yieldOutputStringsFromFile(outFile), gcType)[1];
                pauses.add(fullPauses);
                pauses.add(minorPauses);
            }
            avgRuns.put(gcType, totalTime / runs);
            performanceMap.put(gcType, measuredTimes);
            pausesMap.put(gcType, pauses);
        }
    }

    private static int[] calculateHeapSize(int initStartHeapSize, int initMaxHeapSize, int startHeapIncrementSize, int maxHeapIncrementSize,
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

    private static CLI buildCLI(GCType gcType, int startHeapSize, int maxHeapSize) {
        if (startHeapSize < 1) {
            LOGGER.log(Level.SEVERE, "Invalid argument for initial heap size!");
            throw new IllegalArgumentException("Please provide an initial heap size of at least 1MB!");
        }
        if (maxHeapSize < 16) {
            LOGGER.log(Level.SEVERE, "Invalid argument for maximum heap size!");
            throw new IllegalArgumentException("Please provide a maximum heap size of at least 16MB!");
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
        LOGGER.log(Level.FINE, "Detected Shenandoah GC Type, initializing timeout watcher thread");
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
                        LOGGER.log(Level.WARNING, "Probable premature process interrupt happened");
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
                ex.printStackTrace();
            }
        });
        watcherThread.start();
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

    private int countContinuousHandleAllocations(File outFile) {
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

    private void induceThreadSleep(Map<GCType, Double> avgRunsMap, int magnifier) throws InterruptedException {
        if (lastSuccessfulShenandoahRunTime != 0) {
            Thread.sleep((long) ((lastSuccessfulShenandoahRunTime + 0.25) * 1000L * (magnifier + 1)));
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
                double shenandoahWaitThresholdInMs = 2 * avgRuns;
                Thread.sleep((long) Math.max(250, (shenandoahWaitThresholdInMs * 1000 * (magnifier + 1))));
            } else {
                Thread.sleep(2 * 1000L * (magnifier + 1));
            }
        }
    }

    private static int getNumOfRunsAndHandleUnexpectedThreadEvents(int noOfRuns, final AtomicBoolean processSuspended,
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
                LOGGER.log(Level.SEVERE, "Unknown error occurred, ending analysis");
                throw new IllegalArgumentException("Unknown error occurred during application run. " +
                        "Please verify the integrity of the .class file or check the error output for more details");
            }
            noOfRuns++;
        }
        return noOfRuns;
    }

    private static double yieldRunTimes(final AtomicBoolean erroneousRun, File outFile, GCType gcType, List<Double> measuredTimes,
                                        double totalTime, int runNo) {
        double time;
        if (!erroneousRun.get()) {
            time = yieldGCTimeFromSource(yieldOutputStringsFromFile(outFile), gcType);
            if (gcType == GCType.SHENANDOAH) {
                lastSuccessfulShenandoahRunTime = time;
            }
            measuredTimes.add(time);
            totalTime += time;
            LOGGER.log(Level.INFO, "Run no.: " + (runNo + 1) + " : time: " + time);
        }
        else {
            LOGGER.log(Level.INFO, "Run no.: " + (runNo + 1) + " failed");
        }
        return totalTime;
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

    private static int[] yieldNoOfPausesFromSource(List<String> parsedStrings, GCType gcType) {
        int[] pauses = new int[2];
        int totalPauses;
        int fullPauses;
        switch (gcType) {
            case SERIAL, PARALLEL -> {
                fullPauses = yieldNoOfPausesHelper(parsedStrings, false, "Pause Full", null) / 2;
                totalPauses = yieldNoOfPausesHelper(parsedStrings, false, "Pause", null) / 2;
                pauses[0] = fullPauses;
                pauses[1] = totalPauses - fullPauses;
                return pauses;
            }
            case G1, ZGC -> {
                fullPauses = yieldNoOfPausesHelper(parsedStrings, false, "Pause Full", null) / 2;
                totalPauses = yieldNoOfPausesHelper(parsedStrings, false, "[gc,start    ]", null);
                pauses[0] = fullPauses;
                pauses[1] = totalPauses - fullPauses;
                return pauses;
            }
            case SHENANDOAH -> {
                //TODO fullPauses
                fullPauses = yieldNoOfPausesHelper(parsedStrings, false, "Pause Full", null);
                totalPauses = yieldNoOfPausesHelper(parsedStrings, true, "Pause", "[gc,stats    ]");
                pauses[0] = fullPauses;
                pauses[1] = totalPauses - fullPauses;
                return pauses;
            }
            default -> {
                LOGGER.log(Level.SEVERE, "GCType N/A");
                throw new IllegalArgumentException("Non-existent GC Type");
            }
        }
    }

    private static int yieldNoOfPausesHelper(List<String> parsedStrings, boolean filter, String regex1, String regex2) {
        if(filter && regex2 == null) {
            LOGGER.log(Level.SEVERE, "Second regex is missing");
            throw new IllegalArgumentException("Second regex is missing");
        }
        int counter = 0;
        for(String line : parsedStrings) {
            if(!filter) {
                if(line.contains(regex1)) {
                    counter++;
                }
            }
            else {
                if(line.contains(regex1) && !line.contains(regex2)) {
                    counter++;
                }
            }
        }
        return counter;
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
