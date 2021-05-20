package hu.antalnagy.gcperf.test;

import hu.antalnagy.gcperf.Analysis;
import hu.antalnagy.gcperf.CLI;
import hu.antalnagy.gcperf.GCType;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AnalysisTest {

    private final double EPSILON = 0.01;

    private Analysis analysis;
    private final List<String> testStrings = new ArrayList<>();

    @Before
    public void setUp() {
        analysis = new Analysis("App", List.of(GCType.SERIAL, GCType.PARALLEL, GCType.G1, GCType.ZGC, GCType.SHENANDOAH),
                new Analysis.Metrics[]{Analysis.Metrics.BestGCRuntime, Analysis.Metrics.AvgGCRuntime, Analysis.Metrics.Throughput,
                        Analysis.Metrics.Latency, Analysis.Metrics.MinorPauses, Analysis.Metrics.FullPauses});
        testStrings.clear();
        Collections.addAll(testStrings,
                "[0.173s][info][gc,phases   ] GC(0) Pause Mark Start 0.222ms",
                "[0.426s][info][gc          ] GC(0) Pause Init Update Refs 0.045ms",
                "[0.216s][info][gc,phases   ] GC(0) Pause Mark End 0.278ms",
                "[0.215s][info][gc,phases   ] GC(0) Concurrent Mark 42.567ms",
                "[0.451s][info][gc,start    ] GC(2) Pause Full (Ergonomics)",
                "[0.898s][info][gc,cpu         ] GC(3) User=0.23s Sys=0.02s Real=0.25s",
                "[0.641s][info][gc             ] GC(2) Pause Full (Ergonomics) 116M->116M(290M) 190.829ms",
                "[0.649s][info][gc,start    ] GC(3) Pause Full (Allocation Failure)",
                "[0.898s][info][gc             ] GC(3) Pause Full (Allocation Failure) 190M->190M(290M) 249.133ms",
                "[0.719s][info][gc,start       ] GC(3) Pause Young (Allocation Failure)",
                "[1.896s][info][gc,cpu         ] GC(6) User=0.44s Sys=0.00s Real=0.44s",
                "[3.092s][info][gc,cpu         ] GC(10) User=0.16s Sys=0.00s Real=0.15s",
                "[0.811s][info][gc             ] GC(3) Pause Young (Allocation Failure) 196M->279M(290M) 92.339ms",
                "[1.350s][info][gc,start    ] GC(20) Pause Full (G1 Evacuation Pause)",
                "[1.460s][info][gc             ] GC(20) Pause Full (G1 Evacuation Pause) 456M->177M(456M) 109.413ms",
                "[0.276s][info][gc,start    ] GC(2) Pause Young (Normal) (G1 Evacuation Pause)",
                "[0.287s][info][gc          ] GC(2) Pause Young (Normal) (G1 Evacuation Pause) 57M->58M(278M) 10.615ms");
    }

    @Test
    public void testPerformGCAnalysis() throws IOException {
        assertNotNull(analysis.getMainClass());
        assertNotNull(analysis.getGcTypes());
        assertNotNull(analysis.getMetrics());
        assertNotNull(analysis.getProgress());

        analysis.performGCAnalysis(1,1000,2000,1,1);

        assertFalse(analysis.getAvgRuns().isEmpty());
        assertFalse(analysis.getGcRuntimes().isEmpty());
        assertFalse(analysis.getAvgGCRuns().isEmpty());
        assertFalse(analysis.getPausesMap().isEmpty());
        assertFalse(analysis.getThroughputsMap().isEmpty());
        assertFalse(analysis.getLeaderboard().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> analysis.performGCAnalysis(0,300,400,100,200));
        assertThrows(IllegalArgumentException.class, () -> analysis.performGCAnalysis(2,3000,400,100,200));
        assertThrows(IllegalArgumentException.class, () -> analysis.performGCAnalysis(2,300,9000,100,200));
        assertThrows(IllegalArgumentException.class, () -> analysis.performGCAnalysis(2,300,400,2000,200));
        assertThrows(IllegalArgumentException.class, () -> analysis.performGCAnalysis(2,300,400,100,2000));
    }

    @Test
    public void testBuildCLI() {
        CLI serialCLI = analysis.buildCLI(GCType.SERIAL, 200, 400);
        assertEquals(2, serialCLI.getVmOptions().length);
        assertEquals(1, serialCLI.getGcOptions().length);
        assertEquals(9, serialCLI.getXlogOptions().length);

        CLI parallelCLI = analysis.buildCLI(GCType.PARALLEL, 200, 400);
        assertEquals(2, parallelCLI.getVmOptions().length);
        assertEquals(1, parallelCLI.getGcOptions().length);
        assertEquals(9, parallelCLI.getXlogOptions().length);

        CLI g1CLI = analysis.buildCLI(GCType.G1, 200, 400);
        assertEquals(2, g1CLI.getVmOptions().length);
        assertEquals(1, g1CLI.getGcOptions().length);
        assertEquals(11, g1CLI.getXlogOptions().length);

        CLI zgcCLI = analysis.buildCLI(GCType.ZGC, 200, 400);
        assertEquals(2, zgcCLI.getVmOptions().length);
        assertEquals(2, zgcCLI.getGcOptions().length);
        assertEquals(16, zgcCLI.getXlogOptions().length);

        CLI shenandoahCLI = analysis.buildCLI(GCType.SHENANDOAH, 200, 400);
        assertEquals(2, shenandoahCLI.getVmOptions().length);
        assertEquals(2, shenandoahCLI.getGcOptions().length);
        assertEquals(7, shenandoahCLI.getXlogOptions().length);

        assertThrows(IllegalArgumentException.class, () -> analysis.buildCLI(GCType.SHENANDOAH, 0, 400));
        assertThrows(IllegalArgumentException.class, () -> analysis.buildCLI(GCType.SHENANDOAH, 1, 15));
    }

    @Test
    public void testYieldNoOfPauses() {
        int[] pausesSerial = Analysis.yieldNoOfPauses(testStrings, GCType.SERIAL);
        int fullPausesSerial = pausesSerial[0];
        int minorPausesSerial = pausesSerial[1];
        assertEquals(3, fullPausesSerial);
        assertEquals(3, minorPausesSerial);

        int[] pausesParallel = Analysis.yieldNoOfPauses(testStrings, GCType.PARALLEL);
        int fullPausesParallel = pausesParallel[0];
        int minorPausesParallel = pausesParallel[1];
        assertEquals(3, fullPausesParallel);
        assertEquals(3, minorPausesParallel);

        int[] pausesG1 = Analysis.yieldNoOfPauses(testStrings, GCType.G1);
        int fullPausesG1 = pausesG1[0];
        int minorPausesG1 = pausesG1[1];
        assertEquals(3, fullPausesG1);
        assertEquals(3, minorPausesG1);

        testStrings.add("[0.276s][info][gc    ] GC(1) Pause Young (Normal) (G1 Evacuation Pause)");

        pausesG1 = Analysis.yieldNoOfPauses(testStrings, GCType.G1);
        minorPausesG1 = pausesG1[1];
        assertNotEquals(5, minorPausesG1);
        assertEquals(4, minorPausesG1);

        int[] pausesZGC = Analysis.yieldNoOfPauses(testStrings, GCType.ZGC);
        int fullPausesZGC = pausesZGC[0];
        int minorPausesZGC = pausesZGC[1];
        assertEquals(3, fullPausesZGC);
        assertNotEquals(3, minorPausesZGC);
        assertEquals(2, minorPausesZGC);

        testStrings.add("[0.273s][info][gc,start    ] Garbage Collection (Warmup)");

        pausesZGC = Analysis.yieldNoOfPauses(testStrings, GCType.ZGC);
        minorPausesZGC = pausesZGC[1];
        assertEquals(3, minorPausesZGC);

        int[] pausesShenandoah = Analysis.yieldNoOfPauses(testStrings, GCType.SHENANDOAH);
        int fullPausesShenandoah = pausesShenandoah[0];
        int minorPausesShenandoah;
        assertEquals(0, fullPausesShenandoah);

        testStrings.add("[gc,stats    ]     6 Full GCs");

        pausesShenandoah = Analysis.yieldNoOfPauses(testStrings, GCType.SHENANDOAH);
        fullPausesShenandoah = pausesShenandoah[0];
        minorPausesShenandoah = pausesShenandoah[1];
        assertEquals(6, fullPausesShenandoah);
        assertEquals(8, minorPausesShenandoah);

        testStrings.add("[3.688s][info][gc,stats    ] Pause Init  Update Refs (G)         154 us");
        pausesShenandoah = Analysis.yieldNoOfPauses(testStrings, GCType.SHENANDOAH);
        minorPausesShenandoah = pausesShenandoah[1];
        assertNotEquals(9, minorPausesShenandoah);
        assertEquals(8, minorPausesShenandoah);
    }

    @Test
    public void testYieldGCTimeFromSource() {
        double timeSerial = Analysis.yieldGCTimeFromSource(testStrings, GCType.SERIAL);
        assertEquals(0.84, timeSerial, EPSILON);

        double timeParallel = Analysis.yieldGCTimeFromSource(testStrings, GCType.PARALLEL);
        assertEquals(0.84, timeParallel, EPSILON);

        testStrings.add("[1.496s][info][gc,cpu         ] GC(5) User=0.34s Sys=0.00s Real=0.09s");

        timeSerial = Analysis.yieldGCTimeFromSource(testStrings, GCType.SERIAL);
        assertNotEquals(0.84, timeSerial, EPSILON);

        timeParallel = Analysis.yieldGCTimeFromSource(testStrings, GCType.PARALLEL);
        assertNotEquals(0.84, timeParallel, EPSILON);

        testStrings.add("[2.490s][info][gc          ] GC(32) Concurrent Cycle 213.064ms");

        double timeG1 = Analysis.yieldGCTimeFromSource(testStrings, GCType.G1);
        assertEquals(0.86, timeG1, EPSILON);

        double timeZGC = Analysis.yieldGCTimeFromSource(testStrings, GCType.ZGC);
        assertEquals(0.04, timeZGC, EPSILON);

        Collections.addAll(testStrings,"[0.217s][info][gc,phases   ] GC(0) Concurrent Process Non-Strong References 0.880ms",
                "[0.217s][info][gc,phases   ] GC(0) Concurrent Reset Relocation Set 0.003ms",
                "[0.227s][info][gc,phases   ] GC(0) Concurrent Select Relocation Set 18.399ms",
                "[0.228s][info][gc,phases   ] GC(0) Pause Relocate Start 0.109ms",
                "[0.228s][info][gc,phases   ] GC(0) Concurrent Relocate 0.013ms",
                "[0.355s][info][gc          ] GC(0) Concurrent reset 0.535ms",
                "[0.460s][info][gc          ] GC(0) Concurrent update references 33.667ms");

        timeZGC = Analysis.yieldGCTimeFromSource(testStrings, GCType.ZGC);
        assertNotEquals(0.04, timeZGC, EPSILON);

        double timeShenandoah = Analysis.yieldGCTimeFromSource(testStrings, GCType.SHENANDOAH);
        assertEquals(0.89, timeShenandoah, EPSILON);
    }

    @Test
    public void testYieldSTWTimeFromSource() {
        double timeZGC = Analysis.yieldSTWTimeFromSource(testStrings, GCType.ZGC);
        assertEquals(0, timeZGC, EPSILON);

        double timeShenandoah = Analysis.yieldSTWTimeFromSource(testStrings, GCType.SHENANDOAH);
        assertEquals(0.65, timeShenandoah, EPSILON);

        Collections.addAll(testStrings,"[0.217s][info][gc,phases   ] GC(0) Concurrent Process Non-Strong References 0.880ms",
                "[0.217s][info][gc,phases   ] GC(0) Concurrent Reset Relocation Set 0.003ms",
                "[0.227s][info][gc,phases   ] GC(0) Concurrent Select Relocation Set 18.399ms",
                "[0.228s][info][gc,phases   ] GC(0) Pause Relocate Start 0.109ms",
                "[0.228s][info][gc,phases   ] GC(0) Concurrent Relocate 0.013ms",
                "[0.355s][info][gc          ] GC(0) Concurrent reset 0.535ms",
                "[0.460s][info][gc          ] GC(0) Concurrent update references 33.667ms");

        timeShenandoah = Analysis.yieldSTWTimeFromSource(testStrings, GCType.SHENANDOAH);
        assertNotEquals(0.68, timeShenandoah, EPSILON);
        assertEquals(0.65, timeShenandoah, EPSILON);

        testStrings.add("[0.56s][info][gc,phases   ] GC(0) Pause Relocate Start 14.109ms");

        timeZGC = Analysis.yieldSTWTimeFromSource(testStrings, GCType.ZGC);
        assertNotEquals(0, timeZGC, EPSILON);
        assertEquals(0.01, timeZGC, EPSILON);
    }

    @Test
    public void testCalculateHeapSize() {
        assertEquals(200, Analysis.calculateHeapSize(200,500, 100, 200,
                0, 0)[0]);
        assertEquals(500, Analysis.calculateHeapSize(200,500, 100, 200,
                0, 0)[1]);
        assertEquals(700, Analysis.calculateHeapSize(200,500, 100, 200,
                1, 0)[1]);
        assertEquals(700, Analysis.calculateHeapSize(200,500, 100, 200,
                5, 4)[1]);
        assertNotEquals(2049, Analysis.calculateHeapSize(2049,500, 100, 200,
                0, 0)[0]);
        assertNotEquals(8193, Analysis.calculateHeapSize(150,8193, 100, 200,
                0, 0)[1]);
    }

    @Test
    public void testCalculateThroughput() {
        assertEquals(100.0, Analysis.calculateThroughput(7.71, 0.0001), EPSILON);
        assertEquals(22.5, Analysis.calculateThroughput(4.71, 3.65), EPSILON);
        assertEquals(0.01, Analysis.calculateThroughput(45.71, 45.7099), EPSILON);
    }
}
