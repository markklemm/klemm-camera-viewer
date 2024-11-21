#!/bin/bash

DEST_DIR="$HOME/klemm-camera-viewer"

# Set the Maven and JDK versions
MAVEN_VERSION="3.9.5"
JDK_VERSION="23.0.1"

# Base download directories
LOCAL_DIR="$HOME/local"

MAVEN_DIR="$LOCAL_DIR/maven-$MAVEN_VERSION"
JDK_DIR="$LOCAL_DIR/jdk-$JDK_VERSION"

M2_REPO="$HOME/.m2/repository"  # Default Maven repository location

# Create the local directory if it doesn't exist
mkdir -p "$LOCAL_DIR"

# Check and install Maven if not already installed
if [ ! -d "$MAVEN_DIR" ]; then
  echo "Maven version $MAVEN_VERSION not found. Downloading..."
  MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  wget -q "$MAVEN_URL" -O "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  echo "Extracting Maven to $MAVEN_DIR..."
  mkdir -p "$MAVEN_DIR"
  tar -xvzf "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz" --strip-components=1 -C "$MAVEN_DIR"
  rm -f "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  echo "Maven installed successfully in $MAVEN_DIR."
else
  echo "Maven version $MAVEN_VERSION already installed at $MAVEN_DIR."
fi

# Check and install OpenJDK if not already installed
if [ ! -d "$JDK_DIR" ]; then
  echo "OpenJDK version $JDK_VERSION not found. Downloading..."
  JDK_URL="https://download.java.net/java/GA/jdk${JDK_VERSION}/c28985cbf10d4e648e4004050f8781aa/11/GPL/openjdk-${JDK_VERSION}_linux-x64_bin.tar.gz"
  wget -q "$JDK_URL" -O "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz"
  echo "Extracting OpenJDK to $JDK_DIR..."
  mkdir -p "$JDK_DIR"
  tar -xvzf "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz" --strip-components=1 -C "$JDK_DIR"
  rm -f "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz"
  echo "OpenJDK installed successfully in $JDK_DIR."
else
  echo "OpenJDK version $JDK_VERSION already installed at $JDK_DIR."
fi

# Set environment variables
export JAVA_HOME="$JDK_DIR"
echo "Set JAVA_HOME to $JAVA_HOME"

export MAVEN_HOME="$MAVEN_DIR"
echo "Set MAVEN_HOME to $MAVEN_HOME"

# Run Maven build commands
echo "Building project using Maven..."
$MAVEN_DIR/bin/mvn clean install

# Disable Screen Lock and Power Saving
xset s off
xset -dpms


JAVA_CMD="$JDK_DIR/bin/java"  # Path to Java executable (use "java" for OpenJDK from apt)
# Run the Java application
echo "Starting application with ${JAVA_CMD}"
CLASSPATH="$DEST_DIR/target/classes:$DEST_DIR/target/lib/*"

$JAVA_CMD                 \
  -splash:splash.png      \
  -Dfile.encoding=UTF-8   \
  -Dstdout.encoding=UTF-8 \
  -Dstderr.encoding=UTF-8 \
  -classpath "$CLASSPATH" \
  -XX:+ShowCodeDetailsInExceptionMessages \
  klemm.technology.camera.RtspStreamViewer &
