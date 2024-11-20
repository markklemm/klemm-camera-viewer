#!/bin/bash

DEST_DIR="$HOME/klemm-camera-viewer"

M2_REPO="$HOME/.m2/repository"  # Default Maven repository location
JAVA_CMD="$JDK_DIR/bin/java"  # Path to Java executable (use "java" for OpenJDK from apt)

# Set the Maven and JDK versions
MAVEN_VERSION="3.9.5"
JDK_VERSION="23.0.1"

# Base download directories
LOCAL_DIR="$HOME/local"
MAVEN_DIR="$LOCAL_DIR/maven-$MAVEN_VERSION"
MAVEN_HOME=$MAVEN_DIR
JDK_DIR="$LOCAL_DIR/jdk-$JDK_VERSION"

# Create the local directory if it doesn't exist
rm -rf   "$LOCAL_DIR"
mkdir -p "$LOCAL_DIR"

# Classpath construction

# Add more JAR paths as needed...

# Download and install Maven
echo "Downloading Maven version $MAVEN_VERSION..."
MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"
rm -f "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz"
wget -q "$MAVEN_URL" -O "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz"

echo "Extracting Maven to $MAVEN_DIR..."
mkdir -p "$MAVEN_DIR"
tar -xvzf "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz" --strip-components=1 -C "$MAVEN_DIR"

# Clean up Maven tarball
rm -f "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz"
echo "Maven installed successfully in $MAVEN_DIR."

# Download and install OpenJDK
echo "Downloading OpenJDK version $JDK_VERSION..."
JDK_URL="https://download.java.net/java/GA/jdk${JDK_VERSION}/c28985cbf10d4e648e4004050f8781aa/11/GPL/openjdk-${JDK_VERSION}_linux-x64_bin.tar.gz"
rm -f "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz"
wget -q "$JDK_URL" -O "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz"

echo "Extracting OpenJDK to $JDK_DIR..."
mkdir -p "$JDK_DIR"
tar -xvzf "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz" --strip-components=1 -C "$JDK_DIR"

# Clean up JDK tarball
rm -f "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz"
echo "OpenJDK installed successfully in $JDK_DIR."

# Run Maven build commands
echo "Building project using Maven..."
$MAVEN_HOME/bin/mvn clean install

# Disable Screen Lock and Power Saving
xset s off
xset -dpms

# Run the Java application
echo "Starting application with ${JAVA_CMD}"
CLASSPATH="$DEST_DIR/target/classes:$DEST_DIR/target/lib/*"

$JAVA_CMD \
  -Dfile.encoding=UTF-8 \
  -Dstdout.encoding=UTF-8 \
  -Dstderr.encoding=UTF-8 \
  -classpath "$CLASSPATH" \
  -XX:+ShowCodeDetailsInExceptionMessages \
  klemm.technology.camera.RtspStreamViewer
