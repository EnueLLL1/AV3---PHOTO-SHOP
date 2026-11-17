package estudio.model;

import java.io.Serializable;
import java.util.Objects;

public class JobRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());
    public static final int DEFAULT_TILE_SIZE = 64;
    public static final int DEFAULT_MPI_PROCESSES = 2;

    private final FilterType filter;
    private final ExecutionMode mode;
    private final int threads;
    private final int tileSize;
    private final int mpiProcesses;

    public JobRequest(FilterType filter, ExecutionMode mode, int threads, int tileSize, int mpiProcesses) {
        this.filter = Objects.requireNonNull(filter, "filter must not be null");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.threads = threads <= 0 ? DEFAULT_THREADS : threads;
        this.tileSize = tileSize <= 0 ? DEFAULT_TILE_SIZE : tileSize;
        this.mpiProcesses = mpiProcesses <= 0 ? DEFAULT_MPI_PROCESSES : mpiProcesses;
    }

    public FilterType getFilter() {
        return filter;
    }

    public ExecutionMode getMode() {
        return mode;
    }

    public int getThreads() {
        return threads;
    }

    public int getTileSize() {
        return tileSize;
    }

    public int getMpiProcesses() {
        return mpiProcesses;
    }

    @Override
    public String toString() {
        return "JobRequest{"
                + "filter=" + filter
                + ", mode=" + mode
                + ", threads=" + threads
                + ", tileSize=" + tileSize
                + ", mpiProcesses=" + mpiProcesses
                + '}';
    }
}
