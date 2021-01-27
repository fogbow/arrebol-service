package org.fogbowcloud.arrebol.resource;

import java.util.Map;
import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToOne;
import javax.persistence.Transient;
import org.fogbowcloud.arrebol.execution.TaskExecutionResult;
import org.fogbowcloud.arrebol.execution.TaskExecutor;
import org.fogbowcloud.arrebol.execution.Worker;
import org.fogbowcloud.arrebol.execution.docker.DockerTaskExecutor;
import org.fogbowcloud.arrebol.execution.k8s.K8sTaskExecutor;
import org.fogbowcloud.arrebol.models.specification.Specification;
import org.fogbowcloud.arrebol.models.task.Task;

/**
 * This @{link Worker} implementation matches any @{link Specification}.
 * It delegates @{link TaskExecutor} behaviour to received object.
 */
@Entity
public class MatchAnyWorker implements Worker {

    //simple resource that accepts any request

    @Id
    private String id;
    @Transient
    private ResourceState state;
    @Transient
    private Specification spec;
    @Transient
    private int poolId;
    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true, targetEntity = K8sTaskExecutor.class)
    private TaskExecutor executor;

    public MatchAnyWorker(String id, Specification spec, int poolId, TaskExecutor delegatedExecutor) {
        this.id = id;
        this.spec = spec;
        this.poolId = poolId;
        this.state = ResourceState.IDLE;
        this.executor = delegatedExecutor;
    }

    public MatchAnyWorker() {
    }

    @Override
    public boolean match(Map<String, String> requirements) {
        return true;
    }

    @Override
    public ResourceState getState() {
        return this.state;
    }

    @Override
    public void setState(ResourceState state) {
        this.state = state;
    }

    @Override
    public Specification getSpecification() {
        return this.spec;
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public int getPoolId() {
        return this.poolId;
    }

    @Override
    public Map<String, String> getMetadata() {
        return this.executor.getMetadata();
    }

    @Override
    public String toString() {
        return "id={" + this.id + "} poolId={" + poolId + "} " +
                "executor={" + this.executor + "}";
    }

    @Override
    public TaskExecutionResult execute(Task task) {
        return this.executor.execute(task);
    }
}
