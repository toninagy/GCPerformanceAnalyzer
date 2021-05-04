package hu.antalnagy.gcperf;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Leaderboard {

    private final List<GCType> leaderboard;
    private final Map<GCType, Double> avgGCRuns;
    private final Map<GCType, List<Double>> gcRuntimesMap;
    private final Map<GCType, List<Double>> throughputsMap;
    private final Map<GCType, List<Integer>> pausesMap;
    private final List<GCType> gcTypes;
    
    private static final Logger LOGGER = Logger.getLogger(Leaderboard.class.getSimpleName());

    public Leaderboard(Map<GCType, Double> avgGCRuns, Map<GCType, List<Double>> gcRuntimesMap, Map<GCType, 
            List<Double>> throughputsMap, Map<GCType, List<Integer>> pausesMap, List<GCType> gcTypes) {
        this.leaderboard = new LinkedList<>();
        this.avgGCRuns = avgGCRuns;
        this.gcRuntimesMap = gcRuntimesMap;
        this.throughputsMap = throughputsMap;
        this.pausesMap = pausesMap;
        this.gcTypes = gcTypes;
    }

    public List<GCType> getLeaderboard() {
        return new LinkedList<>(leaderboard);
    }

    public Map<GCType, Double> getAvgGCRuns() {
        return avgGCRuns;
    }

    public Map<GCType, List<Double>> getGcRuntimesMap() {
        return gcRuntimesMap;
    }

    public Map<GCType, List<Double>> getThroughputsMap() {
        return throughputsMap;
    }

    public Map<GCType, List<Integer>> getPausesMap() {
        return pausesMap;
    }

    public List<GCType> getGcTypes() {
        return gcTypes;
    }

    public static Logger getLOGGER() {
        return LOGGER;
    }
    
    public void setLeaderboard(Analysis.Metrics... metrics) {
        List<Analysis.Metrics> metricsList = List.of(metrics);
        Map<GCType, Integer> leaderboardMap = new HashMap<>();
        leaderboard.clear();

        if(metricsList.contains(Analysis.Metrics.BestGCRuntime)) {
            throughputRuntimeHelper(leaderboardMap, gcRuntimesMap, false);
            LOGGER.log(Level.INFO, "Results after weighing in BestGCRuntime metric:");
            leaderboardMap.forEach((gcType, i) -> LOGGER.log(Level.INFO,gcType + " score: " + i));
        }
        if(metricsList.contains(Analysis.Metrics.AvgGCRuntime)) {
            avgRuntimeHelper(leaderboardMap, avgGCRuns);
            LOGGER.log(Level.INFO, "Results after weighing in AvgGCRuntime metric:");
            leaderboardMap.forEach((gcType, i) -> LOGGER.log(Level.INFO,gcType + " score: " + i));
        }
        if(metricsList.contains(Analysis.Metrics.Throughput)) {
            throughputRuntimeHelper(leaderboardMap, throughputsMap, true);
            LOGGER.log(Level.INFO, "Results after weighing in Throughput metric:");
            leaderboardMap.forEach((gcType, i) -> LOGGER.log(Level.INFO,gcType + " score: " + i));
        }
        if(metricsList.contains(Analysis.Metrics.Latency)) {
            latencyHelper(leaderboardMap, gcTypes);
            LOGGER.log(Level.INFO, "Results after weighing in Latency metric:");
            leaderboardMap.forEach((gcType, i) -> LOGGER.log(Level.INFO,gcType + " score: " + i));
        }
        if(metricsList.contains(Analysis.Metrics.MinorPauses)) {
            pausesHelper(leaderboardMap, pausesMap, false);
            LOGGER.log(Level.INFO, "Results after weighing in MinorPauses metric:");
            leaderboardMap.forEach((gcType, i) -> LOGGER.log(Level.INFO,gcType + " score: " + i));
        }
        if(metricsList.contains(Analysis.Metrics.FullPauses)) {
            pausesHelper(leaderboardMap, pausesMap, true);
            LOGGER.log(Level.INFO, "Results after weighing in FullPauses metric:");
            leaderboardMap.forEach((gcType, i) -> LOGGER.log(Level.INFO,gcType + " score: " + i));
        }
        leaderboardMap.entrySet().stream().sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .forEach(e -> leaderboard.add(e.getKey()));
    }

    private static void avgRuntimeHelper(Map<GCType, Integer> leaderboardMap, Map<GCType, Double> avgGCRuns) {
        List<Map.Entry<GCType, Double>> sortedList = avgGCRuns.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toList());
        sortedList.forEach(entry -> {
            GCType key = entry.getKey();
            int value = sortedList.size() - sortedList.indexOf(entry); //best is lowest
            leaderboardMap.merge(key, value, Integer::sum);
        });
    }

    private static void throughputRuntimeHelper(Map<GCType, Integer> leaderboardMap, Map<GCType, List<Double>> throughputsMap,
                                                   boolean isThroughput) {
        List<Map.Entry<GCType, List<Double>>> sortedList = throughputsMap.entrySet().stream()
                .sorted(Comparator.comparing(e -> {
                    if(isThroughput) {
                        return e.getValue().stream().max(Double::compareTo).orElse(0.0);
                    }
                    return e.getValue().stream().min(Double::compareTo).orElse((double) Integer.MAX_VALUE);
                }))
                .collect(Collectors.toList());
        sortedList.forEach(entry -> {
            GCType key = entry.getKey();
            int value;
            if(isThroughput) {
                value = sortedList.indexOf(entry) + 1; //best is highest
            }
            else {
                value = sortedList.size() - sortedList.indexOf(entry); //best is lowest
            }
            leaderboardMap.merge(key, value, Integer::sum);
        });
    }

    private static void pausesHelper(Map<GCType, Integer> leaderboardMap, Map<GCType, List<Integer>> pausesMap, 
                                     boolean fullPauses) {
        List<Map.Entry<GCType, List<Integer>>> sortedList = pausesMap.entrySet().stream()
                .sorted(Comparator.comparing(e -> {
                    List<Integer> values = e.getValue();
                    var pauses = Stream.iterate(fullPauses ? 0 : 1, i -> i + 2).limit(values.size() / 2)
                            .map(values::get).collect(Collectors.toList());
                    return pauses.stream().min(Integer::compareTo).orElse(Integer.MAX_VALUE);
                })).collect(Collectors.toList());
        sortedList.forEach(entry -> {
            GCType key = entry.getKey();
            int value = sortedList.size() - sortedList.indexOf(entry); //best is lowest
            leaderboardMap.merge(key, value, Integer::sum);
        });
    }

    private static void latencyHelper(Map<GCType, Integer> leaderboardMap, List<GCType> gcTypes) {
        int value = gcTypes.size();
        if(gcTypes.contains(GCType.ZGC)) {
            leaderboardMap.merge(GCType.ZGC, value--, Integer::sum);
        }
        if(gcTypes.contains(GCType.SHENANDOAH)) {
            leaderboardMap.merge(GCType.SHENANDOAH, value--, Integer::sum);
        }
        if(gcTypes.contains(GCType.PARALLEL)) {
            leaderboardMap.merge(GCType.PARALLEL, value--, Integer::sum);
        }
        if(gcTypes.contains(GCType.G1)) {
            leaderboardMap.merge(GCType.G1, value--, Integer::sum);
        }
        if(gcTypes.contains(GCType.SERIAL)) {
            leaderboardMap.merge(GCType.SERIAL, value, Integer::sum);
        }
    }
}
