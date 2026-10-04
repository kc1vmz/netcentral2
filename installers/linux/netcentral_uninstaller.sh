#!/bin/bash
NC_OS=$(uname)
if [ "$NC_OS" = "Linux" ]; then
  echo "Welcome to the Net Central UNinstaller for Linux"

  # default environment variables
  NC_CONFIRM_UNINSTALL=Y

  read -e -i $NC_CONFIRM_UNINSTALL -p "Do you want to uninstall Net Central (Y/n)?: " NC_CONFIRM_UNINSTALL

  if [[ "$NC_CONFIRM_UNINSTALL" =~ ^[Yy]$ ]]; then
    # fall through
    echo Starting uninstall of Net Central
  else
    #any other answer - exit
    exit 1
  fi

  echo Backing up /etc/environment
  sudo cp /etc/environment /etc/environment.pre_nc_uninstall

  echo Removing environment variables from /etc/environment
  sudo sed -i '/NC_INSTALLATION_DIR/d' /etc/environment
  sudo sed -i '/NC_DB_DIR/d' /etc/environment
  sudo sed -i '/OSMTC_HTTP_PORT/d' /etc/environment

  echo Removing services
  sudo systemctl stop netcentral
  sudo systemctl disable netcentral
  sudo rm /etc/systemd/system/netcentral.service
  sudo systemctl daemon-reload

  if [ -n "${NC_INSTALLATION_DIR+x}" ]; then
    echo Deleting Net Central application
    sudo rm netcentral*.jar
    echo Not deleting ${NC_INSTALLATION_DIR} - delete independently.
  else
    echo Net Central installation directory unknown - delete independently.
  fi

  if [ -n "${NC_TEMP_DIR+x}" ]; then
    echo Not deleting ${NC_TEMP_DIR} - delete independently.
  else
    echo Net Central temporary directory unknown - delete independently.
  fi

  if [ -n "${NC_DB_DIR+x}" ]; then
    echo Not deleting ${NC_DB_DIR} - delete independently.
  else
    echo Net Central database directory unknown - delete independently.
  fi

  NC_CONFIRM_UNINSTALL=

elif [ "$NC_OS" = "Darwin" ]; then
  echo "This is a Mac Machine - not yet supported"
  exit 1
else
  echo "Unsupported OS -" $NC_OS
  exit 1
fi

echo "Net Central uninstallation complete."