/* (C)2020 */
package org.fogbowcloud.arrebol;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.log4j.Logger;
import org.fogbowcloud.arrebol.datastore.managers.QueueDBManager;
import org.fogbowcloud.arrebol.execution.Worker;
import org.fogbowcloud.arrebol.execution.WorkerTypes;
import org.fogbowcloud.arrebol.execution.creator.DockerWorkerCreator;
import org.fogbowcloud.arrebol.execution.creator.K8sWorkerCreator;
import org.fogbowcloud.arrebol.execution.creator.RawWorkerCreator;
import org.fogbowcloud.arrebol.execution.creator.WorkerCreator;
import org.fogbowcloud.arrebol.models.command.CommandState;
import org.fogbowcloud.arrebol.models.configuration.Configuration;
import org.fogbowcloud.arrebol.models.job.Job;
import org.fogbowcloud.arrebol.models.job.JobState;
import org.fogbowcloud.arrebol.models.task.TaskState;
import org.fogbowcloud.arrebol.processor.DefaultJobProcessor;
import org.fogbowcloud.arrebol.processor.JobProcessor;
import org.fogbowcloud.arrebol.processor.TaskQueue;
import org.fogbowcloud.arrebol.processor.dto.DefaultJobProcessorDTO;
import org.fogbowcloud.arrebol.processor.manager.JobProcessorManager;
import org.fogbowcloud.arrebol.processor.spec.JobProcessorSpec;
import org.fogbowcloud.arrebol.processor.spec.WorkerNode;
import org.fogbowcloud.arrebol.resource.StaticPool;
import org.fogbowcloud.arrebol.resource.WorkerPool;
import org.fogbowcloud.arrebol.scheduler.DefaultScheduler;
import org.fogbowcloud.arrebol.scheduler.FifoSchedulerPolicy;
import org.fogbowcloud.arrebol.utils.ConfValidator;
import org.springframework.stereotype.Component;

@Component
public class ArrebolController {

  private static final int FAIL_EXIT_CODE = 1;
  private static final String defaultQueueId = "default";
  private static final String defaultQueueName = "Default Queue";
  private final Logger LOGGER = Logger.getLogger(ArrebolController.class);
  private final JobProcessorManager jobProcessorManager;
  private WorkerCreator workerCreator;
  private Integer defaultPoolId;

  public ArrebolController() {
    defaultPoolId = 1;
    try {
      Configuration configuration = loadConfigurationFile();
      ConfValidator.validate(configuration);
      buildWorkerCreator(configuration);
    } catch (Throwable e) {
      LOGGER.error(e.getMessage(), e);
      System.exit(FAIL_EXIT_CODE);
    }

    Map<String, JobProcessor> queues = new ConcurrentHashMap<>();
    this.jobProcessorManager = new JobProcessorManager(queues);
  }

  public void start() {
    DefaultJobProcessor jp = QueueDBManager.getInstance().findOne(defaultQueueId);
    if (Objects.isNull(jp)) {
      jp = (DefaultJobProcessor) createDefaultJobProcessor();
    } else {
      TaskQueue tq = new TaskQueue(defaultQueueId, defaultQueueName);
      FifoSchedulerPolicy policy = new FifoSchedulerPolicy();
      WorkerPool pool = createPool(defaultPoolId);
      DefaultScheduler scheduler = new DefaultScheduler(tq, pool, policy);
      jp.setDefaultScheduler(scheduler);
      jp.setTaskQueue(tq);
      resetJobs(jp);
    }
    this.jobProcessorManager.addJobProcessor(jp);
    this.jobProcessorManager.startJobProcessor(defaultQueueId);

    // commit the job pool to DB using a COMMIT_PERIOD_MILLIS PERIOD between successive commits
    // (I also specified the delay to the start the fist commit to be COMMIT_PERIOD_MILLIS)

    // TODO: read from bd
  }

  private void resetJobs(DefaultJobProcessor jp) {
    jp.getJobs().entrySet().stream()
        .filter(x -> hasUnfinishedTask(x.getValue()))
        .forEach(
            x -> {
              // RESET JOB STATE
              resetJob(x.getValue());
              // ADD TASKS TO QUEUE
              x.getValue().getTasks().forEach(t -> jp.getTaskQueue().addTask(t));
            });
  }

  private boolean hasUnfinishedTask(Job job) {
    long unfinished =
        job.getTasks().stream()
            .filter(x -> x.getState() != TaskState.FINISHED && x.getState() != TaskState.FAILED)
            .count();
    return unfinished > 0;
  }

  private void resetJob(Job job) {
    job.getTasks()
        .forEach(
            t ->
                t.getTaskSpec()
                    .getCommands()
                    .forEach(
                        c -> {
                          c.setState(CommandState.UNSTARTED);
                          c.setExitcode(Integer.MAX_VALUE);
                        }));
    job.getTasks().forEach(t -> t.setState(TaskState.PENDING));
    job.setJobState(JobState.QUEUED);
  }

