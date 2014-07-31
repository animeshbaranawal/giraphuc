#!/bin/bash -e

if [ $# -ne 4 ]; then
    echo "usage: $0 input-graph machines edge-type source-vertex"
    echo ""
    echo "edge-type: 0 for byte array edges"
    echo "           1 for hash map edges"
    exit -1
fi

source ../common/get-dirs.sh
source ../common/get-configs.sh

# place input in /user/${USER}/input/
# output is in /user/${USER}/giraph-output/
inputgraph=$(basename $1)
outputdir=/user/${USER}/giraph-output/
hadoop dfs -rmr "$outputdir" || true

# Technically this is the number of "workers", which can be more
# than the number of machines. However, using multiple workers per
# machine is inefficient! Use more Giraph threads instead (see below).
machines=$2

edgetype=$3
case ${edgetype} in
    0) edgeclass="";;     # byte array edges are used by default
    1) edgeclass="-Dgiraph.inputOutEdgesClass=org.apache.giraph.edge.HashMapEdges \
                  -Dgiraph.outEdgesClass=org.apache.giraph.edge.HashMapEdges";;
    *) echo "Invalid edge-type"; exit -1;;
esac

src=$4

## log names
logname=sssp_${inputgraph}_${machines}_${edgetype}_"$(date +%Y%m%d-%H%M%S)"
logfile=${logname}_time.txt       # running time


## start logging memory + network usage
../common/bench-init.sh ${logname}

## start algorithm run
hadoop jar "$GIRAPH_DIR"/giraph-examples/target/giraph-examples-1.1.0-for-hadoop-1.0.4-jar-with-dependencies.jar org.apache.giraph.GiraphRunner \
    ${edgeclass} \
    -Dgiraph.metrics.enable=true \
    -Dgiraph.asyncLocalRead=true \
    -Dgiraph.asyncRemoteRead=true \
    -Dgiraph.numComputeThreads=${GIRAPH_THREADS} \
    -Dgiraph.numInputThreads=${GIRAPH_THREADS} \
    -Dgiraph.numOutputThreads=${GIRAPH_THREADS} \
    org.apache.giraph.examples.SimpleShortestPathsComputation \
    -ca SimpleShortestPathsComputation.sourceId=${src} \
    -vif org.apache.giraph.examples.io.formats.SimpleShortestPathsInputFormat \
    -vip /user/${USER}/input/${inputgraph} \
    -vof org.apache.giraph.io.formats.IdWithValueTextOutputFormat \
    -op "$outputdir" \
    -w ${machines} 2>&1 | tee -a ./logs/${logfile}

## finish logging memory + network usage
../common/bench-finish.sh ${logname}

## clean up step needed for Giraph
./kill-java-job.sh