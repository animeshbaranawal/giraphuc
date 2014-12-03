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

package org.apache.giraph.comm;

import java.util.Iterator;

import org.apache.giraph.bsp.CentralizedServiceWorker;
import org.apache.giraph.comm.messages.MessageWithPhaseUtils;
import org.apache.giraph.comm.netty.NettyWorkerClientRequestProcessor;
import org.apache.giraph.comm.requests.SendWorkerMessagesRequest;
import org.apache.giraph.comm.requests.WritableRequest;
import org.apache.giraph.conf.ImmutableClassesGiraphConfiguration;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.partition.PartitionOwner;
import org.apache.giraph.utils.VertexIdMessages;
import org.apache.giraph.utils.ByteArrayVertexIdMessages;
import org.apache.giraph.utils.PairList;
import org.apache.giraph.worker.WorkerInfo;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.log4j.Logger;

import static org.apache.giraph.conf.GiraphConstants.ADDITIONAL_MSG_REQUEST_SIZE;
import static org.apache.giraph.conf.GiraphConstants.MAX_MSG_REQUEST_SIZE;

import java.io.IOException;

/**
 * Aggregates the messages to be sent to workers so they can be sent
 * in bulk.  Not thread-safe.
 *
 * @param <I> Vertex id
 * @param <M> Message data
 */
