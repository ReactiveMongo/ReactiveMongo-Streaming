JVM_MAX_MEM="2048M"

# See .jvmopts
JVM_OPTS="-Xms$JVM_MAX_MEM -Xmx$JVM_MAX_MEM"
JVM_OPTS="$JVM_OPTS -XX:MetaspaceSize=512M -XX:MaxMetaspaceSize=512M"

export _JAVA_OPTIONS="$JVM_OPTS"
