#! /bin/bash
set -euo pipefail

# Clean cache
rm -rf "$HOME/.ivy2/local/org.reactivemongo"

# Prepare integration env

SCRIPT_DIR=`dirname $0 | sed -e "s|^\./|$PWD/|"`
MONGO_VER="3"

cat > /dev/stdout <<EOF
MongoDB major version: $MONGO_VER
EOF

# OpenSSL
SSL_MAJOR="1.0.0"
SSL_SUFFIX="10"
SSL_RELEASE="1.0.2"
SSL_FULL_RELEASE="1.0.2u"
SSL_GH_TAG="OpenSSL_1_0_2u"
SSL_DL_URL="https://github.com/openssl/openssl/releases/download/${SSL_GH_TAG}/openssl-${SSL_FULL_RELEASE}.tar.gz"
SSL_HOME="$HOME/ssl"
SSL_LIB="$SSL_HOME/lib"

if [ ! -f "$SSL_LIB/libssl.so.$SSL_MAJOR" ] || [ ! -f "$SSL_LIB/libcrypto.so.$SSL_MAJOR" ]; then
  echo "[INFO] Building OpenSSL $SSL_MAJOR ..."

  cd /tmp

  echo "[INFO] Downloading OpenSSL from $SSL_DL_URL ..."
  curl -fL -s -o - "$SSL_DL_URL" | tar -xzf -

  cd "openssl-${SSL_FULL_RELEASE}"
  rm -rf "$SSL_HOME" && mkdir "$SSL_HOME"
  ./config -shared enable-ssl2 --prefix="$SSL_HOME" > /dev/null
  make depend > /dev/null
  make install > /dev/null
fi

if [ ! -d "$SSL_LIB" ] || [ ! -f "$SSL_LIB/libssl.so.$SSL_MAJOR" ] || [ ! -f "$SSL_LIB/libcrypto.so.$SSL_MAJOR" ]; then
  echo "[ERROR] OpenSSL libraries are missing in $SSL_LIB"
  exit 1
fi

ln -sf "$SSL_LIB/libssl.so.$SSL_MAJOR" "$SSL_LIB/libssl.so.$SSL_SUFFIX"
ln -sf "$SSL_LIB/libcrypto.so.$SSL_MAJOR" "$SSL_LIB/libcrypto.so.$SSL_SUFFIX"

export PATH="$SSL_HOME/bin:$PATH"
export LD_LIBRARY_PATH="$SSL_LIB:${LD_LIBRARY_PATH:-}"

# Build MongoDB
MONGO_MINOR="3.6.6"

# Build MongoDB
echo "[INFO] Building MongoDB ${MONGO_MINOR} ..."

cd "$HOME"

MONGO_ARCH="x86_64-amazon"
MONGO_HOME="$HOME/mongodb-linux-$MONGO_ARCH-$MONGO_MINOR"

if [ ! -x "$MONGO_HOME/bin/mongod" ]; then
    if [ -d "$MONGO_HOME" ]; then
      rm -rf "$MONGO_HOME"
    fi

    curl -s -o - "https://fastdl.mongodb.org/linux/mongodb-linux-$MONGO_ARCH-$MONGO_MINOR.tgz" | tar -xzf -
    chmod u+x "$MONGO_HOME/bin/mongod"
fi

if ldd "$MONGO_HOME/bin/mongod" | grep -q 'not found'; then
    echo "[ERROR] Missing shared libraries for $MONGO_HOME/bin/mongod"
    ldd "$MONGO_HOME/bin/mongod"
    exit 1
fi

echo "[INFO] MongoDB available at $MONGO_HOME"

PATH="$MONGO_HOME/bin:$PATH"

mkdir /tmp/mongodb

# MongoDB setup
MAX_CON=`ulimit -n`

if [ $MAX_CON -gt 1024 ]; then
    MAX_CON=`expr $MAX_CON - 1024`
fi

echo "[INFO] Max connection: $MAX_CON"

cp "$SCRIPT_DIR/mongod3.conf" /tmp/mongod.conf

echo "  maxIncomingConnections: $MAX_CON" >> /tmp/mongod.conf

echo "# MongoDB Configuration:"
cat /tmp/mongod.conf

# Export environment for integration tests

cat > /tmp/integration-env.sh <<EOF
PATH="$PATH"
LD_LIBRARY_PATH="$LD_LIBRARY_PATH"
EOF
