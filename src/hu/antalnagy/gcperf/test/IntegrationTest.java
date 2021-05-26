package hu.antalnagy.gcperf.test;

import com.github.sh0nk.matplotlib4j.PythonExecutionException;
import hu.antalnagy.gcperf.Analysis;
import hu.antalnagy.gcperf.GCType;
import hu.antalnagy.gcperf.driver.GCPerfDriver;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class IntegrationTest {

    private static final GCPerfDriver gcPerfDriver = new GCPerfDriver();

    private void analysisIntegrationTest(Analysis analysis) {
        assertNotNull(analysis.getMainClass());
        assertNotNull(analysis.getGcTypes());
        assertNotNull(analysis.getMetrics());
        assertNotNull(analysis.getProgress());
        assertNotNull(analysis.getLeaderboard());
        assertFalse(analysis.getLeaderboard().isEmpty());
        assertTrue(analysis.getProgress().isDone());
    }

    @Test
    public void gcPerfDriverIntegrationTest() throws PythonExecutionException, IOException, InterruptedException {
        assertNull(gcPerfDriver.getAnalysis());
        assertNull(gcPerfDriver.getDbDriver());
        assertNull(gcPerfDriver.getGcPerfPlot());
        assertThrows(NullPointerException.class, gcPerfDriver::getLeaderboard); //because analysis is null
        assertThrows(NullPointerException.class, gcPerfDriver::getProgress); //because analysis is null
        assertThrows(NullPointerException.class, gcPerfDriver::getResultMetrics); //because analysis is null

        gcPerfDriver.launch(new File("App.class"), 1, 500, 600, 1, 1,
                List.of(GCType.SERIAL, GCType.PARALLEL, GCType.G1, GCType.ZGC, GCType.SHENANDOAH), new Analysis.Metrics[]{Analysis.Metrics.BestGCRuntime, Analysis.Metrics.AvgGCRuntime, Analysis.Metrics.Throughput,
                        Analysis.Metrics.Latency, Analysis.Metrics.MinorPauses, Analysis.Metrics.FullPauses}, false, true);

        assertNotNull(gcPerfDriver.getAnalysis());
        analysisIntegrationTest(gcPerfDriver.getAnalysis());

        assertNotNull(gcPerfDriver.getDbDriver());
        gcPerfDriver.getDbDriver().createConnectionAndStatement();
        assertFalse(gcPerfDriver.getDbDriver().queryRows().isEmpty());
        gcPerfDriver.getDbDriver().close();

        assertNotNull(gcPerfDriver.getLeaderboard());
        assertNotNull(gcPerfDriver.getProgress());
        assertTrue(gcPerfDriver.getProgress().isDone());
        assertNotNull(gcPerfDriver.getResultMetrics());
        assertFalse(gcPerfDriver.getResultMetrics().isEmpty());

        assertNotNull(gcPerfDriver.getGcPerfPlot());
    }
}
