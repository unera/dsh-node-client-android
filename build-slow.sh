#!/bin/sh
# Build without hogging the host.
#
# The machine has three cores and three gigabytes, shared with the assistant this app talks to.
# `nice` lowers the priority and `taskset` keeps the build on two cores, so the other work stays
# responsive; one worker and no daemon finish the rest of the job. It is slower on purpose.
set -e
exec nice -n 19 taskset -c 0-1 ./gradlew "$@" --no-daemon --max-workers=1
