#! /bin/bash

set -e

source /tmp/integration-env.sh

export LD_LIBRARY_PATH

SCRIPT_DIR=`dirname $0 | sed -e "s|^\./|$PWD/|"`

cd "$SCRIPT_DIR/.."

sbt ++$TRAVIS_SCALA_VERSION scalariformFormat test:scalariformFormat
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

# Silly workaround
# scala.reflect.internal.Types$TypeError: illegal cyclic inheritance involving <refinement>
> akka-stream/src/test/scala/GridFSSpec.scala

TEST_ARGS=";findbugs ;mimaReportBinaryIssues ;update ;test:compile ;doc"

if [ "v$TRAVIS_SCALA_VERSION" = "v2.12.10" ]; then
    TEST_ARGS=";scapegoat $TEST_ARGS"
fi

sbt ++$TRAVIS_SCALA_VERSION "$TEST_ARGS"

# Silly workaround
# scala.reflect.internal.Types$TypeError: illegal cyclic inheritance involving <refinement>
git checkout akka-stream/src/test/scala/GridFSSpec.scala
sbt ++$TRAVIS_SCALA_VERSION testOnly
