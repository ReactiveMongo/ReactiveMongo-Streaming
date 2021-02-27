#! /bin/bash

set -e

source /tmp/integration-env.sh

export LD_LIBRARY_PATH

SCRIPT_DIR=`dirname $0 | sed -e "s|^\./|$PWD/|"`

cd "$SCRIPT_DIR/.."

sbt ++$SCALA_VERSION scalariformFormat test:scalariformFormat
git diff --exit-code || (
  echo "ERROR: Scalariform check failed, see differences above."
  echo "To fix, format your sources using ./build scalariformFormat test:scalariformFormat before submitting a pull request."
  echo "Additionally, please squash your commits (eg, use git commit --amend) if you're going to update this pull request."
  false
)

source "$SCRIPT_DIR/jvmopts.sh"

cat > /dev/stdout <<EOF
- JVM options: $JVM_OPTS
EOF

export JVM_OPTS

TEST_ARGS=";error ;test:compile ;findbugs ;mimaReportBinaryIssues "
TEST_ARGS="$TEST_ARGS ;warn ;testOnly ;doc"

if [ "v$SCALA_VERSION" = "v2.12.13" ]; then
    TEST_ARGS=";scapegoat $TEST_ARGS"
fi

sbt ++$SCALA_VERSION "$TEST_ARGS"
