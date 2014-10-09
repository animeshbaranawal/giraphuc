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
  private static final Logger LOG  = Logger.getLogger(ComputeCallable.class);
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
   * @param localMessageStore Local message store (local-only, if using async)
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

    List<PartitionStats> partitionStatsList = Lists.newArrayList();
    while (!partitionIdQueue.isEmpty()) {
      Integer partitionId = partitionIdQueue.poll();
      if (partitionId == null) {
        break;
      }

      Partition<I, V, E> partition =
          serviceWorker.getPartitionStore().getOrCreatePartition(partitionId);

      Computation<I, V, E, M1, M2> computation =
          (Computation<I, V, E, M1, M2>) configuration.createComputation();
      computation.initialize(graphState, workerClientRequestProcessor,
          serviceWorker.getGraphTaskManager(), aggregatorUsage, workerContext);
      computation.preSuperstep();

      try {
        PartitionStats partitionStats =
            computePartition(computation, partition);
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

        // TODO-YH: why does recomputing here (or adding partition id
        // back on to queue) cause termination issues?

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
   * @return Partition stats for this computed partition
   */
  private PartitionStats computePartition(
      Computation<I, V, E, M1, M2> computation,
      Partition<I, V, E> partition) throws IOException, InterruptedException {
    PartitionStats partitionStats =
        new PartitionStats(partition.getId(), 0, 0, 0, 0, 0);
    long verticesComputedProgress = 0;

    AsyncConfiguration asyncConf = configuration.getAsyncConf();

    // Make sure this is thread-safe across runs
    synchronized (partition) {
      for (Vertex<I, V, E> vertex : partition) {
        Iterable<M1> messages;

        // NOTE: This assumes messages are queued instantly, or in gaps
        // of X vertices (cannot do it by size, b/c then # of old messages
        // will vary).
        //
        // TODO-YH: this does not handle algs with multiple computation
        // phases! We would need way of knowing phase transition BEFORE the
        // superstep in which it occurs: b/c messages intended to be read
        // in new phase will be immediately visible in local message store,
        // and we should not read it.

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
        // Type 1 algs should clear old messages to make way for new,
        // while type 2 algs should show them immediately.
        //
        // HOWEVER, algorithms typically only do initialization in first
        // superstep, so we instead persist them to be processed in
        // a subsequent superstep.
        //
        // NOTE: Correctly clearing for type 1 algs gives large performance
        // benefits in terms of convergence (by at least 100x), but WILL
        // cause correctness issues if algorithm is actually type 2 alg.

        // YH: messageStore and localMessageStore are set correctly by
        // GraphTaskManager to be remote-only/BSP and local-only/BSP

        // TODO-YH: deal with pending mutations by skipping message remove()
        // for vertices that don't yet exist

        if (asyncConf.doRemoteRead() && asyncConf.isNewPhase()) {
          // for needAllMsgs(), nothing needs to be done either
          messages = EmptyIterable.<M1>get();
        } else if (asyncConf.needAllMsgs()) {
          // no need to remove, as we always overwrite
          messages = ((MessageWithSourceStore) messageStore).
            getVertexMessagesWithoutSource(vertex.getId());
        } else {
          // always remove messages immediately (rather than get and clear)
          messages = messageStore.removeVertexMessages(vertex.getId());
        }

        if (asyncConf.doLocalRead() && asyncConf.isNewPhase()) {
          // do nothing
          messages = messages;
        } else {
          // concat w/ local store if at least one async mode is enabled
          // (otherwise, messages is already reading BSP store)
          if (asyncConf.doRemoteRead() || asyncConf.doLocalRead()) {
            if (asyncConf.needAllMsgs()) {
              messages = Iterables.concat(messages,
                ((MessageWithSourceStore) localMessageStore).
                getVertexMessagesWithoutSource(vertex.getId()));
            } else {
              messages = Iterables.concat(messages,
                localMessageStore.removeVertexMessages(vertex.getId()));
            }
          }
        }

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
      // (For needAllMsgs() without async, still need to clear message
      //  stores, as BSP will rotate message stores)
      //
      // TODO-YH: can partitions disappear?...
      if (!asyncConf.doRemoteRead()) {
        messageStore.clearPartition(partition.getId());
      } else {
        if (!asyncConf.doLocalRead()) {
          localMessageStore.clearPartition(partition.getId());
        }
      }
    }
    WorkerProgress.get().addVerticesComputed(verticesComputedProgress);
    WorkerProgress.get().incrementPartitionsComputed();
    return partitionStats;
  }
}
