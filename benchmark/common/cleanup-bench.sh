#!/bin/bash

# Cleans up rogue stat programs created by bench-init,
# in the event that bench-finish was unable to run.
#
# Alternatively, one can run bench-finish by passing in
# the correct log name prefix to clean things up and get
# the worker machines' (incomplete) logs.

source "$(dirname "${BASH_SOURCE[0]}")"/get-hosts.sh

for ((i = 0; i <= ${_NUM_MACHINES}; i++)); do
    ssh ${_MACHINES[$i]} "kill \$(pgrep sar) & kill \$(pgrep free)" &
done
wait