package estudio.net;

import estudio.model.JobRequest;
import estudio.model.JobStatus;
import estudio.model.JobTicket;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Objects;

public class CoordinatorRmiImpl extends UnicastRemoteObject implements CoordinatorRmiService {
    private static final long serialVersionUID = 1L;

    private final transient JobCoordinator coordinator;

    public CoordinatorRmiImpl(JobCoordinator coordinator) throws RemoteException {
        super();
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
    }

    @Override
    public JobTicket submitJob(JobRequest request) throws RemoteException {
        try {
            return coordinator.submitJob(request);
        } catch (RuntimeException e) {
            throw new RemoteException("Failed to submit job: " + e.getMessage(), e);
        }
    }

    @Override
    public JobStatus getJobStatus(String jobId) throws RemoteException {
        try {
            return coordinator.getJobStatus(jobId);
        } catch (RuntimeException e) {
            throw new RemoteException("Failed to query job status: " + e.getMessage(), e);
        }
    }

    @Override
    public String ping() throws RemoteException {
        return coordinator.ping();
    }
}
