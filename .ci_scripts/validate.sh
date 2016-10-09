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

# Sonatype staging (avoid Central sync delay)
perl -pe "s|resolvers |resolvers += \"Sonatype Staging\" at \"https://oss.sonatype.org/content/repositories/staging/\"\n\r\nresolvers |" < "$SCRIPT_DIR/../build.sbt" > /tmp/build.sbt && mv /tmp/build.sbt "$SCRIPT_DIR/../build.sbt"

R=0
for REPO in `curl -s https://oss.sonatype.org/content/repositories/ | grep 'href="https://oss.sonatype.org/content/repositories/orgreactivemongo' | cut -d '"' -f 2`; do
  perl -pe "s|resolvers |resolvers += \"Staging $R\" at \"$REPO\"\n\r\nresolvers |" < "$SCRIPT_DIR/../build.sbt" > /tmp/build.sbt && mv /tmp/build.sbt "$SCRIPT_DIR/../build.sbt"
done

if [ `sbt 'show version' 2>&1 | tail -n 1 | cut -d ' ' -f 2 | grep -- '-SNAPSHOT' | wc -l` -eq 1 ]; then
  perl -pe "s|resolvers |resolvers += \"Sonatype Snapshots\" at \"https://oss.sonatype.org/content/repositories/snapshots/\"\n\r\nresolvers |" < "$SCRIPT_DIR/../build.sbt" > /tmp/build.sbt && mv /tmp/build.sbt "$SCRIPT_DIR/../build.sbt"
fi

source "$SCRIPT_DIR/jvmopts.sh"

cat > /dev/stdout <<EOF
- JVM options: $JVM_OPTS
EOF

export JVM_OPTS

sbt ++$TRAVIS_SCALA_VERSION ";findbugs ;testOnly"