  private JobProcessor createDefaultJobProcessor() {
    TaskQueue tq = new TaskQueue(defaultQueueId, defaultQueueName);

    WorkerPool pool = createPool(defaultPoolId);

    // create the scheduler bind the pieces together
    FifoSchedulerPolicy policy = new FifoSchedulerPolicy();
    DefaultScheduler scheduler = new DefaultScheduler(tq, pool, policy);
    return new DefaultJobProcessor(defaultQueueId, defaultQueueName, tq, scheduler, pool);
  }

  private Configuration loadConfigurationFile() {
    Configuration configuration = null;
    Reader targetReader;
    String confFilePath = System.getProperty(ArrebolApplication.CONF_FILE_PROPERTY);
    try {
      if (Objects.isNull(confFilePath)) {
        confFilePath = "arrebol.json";
        targetReader =
            new InputStreamReader(
                Objects.requireNonNull(
                    ArrebolApplication.class.getClassLoader().getResourceAsStream(confFilePath)));
      } else {
        InputStream fileInputStream = new FileInputStream(confFilePath);
        targetReader = new InputStreamReader(fileInputStream);
      }
      BufferedReader bufferedReader = new BufferedReader(targetReader);
      Gson gson = new Gson();
      configuration = gson.fromJson(bufferedReader, Configuration.class);
    } catch (Exception e) {
      System.exit(1);
    }
    return configuration;
  }

  private WorkerPool createPool(int poolId) {

    // we need to deal with missing/wrong properties

    Collection<Worker> workers =
        Collections.synchronizedList(new LinkedList<>(workerCreator.createWorkers(poolId)));

    WorkerPool pool = new StaticPool(poolId, workers);
    LOGGER.info("pool={" + pool + "} created with workers={" + workers + "}");

    return pool;
  }

  public void stop() {
    // TODO: delete all resources?
  }

  String addJob(String queue, Job job) {
    job.setJobState(JobState.QUEUED);
    this.jobProcessorManager.addJob(queue, job);

    return job.getId();
  }

  void stopJob(Job job) {
    //// still unsupported
  }

  Job getJob(String queueId, String jobId) {
    return this.jobProcessorManager.getJob(queueId, jobId);
  }

  TaskState getTaskState(String taskId) {
    // FIXME:
    return null;
  }

  private void buildWorkerCreator(Configuration configuration) throws Exception {
    String poolType = configuration.getPoolType();
    if (poolType.equals(WorkerTypes.DOCKER.getType())) {
      this.workerCreator = new DockerWorkerCreator(configuration);
    } else if (poolType.equals(WorkerTypes.RAW.getType())) {
      this.workerCreator = new RawWorkerCreator(configuration);
    } else if (poolType.equals(WorkerTypes.K8S.getType())) {
      this.workerCreator = new K8sWorkerCreator(configuration);
    } else {
      String poolTypeMsg =
          "Worker Pool Type configuration property wrong or missing. Please, verify your configuration file.";
      throw new IllegalArgumentException(poolTypeMsg);
    }
  }

  String createQueue(JobProcessorSpec jobProcessorSpec) {
    JobProcessor jobProcessor = createQueueFromSpec(jobProcessorSpec);
    this.jobProcessorManager.addJobProcessor(jobProcessor);
    this.jobProcessorManager.startJobProcessor(jobProcessor.getId());
    return jobProcessor.getId();
  }

  private JobProcessor createQueueFromSpec(JobProcessorSpec jobProcessorSpec) {
    String queueId = UUID.randomUUID().toString();
    TaskQueue tq = new TaskQueue(queueId, jobProcessorSpec.getName());

    defaultPoolId++;
    WorkerPool pool = createPool(defaultPoolId, jobProcessorSpec.getWorkerNodes());

    // create the scheduler bind the pieces together
    FifoSchedulerPolicy policy = new FifoSchedulerPolicy();
    DefaultScheduler scheduler = new DefaultScheduler(tq, pool, policy);
    return new DefaultJobProcessor(queueId, jobProcessorSpec.getName(), tq, scheduler, pool);
  }

  private WorkerPool createPool(int poolId, List<WorkerNode> workerNodes) {
    Collection<Worker> workers = Collections.synchronizedList(new LinkedList<>());
    for (WorkerNode workerNode : workerNodes) {
      workers.addAll(workerCreator.createWorkers(poolId, workerNode));
    }

    WorkerPool pool = new StaticPool(poolId, workers);
    LOGGER.info("pool={" + pool + "} created with workers={" + workers + "}");

    return pool;
  }

  List<DefaultJobProcessorDTO> getQueues() {
    LOGGER.info("Getting all queues");
    return this.jobProcessorManager.getJobProcessors();
  }

  public void addWorkers(String queueId, WorkerNode workerNode) {
    // REVIEW POOL ID
    LOGGER.info("Adding WorkerNode [" + workerNode.getAddress() + "] to Queue [" + queueId + "]");
    Collection<Worker> workers = workerCreator.createWorkers(defaultPoolId, workerNode);
    this.jobProcessorManager.addWorkers(queueId, workers);
  }

  DefaultJobProcessorDTO getQueue(String queueId) {
    LOGGER.info("Getting queue [" + queueId + "]");
    return this.jobProcessorManager.getJobProcessor(queueId);
  }
}
