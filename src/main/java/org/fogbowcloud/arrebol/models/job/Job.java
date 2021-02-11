/* (C)2020 */
package org.fogbowcloud.arrebol.models.job;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.*;
import javax.persistence.*;
import org.fogbowcloud.arrebol.models.task.Task;

@Entity
public class Job implements Serializable {

  private static final long serialVersionUID = -6111900503095749695L;

  @Id private String id;
  private String label;

  @Enumerated(EnumType.STRING)
  @JsonProperty("job_state")
  private JobState jobState;

  @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "job_id")
  private Collection<Task> tasks;

  public Job(String label, Collection<Task> tasks) {
    this.id = UUID.randomUUID().toString();
    this.jobState = JobState.SUBMITTED;
    this.label = label;

    this.tasks = tasks;
  }

  Job() {
    // Default constructor
  }

  public String getId() {
    return this.id;
  }

  public String getLabel() {
    return this.label;
  }

  public JobState getJobState() {
    return this.jobState;
  }

  public void setJobState(JobState jobState) {
    this.jobState = jobState;
  }

  public Collection<Task> getTasks() {
    return this.tasks;
  }
}
