package estudio.model;

import java.io.Serializable;
import java.util.Objects;

public class JobStatus implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum State {
        AWAITING_UPLOAD,
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private final String jobId;
    private final State state;
    private final int completedSteps;
    private final int totalSteps;
    private final String message;

    public JobStatus(String jobId, State state, int completedSteps, int totalSteps, String message) {
        if (Objects.requireNonNull(jobId, "jobId must not be null").isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        this.jobId = jobId;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.completedSteps = Math.max(0, completedSteps);
        this.totalSteps = Math.max(0, totalSteps);
        this.message = message == null ? "" : message;
    }

    public String getJobId() {
        return jobId;
    }

    public State getState() {
        return state;
    }

    public int getCompletedSteps() {
        return completedSteps;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "JobStatus{"
                + "jobId='" + jobId + '\''
                + ", state=" + state
                + ", completedSteps=" + completedSteps
                + ", totalSteps=" + totalSteps
                + ", message='" + message + '\''
                + '}';
    }
}
