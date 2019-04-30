package org.fogbowcloud.arrebol;

import org.fogbowcloud.arrebol.execution.RawTaskExecutor;
import org.fogbowcloud.arrebol.execution.TaskExecutor;
import org.fogbowcloud.arrebol.execution.Worker;
import org.fogbowcloud.arrebol.models.command.Command;
import org.fogbowcloud.arrebol.models.job.Job;
import org.fogbowcloud.arrebol.models.specification.Specification;
import org.fogbowcloud.arrebol.models.task.Task;
import org.fogbowcloud.arrebol.models.task.TaskSpec;
import org.fogbowcloud.arrebol.models.task.TaskState;
import org.fogbowcloud.arrebol.resource.MatchAnyWorker;
import org.fogbowcloud.arrebol.queue.TaskQueue;
import org.fogbowcloud.arrebol.resource.StaticPool;
import org.fogbowcloud.arrebol.resource.WorkerPool;
import org.fogbowcloud.arrebol.scheduler.DefaultScheduler;
import org.fogbowcloud.arrebol.scheduler.FifoSchedulerPolicy;
import org.junit.Assert;
import org.junit.Test;
import org.apache.log4j.Logger;
import java.util.*;

public class RawWorkerIntegrationTest {

    private final Logger LOGGER = Logger.getLogger(RawWorkerIntegrationTest.class);

    @Test
    public void addTaskWhenNoResources() throws InterruptedException {

        // setup

        // create the queue
        String queueId = UUID.randomUUID().toString();
        String queueName = "defaultQueue";
        TaskQueue queue = new TaskQueue(queueId, queueName);

        // create the pool
        // FIXME: we are missing something related to worker/resource func
        int poolId = 1;
        Collection<Worker> workers = createPool(5, poolId);
        WorkerPool pool = new StaticPool(poolId, workers);

        // create the scheduler
        // bind the pieces together
        FifoSchedulerPolicy policy = new FifoSchedulerPolicy();
        DefaultScheduler scheduler = new DefaultScheduler(queue, pool, policy);

        // submit job1 (one task, /usr/bin/true), job2 (two tasks /usr/bin/true), job3 (three tasks
        // /usr/bin/true)
        // job1
        Job job1 = createJob("job1", new String[] {"true"});
        Job job2 = createJob("job2", new String[] {"true", "true"});
        Job job3 = createJob("job3", new String[] {"true", "true", "true"});

        Collection<Job> jobs = new LinkedList<Job>();
        jobs.add(job1);
        jobs.add(job2);
        jobs.add(job3);

        for (Job job : jobs) {
            for (Task task : job.getTasks().values()) {
                queue.addTask(task);
            }
        }

        // exercise
        new Thread(scheduler, "scheduler-thread").start();

        // verify
        // busy wait, all jobs should finished, eventually
        // assert the exitValues
        while (!allFinished(jobs)) {
            LOGGER.info("waiting queue to become empty");
            Thread.sleep(10000);
        }

        Assert.assertTrue(queue.queue().isEmpty());
    }

    private boolean allFinished(Collection<Job> jobs) {
        boolean allFinished = true;
        for (Job job : jobs) {
            if (!finished(job)) {
                allFinished = false;
                break;
            }
        }
        return allFinished;
    }

    private boolean finished(Job job) {
        boolean allFinished = true;
        for (Task task : job.getTasks().values()) {
            if (!task.getState().equals(TaskState.FINISHED)) {
                allFinished = false;
            }
        }
        return allFinished;
    }

    private Job createJob(String jobId, String[] cmdsStr) {

        Collection<Task> tasks = new LinkedList<>();
        for (String cmd : cmdsStr) {
            Task task = createTask(cmd);
            tasks.add(task);
        }

        Job job = new Job(jobId, tasks);
        return job;
    }

    private int idCount;

    private Task createTask(String cmd) {


        List<Command> cmds = new LinkedList<Command>();
        cmds.add(new Command((cmd)));

        String taskId = "taskId-" + idCount++;
        Task task = new Task(taskId, new TaskSpec(taskId, new Specification("fake-image",
                "fake-user", "fake-key", "fake-path", new HashMap<>()), cmds, new HashMap<>()));

        return task;
    }

    private Collection<Worker> createPool(int size, int poolId) {
        Collection<Worker> workers = new LinkedList<Worker>();
        int poolSize = 5;
        Specification resourceSpec = null;
        for (int i = 0; i < poolSize; i++) {
            TaskExecutor taskExecutor = new RawTaskExecutor();
            workers.add(new MatchAnyWorker(resourceSpec, "resourceId-" + i, poolId, taskExecutor));
        }
        return workers;
    }

    private class DummySpec extends Specification {

        public DummySpec() {
            this(null, null, null, null, null);
        }

        public DummySpec(String image, String username, String publicKey, String privateKeyFilePath,
                Map<String, String> requirements) {
            super(image, username, publicKey, privateKeyFilePath, requirements);
        }

        public DummySpec(String image, String username, String publicKey, String privateKeyFilePath,
                Map<String, String> requirements, String cloudName) {
            super(image, username, publicKey, privateKeyFilePath, requirements, cloudName);
        }
    }
}
