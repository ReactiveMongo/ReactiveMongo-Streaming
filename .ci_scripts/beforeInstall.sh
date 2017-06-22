#! /bin/sh

# Clean cache
rm -rf "$HOME/.ivy2/local/org.reactivemongo"

# Prepare integration env

SCRIPT_DIR=`dirname $0 | sed -e "s|^\./|$PWD/|"`
MONGO_VER="3"

cat > /dev/stdout <<EOF
MongoDB major version: $MONGO_VER
EOF

MAX_CON=`ulimit -n`

if [ $MAX_CON -gt 1024 ]; then
    MAX_CON=`expr $MAX_CON - 1024`
fi

echo "Max connection: $MAX_CON"

# OpenSSL
if [ ! -L "$HOME/ssl/lib/libssl.so.1.0.0" ]; then
  cd /tmp
  curl -s -o - https://www.openssl.org/source/openssl-1.0.1s.tar.gz | tar -xzf -
  cd openssl-1.0.1s
  rm -rf "$HOME/ssl" && mkdir "$HOME/ssl"
  ./config -shared enable-ssl2 --prefix="$HOME/ssl" > /dev/null
  make depend > /dev/null
  make install > /dev/null
else
  rm -f "$HOME/ssl/lib/libssl.so.1.0.0" "libcrypto.so.1.0.0"
fi

ln -s "$HOME/ssl/lib/libssl.so.1.0.0" "$HOME/ssl/lib/libssl.so.10"
ln -s "$HOME/ssl/lib/libcrypto.so.1.0.0" "$HOME/ssl/lib/libcrypto.so.10"

export LD_LIBRARY_PATH="$HOME/ssl/lib:$LD_LIBRARY_PATH"

# Build MongoDB
if [ "$MONGO_VER" = "3" ]; then
    if [ ! -x "$HOME/mongodb-linux-x86_64-amazon-3.4.5/bin/mongod" ]; then
        curl -s -o /tmp/mongodb.tgz https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-amazon-3.4.5.tgz
        cd "$HOME" && rm -rf mongodb-linux-x86_64-amazon-3.4.5
        tar -xzf /tmp/mongodb.tgz && rm -f /tmp/mongodb.tgz
        chmod u+x mongodb-linux-x86_64-amazon-3.4.5/bin/mongod
    fi

    export PATH="$HOME/mongodb-linux-x86_64-amazon-3.4.5/bin:$PATH"
    cp "$SCRIPT_DIR/mongod3.conf" /tmp/mongod.conf

    echo "  maxIncomingConnections: $MAX_CON" >> /tmp/mongod.conf
else
    if [ ! -x "$HOME/mongodb-linux-x86_64-2.6.12/bin/mongod" ]; then
        curl -s -o /tmp/mongodb.tgz https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-2.6.12.tgz
        cd "$HOME" && rm -rf mongodb-linux-x86_64-2.6.12
        tar -xzf /tmp/mongodb.tgz && rm -f /tmp/mongodb.tgz
        chmod u+x mongodb-linux-x86_64-2.6.12/bin/mongod
    fi

    export PATH="$HOME/mongodb-linux-x86_64-2.6.12/bin:$PATH"
    cp "$SCRIPT_DIR/mongod26.conf" /tmp/mongod.conf

    echo "  maxIncomingConnections: $MAX_CON" >> /tmp/mongod.conf
fi

mkdir /tmp/mongodb

# MongoDB
echo "# MongoDB Configuration:"
cat /tmp/mongod.conf

numactl --interleave=all mongod -f /tmp/mongod.conf --port 27017 --fork

MONGOD_PID=`ps -o pid,comm -u $USER | grep 'mongod$' | awk '{ printf("%s\n", $1); }'`

if [ "x$MONGOD_PID" = "x" ]; then
    echo -e "\nERROR: Fails to start the custom 'mongod' instance" > /dev/stderr

    mongod --version
    PID=`ps -o pid,comm -u $USER | grep 'mongod$' | awk '{ printf("%s\n", $1); }'`

    if [ ! "x$PID" = "x" ]; then
        pid -p $PID
    else
        echo "ERROR: MongoDB process not found" > /dev/stderr
    fi

    tail -n 100 /tmp/mongod.log

    exit 1
fi

# Export environment for integration tests

cat > /tmp/integration-env.sh <<EOF
PATH="$PATH"
LD_LIBRARY_PATH="$LD_LIBRARY_PATH"
EOF
