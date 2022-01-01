#! /bin/bash

set -e

source /tmp/integration-env.sh

export LD_LIBRARY_PATH

SCRIPT_DIR=`dirname $0 | sed -e "s|^\./|$PWD/|"`

cd "$SCRIPT_DIR/.."

if [ ! `echo "$SCALA_VERSION" | sed -e 's/^3\..*/3/'` = "3" ]; then
    sbt ++$SCALA_VERSION ';scalafixAll -check ;scalafmtAll'

    git diff --exit-code || (
        echo "ERROR: Scalafmt check failed, see differences above."
        echo "To fix, format your sources using ./build scalafmtAll before submitting a pull request."
        echo "Additionally, please squash your commits (eg, use git commit --amend) if you're going to update this pull request."
        false
    )
fi

source "$SCRIPT_DIR/jvmopts.sh"

cat > /dev/stdout <<EOF
- JVM options: $JVM_OPTS
EOF

export JVM_OPTS

TEST_ARGS=";error ;test:compile ;mimaReportBinaryIssues "
TEST_ARGS="$TEST_ARGS ;warn ;testOnly ;doc"

sbt ++$SCALA_VERSION "$TEST_ARGS"
