#!/bin/bash


# place a copy of this script in the DEST_DIR folder
# RUN chmod +x checkout_build_run.sh

# RUN see "Setup Kiosk Mode" - create auto login and auto start
# mkdir -p ~/.config/autostart
# cp camera.desktop ~/.config/autostart/


# Variables
REPO_URL="https://github.com/markklemm/klemm-camera-viewer.git"
BRANCH_NAME="develop"  # Replace with the desired branch name
DEST_DIR="$HOME/klemm-camera-viewer"
M2_REPO="$HOME/.m2/repository"  # Default Maven repository location
JAVA_CMD="$JDK_DIR/bin/java"  # Path to Java executable (use "java" for OpenJDK from apt)

# Set the Maven and JDK versions
MAVEN_VERSION="3.9.5"
JDK_VERSION="23"

SCRIPT_DIR=$(dirname "$(realpath "$0")")

if [[ "$SCRIPT_DIR" == "$(realpath "$DEST_DIR")" ]]; then
  echo "Error: Script is located inside $DEST_DIR. Moving it to a temporary location and re-running."

  TMP_SCRIPT=$(mktemp)
  cp "$0" "$TMP_SCRIPT"

  chmod +x $TMP_SCRIPT
  bash "$TMP_SCRIPT" "$@"  # Pass along any arguments the script was run with
  rm "$TMP_SCRIPT"

  exit 0  # Exit successfully since the script continues from the temporary copy
fi

# Base download directories
LOCAL_DIR="$HOME/local"
MAVEN_DIR="$LOCAL_DIR/maven-$MAVEN_VERSION"
MAVEN_HOME=$MAVEN_DIR
JDK_DIR="$LOCAL_DIR/jdk-$JDK_VERSION"

# Create the local directory if it doesn't exist
rm -rf   "$LOCAL_DIR"
mkdir -p "$LOCAL_DIR"

# Download and install Maven
echo "Downloading Maven version $MAVEN_VERSION..."
MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"
wget -q "$MAVEN_URL" -O "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz"

echo "Extracting Maven to $MAVEN_DIR..."
mkdir -p "$MAVEN_DIR"
tar -xvzf "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz" --strip-components=1 -C "$MAVEN_DIR"

# Clean up Maven tarball
rm "/tmp/apache-maven-$MAVEN_VERSION-bin.tar.gz"
echo "Maven installed successfully in $MAVEN_DIR."

# Download and install OpenJDK
echo "Downloading OpenJDK version $JDK_VERSION..."
JDK_URL="https://download.java.net/java/GA/jdk${JDK_VERSION}/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz"
wget -q "$JDK_URL" -O "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz"

echo "Extracting OpenJDK to $JDK_DIR..."
mkdir -p "$JDK_DIR"
tar -xvzf "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz" --strip-components=1 -C "$JDK_DIR"

# Clean up JDK tarball
rm "/tmp/jdk-${JDK_VERSION}_linux-x64_bin.tar.gz"
echo "OpenJDK installed successfully in $JDK_DIR."

# Add environment variables to shell config
# echo "Updating environment variables..."
# SHELL_CONFIG="$HOME/.bashrc"
# {
#   echo ""
#   echo "# Maven configuration"
#   echo "export MAVEN_HOME=$MAVEN_DIR"
#   echo "export PATH=\$MAVEN_HOME/bin:\$PATH"
#   echo ""
#   echo "# OpenJDK configuration"
#   echo "export JAVA_HOME=$JDK_DIR"
#   echo "export PATH=\$JAVA_HOME/bin:\$PATH"
# } >> "$SHELL_CONFIG"

# echo "Done! Please run 'source ~/.bashrc' or restart your terminal to apply changes."




# Classpath construction
CLASSPATH="$DEST_DIR/target/classes"
CLASSPATH+=":$M2_REPO/org/bytedeco/ffmpeg-platform/6.1.1-1.5.10/ffmpeg-platform-6.1.1-1.5.10.jar"
CLASSPATH+=":$M2_REPO/org/bytedeco/javacpp-platform/1.5.10/javacpp-platform-1.5.10.jar"
CLASSPATH+=":$M2_REPO/org/bytedeco/ffmpeg/6.1.1-1.5.10/ffmpeg-6.1.1-1.5.10.jar"
CLASSPATH+=":$M2_REPO/org/bytedeco/javacv/1.5.10/javacv-1.5.10.jar"
CLASSPATH+=":$M2_REPO/org/bytedeco/opencv/4.9.0-1.5.10/opencv-4.9.0-1.5.10.jar"
CLASSPATH+=":$M2_REPO/org/bytedeco/libdc1394/2.2.6-1.5.9/libdc1394-2.2.6-1.5.9.jar"
CLASSPATH+=":$M2_REPO/org/bytedeco/libfreenect/0.5.7-1.5.9/libfreenect-0.5.7-1.5.9.jar"
# Add more JAR paths as needed...

# Clean up any existing repository directory
if [ -d "$DEST_DIR" ]; then
  echo "Removing existing project directory at $DEST_DIR..."
  rm -rf "$DEST_DIR"
fi

# Clone the repository and check out the specified branch
echo "Cloning repository..."
git clone -b "$BRANCH_NAME" "$REPO_URL" "$DEST_DIR"
cd "$DEST_DIR" || exit 1

chmod +x checkout_build_run.sh

# Run Maven build commands
echo "Building project using Maven..."
$MAVEN_HOME/bin/mvn clean install

# Disable Screen Lock and Power Saving
xset s off
xset -dpms

# Run the Java application
echo "Starting application..."
$JAVA_CMD \
  -Dfile.encoding=UTF-8 \
  -Dstdout.encoding=UTF-8 \
  -Dstderr.encoding=UTF-8 \
  -classpath "$CLASSPATH" \
  -XX:+ShowCodeDetailsInExceptionMessages \
  klemm.technology.camera.RtspStreamViewer
