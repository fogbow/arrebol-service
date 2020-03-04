package org.fogbowcloud.arrebol.scheduler;

import org.apache.log4j.Logger;
import org.fogbowcloud.arrebol.execution.ExecutionBroker;
import org.fogbowcloud.arrebol.execution.Worker;
import org.fogbowcloud.arrebol.models.task.Task;
import org.fogbowcloud.arrebol.models.task.TaskState;
import org.fogbowcloud.arrebol.processor.TaskQueue;
import org.fogbowcloud.arrebol.resource.WorkerPool;
import org.fogbowcloud.arrebol.resource.ResourceState;

import java.util.Collection;

public class DefaultScheduler implements Runnable {
    //TODO: to pick a better name (maybe silly-scheduler? :))

    private final Logger LOGGER = Logger.getLogger(DefaultScheduler.class);

    //TODO: to be loaded from conf
    private static final int SCHEDULER_PERIOD_MILLIS = 5000;

    private final TaskQueue queue;
    private final WorkerPool pool;
    private final SchedulerPolicy policy;
    private final ExecutionBroker executionBroker;

    public DefaultScheduler(TaskQueue queue, WorkerPool pool, SchedulerPolicy policy) {

        //this implementation is super-naive: we create a new allocation plan whenever
        //a notification is received (notifyAddedJob or notifyAvailableResource). Failures are just ignored.
        //There are plenty of possible improvements: e.g waiting a batch of notifications before deciding ...
        //to improve utilization, we also add the notification to a queues that is consume by a planner thread
        //we might also want to "offer" resources based on queue priority

        this.queue = queue;
        this.pool = pool;
        this.policy = policy;
        this.executionBroker = new ExecutionBroker();
    }

    @Override
    public void run() {

        while (true) {
            try {
                Collection<AllocationPlan> plan = this.policy.schedule(this.queue, this.pool);
                executePlan(plan);
                Thread.sleep(SCHEDULER_PERIOD_MILLIS);
            } catch (Throwable e) {
                LOGGER.error("Scheduler execution aborted", e);
                System.exit(1);
            }
        }
    }

    //below method was an independent abstraction some commits ago. Maybe, we can make it again
    private void executePlan(Collection<AllocationPlan> plans) {

        for (AllocationPlan plan : plans) {

            switch (plan.getType()) {
                case RUN: {

                    Task task = plan.getTask();
                    task.setState(TaskState.RUNNING);
                    this.queue.removeTask(task);

                    Worker worker = plan.getWorker();
                    worker.setState(ResourceState.ALLOCATED);

                    executionBroker.execute(plan.getTask(), worker);
                    break;
                }
                case STOP: {
                    break;
                }
                default: {

                }
            }
        }
    }
}