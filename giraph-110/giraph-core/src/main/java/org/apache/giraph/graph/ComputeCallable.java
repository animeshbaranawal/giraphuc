/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.giraph.graph;

import org.apache.giraph.bsp.CentralizedServiceWorker;
import org.apache.giraph.comm.WorkerClientRequestProcessor;
import org.apache.giraph.comm.messages.MessageStore;
import org.apache.giraph.comm.messages.with_source.MessageWithSourceStore;
import org.apache.giraph.comm.netty.NettyWorkerClientRequestProcessor;
import org.apache.giraph.conf.AsyncConfiguration;
import org.apache.giraph.conf.ImmutableClassesGiraphConfiguration;
import org.apache.giraph.io.SimpleVertexWriter;
import org.apache.giraph.metrics.GiraphMetrics;
import org.apache.giraph.metrics.MetricNames;
import org.apache.giraph.metrics.SuperstepMetricsRegistry;
import org.apache.giraph.partition.Partition;
import org.apache.giraph.partition.PartitionStats;
import org.apache.giraph.partition.PartitionPhilosophersTable;
import org.apache.giraph.partition.VertexPhilosophersTable;
import org.apache.giraph.time.SystemTime;
import org.apache.giraph.time.Time;
import org.apache.giraph.time.Times;
import org.apache.giraph.utils.EmptyIterable;
import org.apache.giraph.utils.MemoryUtils;
import org.apache.giraph.utils.TimedLogger;
import org.apache.giraph.utils.Trimmable;
import org.apache.giraph.worker.WorkerContext;
import org.apache.giraph.worker.WorkerProgress;
import org.apache.giraph.worker.WorkerThreadAggregatorUsage;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.log4j.Logger;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.yammer.metrics.core.Counter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;

/**
 * Compute as many vertex partitions as possible.  Every thread will has its
 * own instance of WorkerClientRequestProcessor to send requests.  Note that
 * the partition ids are used in the partitionIdQueue rather than the actual
 * partitions since that would cause the partitions to be loaded into memory
 * when using the out-of-core graph partition store.  We should only load on
 * demand.
 *
 * @param <I> Vertex index value
 * @param <V> Vertex value
 * @param <E> Edge value
 * @param <M1> Incoming message type
 * @param <M2> Outgoing message type
 */
