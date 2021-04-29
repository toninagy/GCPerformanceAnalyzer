package hu.antalnagy.gcperf.driver;

import com.github.sh0nk.matplotlib4j.PythonExecutionException;
import hu.antalnagy.gcperf.Analysis;
import hu.antalnagy.gcperf.GCType;
import hu.antalnagy.gcperf.persistence.DBDriver;
import hu.antalnagy.gcperf.plot.GCPerfPlot;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class GCPerfDriver {
    private static final Path LOC_PATH = Paths.get("").toAbsolutePath();
    private static final Path LOC_LOG_PATH = Paths.get(LOC_PATH + "/log");
    private static final Path LOC_OUT_PATH = Paths.get(LOC_PATH + "/res/out");
    private static final Path LOC_OUT_ERR_PATH = Paths.get(LOC_PATH + "/res/outErr").toAbsolutePath();
    private static final Path LOC_OUT_BIN_PATH = Paths.get(LOC_PATH + "/bin").toAbsolutePath();
    private static final Path LOC_OUT_CSV_PATH = Paths.get(LOC_PATH + "/res/csv").toAbsolutePath();
    private static final Logger LOGGER = Logger.getLogger(GCPerfDriver.class.getSimpleName());

    private DBDriver dbDriver;
    private String mainClass;
    private Analysis analysis;
    private List<String> resultMetrics;

    public static void main(String[] args) { //todo remove main from deployment
        var list = new ArrayList<GCType>();
//            list.add(GCType.SERIAL);
//            list.add(GCType.PARALLEL);
        list.add(GCType.G1);
//        list.add(GCType.ZGC);
        list.add(GCType.SHENANDOAH);
        GCPerfDriver gcPerfDriver;
        try {
            gcPerfDriver = new GCPerfDriver();
            gcPerfDriver.launch(new File("App.class"), 2, 323, 400,
                    40, 50, list, new Analysis.Metrics[]{Analysis.Metrics.BestGCRuntime,
                            Analysis.Metrics.AvgGCRuntime, Analysis.Metrics.Throughput, Analysis.Metrics.Latency,
                            Analysis.Metrics.MinorPauses, Analysis.Metrics.FullPauses}, false, false);
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

    public Analysis.Progress getProgress() {
        return analysis.getProgress();
    }

    public List<String> getResultMetrics() {
        return new ArrayList<>(resultMetrics);
    }

    public List<GCType> getLeaderboard() {
        return new LinkedList<>(analysis.getLeaderboard());
    }

    public Logger getAnalysisLogger() {
        return Analysis.getLOGGER();
    }

    public Logger getDBDriverLogger() {
        return DBDriver.getLOGGER();
    }

    /***
     * @param file .class file or .jar file
     * @param initStartHeapSize Start heap size in MB
     * @param initMaxHeapSize Start maximum heap size in MB
     * @param startHeapIncrementSize Start heap size increment in MB
     * @param maxHeapIncrementSize Maximum heap size increment in MB
     * @param gcTypes Selected Garbage Collector Types
     * @param exportToCSV Export to csv file
     */
    public void launch(File file, int numOfRuns, int initStartHeapSize, int initMaxHeapSize, int startHeapIncrementSize,
                       int maxHeapIncrementSize, List<GCType> gcTypes, Analysis.Metrics[] metrics,
                       boolean exportToCSV, boolean plot) throws IOException, PythonExecutionException, InterruptedException {
        this.resultMetrics = new ArrayList<>();
        try {
            extractBinariesAndSetMainClass(file);
            analysis = new Analysis(mainClass, gcTypes, metrics);
            FileHandler fileHandler = new FileHandler(LOC_LOG_PATH.toString());
            SimpleFormatter formatter = new SimpleFormatter();
            fileHandler.setFormatter(formatter);
            getAnalysisLogger().addHandler(fileHandler);
            getDBDriverLogger().addHandler(fileHandler);
            LOGGER.addHandler(fileHandler);
            analysis.performGCAnalysis(numOfRuns, initStartHeapSize, initMaxHeapSize,
                    startHeapIncrementSize, maxHeapIncrementSize);
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "IOException occurred");
            throw new IOException(ex.getMessage());
        } catch (InterruptedException ex) {
            LOGGER.log(Level.SEVERE, "InterruptedException occurred");
            throw new InterruptedException(ex.getMessage());
        }
        var runtimesMap = analysis.getGcRuntimesMap();
        var avgRuntimesMap = analysis.getAvgGCRuns();
        var throughputsMap = analysis.getThroughputsMap();
        var pausesMap = analysis.getPausesMap();
        var leaderboard = analysis.getLeaderboard();
        leaderboard.forEach(record -> LOGGER.log(Level.INFO, leaderboard.indexOf(record) + 1 + ": " + record.name()));
        resultsList(gcTypes, runtimesMap, throughputsMap, pausesMap);
        if(dbDriver == null) {
            dbDriver = new DBDriver();
        }
        else {
            dbDriver.createConnectionAndStatement();
        }
        try {
            dbDriver.insertRow(new Timestamp(System.currentTimeMillis()), file.getName(), getLeaderboard());
        } finally {
            dbDriver.close();
        }
        analysis.getProgress().setDone(true);
        if(plot) {
            try {
                plotResults(gcTypes, runtimesMap, avgRuntimesMap, throughputsMap);
            } catch (PythonExecutionException ex) {
                LOGGER.log(Level.SEVERE, "PythonExecutionException occurred");
                throw new PythonExecutionException(ex.getMessage());
            }
        }
        if (exportToCSV) {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            Date date = new Date(System.currentTimeMillis());
            createCSVFile(gcTypes, runtimesMap, throughputsMap, pausesMap, "results-" + formatter.format(date) + ".csv");
        }
    }

    private static void createBinDirectoryAndCopyFile(File file) throws IOException {
        if (Files.exists(LOC_OUT_BIN_PATH)) {
            if (!deleteFilesInDirectory(LOC_OUT_BIN_PATH.toFile())) {
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

    private void extractBinariesAndSetMainClass(File file) throws IOException, InterruptedException {
        String fileName = file.getName();
        createBinDirectoryAndCopyFile(file);

        if (fileName.endsWith(".class")) {
            mainClass = fileName.substring(0, fileName.length() - 6);
        } else if (fileName.endsWith(".jar")) {
            ProcessBuilder processBuilder = new ProcessBuilder("jar", "xf", fileName);
            processBuilder.directory(LOC_OUT_BIN_PATH.toFile());
            Process process = processBuilder.start();
            process.waitFor();
            File[] files = Paths.get(LOC_OUT_BIN_PATH + "/META-INF").toFile().listFiles();
            if (files == null) {
                LOGGER.log(Level.SEVERE, "Empty binaries directory");
                throw new IllegalArgumentException("Empty binaries directory");
            }
            Optional<File> manifest = Arrays.stream(files).filter(f -> f.getName().contains("MANIFEST.MF")).findFirst();
            if (manifest.isEmpty()) {
                LOGGER.log(Level.SEVERE, "MANIFEST.MF file missing");
                throw new IllegalArgumentException("MANIFEST.MF file couldn't be found. Please check .jar file " +
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
        } else {
            LOGGER.log(Level.SEVERE, "File format not supported");
            throw new IllegalArgumentException("File format not supported. Please provide either a .class file or a .jar file");
        }
    }

    private void createCSVFile(List<GCType> gcTypes, Map<GCType, List<Double>> runtimesMap, Map<GCType, List<Double>> throughputMap,
                               Map<GCType, List<Integer>> pausesMap, String fileName) {
        File outFile = new File(LOC_OUT_CSV_PATH + "/" + fileName);
        try (PrintWriter printWriter = new PrintWriter(outFile)) {
            printWriter.write("GCType,RunNo,GCRuntime(sec),Throughput(%),FullPauses,MinorPauses\n");
            for (GCType gcType : gcTypes) {
                String result = buildResultString(runtimesMap, throughputMap, pausesMap, gcType);
                printWriter.write(result);
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "IO exception occurred");
            ex.printStackTrace();
        } catch (NullPointerException npe) {
            LOGGER.log(Level.SEVERE, "NPE occurred, possibly empty maps provided");
            npe.printStackTrace();
        }
    }

    private String buildResultString(Map<GCType, List<Double>> runtimesMap, Map<GCType, List<Double>> throughputMap,
                                           Map<GCType, List<Integer>> pausesMap, GCType gcType) {
        List<Double> runs = runtimesMap.get(gcType);
        List<Double> throughputs = throughputMap.get(gcType);
        List<Integer> pauses = pausesMap.get(gcType);
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0, j = 0; i < runs.size(); i++, j++) {
            stringBuilder.append(gcType.name()).append(",").append(i + 1).append(",").append(runs.get(i)).append(",")
                    .append(throughputs.get(i)).append(",").append(pauses.get(j)).append(",")
                    .append(pauses.get(++j)).append("\n");
        }
        return stringBuilder.toString();
    }

    private void resultsList(List<GCType> gcTypes, Map<GCType, List<Double>> runtimesMap, Map<GCType, List<Double>> throughputMap,
                                        Map<GCType, List<Integer>> pausesMap) {
        for (GCType gcType : gcTypes) {
            String result = buildResultString(runtimesMap, throughputMap, pausesMap, gcType);
            resultMetrics.addAll(Arrays.asList(result.split("\n")));
        }
    }

    private void plotResults(List<GCType> gcTypes, Map<GCType, List<Double>> runtimesMap, Map<GCType, Double> avgRuntimesMap,
                             Map<GCType, List<Double>> throughputsMap) throws IOException, PythonExecutionException {
        GCPerfPlot gcPerfPlot = constructGcPerfPlot(gcTypes, runtimesMap, avgRuntimesMap, throughputsMap);
        gcPerfPlot.plotRuntimes();
        gcPerfPlot.plotThroughputs();
        gcPerfPlot.plotAvgRuntimes();
    }

    private GCPerfPlot constructGcPerfPlot(List<GCType> gcTypes, Map<GCType, List<Double>> runtimesMap, Map<GCType, Double> avgRuntimesMap,
                                           Map<GCType, List<Double>> throughputsMap) {
        GCPerfPlot gcPerfPlot = GCPerfPlot.getInstance();
        gcPerfPlot.setGcTypes(gcTypes);
        gcPerfPlot.setRuntimesMap(runtimesMap);
        gcPerfPlot.setAvgRuntimesMap(avgRuntimesMap);
        gcPerfPlot.setThroughputsMap(throughputsMap);
        return gcPerfPlot;
    }
}
