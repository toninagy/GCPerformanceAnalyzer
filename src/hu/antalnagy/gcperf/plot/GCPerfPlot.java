package hu.antalnagy.gcperf.plot;

import com.github.sh0nk.matplotlib4j.Plot;
import com.github.sh0nk.matplotlib4j.PythonExecutionException;
import com.github.sh0nk.matplotlib4j.builder.HistBuilder;
import hu.antalnagy.gcperf.GCType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GCPerfPlot {

    private static final GCPerfPlot gcPerfPlot = new GCPerfPlot();
    private final Plot plot = Plot.create();
    private List<GCType> gcTypes;
    private Map<GCType, List<Double>> runtimesMap;
    private Map<GCType, Double> avgRuntimesMap;
    private Map<GCType, List<Double>>  throughputsMap;

    private static final Logger LOGGER = Logger.getLogger(GCPerfPlot.class.getSimpleName());

    private GCPerfPlot() {}

    public static GCPerfPlot getInstance() {
        return gcPerfPlot;
    }

    public void setGcTypes(List<GCType> gcTypes) {
        this.gcTypes = new ArrayList<>(gcTypes);
    }

    public void setRuntimesMap(Map<GCType, List<Double>> runtimesMap) {
        this.runtimesMap = new HashMap<>(runtimesMap);
    }

    public void setAvgRuntimesMap(Map<GCType, Double> avgRuntimesMap) {
        this.avgRuntimesMap = new HashMap<>(avgRuntimesMap);
    }

    public void setThroughputsMap(Map<GCType, List<Double>> throughputsMap) {
        this.throughputsMap = new HashMap<>(throughputsMap);
    }

    public void plotHelper(String title, List<Double> measurements, List<Double> bins, double min, double max)
            throws IOException, PythonExecutionException {
        bins.add(max + max/10);
        plot.hist().add(measurements).rwidth(0.025).bins(bins).stacked(false).align(HistBuilder.Align.left)
                .orientation(HistBuilder.Orientation.vertical);
        plot.xlim(min - min/10, max + max/10);
        plot.title(title);
        plot.show();
        plot.close();
    }

    private List<Double> getBins(List<Double> measurements) {
        return new ArrayList<>(measurements).stream().sorted().collect(Collectors.toList());
    }

    private double[] getMinMaxLimits(List<Double> measurements) {
        if(measurements.isEmpty()) {
            LOGGER.log(Level.SEVERE, "No values available");
            throw new IllegalArgumentException("No values available");
        }
        double xLimMin = measurements.stream().min(Double::compare).orElse(0.0);
        double xLimMax = measurements.stream().max(Double::compare).orElse(0.0);
        return new double[] {xLimMin, xLimMax};
    }

    public void plotRuntimes() throws IOException, PythonExecutionException {
        for(GCType gcType : gcTypes) {
            var runtimes = runtimesMap.get(gcType);
            double[] limits = getMinMaxLimits(runtimes);
            List<Double> bins = getBins(runtimes);
            plotHelper(gcType.name() + " Runtime (in s)", runtimes, bins, limits[0], limits[1]);
        }
    }

    public void plotThroughputs() throws IOException, PythonExecutionException {
        for(GCType gcType : gcTypes) {
            var throughputs = throughputsMap.get(gcType);
            double[] limits = getMinMaxLimits(throughputs);
            List<Double> bins = getBins(throughputs);
            plotHelper(gcType.name() + " Throughput (in %)", throughputs, bins, limits[0], limits[1]);
        }
    }

    public void plotAvgRuntimes() throws IOException, PythonExecutionException {
        StringBuilder sb = new StringBuilder();
        var sortedGCTypes = gcTypes.stream().sorted((gcType1, gcType2) ->
                avgRuntimesMap.get(gcType1) < avgRuntimesMap.get(gcType2) ? -1 :
                        (avgRuntimesMap.get(gcType1).equals(avgRuntimesMap.get(gcType2)) ? 0 : 1)).collect(Collectors.toList());
        var sortedAvgRuntimes = avgRuntimesMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).map(Map.Entry::getValue).collect(Collectors.toList());
        List<Double> bins = getBins(sortedAvgRuntimes);
        for(GCType gcType : sortedGCTypes) {
            sb.append(gcType.name()).append(" ");
        }
        plot.xlabel(sb.toString());
        plotHelper("Average Runtimes (in s) by GC Type", sortedAvgRuntimes, bins, bins.get(0), bins.get(bins.size()-1));
    }
}
