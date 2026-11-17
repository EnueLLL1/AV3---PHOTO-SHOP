package estudio.net;

import estudio.model.JobRequest;
import estudio.model.JobStatus;
import estudio.model.JobTicket;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface CoordinatorRmiService extends Remote {
    String BINDING_NAME = "EstudioImagensCoordinator";

    JobTicket submitJob(JobRequest request) throws RemoteException;

    JobStatus getJobStatus(String jobId) throws RemoteException;

    String ping() throws RemoteException;
}
