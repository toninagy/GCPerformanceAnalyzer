package hu.antalnagy.gcperf.test;

import hu.antalnagy.gcperf.Analysis;
import hu.antalnagy.gcperf.GCType;
import hu.antalnagy.gcperf.plot.GCPerfPlot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class AnalysisTest {

    private GCPerfPlot gcPerfPlot;
    private Analysis analysis;

    @Before
    public void setUp() {
        gcPerfPlot = GCPerfPlot.getInstance();
        analysis = new Analysis("dummyClass", List.of(GCType.G1, GCType.SHENANDOAH),
                new Analysis.Metrics[]{Analysis.Metrics.BestGCRuntime, Analysis.Metrics.AvgGCRuntime});
    }

    @After
    public void tearDown() {

    }

    @Test
    public void testLeaderboard() {
        analysis.setLeaderboard(Analysis.Metrics.BestGCRuntime, Analysis.Metrics.AvgGCRuntime);
    }
}
