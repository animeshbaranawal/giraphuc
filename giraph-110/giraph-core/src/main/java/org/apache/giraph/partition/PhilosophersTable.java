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

package org.apache.giraph.partition;

import org.apache.giraph.bsp.CentralizedServiceWorker;
import org.apache.giraph.comm.requests.SendDistributedLockingForkRequest;
import org.apache.giraph.comm.requests.SendDistributedLockingTokenRequest;
import org.apache.giraph.conf.ImmutableClassesGiraphConfiguration;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.Vertex;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.log4j.Logger;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * YH: Implements the hygienic dining philosophers solution.
 *
 * @param <I> Vertex id
 * @param <V> Vertex value
 * @param <E> Edge value
 */
public class PhilosophersTable<I extends WritableComparable,
    V extends Writable, E extends Writable> {
  /** Class logger */
  private static final Logger LOG = Logger.getLogger(PhilosophersTable.class);

  /** Mask for have-token bit */
  private static final byte MASK_HAVE_TOKEN = 0x1;
  /** Mask for have-fork bit */
  private static final byte MASK_HAVE_FORK = 0x2;
  /** Mask for is-dirty bit */
  private static final byte MASK_IS_DIRTY = 0x4;

  /** Provided configuration */
  private final ImmutableClassesGiraphConfiguration<I, V, E> conf;
  /** Service worker */
  private final CentralizedServiceWorker<I, V, E> serviceWorker;

  /** Lock for condition var */
  private final Lock cvLock = new ReentrantLock();
  /** Condition var indicating arrival of fork */
  private final Condition getFork = cvLock.newCondition();

  /**
   * Map of vertex id (philosopher) to vertex ids (vertex's neighbours)
   * to single byte (indicates status, fork, token, dirty/clean).
   *
   * Basically, this tracks the state of philosophers (boundary vertices),
   * who are sitting at a distributed table, that are local to this worker.
   */
  private Long2ObjectOpenHashMap<Long2ByteOpenHashMap> pMap;

  /**
   * Constructor
   *
   * @param conf Configuration used.
   * @param serviceWorker Service worker
   */
  public PhilosophersTable(
     ImmutableClassesGiraphConfiguration<I, V, E> conf,
     CentralizedServiceWorker<I, V, E> serviceWorker) {
    Class<I> vertexIdClass = conf.getVertexIdClass();
    if (!vertexIdClass.equals(LongWritable.class)) {
      // TODO-YH: implement general support (easy to do, but tedious)
      throw new RuntimeException(
        "Non-long vertex id classes not yet supported!");
    }

    this.conf = conf;
    this.serviceWorker = serviceWorker;
    this.pMap = new Long2ObjectOpenHashMap<Long2ByteOpenHashMap>();
  }

  /**
   * Add and initialize a vertex as a philosopher if it's a boundary vertex.
   * Must not be called/used when compute threads are executing.
   *
   * @param vertex Vertex to be added
   */
  public void addVertexIfBoundary(Vertex<I, V, E> vertex) {
    Long2ByteOpenHashMap neighbours = null;

    int partitionId = serviceWorker.
      getVertexPartitionOwner(vertex.getId()).getPartitionId();
    long pId = ((LongWritable) vertex.getId()).get();  // "philosopher" id

    // TODO-YH: assumes undirected graph... for directed graph,
    // need do broadcast to all neighbours
    for (Edge<I, E> e : vertex.getEdges()) {
      int dstPartitionId = serviceWorker.
        getVertexPartitionOwner(e.getTargetVertexId()).getPartitionId();
      long neighbourId = ((LongWritable) e.getTargetVertexId()).get();
      byte forkInfo = 0;

      // Determine if neighbour is on different partition.
      // This does two things:
      // - if vertex is internal, skips creating "neighbours" altogether
      // - if vertex is boundary, skips tracking internal neighbours
      //   (same partition = executed by single thread = no forks needed)
      if (dstPartitionId != partitionId) {
        if (neighbours == null) {
          neighbours = new Long2ByteOpenHashMap(vertex.getNumEdges());
        }

        // For acyclic precedence graph, always initialize
        // tokens at smaller id and dirty fork at larger id.
        // Skip self-loops (saves a bit of space).
        if (neighbourId == pId) {
          continue;
        } else if (neighbourId < pId) {
          // I am larger id, so I hold dirty fork
          forkInfo |= MASK_HAVE_FORK;
          forkInfo |= MASK_IS_DIRTY;
        } else {
          forkInfo |= MASK_HAVE_TOKEN;
        }
        neighbours.put(neighbourId, forkInfo);
      }
    }

    if (neighbours != null) {
      synchronized (pMap) {
        Long2ByteOpenHashMap ret = pMap.put(pId, neighbours);
        if (ret != null) {
          throw new RuntimeException("Duplicate neighbours!");
        }
      }
    }
  }

  /**
   * Whether vertex is a boundary vertex (philosopher). Thread-safe
   * for all concurrent calls EXCEPT addBoundaryVertex() calls.
   *
   * @param vertexId Vertex id to check
   * @return True if vertex is boundary vertex
   */
  public boolean isBoundaryVertex(I vertexId) {
    return pMap.containsKey(((LongWritable) vertexId).get());
  }


  /**
   * Blocking call that returns when all forks are acquired and
   * the philosopher is ready to eat. Equivalent to "start eating".
   *
   * @param vertexId Vertex id (philosopher) to acquire forks for
   */
  public void acquireForks(I vertexId) {
    LOG.info("[[TESTING]] " + vertexId + ": acquiring forks");
    boolean needRemoteFork = false;
    boolean needForks = false;
    LongWritable dstId = new LongWritable();  // reused

    long pId = ((LongWritable) vertexId).get();
    Long2ByteOpenHashMap neighbours = pMap.get(pId);

    synchronized (neighbours) {
      ObjectIterator<Long2ByteMap.Entry> itr =
        neighbours.long2ByteEntrySet().fastIterator();
      while (itr.hasNext()) {
        Long2ByteMap.Entry e = itr.next();
        long neighbourId = e.getLongKey();
        byte forkInfo = e.getByteValue();

        if (haveToken(forkInfo) && !haveFork(forkInfo)) {
          forkInfo &= ~MASK_HAVE_TOKEN;
          // must apply updates BEFORE sending token requests,
          // as local requests that are satisified imimdeatily
          // WILL modify forkInfo!!
          neighbours.put(neighbourId, forkInfo);
          needForks = true;
          LOG.info("[[TESTING]] " + vertexId + ":   missing fork " +
                   neighbourId + " " + toString(forkInfo));
          dstId.set(neighbourId);
          needRemoteFork |= sendToken(vertexId, (I) dstId);
        } else if (!haveToken(forkInfo) &&
                   haveFork(forkInfo) && isDirty(forkInfo)) {
          forkInfo &= ~MASK_IS_DIRTY;
          neighbours.put(neighbourId, forkInfo);
          LOG.info("[[TESTING]] " + vertexId + ":   have fork " +
                   neighbourId + " " + toString(forkInfo));
        } else {
          new RuntimeException("Unexpected philosopher state!");
        }
      }
    }

    if (needRemoteFork) {
      LOG.info("[[TESTING]] " + vertexId + ":   flushing");
      serviceWorker.getWorkerClient().waitAllRequests();
      LOG.info("[[TESTING]] " + vertexId + ":   done flush");
    }

    if (!needForks) {
      LOG.info("[[TESTING]] " + vertexId + ": got all forks");
      return;
    }

    while (true) {
      LOG.info("[[TESTING]] " + vertexId + ":   trying to get lock");
      // must lock entire inner loop so we never miss signals
      cvLock.lock();
      LOG.info("[[TESTING]] " + vertexId + ":   got lock");

      // TODO-YH: use a id -> num-forks-got map, will make check faster
      // should recheck forks right away, since some if not all
      // forks may have already arrived (due to waitAllRequests)
      needForks = false;
      synchronized (neighbours) {
        ObjectIterator<Long2ByteMap.Entry> itr =
          neighbours.long2ByteEntrySet().fastIterator();
        while (itr.hasNext()) {
          // TODO-YH: compact this
          Long2ByteMap.Entry e = itr.next();
          byte forkInfo = e.getByteValue();
          if (!haveFork(forkInfo)) {
            LOG.info("[[TESTING]] " + vertexId + ":  still need fork " +
                     e.getLongKey());
            needForks = true;
            break;
          }
        }
      }

      if (!needForks) {
        cvLock.unlock();
        LOG.info("[[TESTING]] " + vertexId + ": got all forks");
        break;
      }

      try {
        getFork.await();  // wait for signal
        LOG.info("[[TESTING]] " + vertexId + ":  signalled");
      } catch (InterruptedException e) {
        throw new RuntimeException("Got interrupted!");  // blow up
      } finally {
        cvLock.unlock();
      }
    }
  }

  /**
   * Dirties used forks and satisfies any pending requests
   * for such forks. Equivalent to "stop eating".
   *
   * @param vertexId Vertex id (philosopher) that finished eating
   */
  public void releaseForks(I vertexId) {
    LOG.info("[[TESTING]] " + vertexId + ": releasing forks");
    boolean needFlush = false;
    LongWritable dstId = new LongWritable();  // reused

    long pId = ((LongWritable) vertexId).get();
    Long2ByteOpenHashMap neighbours = pMap.get(pId);

    // all held forks are (implicitly) dirty
    synchronized (neighbours) {
      ObjectIterator<Long2ByteMap.Entry> itr =
        neighbours.long2ByteEntrySet().fastIterator();
      while (itr.hasNext()) {
        Long2ByteMap.Entry e = itr.next();
        long neighbourId = e.getLongKey();
        byte forkInfo = e.getByteValue();

        if (haveToken(forkInfo)) {
          // send fork if our philosopher pair has requested it
          forkInfo &= ~MASK_HAVE_FORK;
          neighbours.put(neighbourId, forkInfo);
          LOG.info("[[TESTING]] " + vertexId + ": sending clean fork to " +
                   neighbourId + " " + toString(forkInfo));
          dstId.set(neighbourId);
          needFlush |= sendFork(vertexId, (I) dstId);
        } else {
          // otherwise, explicitly dirty the fork
          // (so that fork is released immediately on token receipt)
          forkInfo |= MASK_IS_DIRTY;
          neighbours.put(neighbourId, forkInfo);
          LOG.info("[[TESTING]] " + vertexId + ": dirty fork " +
                   neighbourId + " " + toString(forkInfo));
        }
      }
    }

    if (needFlush) {
      LOG.info("[[TESTING]] " + vertexId + ": flushing");
      serviceWorker.getWorkerClient().waitAllRequests();
      LOG.info("[[TESTING]] " + vertexId + ": done flush");
    }
  }

  /**
   * Send token/request for fork.
   *
   * @param senderId Sender of token
   * @param receiverId Receiver of token
   * @return True if receiver is remote, false if local
   */
  public boolean sendToken(I senderId, I receiverId) {
    int dstTaskId = serviceWorker.getVertexPartitionOwner(receiverId).
      getWorkerInfo().getTaskId();

    if (serviceWorker.getWorkerInfo().getTaskId() == dstTaskId) {
      LOG.info("[[TESTING]] " + senderId +
               ": send local token to " + receiverId);
      // handle request locally
      receiveToken(senderId, receiverId);
      LOG.info("[[TESTING]] " + senderId +
               ": SENT local token to " + receiverId);
      return false;
    } else {
      LOG.info("[[TESTING]] " + senderId +
               ": send remote token to " + receiverId);
      serviceWorker.getWorkerClient().sendWritableRequest(
        dstTaskId,
        new SendDistributedLockingTokenRequest(senderId, receiverId, conf));
      LOG.info("[[TESTING]] " + senderId +
               ": SENT remote token to " + receiverId);
      return true;
    }
  }

  /**
   * Send fork.
   *
   * @param senderId Sender of fork
   * @param receiverId Receiver of fork
   * @return True if receiver is remote, false if local
   */
  public boolean sendFork(I senderId, I receiverId) {
    int dstTaskId = serviceWorker.getVertexPartitionOwner(receiverId).
      getWorkerInfo().getTaskId();

    if (serviceWorker.getWorkerInfo().getTaskId() == dstTaskId) {
      LOG.info("[[TESTING]] " + senderId +
               ": send local fork to " + receiverId);
      // handle request locally
      receiveFork(senderId, receiverId);
      LOG.info("[[TESTING]] " + senderId +
               ": SENT local fork to " + receiverId);
      return false;
    } else {
      LOG.info("[[TESTING]] " + senderId +
               ": send remote fork to " + receiverId);
      serviceWorker.getWorkerClient().sendWritableRequest(
        dstTaskId,
        new SendDistributedLockingForkRequest(senderId, receiverId, conf));
      LOG.info("[[TESTING]] " + senderId +
               ": SENT remote fork to " + receiverId);
      return true;
    }
  }

  /**
   * Process a received token/request for fork.
   *
   * @param senderId Sender of token
   * @param receiverId New holder of token
   */
  public void receiveToken(I senderId, I receiverId) {
    boolean needFlush = false;

    long pId = ((LongWritable) receiverId).get();
    Long2ByteOpenHashMap neighbours = pMap.get(pId);

    synchronized (neighbours) {
      long neighbourId = ((LongWritable) senderId).get();
      byte forkInfo = neighbours.get(neighbourId);

      // fork requests are always sent with a token
      forkInfo |= MASK_HAVE_TOKEN;
      LOG.info("[[TESTING]] " + receiverId + ": got token from " +
               senderId  + " " + toString(forkInfo));

      // If fork is dirty, we can immediately send it.
      // Otherwise, return---fork holder will eventually see
      // token when it dirties the fork.
      if (isDirty(forkInfo)) {
        forkInfo &= ~MASK_HAVE_FORK;  // fork sent
        forkInfo &= ~MASK_IS_DIRTY;   // no longer dirty
        needFlush |= sendFork(receiverId, senderId);
      }

      neighbours.put(neighbourId, forkInfo);
    }

    // TODO-YH: w/o async thread, this causes deadlock
    if (needFlush) {
      new Thread(new Runnable() {
          @Override
          public void run() {
            LOG.info("[[TESTING]] flushing on separate thread");
            serviceWorker.getWorkerClient().waitAllRequests();
            LOG.info("[[TESTING]] done flush on separate thread");
          }
        }).start();
    }
  }

  /**
   * Process a received fork.
   *
   * @param senderId Sender of fork
   * @param receiverId New holder of fork
   */
  public void receiveFork(I senderId, I receiverId) {
    long pId = ((LongWritable) receiverId).get();
    Long2ByteOpenHashMap neighbours = pMap.get(pId);

    synchronized (neighbours) {
      long neighbourId = ((LongWritable) senderId).get();
      byte forkInfo = neighbours.get(neighbourId);
      forkInfo |= MASK_HAVE_FORK;
      neighbours.put(neighbourId, forkInfo);
      LOG.info("[[TESTING]] " + receiverId + ": got fork from " +
               senderId + " " + toString(forkInfo));
    }

    // signal fork arrival
    // TODO-YH: skip for local???
    cvLock.lock();
    getFork.signalAll();
    cvLock.unlock();
    LOG.info("[[TESTING]] " + receiverId + ": SENT signal");
  }

  /**
   * @param forkInfo Philosopher's state
   * @return True if have token
   */
  private boolean haveToken(byte forkInfo) {
    return (forkInfo & MASK_HAVE_TOKEN) != 0;
  }

  /**
   * @param forkInfo Philosopher's state
   * @return True if have fork
   */
  private boolean haveFork(byte forkInfo) {
    return (forkInfo & MASK_HAVE_FORK) != 0;
  }

  /**
   * @param forkInfo Philosopher's state
   * @return True if fork is dirty
   */
  private boolean isDirty(byte forkInfo) {
    return (forkInfo & MASK_IS_DIRTY) != 0;
  }

  /**
   * @param forkInfo Philosopher's state
   * @return String
   */
  private String toString(byte forkInfo) {
    return "(" + ((forkInfo & 0x4) >>> 2) +
      "," + ((forkInfo & 0x2) >>> 1) +
      "," + (forkInfo & 0x1) + ")";
  }
}