@SuppressWarnings("unchecked")
public class SendMessageCache<I extends WritableComparable, M extends Writable>
    extends SendVertexIdDataCache<I, M, VertexIdMessages<I, M>> {
  /** Class logger */
  private static final Logger LOG =
      Logger.getLogger(SendMessageCache.class);
  /** Messages sent during the last superstep */
  protected long totalMsgsSentInSuperstep = 0;
  /** Local messages sent during the last superstep */
  protected long totalLocalMsgsSentInSuperstep = 0;
  /** Message bytes sent during the last superstep */
  protected long totalMsgBytesSentInSuperstep = 0;
  /** Max message size sent to a worker */
  protected final int maxMessagesSizePerWorker;
  /** NettyWorkerClientRequestProcessor for message sending */
  protected final NettyWorkerClientRequestProcessor<I, ?, ?> clientProcessor;

  /**
   * YH: Whether the subsequent messages will be for the next phase
   * (if performing multi-phase computation).
   */
  protected boolean forNextPhase;

  /**
   * Constructor
   *
   * @param conf Giraph configuration
   * @param serviceWorker Service worker
   * @param processor NettyWorkerClientRequestProcessor
   * @param maxMsgSize Max message size sent to a worker
   */
  public SendMessageCache(ImmutableClassesGiraphConfiguration conf,
      CentralizedServiceWorker<?, ?, ?> serviceWorker,
      NettyWorkerClientRequestProcessor<I, ?, ?> processor,
      int maxMsgSize) {
    super(conf, serviceWorker, MAX_MSG_REQUEST_SIZE.get(conf),
        ADDITIONAL_MSG_REQUEST_SIZE.get(conf));
    maxMessagesSizePerWorker = maxMsgSize;
    clientProcessor = processor;
  }

  @Override
  public VertexIdMessages<I, M> createVertexIdData() {
    return new ByteArrayVertexIdMessages<I, M>(
        getConf().getOutgoingMessageValueFactory());
  }

  /**
   * YH: Flag all subsequent messages as being either for the current
   * phase (false) or for the next phase (true).
   *
   * @param forNextPhase True if message should be processed in next phase.
   */
  public void setForNextPhase(boolean forNextPhase) {
    this.forNextPhase = forNextPhase;
  }

  /**
   * Add a message to the cache.
   *
   * @param workerInfo the remote worker destination
   * @param partitionId the remote Partition this message belongs to
   * @param destVertexId vertex id that is ultimate destination
   * @param message Message to send to remote worker
   * @return Size of messages for the worker.
   */
  public int addMessage(WorkerInfo workerInfo,
                        int partitionId, I destVertexId, M message) {
    return addData(workerInfo, partitionId, destVertexId, message);
  }

  /**
   * Add a message to the cache with serialized ids.
   *
   * @param workerInfo The remote worker destination
   * @param partitionId The remote Partition this message belongs to
   * @param serializedId Serialized vertex id that is ultimate destination
   * @param idSerializerPos The end position of serialized id in the byte array
   * @param message Message to send to remote worker
   * @return Size of messages for the worker.
   */
  protected int addMessage(WorkerInfo workerInfo, int partitionId,
      byte[] serializedId, int idSerializerPos, M message) {
    return addData(
      workerInfo, partitionId, serializedId,
      idSerializerPos, message);
  }

  /**
   * Gets the messages for a worker and removes it from the cache.
   *
   * @param workerInfo the address of the worker who owns the data
   *                   partitions that are receiving the messages
   * @return List of pairs (partitionId, ByteArrayVertexIdMessages),
   *         where all partition ids belong to workerInfo
   */
  protected PairList<Integer, VertexIdMessages<I, M>>
  removeWorkerMessages(WorkerInfo workerInfo) {
    return removeWorkerData(workerInfo);
  }

  /**
   * Gets all the messages and removes them from the cache.
   *
   * @return All vertex messages for all partitions
   */
  private PairList<WorkerInfo, PairList<
      Integer, VertexIdMessages<I, M>>> removeAllMessages() {
    return removeAllData();
  }

  /**
   * YH: Creates a writable request.
   *
   * @param workerMessages The messages to store in the request.
   * @return A writable worker request
   */
  protected WritableRequest createWritableRequest(
      PairList<Integer, VertexIdMessages<I, M>> workerMessages) {
    return new SendWorkerMessagesRequest<I, M>(workerMessages, getConf());
  }

  /**
   * Send a message to a target vertex id.
   *
   * @param destVertexId Target vertex id
   * @param message The message sent to the target
   */
  public void sendMessageRequest(I destVertexId, M message) {
    PartitionOwner owner =
      getServiceWorker().getVertexPartitionOwner(destVertexId);
    WorkerInfo workerInfo = owner.getWorkerInfo();
    final int partitionId = owner.getPartitionId();

    if (LOG.isTraceEnabled()) {
      LOG.trace("sendMessageRequest: Send bytes (" + message.toString() +
        ") to " + destVertexId + " on worker " + workerInfo);
    }

    // YH: if not doing multiphase, ignore forNextPhase
    if (!getConf().getAsyncConf().isMultiPhase()) {
      forNextPhase = false;
    }

    // YH: this is used for termination in BspServiceMaster.
    // Namely, if number of messages is 0 and all vertices are halted,
    // then entire computation terminates. So don't mess with this.
    //
    // Note that this includes local messages (if using async), while
    // totalMsgBytesSentInSuperstep *excludes* local messages.
    ++totalMsgsSentInSuperstep;

    // YH: short-circuit local messages directly to message store.
    // This should cut down GC and memory overheads substantially, as byte
    // array caches are not allocated. Additionally, this gives better
    // async performance (direct sends => more recent data).
    //
    // Note: taskId is same for all partitions local to same worker
    // b/c it relies on mapred.task.partition (= attempt_...._m_..._0)
    if (getConf().getAsyncConf().isAsync() &&
        getServiceWorker().getWorkerInfo().getTaskId() ==
        workerInfo.getTaskId()) {

      // Mimics doRequest() in comm.requests.SendWorkerMessageRequest
      //
      // NegativeArraySizeException is copied from catch of write message
      // functions for vertex id iterators (see addPartitionMessages()).
      try {
        ++totalLocalMsgsSentInSuperstep;

        if (!forNextPhase) {
          // YH: Dest vertex id needs to be cloned, since we never serialize it.
          // This is handled within message store implementations.
          // (Code path for remote messages need not clone, as dst ids there
          // are serialized to byte array when caching.)
          getServiceWorker().getServerData().getLocalMessageStore().
            addPartitionMessage(partitionId, destVertexId, message);
        } else {
          // YH: if for next phase, put it on the next phase message store
          getServiceWorker().getServerData().getNextPhaseLocalMessageStore().
            addPartitionMessage(partitionId, destVertexId, message);
        }
      } catch (NegativeArraySizeException e) {
        throw new RuntimeException("The numbers of bytes sent to vertex " +
            destVertexId + " exceeded the max capacity of " +
            "its ExtendedDataOutput. Please consider setting " +
            "giraph.useBigDataIOForMessages=true. If there are super-vertices" +
            " in the graph which receive a lot of messages (total serialized " +
            "size of messages goes beyond the maximum size of a byte array), " +
            "setting this option to true will remove that limit");
      } catch (IOException e) {
        throw new RuntimeException("sendMessageRequest: Got IOException ", e);
      }

      // dummy call to keep counters consistent
      clientProcessor.doRequest(workerInfo, null);
      // notify sending, as per normal code path
      getServiceWorker().getGraphTaskManager().notifySentMessages();

      return;
    }

    // YH: If a message is for the next phase, use the leftmost bit as a flag.
    // This will naturally separate the different types of messages AND enable
    // the receiver to distinguish between the two cases.
    final int partitionIdWithPhase =
      MessageWithPhaseUtils.encode(partitionId, forNextPhase);

    // Add the message to the cache
    int workerMessageSize = addMessage(
      workerInfo, partitionIdWithPhase, destVertexId, message);

    // TODO-YH: SendMessageToAllCache isn't modified

    // Send a request if the cache of outgoing message to
    // the remote worker 'workerInfo' is full enough to be flushed
    if (workerMessageSize >= maxMessagesSizePerWorker) {
      PairList<Integer, VertexIdMessages<I, M>>
        workerMessages = removeWorkerMessages(workerInfo);
      WritableRequest writableRequest = createWritableRequest(workerMessages);
      // YH: incrSentBytes for AsyncConfiguration (if needed) is done in
      // BSPServiceWorker to reduce contention
      totalMsgBytesSentInSuperstep += writableRequest.getSerializedSize();
      clientProcessor.doRequest(workerInfo, writableRequest);
      // Notify sending
      getServiceWorker().getGraphTaskManager().notifySentMessages();
    }
  }

  /**
   * An iterator wrapper on edges to return
   * target vertex ids.
   */
  private class TargetVertexIdIterator implements Iterator<I> {
    /** An edge iterator */
    private Iterator<Edge<I, Writable>> edgesIterator;

    /**
     * Constructor.
     *
     * @param vertex The source vertex of the out edges
     */
    private TargetVertexIdIterator(Vertex<I, ?, ?> vertex) {
      edgesIterator =
        ((Vertex<I, Writable, Writable>) vertex).getEdges().iterator();
    }

    @Override
    public boolean hasNext() {
      return edgesIterator.hasNext();
    }

    @Override
    public I next() {
      return edgesIterator.next().getTargetVertexId();
    }

    @Override
    public void remove() {
      // No operation.
    }
  }

  /**
   * Send message to all its neighbors
   *
   * @param vertex The source vertex
   * @param message The message sent to a worker
   */
  public void sendMessageToAllRequest(Vertex<I, ?, ?> vertex, M message) {
    TargetVertexIdIterator targetVertexIterator =
      new TargetVertexIdIterator(vertex);
    sendMessageToAllRequest(targetVertexIterator, message);
  }

  /**
   * Send message to the target ids in the iterator
   *
   * @param vertexIdIterator The iterator of target vertex ids
   * @param message The message sent to a worker
   */
  public void sendMessageToAllRequest(Iterator<I> vertexIdIterator, M message) {
    while (vertexIdIterator.hasNext()) {
      sendMessageRequest(vertexIdIterator.next(), message);
    }
  }

  /**
   * Flush the rest of the messages to the workers.
   */
  public void flush() {
    PairList<WorkerInfo, PairList<Integer,
        VertexIdMessages<I, M>>>
    remainingMessageCache = removeAllMessages();
    PairList<WorkerInfo, PairList<
        Integer, VertexIdMessages<I, M>>>.Iterator
    iterator = remainingMessageCache.getIterator();
    while (iterator.hasNext()) {
      iterator.next();
      WritableRequest writableRequest =
        createWritableRequest(iterator.getCurrentSecond());
      totalMsgBytesSentInSuperstep += writableRequest.getSerializedSize();
      clientProcessor.doRequest(
        iterator.getCurrentFirst(), writableRequest);
    }
  }

  /**
   * Reset the message count per superstep.
   *
   * @return The message count sent in last superstep
   */
  public long resetMessageCount() {
    // YH: if barriers are disabled, return only local sent messages
    long messagesSentInSuperstep;
    if (getConf().getAsyncConf().disableBarriers()) {
      messagesSentInSuperstep = totalLocalMsgsSentInSuperstep;
    } else {
      messagesSentInSuperstep = totalMsgsSentInSuperstep;
    }

    totalMsgsSentInSuperstep = 0;
    totalLocalMsgsSentInSuperstep = 0;
    return messagesSentInSuperstep;
  }

  /**
   * Reset the message bytes count per superstep.
   *
   * @return The message count sent in last superstep
   */
  public long resetMessageBytesCount() {
    long messageBytesSentInSuperstep = totalMsgBytesSentInSuperstep;
    totalMsgBytesSentInSuperstep = 0;
    return messageBytesSentInSuperstep;
  }
}
