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
package org.apache.giraph.examples.okapi.graphs;

import java.io.IOException;
import java.util.HashSet;

import org.apache.giraph.examples.okapi.common.computation.SendFriends;
import org.apache.giraph.examples.okapi.common.data.LongArrayListWritable;
import org.apache.giraph.examples.okapi.common.data.MessageWrapper;
import org.apache.giraph.examples.okapi.utils.Counters;

import org.apache.giraph.aggregators.DoubleSumAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.BasicComputation;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;

/**
 * The clustering coefficient is used to measure how well vertices are connected
 * to each other. There are two types of the clustering coefficient metric:
 *
 * The <i>local clustering coefficient</i> is defined for each vertex and
 * measures how close a vertex and its neighbors are to a clique. Given a vertex
 * <i>v</i>, the clustering coefficient <code>C(v)</code> for an undirected
 * graph is defined as:
 * <p align="center">
 * <code>C(v) = N/N<sub>max</sub></code>
 * </p>
 * where <code>N</code> is the number of edges between vertices that are
 * neighbors of <code>v</code> and <code>N<sub>max</sub></code> is the
 * maximum possible number of edges between vertices that are neighbors
 * of <code>v</code>.
 *
 * If <code>k</code> is the number of neighbors of <code>v</code> then in
 * directed graphs:
 * <p align="center">
 * <code>C(v) = N/(k*(k-1))</code>
 * </p>
 *
 * In undirected graphs:
 * <p align="center">
 * <code>C(v) = N/(k*(k-1)/2)</code>
 * </p>
 *
 * <p>
 * The <i>global clustering coefficient</i> is used to measure the overall
 * clustering of the vertices in the graph and is defined as the average of the
 * local clustering coefficients across all vertices in a graph.
 * </p>
 *
 * <p>
 * This implementation computes the local clustering coefficient for every
 * vertex in the graph as well as the global clustering coefficient.
 *
 * The output of the computation has the format:
 * vertexId cl_coefficient.
 *
 * The global clustering coefficient is set as a Hadoop counter. You can check
 * its value in the standard output of the terminal or in the Hadoop web
 * interface.
 *
 * This computation works for both directed and undirected graphs.
 *
 * </p>
 *
 * <p>
 * http://en.wikipedia.org/wiki/Clustering_coefficient
 * </p>
 */
public class ClusteringCoefficient {

  /**
   * Used to aggregate the local clustering coefficients, and compute the
   * global one.
   */
  private static final String CL_COEFFICIENT_AGGREGATOR =
    "coefficient.aggregator";

  /**
   * Aggregator used to store the global clustering coefficient.
   */
  private static final String GLOBAL_CLUSTERING_COEFFICIENT =
      "global.clustering.coefficient";

  /** */
  private static final String COUNTER_GROUP = "Clustering Coefficient";
  /** */
  private static final String COUNTER_NAME = "Global (x1000)";

  /** Do not instantiate. */
  private ClusteringCoefficient() {
  }

  /**
   * Does something.
   */
  public static class SendFriendsList extends SendFriends<LongWritable,
    DoubleWritable, NullWritable, LongIdFriendsList> {
  }

  /**
   * Does something.
   */
  public static class ClusteringCoefficientComputation extends
    BasicComputation<LongWritable, DoubleWritable,
    NullWritable, LongIdFriendsList> {

    @Override
    public void compute(
      Vertex<LongWritable, DoubleWritable, NullWritable> vertex,
      Iterable<LongIdFriendsList> messages)
      throws IOException {

      // Add the friends of this vertex in a HashSet so that we can check
      // for the existence of triangles quickly.
      HashSet<LongWritable> friends = new HashSet<LongWritable>();
      for (Edge<LongWritable, NullWritable> edge : vertex.getEdges()) {
        friends.add(new LongWritable(edge.getTargetVertexId().get()));
      }

      int edges = vertex.getNumEdges();
      int triangles = 0;
      for (LongIdFriendsList msg : messages) {
        for (LongWritable id : msg.getMessage()) {
          if (friends.contains(id)) {
            // Triangle found
            triangles++;
          }
        }
      }

      double clusteringCoefficient =
          ((double) triangles) / ((double) edges * (edges - 1));

      DoubleWritable clCoefficient = new DoubleWritable(clusteringCoefficient);
      aggregate(CL_COEFFICIENT_AGGREGATOR, clCoefficient);
      vertex.setValue(clCoefficient);
      vertex.voteToHalt();
    }
  }

  /**
   * Does something.
   */
  public static class LongIdFriendsList extends MessageWrapper<LongWritable,
    LongArrayListWritable> {

    @Override
    public Class<LongWritable> getVertexIdClass() {
      return LongWritable.class;
    }

    @Override
    public Class<LongArrayListWritable> getMessageClass() {
      return LongArrayListWritable.class;
    }
  }



  /**
   * Coordinates the execution of the algorithm.
   */
  public static class MasterCompute extends DefaultMasterCompute {

    @Override
    public final void initialize() throws InstantiationException,
        IllegalAccessException {

      registerAggregator(CL_COEFFICIENT_AGGREGATOR, DoubleSumAggregator.class);
    }

    @Override
    public final void compute() {
      long superstep = getSuperstep();
      if (superstep == 0) {
        setComputation(SendFriendsList.class);
      } else {
        setComputation(ClusteringCoefficientComputation.class);
      }
      if (superstep == 2) {
        double partialSum = ((DoubleWritable) getAggregatedValue(
            CL_COEFFICIENT_AGGREGATOR)).get();
        double globalCoefficient = partialSum / (double) getTotalNumVertices();
        Counters.updateCounter(getContext(), COUNTER_GROUP, COUNTER_NAME,
            (long) (1000 * globalCoefficient));
      }
    }
  }
}