public class ComputeCallable<I extends WritableComparable, V extends Writable,
    E extends Writable, M1 extends Writable, M2 extends Writable>
    implements Callable<Collection<PartitionStats>> {
  /** Class logger */
  private static final Logger LOG = Logger.getLogger(ComputeCallable.class);
  /** Class time object */
  private static final Time TIME = SystemTime.get();
  /** How often to update WorkerProgress */
  private static final long VERTICES_TO_UPDATE_PROGRESS = 100000;
  /** Context */
  private final Mapper<?, ?, ?, ?>.Context context;
  /** Graph state */
  private final GraphState graphState;
  /** Thread-safe queue of all partition ids */
  private final BlockingQueue<Integer> partitionIdQueue;
  /** Message store */
  private final MessageStore<I, M1> messageStore;
  /** YH: Local message store */
  private final MessageStore<I, M1> localMessageStore;
  /** Configuration */
  private final ImmutableClassesGiraphConfiguration<I, V, E> configuration;
  /** Worker (for NettyWorkerClientRequestProcessor) */
  private final CentralizedServiceWorker<I, V, E> serviceWorker;
  /** Dump some progress every 30 seconds */
  private final TimedLogger timedLogger = new TimedLogger(30 * 1000, LOG);
  /** VertexWriter for this ComputeCallable */
  private SimpleVertexWriter<I, V, E> vertexWriter;
  /** Get the start time in nanos */
  private final long startNanos = TIME.getNanoseconds();

  /** YH: Async configuration */
  private final AsyncConfiguration asyncConf;

  // Per-Superstep Metrics
  /** Messages sent */
  private final Counter messagesSentCounter;
  /** Message bytes sent */
  private final Counter messageBytesSentCounter;

  /**
   * Constructor
   *
   * @param context Context
   * @param graphState Current graph state (use to create own graph state)
   * @param messageStore Message store (remote-only, if using async)
   * @param localMessageStore Local-only message store (null if not async)
   * @param partitionIdQueue Queue of partition ids (thread-safe)
   * @param configuration Configuration
   * @param serviceWorker Service worker
   */
  public ComputeCallable(
      Mapper<?, ?, ?, ?>.Context context, GraphState graphState,
      MessageStore<I, M1> messageStore,
      MessageStore<I, M1> localMessageStore,
      BlockingQueue<Integer> partitionIdQueue,
      ImmutableClassesGiraphConfiguration<I, V, E> configuration,
      CentralizedServiceWorker<I, V, E> serviceWorker) {
    this.context = context;
    this.configuration = configuration;
    this.partitionIdQueue = partitionIdQueue;
    this.messageStore = messageStore;
    this.localMessageStore = localMessageStore;
    this.serviceWorker = serviceWorker;
    this.graphState = graphState;

    SuperstepMetricsRegistry metrics = GiraphMetrics.get().perSuperstep();
    messagesSentCounter = metrics.getCounter(MetricNames.MESSAGES_SENT);
    messageBytesSentCounter =
      metrics.getCounter(MetricNames.MESSAGE_BYTES_SENT);

    asyncConf = configuration.getAsyncConf();
  }

  @Override
  public Collection<PartitionStats> call() {
    // Thread initialization (for locality)
    WorkerClientRequestProcessor<I, V, E> workerClientRequestProcessor =
        new NettyWorkerClientRequestProcessor<I, V, E>(
            context, configuration, serviceWorker);
    WorkerThreadAggregatorUsage aggregatorUsage =
        serviceWorker.getAggregatorHandler().newThreadAggregatorUsage();
    WorkerContext workerContext = serviceWorker.getWorkerContext();

    vertexWriter = serviceWorker.getSuperstepOutput().getVertexWriter();

    PartitionPhilosophersTable pTable =
      serviceWorker.getPartitionPhilosophersTable();

    List<PartitionStats> partitionStatsList = Lists.newArrayList();
    while (!partitionIdQueue.isEmpty()) {
      Integer partitionId = partitionIdQueue.poll();
      if (partitionId == null) {
        break;
      }

      Partition<I, V, E> partition =
          serviceWorker.getPartitionStore().getOrCreatePartition(partitionId);

      // YH: For partition-based dist locking, acquire forks before
      // executing partition. Skip this if first superstep.
      if (asyncConf.partitionLockSerialized() &&
          serviceWorker.getLogicalSuperstep() > 0) {
        // skip partitions that don't need to be executed
        if (pTable.allVerticesHalted(partitionId) &&
            !hasMessages(partitionId.intValue())) {
          PartitionStats partitionStats =
            new PartitionStats(partitionId, partition.getVertexCount(),
                               partition.getVertexCount(),
                               partition.getEdgeCount(), 0, 0);
          partitionStatsList.add(partitionStats);

          serviceWorker.getPartitionStore().putPartition(partition);
          continue;
        } else {
          pTable.acquireForks(partitionId);
        }
      }

      Computation<I, V, E, M1, M2> computation =
          (Computation<I, V, E, M1, M2>) configuration.createComputation();
      computation.initialize(graphState, workerClientRequestProcessor,
          serviceWorker.getGraphTaskManager(), aggregatorUsage, workerContext);
      computation.preSuperstep();

      try {
        PartitionStats partitionStats =
            computePartition(computation, partition,
                             workerClientRequestProcessor);

        // YH: immediately release partition forks
        if (asyncConf.partitionLockSerialized() &&
            serviceWorker.getLogicalSuperstep() > 0) {
          // Flush all caches BEFORE releasing forks!
          // Releasing forks will flush msgs to network if needed.
          try {
            workerClientRequestProcessor.flush();
          } catch (IOException e) {
            throw new IllegalStateException("call: Flushing failed.", e);
          }
          pTable.releaseForks(partitionId);

          boolean allHalted =
            partitionStats.getFinishedVertexCount() ==
            partitionStats.getVertexCount();
          pTable.setAllVerticesHalted(partitionId, allHalted);
        }

        partitionStatsList.add(partitionStats);

        // YH: if barriers are disabled, number of messages is LOCAL
        // number of messages; otherwise it is total (local + remote)
        // TODO-YH: Note that this can make metrics incorrect!!
        long partitionMsgs = workerClientRequestProcessor.resetMessageCount();
        partitionStats.addMessagesSentCount(partitionMsgs);
        messagesSentCounter.inc(partitionMsgs);

        // YH: if async is enabled, messages bytes is always for
        // REMOTE messages; otherwise it is total (local + remote)
        long partitionMsgBytes =
          workerClientRequestProcessor.resetMessageBytesCount();
        partitionStats.addMessageBytesSentCount(partitionMsgBytes);
        messageBytesSentCounter.inc(partitionMsgBytes);

        // YH: recomputing partition or adding partition id back
        // on to queue CAN cause termination issues if partition stats
        // are not properly managed. In particularly, partition stats
        // are ultimately used as input to graph state of next iteration!

        timedLogger.info("call: Completed " +
            partitionStatsList.size() + " partitions, " +
            partitionIdQueue.size() + " remaining " +
            MemoryUtils.getRuntimeMemoryStats());
      } catch (IOException e) {
        throw new IllegalStateException("call: Caught unexpected IOException," +
            " failing.", e);
      } catch (InterruptedException e) {
        throw new IllegalStateException("call: Caught unexpected " +
            "InterruptedException, failing.", e);
      } finally {
        serviceWorker.getPartitionStore().putPartition(partition);
      }

      computation.postSuperstep();
    }

    // Return VertexWriter after the usage
    serviceWorker.getSuperstepOutput().returnVertexWriter(vertexWriter);

    if (LOG.isInfoEnabled()) {
      float seconds = Times.getNanosSince(TIME, startNanos) /
          Time.NS_PER_SECOND_AS_FLOAT;
      LOG.info("call: Computation took " + seconds + " secs for "  +
          partitionStatsList.size() + " partitions on superstep " +
          graphState.getSuperstep() + ".  Flushing started");
    }
    try {
      workerClientRequestProcessor.flush();
      // The messages flushed out from the cache is
      // from the last partition processed
      if (partitionStatsList.size() > 0) {
        long partitionMsgBytes =
          workerClientRequestProcessor.resetMessageBytesCount();
        partitionStatsList.get(partitionStatsList.size() - 1).
          addMessageBytesSentCount(partitionMsgBytes);
        messageBytesSentCounter.inc(partitionMsgBytes);
      }
      aggregatorUsage.finishThreadComputation();
    } catch (IOException e) {
      throw new IllegalStateException("call: Flushing failed.", e);
    }
    return partitionStatsList;
  }

  /**
   * Compute a single partition
   *
   * @param computation Computation to use
   * @param partition Partition to compute
   * @param workerClientRequestProcessor Client processor for this thread
   * @return Partition stats for this computed partition
   */
  private PartitionStats computePartition(
      Computation<I, V, E, M1, M2> computation,
      Partition<I, V, E> partition,
      WorkerClientRequestProcessor<I, V, E> workerClientRequestProcessor)
    throws IOException, InterruptedException {
    PartitionStats partitionStats =
        new PartitionStats(partition.getId(), 0, 0, 0, 0, 0);
    long verticesComputedProgress = 0;

    // Make sure this is thread-safe across runs
    synchronized (partition) {
      for (Vertex<I, V, E> vertex : partition) {
        I vertexId = vertex.getId();

        // YH: first superstep must allow ALL vertices to execute
        // as it can involve initialization that MUST be done
        // (i.e., backwards compatability with regular BSP algs)
        if (asyncConf.tokenSerialized() &&
            serviceWorker.getLogicalSuperstep() > 0) {
          // (see below for usage)
          boolean needWake = !asyncConf.needAllMsgs() &&
            vertex.isHalted() && hasMessages(vertexId);

          // internal vertices can always execute
          // boundary vertices need token to execute
          //
          // NOTE: token passing does not need to flush any caches,
          // b/c token exchange happens only after all compute threads
          // are killed (=> all caches are flushed).
          switch (serviceWorker.getVertexTypeStore().
                  getVertexType(vertexId)) {
          case INTERNAL:
            computeVertex(computation, partition, vertex,
                          removeLocalMessages(vertexId));
            break;
          case LOCAL_BOUNDARY:
            // local-only boundary only needs local token
            if (asyncConf.haveLocalToken(partition.getId())) {
              computeVertex(computation, partition, vertex,
                            removeLocalMessages(vertexId));
            } else if (needWake) {
              // Presence of local messages is NOT reported to master for
              // termination check, so we MUST wake up any skipped vertices
              // that have local messages to ensure active vertices != 0.
              //
              // Only needed for AP---BAP will not use barrier when there
              // are still local messages. Not needed when needAllMsgs, b/c
              // there will always be remote messages.
              vertex.wakeUp();
            }
            break;
          case REMOTE_BOUNDARY:
            // remote-only boundary only needs global token
            if (asyncConf.haveGlobalToken()) {
              computeVertex(computation, partition, vertex,
                            removeAllMessages(vertexId));
            } else if (needWake) {
              // Remote messages are reported to master (even after
              // being received), so this isn't actually an issue.
              // Leave it here just in case.
              vertex.wakeUp();
            }
            break;
          case MIXED_BOUNDARY:
            // local+remote boundary needs both tokens
            if (asyncConf.haveGlobalToken() &&
                asyncConf.haveLocalToken(partition.getId())) {
              computeVertex(computation, partition, vertex,
                            removeAllMessages(vertexId));
            } else if (needWake) {
              // Combination of above issues.
              vertex.wakeUp();
            }
            break;
          default:
            throw new RuntimeException("Invalid vertex type!");
          }

        } else if (asyncConf.vertexLockSerialized() &&
                   serviceWorker.getLogicalSuperstep() > 0) {
          VertexPhilosophersTable pTable =
            serviceWorker.getVertexPhilosophersTable();
          if (pTable.isBoundaryVertex(vertexId)) {
            // skip halted vertices that have no messages to wake with
            if (!(vertex.isHalted() && !hasMessages(vertexId))) {
              pTable.acquireForks(vertexId);
              computeVertex(computation, partition, vertex,
                            removeAllMessages(vertexId));

              // Flush all caches BEFORE releasing forks!
              // Releasing forks will flush msgs to network if needed.
              try {
                workerClientRequestProcessor.flush();
              } catch (IOException e) {
                throw new IllegalStateException("call: Flushing failed.", e);
              }
              pTable.releaseForks(vertexId);
            }
          } else {
            computeVertex(computation, partition, vertex,
                          removeLocalMessages(vertexId));
          }

        } else {
          // regular non-serializable execution or
          // partition lock-serialized when not skipping partition
          computeVertex(computation, partition, vertex,
                        removeAllMessages(vertexId));
        }

        if (vertex.isHalted()) {
          partitionStats.incrFinishedVertexCount();
        }
        // Remove the messages now that the vertex has finished computation
        // YH: we do this right when we get messages, so this isn't needed
        // (For needAllMsgs(), we overwrite, so this isn't needed as well)
        //messageStore.clearVertexMessages(vertex.getId());

        // Add statistics for this vertex
        partitionStats.incrVertexCount();
        partitionStats.addEdgeCount(vertex.getNumEdges());

        verticesComputedProgress++;
        if (verticesComputedProgress == VERTICES_TO_UPDATE_PROGRESS) {
          WorkerProgress.get().addVerticesComputed(verticesComputedProgress);
          verticesComputedProgress = 0;
        }
      }

      // YH: clear partition if using normal BSP message store.
      // Should NOT clear partitions for stores otherwise, as they will
      // have picked up unprocessed messages during compute calls above.
      //
      // Note: partitions won't disappear during computation.
      if (!asyncConf.isAsync()) {
        messageStore.clearPartition(partition.getId());
      }
    }
    WorkerProgress.get().addVerticesComputed(verticesComputedProgress);
    WorkerProgress.get().incrementPartitionsComputed();
    return partitionStats;
  }

  /**
   * Get and remove all messages (local and remote) for a vertex.
   * (Does not do remove if asyncNeedAllMessages option is true.)
   *
   * @param vertexId Id of the vertex to be computed
   * @return All messages for the vertex.
   */
  private Iterable<M1> removeAllMessages(I vertexId) throws IOException {
    // Two types of algorithms:
    // 1. vertices need all messages (aka, stationary; e.g., PageRank)
    //    -> newer messages always as up-to-date as older messages
    //    -> edge case is exactly 1 superstep (b/c there, old messages
    //       are valuable as they only appear once)
    //
    // 2. vertices can handle partial messages (e.g., SSSP/WCC)
    //    -> this subsumes case where old messages are more valuable
    //       than newer messages, as they won't be repeated
    //
    // Type 1 algs should overwrite old msgs with new ones,
    // while type 2 algs should show them immediately.
    //
    // HOWEVER, algorithms typically only do initialization in first
    // superstep, so we instead persist them to be processed in
    // a subsequent superstep.

    // YH: messageStore and localMessageStore are set correctly by
    // GraphTaskManager to be remote-only/BSP and local-only/BSP

    Iterable<M1> messages;

    if (asyncConf.isAsync()) {
      // YH: (logical) SS0 is special case for async, b/c many algs send
      // messages but do not have any logic to process them, so messages
      // revealed in SS0 gets lost. Hence, keep them until after.
      if (serviceWorker.getLogicalSuperstep() == 0) {
        messages = EmptyIterable.<M1>get();
      } else if (asyncConf.needAllMsgs()) {
        // no need to remove, as we always overwrite
        messages = Iterables.concat(
          ((MessageWithSourceStore) messageStore).
            getVertexMessagesWithoutSource(vertexId),
          ((MessageWithSourceStore) localMessageStore).
            getVertexMessagesWithoutSource(vertexId));
      } else {
        // always remove messages immediately (rather than get and clear)
        messages = Iterables.concat(
          messageStore.removeVertexMessages(vertexId),
          localMessageStore.removeVertexMessages(vertexId));
      }
    } else {
      // regular BSP---always remove instead of get and clear
      messages = messageStore.removeVertexMessages(vertexId);
    }

    return messages;
  }

  /**
   * Get and remove only local messages for a vertex.
   * (Does not do remove if asyncNeedAllMessages option is true.)
   *
   * @param vertexId Id of the vertex to be computed
   * @return Local messages for the vertex.
   */
  private Iterable<M1> removeLocalMessages(I vertexId) throws IOException {
    Iterable<M1> messages;

    if (asyncConf.isAsync()) {
      // YH: (logical) SS0 is special case for async, b/c many algs send
      // messages but do not have any logic to process them, so messages
      // revealed in SS0 gets lost. Hence, keep them until after.
      if (serviceWorker.getLogicalSuperstep() == 0) {
        messages = EmptyIterable.<M1>get();
      } else if (asyncConf.needAllMsgs()) {
        // no need to remove, as we always overwrite
        messages = ((MessageWithSourceStore) localMessageStore).
          getVertexMessagesWithoutSource(vertexId);
      } else {
        // always remove messages immediately (rather than get and clear)
        messages = localMessageStore.removeVertexMessages(vertexId);
      }
    } else {
      // BSP not supported b/c it has no local store
      //
      // also no support for serializability in BSP; use AP/BAP instead
      // (BSP requires particular conditions, which are not performant)
      throw new UnsupportedOperationException("BSP not supported");
    }

    return messages;
  }

  /**
   * Return whether a vertex has messages.
   * Result is consistent with removeAllMessages(). That is, if this
   * returns true, removeAllMessages() will return a non-empty Iterable.
   *
   * @param vertexId Id of vertex to check
   * @return True if vertex has messages
   */
  private boolean hasMessages(I vertexId) {
    if (asyncConf.isAsync()) {
      if (serviceWorker.getLogicalSuperstep() == 0) {
        return false;
      } else if (asyncConf.needAllMsgs()) {
        return true;
      } else {
        return messageStore.hasMessagesForVertex(vertexId) ||
          localMessageStore.hasMessagesForVertex(vertexId);
      }
    } else {
      return messageStore.hasMessagesForVertex(vertexId);
    }
  }

  /**
   * Return whether a partition has messages.
   *
   * @param partitionId Id of partition to check
   * @return True if partition has messages
   */
  private boolean hasMessages(int partitionId) {
    if (asyncConf.isAsync()) {
      if (serviceWorker.getLogicalSuperstep() == 0) {
        return false;
      } else if (asyncConf.needAllMsgs()) {
        return true;
      } else {
        return messageStore.hasMessagesForPartition(partitionId) ||
          localMessageStore.hasMessagesForPartition(partitionId);
      }
    } else {
      return messageStore.hasMessagesForPartition(partitionId);
    }
  }

  /**
   * Compute a single vertex.
   *
   * @param computation Computation to use
   * @param vertex Vertex to compute
   * @param messages Messages for the vertex
   * @param partition Partition being computed
   */
  private void computeVertex(
      Computation<I, V, E, M1, M2> computation,
      Partition<I, V, E> partition, Vertex<I, V, E> vertex,
      Iterable<M1> messages) throws IOException, InterruptedException {
    if (vertex.isHalted() && !Iterables.isEmpty(messages)) {
      vertex.wakeUp();
    }
    if (!vertex.isHalted()) {
      context.progress();

      // YH: set source id before compute(), and remove the (stale)
      // reference immediately after compute() is done. This is
      // thread-safe as there is one Computation per thread.
      computation.setCurrentSourceId(vertex.getId());
      computation.compute(vertex, messages);
      computation.setCurrentSourceId(null);

      // Need to unwrap the mutated edges (possibly)
      vertex.unwrapMutableEdges();
      //Compact edges representation if possible
      if (vertex instanceof Trimmable) {
        ((Trimmable) vertex).trim();
      }
      // Write vertex to superstep output (no-op if it is not used)
      vertexWriter.writeVertex(vertex);
      // Need to save the vertex changes (possibly)
      partition.saveVertex(vertex);
    }
  }
}
