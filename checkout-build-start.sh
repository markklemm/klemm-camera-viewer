#!/bin/bash


# place a copy of this script in the DEST_DIR folder
# RUN chmod +x checkout-build-start.sh

# RUN see "Setup Kiosk Mode" - create auto login and auto start
# mkdir -p ~/.config/autostart
# cp camera.desktop ~/.config/autostart/

DEST_DIR="$HOME/klemm-camera-viewer"

# Variables
REPO_URL="https://github.com/markklemm/klemm-camera-viewer.git"
BRANCH_NAME="develop"  # Replace with the desired branch name

SCRIPT_DIR=$(dirname "$(realpath "$0")")

if [[ "$SCRIPT_DIR" == "$(realpath "$DEST_DIR")" ]]; then
  echo "Script is located inside $DEST_DIR. Moving it to a temporary location and re-running."

  TMP_SCRIPT=$(mktemp)
  cp "$0" "$TMP_SCRIPT"

  chmod +x $TMP_SCRIPT
  bash "$TMP_SCRIPT" "$@"  # Pass along any arguments the script was run with
  rm "$TMP_SCRIPT"

  exit 0  # Exit successfully since the script continues from the temporary copy
fi

# Clean up any existing repository directory
if [ -d "$DEST_DIR" ]; then
  echo "Removing existing project directory at $DEST_DIR..."
  rm -rf "$DEST_DIR"
fi

# Clone the repository and check out the specified branch
echo "Cloning repository..."
git clone -b "$BRANCH_NAME" "$REPO_URL" "$DEST_DIR"
cd "$DEST_DIR"                   || exit 1

# setup for next run
chmod +x checkout-build-start.sh || exit 1

#install an updated link
chmod +x ~/camera.desktop
rm -f ~/.local/share/applications/camera.desktop
cp ~/camera.desktop ~/.local/share/applications/

chmod +x build-start.sh          || exit 1
./build-start.sh                 || exit 1
