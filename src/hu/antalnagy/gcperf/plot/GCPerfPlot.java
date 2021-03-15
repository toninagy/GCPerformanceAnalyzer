package hu.antalnagy.gcperf.plot;

import com.github.sh0nk.matplotlib4j.Plot;
import com.github.sh0nk.matplotlib4j.PythonExecutionException;
import com.github.sh0nk.matplotlib4j.builder.HistBuilder;
import hu.antalnagy.gcperf.GCType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GCPerfPlot {

    private final Plot plot = Plot.create();
    private List<Double> measurements;
    private GCType gcType;

    private static final Logger LOGGER = Logger.getLogger(GCPerfPlot.class.getSimpleName());

    public GCPerfPlot(GCType gcType, List<Double> measurements) {
        this.gcType = gcType;
        this.measurements = new ArrayList<>(measurements);
    }

    public List<Double> getMeasurements() {
        return new ArrayList<>(measurements);
    }

    public GCType getGcType() {
        return gcType;
    }

    public void setMeasurements(List<Double> measurements) {
        this.measurements = new ArrayList<>(measurements);
    }

    public void setGcType(GCType gcType) {
        this.gcType = gcType;
    }

    public void plotMeasurements() throws IOException, PythonExecutionException {
        final var xlimMin = measurements.stream().min(Double::compare);
        final var xlimMax = measurements.stream().max(Double::compare);
        if(xlimMin.isEmpty()) {
            LOGGER.log(Level.SEVERE, "xLim Minimum value or Maximum value can not be determined");
            throw new IllegalArgumentException("Input values array is empty");
        }
        List<Double> bin = new ArrayList<>(measurements).stream().sorted().collect(Collectors.toList());
        final double max = xlimMax.get();
        final double min = xlimMin.get();
        bin.add(max + max / 10);
        plot.hist().add(measurements).rwidth(0.025).bins(bin).stacked(false).align(HistBuilder.Align.left).orientation(HistBuilder.Orientation.vertical);
        plot.xlim(min - min/10, max + max/10);
        plot.title(gcType.name());
        plot.show();
        plot.close();
    }
}
