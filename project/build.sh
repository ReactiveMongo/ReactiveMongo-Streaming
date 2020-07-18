#!/usr/bin/env bash
  
set -e

DIR=`dirname $0 | sed -e "s|^./|$PWD/|"`

cd "$DIR/.."

sbt +clean +makePom +packageBin +packageSrc +packageDoc
