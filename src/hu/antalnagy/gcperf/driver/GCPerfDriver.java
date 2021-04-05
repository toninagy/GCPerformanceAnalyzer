package hu.antalnagy.gcperf.driver;

import com.github.sh0nk.matplotlib4j.PythonExecutionException;
import hu.antalnagy.gcperf.Analysis;
import hu.antalnagy.gcperf.GCType;
import hu.antalnagy.gcperf.plot.GCPerfPlot;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GCPerfDriver {
    private static final Path LOC_PATH = Paths.get("").toAbsolutePath();
    private static final Path LOC_OUT_PATH = Paths.get(LOC_PATH + "/res/out");
    private static final Path LOC_OUT_ERR_PATH = Paths.get(LOC_PATH + "/res/outErr").toAbsolutePath();
    private static final Path LOC_OUT_BIN_PATH = Paths.get(LOC_PATH + "/bin").toAbsolutePath();
    private static final Path LOC_OUT_CSV_PATH = Paths.get(LOC_PATH + "/res/csv").toAbsolutePath();
    private static final Logger LOGGER = Logger.getLogger(GCPerfDriver.class.getSimpleName());

    private static String mainClass = null;
    private static List<GCType> gcTypes = null;


    public static void main(String[] args) {
        try {
            var list = new ArrayList<GCType>();
            list.add(GCType.SERIAL);
            list.add(GCType.PARALLEL);
            list.add(GCType.G1);
            list.add(GCType.ZGC);
            list.add(GCType.SHENANDOAH);
            launch(new File("App.class"), 2, 200, 400,
                    50, 100, list, true);
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

    /***
     * @param file .class file or .jar file
     * @param initStartHeapSize Start heap size in MB
     * @param initMaxHeapSize Start maximum heap size in MB
     * @param startHeapIncrementSize Start heap size increment in MB
     * @param maxHeapIncrementSize Maximum heap size increment in MB
     * @param gcTypes Selected Garbage Collector Types
     * @param exportToCSV Export to csv file
     */
    public static void launch(File file, int numOfRuns, int initStartHeapSize, int initMaxHeapSize, int startHeapIncrementSize,
                              int maxHeapIncrementSize, List<GCType> gcTypes, boolean exportToCSV) throws IOException,
            PythonExecutionException, InterruptedException {

        GCPerfDriver.gcTypes = new ArrayList<>(gcTypes);
        extractBinariesAndSetMainClass(file);
        Analysis analysis = new Analysis(mainClass, gcTypes);
        analysis.performGCAnalysis(numOfRuns, initStartHeapSize, initMaxHeapSize,
                startHeapIncrementSize, maxHeapIncrementSize);
        var runtimesMap = analysis.getGcRuntimesMap();
        var avgRuntimesMap = analysis.getAvgRuns();
        var throughputMap = analysis.getThroughputMap();
        var pauseTimesMap = analysis.getPausesMap();
        GCType suggestedGCType = analysis.getSuggestedGC();
        plotResults(runtimesMap);
        if(exportToCSV) {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            Date date = new Date(System.currentTimeMillis());
            createCSVFile(runtimesMap, throughputMap, pauseTimesMap, "results-" + formatter.format(date) + ".csv");
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

    private static void createCSVFile(Map<GCType, List<Double>> runtimesMap, Map<GCType, List<Double>> throughputMap,
                                      Map<GCType, List<Integer>> pausesMap, String fileName) {
        File outFile = new File(LOC_OUT_CSV_PATH + "/" + fileName);
        try (PrintWriter printWriter = new PrintWriter(outFile)) {
            printWriter.write("GCType,RunNo,Runtime(sec),Throughput(%),FullPauses,MinorPauses\n");
            for (GCType gcType : gcTypes) {
                List<Double> runs = runtimesMap.get(gcType);
                List<Double> throughputs = throughputMap.get(gcType);
                List<Integer> pauses = pausesMap.get(gcType);
                StringBuilder stringBuilder = new StringBuilder();
                for (int i = 0, j = 0; i < runs.size(); i++, j++) {
                    stringBuilder.append(gcType.name()).append(",").append(i + 1).append(",").append(runs.get(i)).append(",")
                            .append(throughputs.get(i)).append(",").append(pauses.get(j)).append(",")
                            .append(pauses.get(++j)).append("\n");
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
            if(!entry.getValue().isEmpty()) {
                gcPerfPlot.setGcType(entry.getKey());
                gcPerfPlot.setMeasurements(entry.getValue());
                gcPerfPlot.plotMeasurements();
            }
            else {
                LOGGER.log(Level.WARNING, "Empty values for GC Type: " + entry.getKey());
            }
        }
    }

}
