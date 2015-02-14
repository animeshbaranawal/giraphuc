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

import org.apache.giraph.conf.ImmutableClassesGiraphConfiguration;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.graph.VertexValueCombiner;
import org.apache.giraph.partition.VertexTypeStore.VertexType;
import org.apache.giraph.utils.VertexIterator;
import org.apache.giraph.worker.WorkerInfo;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.util.Progressable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Basic partition class for other partitions to extend. Holds partition id,
 * configuration, progressable and partition context
 *
 * @param <I> Vertex index value
 * @param <V> Vertex value
 * @param <E> Edge value
 */
public abstract class BasicPartition<I extends WritableComparable,
    V extends Writable, E extends Writable>
    implements Partition<I, V, E> {
  /** Configuration from the worker */
  private ImmutableClassesGiraphConfiguration<I, V, E> conf;
  /** Partition id */
  private int id;
  /** Context used to report progress */
  private Progressable progressable;
  /** Vertex value combiner */
  private VertexValueCombiner<V> vertexValueCombiner;

  @Override
  public void initialize(int partitionId, Progressable progressable) {
    setId(partitionId);
    setProgressable(progressable);
    vertexValueCombiner = conf.createVertexValueCombiner();
  }

  @Override
  public void setConf(
      ImmutableClassesGiraphConfiguration<I, V, E> configuration) {
    conf = configuration;
  }

  @Override
  public ImmutableClassesGiraphConfiguration<I, V, E> getConf() {
    return conf;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void setId(int id) {
    this.id = id;
  }

  @Override
  public void progress() {
    if (progressable != null) {
      progressable.progress();
    }
  }

  @Override
  public void setProgressable(Progressable progressable) {
    this.progressable = progressable;
  }

  public VertexValueCombiner<V> getVertexValueCombiner() {
    return vertexValueCombiner;
  }

  @Override
  public void addPartitionVertices(VertexIterator<I, V, E> vertexIterator) {
    while (vertexIterator.hasNext()) {
      vertexIterator.next();
      Vertex<I, V, E> vertex = vertexIterator.getVertex();

      // YH: vertices are added to their partitions AFTER they are
      // transferred to their correct owner. Hence, this ensures only
      // owning worker knows which of its vertices are boundary.
      //
      // This also avoids needing to store boolean for every vertex.
      //
      // NOTE: Though this doesn't currently support mutations, it can.
      // To support mutations, we need to augment Vertex's edge mutation
      // functions with "is-boundary?" rechecks. (If boolean is added
      // to Vertex, need to modify WritableUtils as well.)
      if (getConf().getAsyncConf().tokenSerialized()) {
        boolean isRemoteBoundary = false;
        boolean isLocalBoundary = false;

        WorkerInfo myWorker = getConf().getServiceWorker().getWorkerInfo();
        VertexTypeStore vtStore =
          getConf().getServiceWorker().getVertexTypeStore();

        // TODO-YH: assumes undirected graph... for directed graph,
        // need do broadcast to all neighbours
        for (Edge<I, E> e : vertex.getEdges()) {
          PartitionOwner dstOwner = getConf().getServiceWorker().
            getVertexPartitionOwner(e.getTargetVertexId());

          int dstPartitionId = dstOwner.getPartitionId();
          WorkerInfo dstWorker = dstOwner.getWorkerInfo();

          // check if neighbour is remote; if not,
          // check if neighbour is in another local partition
          // id is this (vertex's) partition id
          if (!myWorker.equals(dstWorker)) {
            isRemoteBoundary = true;
          } else if (dstPartitionId != id) {
            isLocalBoundary = true;
          }

          // need to check all edges before concluding vertex
          // is ONLY local or remote boundary, but if it's
          // already both, we can quit early
          if (isRemoteBoundary && isLocalBoundary) {
            break;
          }
        }

        if (!isRemoteBoundary && !isLocalBoundary) {
          vtStore.setVertexType(vertex.getId(), VertexType.INTERNAL);
        } else if (!isRemoteBoundary && isLocalBoundary) {
          vtStore.setVertexType(vertex.getId(), VertexType.LOCAL_BOUNDARY);
        } else if (isRemoteBoundary && !isLocalBoundary) {
          vtStore.setVertexType(vertex.getId(), VertexType.REMOTE_BOUNDARY);
        } else {
          // not actually necessary
          vtStore.setVertexType(vertex.getId(), VertexType.BOTH_BOUNDARY);
        }

      } else if (getConf().getAsyncConf().lockSerialized()) {
        // TODO-YH: assumes undirected graph... for directed graph,
        // need do broadcast to all neighbours
        for (Edge<I, E> e : vertex.getEdges()) {
          int dstPartitionId = getConf().getServiceWorker().
            getVertexPartitionOwner(e.getTargetVertexId()).getPartitionId();

          if (dstPartitionId != id) {
            getConf().getServiceWorker().getPhilosophersTable().
              addBoundaryVertex(vertex);
            break;
          }
        }
      }

      // Release the vertex if it was put, otherwise reuse as an optimization
      if (putOrCombine(vertex)) {
        vertexIterator.releaseVertex();
      }
    }
  }

  @Override
  public void write(DataOutput output) throws IOException {
    output.writeInt(id);
  }

  @Override
  public void readFields(DataInput input) throws IOException {
    id = input.readInt();
  }
}
