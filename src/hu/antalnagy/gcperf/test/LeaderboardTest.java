package hu.antalnagy.gcperf.test;

import hu.antalnagy.gcperf.Analysis;
import hu.antalnagy.gcperf.GCType;
import hu.antalnagy.gcperf.Leaderboard;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class LeaderboardTest {

    private Leaderboard leaderboard;

    @Before
    public void setUp() {
        Map<GCType, Double> avgGCRuns = new HashMap<>();
        Map<GCType, List<Double>> gcRuntimesMap = new HashMap<>();
        Map<GCType, List<Double>> throughputsMap = new HashMap<>();
        Map<GCType, List<Integer>> pausesMap = new HashMap<>();
        List<GCType> gcTypes = new LinkedList<>();
        leaderboard = new Leaderboard(avgGCRuns, gcRuntimesMap, throughputsMap, pausesMap, gcTypes);
    }

    @Test
    public void testBestGCRuntimeMetric() {
        leaderboard.getGcTypes().add(GCType.SERIAL);
        List<Double> runtimesSerial = new ArrayList<>();
        runtimesSerial.add(3.25);
        runtimesSerial.add(2.25);
        leaderboard.getGcRuntimesMap().put(GCType.SERIAL, runtimesSerial);
        leaderboard.setLeaderboard(Analysis.Metrics.BestGCRuntime);

        LinkedList<GCType> results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.SERIAL, results.getFirst());

        List<Double> runtimesG1 = new ArrayList<>(runtimesSerial);
        runtimesG1.add(0.25);
        leaderboard.getGcRuntimesMap().put(GCType.G1, runtimesG1);

        leaderboard.setLeaderboard(Analysis.Metrics.BestGCRuntime);
        results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.G1, results.getFirst());

        List<Double> runtimesZGC = new ArrayList<>(runtimesG1);
        runtimesZGC.add(0.1);
        runtimesSerial.add(0.24);
        leaderboard.getGcRuntimesMap().put(GCType.ZGC, runtimesZGC);
        leaderboard.setLeaderboard(Analysis.Metrics.BestGCRuntime);
        results = (LinkedList<GCType>) leaderboard.getLeaderboard();

        assertEquals(GCType.G1, results.getLast());
        assertEquals(GCType.ZGC, results.getFirst());
    }

    @Test
    public void testAvgGCRuntimeMetric() {
        leaderboard.getGcTypes().add(GCType.SERIAL);
        leaderboard.getGcTypes().add(GCType.PARALLEL);
        leaderboard.getGcTypes().add(GCType.SHENANDOAH);
        leaderboard.getAvgGCRuns().put(GCType.SERIAL, (3.25 + 2.25) / 2);
        leaderboard.getAvgGCRuns().put(GCType.PARALLEL, (1.25 + 2.25) / 2);
        leaderboard.getAvgGCRuns().put(GCType.SHENANDOAH, (1.25 + 0.25) / 2);
        leaderboard.setLeaderboard(Analysis.Metrics.AvgGCRuntime);

        LinkedList<GCType> results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.SERIAL, results.getLast());
        assertEquals(GCType.SHENANDOAH, results.getFirst());
    }

    @Test
    public void testThroughputMetric() {
        leaderboard.getGcTypes().add(GCType.G1);
        List<Double> throughputsG1 = new ArrayList<>();
        throughputsG1.add(67.25);
        throughputsG1.add(92.25);
        leaderboard.getThroughputsMap().put(GCType.G1, throughputsG1);
        leaderboard.setLeaderboard(Analysis.Metrics.Throughput);

        LinkedList<GCType> results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.G1, results.getFirst());

        leaderboard.getGcTypes().add(GCType.ZGC);
        List<Double> throughputsZGC = new ArrayList<>(throughputsG1);
        throughputsZGC.add(9.25);
        throughputsZGC.remove(92.25);
        leaderboard.getThroughputsMap().put(GCType.ZGC, throughputsZGC);

        leaderboard.setLeaderboard(Analysis.Metrics.Throughput);
        results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.G1, results.getFirst());

        throughputsZGC.add(98.95);
        leaderboard.setLeaderboard(Analysis.Metrics.Throughput);
        results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.ZGC, results.getFirst());

        leaderboard.getGcTypes().add(GCType.SHENANDOAH);
        List<Double> throughputsShenandoah = new ArrayList<>(throughputsG1);
        throughputsShenandoah.add(99.25);
        leaderboard.getThroughputsMap().put(GCType.SHENANDOAH, throughputsShenandoah);
        leaderboard.setLeaderboard(Analysis.Metrics.Throughput);
        results = (LinkedList<GCType>) leaderboard.getLeaderboard();

        assertEquals(GCType.G1, results.getLast());
        assertEquals(GCType.SHENANDOAH, results.getFirst());
    }

    @Test
    public void testPausesMetric() {
        leaderboard.getGcTypes().add(GCType.SERIAL);
        List<Integer> pausesSerial = new ArrayList<>();
        pausesSerial.add(3);
        pausesSerial.add(20);
        leaderboard.getPausesMap().put(GCType.SERIAL, pausesSerial);

        leaderboard.setLeaderboard(Analysis.Metrics.MinorPauses);
        LinkedList<GCType> results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.SERIAL, results.getFirst());

        List<Integer> pausesParallel = new ArrayList<>(pausesSerial);
        pausesParallel.add(4);
        pausesParallel.add(19);
        leaderboard.getPausesMap().put(GCType.PARALLEL, pausesParallel);

        leaderboard.setLeaderboard(Analysis.Metrics.MinorPauses);
        results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.PARALLEL, results.getFirst());

        pausesParallel.remove(Integer.valueOf(3));
        pausesParallel.remove(Integer.valueOf(20));
        leaderboard.setLeaderboard(Analysis.Metrics.FullPauses);
        results = (LinkedList<GCType>) leaderboard.getLeaderboard();
        assertEquals(GCType.SERIAL, results.getFirst());
    }
}
