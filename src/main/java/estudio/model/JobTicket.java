package estudio.model;

import java.io.Serializable;
import java.util.Objects;

public class JobTicket implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String socketHost;
    private final int socketPort;
    private final int queuePosition;

    public JobTicket(String jobId, String socketHost, int socketPort, int queuePosition) {
        if (Objects.requireNonNull(jobId, "jobId must not be null").isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        if (Objects.requireNonNull(socketHost, "socketHost must not be null").isBlank()) {
            throw new IllegalArgumentException("socketHost must not be blank");
        }
        if (socketPort <= 0 || socketPort > 65535) {
            throw new IllegalArgumentException("socketPort must be in [1, 65535]: " + socketPort);
        }
        if (queuePosition < 0) {
            throw new IllegalArgumentException("queuePosition must be >= 0: " + queuePosition);
        }
        this.jobId = jobId;
        this.socketHost = socketHost;
        this.socketPort = socketPort;
        this.queuePosition = queuePosition;
    }

    public String getJobId() {
        return jobId;
    }

    public String getSocketHost() {
        return socketHost;
    }

    public int getSocketPort() {
        return socketPort;
    }

    public int getQueuePosition() {
        return queuePosition;
    }

    @Override
    public String toString() {
        return "JobTicket{"
                + "jobId='" + jobId + '\''
                + ", socketHost='" + socketHost + '\''
                + ", socketPort=" + socketPort
                + ", queuePosition=" + queuePosition
                + '}';
    }
}
