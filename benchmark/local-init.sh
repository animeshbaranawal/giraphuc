#!/bin/bash -e

# Initialize Hadoop and all systems for local testing.
#
# This is for LOCAL TESTING only!! Ensure that:
#   1. LOCAL_MACHINES is the number of pseudo-machines you want.
#   2. ./common/get-dirs.sh has a correct DIR_PREFIX
#   3. ./common/get-config.sh has correct JVM Xmx sizes

# number of pseudo machines to use
# adjust JVM Xmx accordingly to avoid running out of memory!
LOCAL_MACHINES=3


cd "$(dirname "${BASH_SOURCE[0]}")"
source ./common/get-dirs.sh
source ./common/get-configs.sh

echo "Generating get-hosts.sh..."
echo '#!/bin/bash

# Set the prefix name and number of slaves/worker machines.
# NOTE: This file is automatically generated by local-init.sh!

_MACHINES=($(hostname))
_NUM_MACHINES=0' > ./common/get-hosts.sh

source ./common/get-hosts.sh


echo "Updating Hadoop configs..."
./hadoop/init.sh > /dev/null      # quiet

# for local testing, need to create slave manually
#
# Note: we can use multiple localhosts in _MACHINES, but
# that starts up a bunch of sar instances on a single machine,
# which is largely meaningless
rm -f "$HADOOP_DIR"/conf/slaves
for ((i = 1; i <= ${LOCAL_MACHINES}; i++)); do
    echo "localhost" >> "$HADOOP_DIR"/conf/slaves
done

###############
# Hadoop
###############
# remove old HDFS data (on master and worker machines)
# NOTE: removing HDFS folder will kill targets of symlinks in logs/userlogs/
echo "Removing old HDFS data and Hadoop logs..."

stop-all.sh > /dev/null   # just in case anything is running

rm -rf "$HADOOP_DATA_DIR"
rm -rf "$HADOOP_DIR"/logs/*

# create new HDFS & start Hadoop
echo "Creating new HDFS..."
hadoop namenode -format

echo "Starting up Hadoop..."
start-all.sh

# wait until Hadoop starts up (HDFS exits safemode)
echo "Waiting for Hadoop to start..."
hadoop dfsadmin -safemode wait > /dev/null

# NOTE: for some reason HDFS is still not ready after safemode is off,
# so sleep for 30s to ensure GPS init will succeed
sleep 30

###############
# Systems
###############
# NOTE: we're duplicating each system's init.sh file...
# It's a little messy but avoids cluttering up the existing files

# nothing to do for Giraph

echo "Initializing GraphLab..."
rm -f ./graphlab/machines
for ((i = 1; i <= ${LOCAL_MACHINES}; i++)); do
    echo "localhost" >> ./graphlab/machines
done


###############
# Datasets
###############
hadoop dfs -mkdir ./input || true
#echo "Loading datasets..."
#./datasets/load-files.sh