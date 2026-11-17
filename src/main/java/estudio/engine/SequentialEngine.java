package estudio.engine;

import estudio.core.ConvolutionOps;
import estudio.model.FilterType;

import java.nio.file.Path;
import java.util.List;

public class SequentialEngine implements ImageEngine {

    @Override
    public EngineResult process(
            List<Path> inputFiles,
            Path outputDir,
            FilterType filter,
            ProgressListener listener) throws Exception {
        String details = "Sequential (1 worker), filter=" + filter;
        return BatchEngineSupport.runBatch(
                "SEQUENTIAL",
                1,
                details,
                inputFiles,
                outputDir,
                filter,
                listener,
                ConvolutionOps::applyFull);
    }
}
