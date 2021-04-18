package hu.antalnagy.gcperf.plot;

import com.github.sh0nk.matplotlib4j.Plot;
import com.github.sh0nk.matplotlib4j.PythonExecutionException;
import com.github.sh0nk.matplotlib4j.builder.HistBuilder;
import hu.antalnagy.gcperf.GCType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GCPerfPlot {

    private final Plot plot = Plot.create();
    private List<GCType> gcTypes;
    private Map<GCType, List<Double>> runtimesMap;
    private Map<GCType, Double> avgRuntimesMap;
    private Map<GCType, List<Double>>  throughputsMap;
    private Map<GCType, List<Integer>> pausesMap;

    private static final Logger LOGGER = Logger.getLogger(GCPerfPlot.class.getSimpleName());

    public GCPerfPlot(List<GCType> gcTypes, Map<GCType, List<Double>> runtimesMap, Map<GCType, Double> avgRuntimesMap,
                      Map<GCType, List<Double>> throughputsMap, Map<GCType, List<Integer>> pausesMap) {
        this.gcTypes = gcTypes;
        this.runtimesMap = runtimesMap;
        this.avgRuntimesMap = avgRuntimesMap;
        this.throughputsMap = throughputsMap;
        this.pausesMap = pausesMap;
    }

    public List<GCType> getGcTypes() {
        return gcTypes;
    }

    public void setGcTypes(List<GCType> gcTypes) {
        this.gcTypes = gcTypes;
    }

    public Map<GCType, List<Double>> getRuntimesMap() {
        return runtimesMap;
    }

    public void setRuntimesMap(Map<GCType, List<Double>> runtimesMap) {
        this.runtimesMap = runtimesMap;
    }

    public Map<GCType, Double> getAvgRuntimesMap() {
        return avgRuntimesMap;
    }

    public void setAvgRuntimesMap(Map<GCType, Double> avgRuntimesMap) {
        this.avgRuntimesMap = avgRuntimesMap;
    }

    public Map<GCType, List<Double>> getThroughputsMap() {
        return throughputsMap;
    }

    public void setThroughputsMap(Map<GCType, List<Double>> throughputsMap) {
        this.throughputsMap = throughputsMap;
    }

    public Map<GCType, List<Integer>> getPausesMap() {
        return pausesMap;
    }

    public void setPausesMap(Map<GCType, List<Integer>> pausesMap) {
        this.pausesMap = pausesMap;
    }

    public void plotHelper(String title, List<Double> measurements, List<Double> bins, double max, double min)
            throws IOException, PythonExecutionException {
        double maxIncrement = max <= 101.0 ? 1 : max % 100.0;
        bins.add(max + maxIncrement);
        plot.hist().add(measurements).rwidth(0.025).bins(bins).stacked(false).align(HistBuilder.Align.left)
                .orientation(HistBuilder.Orientation.vertical);
        plot.xlim(min - min/10, max + max/10);
        plot.title(title);
        plot.show();
        plot.close();
    }

    public void plotRuntimes() throws IOException, PythonExecutionException {
        for(GCType gcType : gcTypes) {
            var runtimes = runtimesMap.get(gcType);
            final var xlimMin = runtimes.stream().min(Double::compare);
            final var xlimMax = runtimes.stream().max(Double::compare);
            if(xlimMin.isEmpty()) {
                LOGGER.log(Level.SEVERE, "xLim Minimum value or Maximum value can not be determined");
                throw new IllegalArgumentException("Input values list is empty");
            }
            List<Double> bins = new ArrayList<>(runtimes).stream().sorted().collect(Collectors.toList());
            plotHelper(gcType.name() + " Runtime (in s)", runtimes, bins, xlimMax.get(), xlimMin.get());
        }
    }

    public void plotThroughputs() throws IOException, PythonExecutionException {
        for(GCType gcType : gcTypes) {
            var throughputs = throughputsMap.get(gcType);
            final var xlimMin = -1.0;
            final var xlimMax = 101.0;
            if(throughputs.isEmpty()) {
                LOGGER.log(Level.SEVERE, "No throughput values available");
                throw new IllegalArgumentException("No throughput values available");
            }
            List<Double> bins = new ArrayList<>(throughputs).stream().sorted().collect(Collectors.toList());
            plotHelper(gcType.name() + " Throughput (in %)", throughputs, bins, xlimMax, xlimMin);
        }
    }

    public void plotAvgRuntimes() throws IOException, PythonExecutionException {
        StringBuilder sb = new StringBuilder();
        var sortedGCTypes = gcTypes.stream().sorted((gcType1, gcType2) ->
                avgRuntimesMap.get(gcType1) < avgRuntimesMap.get(gcType2) ? -1 :
                        (avgRuntimesMap.get(gcType1).equals(avgRuntimesMap.get(gcType2)) ? 0 : 1)).collect(Collectors.toList());
        var sortedAvgRuntimesMap = avgRuntimesMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).map(Map.Entry::getValue).collect(Collectors.toList());
        List<Double> bins = new ArrayList<>(sortedAvgRuntimesMap).stream().sorted().collect(Collectors.toList());
        bins.add(bins.get(bins.size()-1) + 1.0);
        plot.hist().add(sortedAvgRuntimesMap).rwidth(0.025).bins(bins).stacked(false).align(HistBuilder.Align.left)
                .orientation(HistBuilder.Orientation.vertical);
        for(GCType gcType : sortedGCTypes) {
            sb.append(gcType.name()).append(" ");
        }
        plot.xlabel(sb.toString());
        plot.xlim(bins.get(0) - bins.get(0) / 10, bins.get(bins.size()-1) - bins.get(bins.size()-1) / 10);
        plot.title("Average Runtimes (in s) by GC Type");
        plot.show();
        plot.close();
    }
}
