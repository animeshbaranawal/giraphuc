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

package org.apache.giraph.io.superstep_output;

import org.apache.giraph.io.SimpleVertexWriter;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;

import java.io.IOException;

/**
 * Interface for outputing data during the computation.
 *
 * @param <I> Vertex id
 * @param <V> Vertex value
 * @param <E> Edge value
 */
public interface SuperstepOutput<I extends WritableComparable,
    V extends Writable, E extends Writable> {

  /**
   * Get the Writer. You have to return it after usage in order for it to be
   * properly closed.
   *
   * @return SimpleVertexWriter
   */
  SimpleVertexWriter<I, V, E> getVertexWriter();

  /**
   * Return the Writer after usage, which you got by calling
   * {@link #getVertexWriter()}
   *
   * @param vertexWriter SimpleVertexWriter which you are returning
   */
  void returnVertexWriter(SimpleVertexWriter<I, V, E> vertexWriter);

  /**
   * Finalize this output in the end of the application
   */
  void postApplication() throws IOException, InterruptedException;
}
