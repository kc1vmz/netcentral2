#!/bin/bash
# Net Central Installer
# Copyright (c) 2026 John Rokicki KC1VMZ
# 
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
# 
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
# 
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# http://www.kc1vmz.com
# 
NC_UPGRADE=N
NC_OS=$(uname)
if [ "$NC_OS" = "Linux" ]; then
  echo "Welcome to the Net Central installer for Linux"
  echo "Copyright (c) 2026 John Rokicki KC1VMZ - Licensed under GPL v3"
  echo "https://www.kc1vmz.com"

  if grep -q NC_INSTALLATION_DIR /etc/environment; then
    echo "A previous version of Net Central was detected."
    NC_UPGRADE=Y
    read -e -i $NC_UPGRADE -p "Do you wish to upgrade Net Central?: " NC_UPGRADE
    if [[ "$NC_UPGRADE" =~ ^[Nn]$ ]]; then
      echo "Net Central installation will now exit."
      exit 1
    fi
  fi

  # default environment variables
  NC_VERSION=2.0.0
  NC_INSTALLATION_DIR=~/netcentral
  NC_DB_DIR=~/netcentral/db
  NC_TEMP_DIR=~/netcentral/tmp
  NC_PORT=8080
  NC_INSTALL_SERVICES=Y

  if [[ "$NC_INSTALL_SERVICES" =~ ^[Yy]$ ]]; then
    read -e -i $NC_VERSION -p "What version of Net Central?: " NC_VERSION
    if [[ "$NC_UPGRADE" =~ ^[Nn]$ ]]; then
      read -e -i $NC_INSTALLATION_DIR -p "Where should Net Central be installed?: " NC_INSTALLATION_DIR
      read -e -i $NC_DB_DIR -p "Where should Net Central database space be located?: " NC_DB_DIR
      read -e -i $NC_TEMP_DIR -p "Where should Net Central temp space be located?: " NC_TEMP_DIR
      read -e -i $NC_PORT -p "What port should the Net Central HTTP service listen on?: " NC_PORT
      read -e -i $NC_INSTALL_SERVICES -p "Do you want Net Central to be configured as services and started at boot time (Y/n)?: " NC_INSTALL_SERVICES
    else
      NC_INSTALLATION_DIR=$(grep "^NC_INSTALLATION_DIR=" /etc/environment | sed 's/^NC_INSTALLATION_DIR=//' | tr -d '"')
      NC_INSTALLATION_DIR=$NC_INSTALLATION_DIR
    fi
  fi

  sudo cp /etc/environment /etc/environment.pre_nc_install

  JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{sub("^$", "0", $2); print $1$2}')
  if [ "$JAVA_VER" -ge 21 ]; then
    # fall through
    echo "Java 21+ verified"
  else
    echo "Java 21 or greater not installed - exiting."
    sudo rm /etc/environment.pre_nc_install
    exit 1
  fi

  NC_INSTALLATION_DIR=$NC_INSTALLATION_DIR
  if [[ "$NC_UPGRADE" =~ ^[Nn]$ ]]; then
    echo "NC_INSTALLATION_DIR=$NC_INSTALLATION_DIR" | sudo tee -a /etc/environment >  /dev/null
    echo "NC_DB_DIR=$NC_DB_DIR" | sudo tee -a /etc/environment >  /dev/null
    echo "NC_HTTP_PORT=$NC_PORT" | sudo tee -a /etc/environment >  /dev/null

    mkdir $NC_INSTALLATION_DIR
    sudo mkdir $NC_TEMP_DIR
    sudo chown $USER $NC_TEMP_DIR
    sudo mkdir $NC_DB_DIR
    sudo chown $USER $NC_DB_DIR
  fi

  echo "Retrieving Net Central binary"
  cd $NC_INSTALLATION_DIR
  NC_SRC_URL_ROOT=https://github.com/kc1vmz/netcentral2/releases/download/v$NC_VERSION
  wget -q -O netcentral-$NC_VERSION.jar $NC_SRC_URL_ROOT/netcentral-$NC_VERSION.jar
  NC_SRC_URL_ROOT=

  chmod +x netcentral-$NC_VERSION.jar

  if [[ "$NC_UPGRADE" =~ ^[Nn]$ ]]; then
    NC_DB_DIR=$NC_DB_DIR
    NC_TEMP_DIR=$NC_TEMP_DIR
    NC_HTTP_PORT=$NC_PORT
  else
    if [ -n "/etc/systemd/system/netcentral.service+x" ]; then
      NC_INSTALL_SERVICES=Y
    else
      NC_INSTALL_SERVICES=N
    fi
  fi

  if [[ "$NC_INSTALL_SERVICES" =~ ^[Yy]$ ]]; then
    if [[ "$NC_UPGRADE" =~ ^[Nn]$ ]]; then
      echo '[Unit]' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'Description=Net Central' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo '[Service]' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'User='$LOGNAME | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'WorkingDirectory='$NC_INSTALLATION_DIR | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'Type=simple' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'Restart=always' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'SuccessExitStatus=143' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'RemainAfterExit=yes' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'ExecStart=java -jar '$NC_INSTALLATION_DIR'/netcentral-'$NC_VERSION'.jar' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'Environment=NC_DB_DIR='$NC_DB_DIR | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'Environment=NC_TEMP_DIR='$NC_TEMP_DIR | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'Environment=NC_HTTP_PORT='$NC_PORT | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'Environment=NC_INSTALLATION_DIR='$NC_INSTALLATION_DIR | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo '[Install]' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      echo 'WantedBy=multi-user.target' | sudo tee -a /etc/systemd/system/netcentral.service >  /dev/null
      sudo systemctl daemon-reload
      sudo systemctl enable netcentral
      sudo systemctl start netcentral
      echo "Net Central running as background service."
    else
      sudo systemctl stop netcentral
      sudo sed -i 's/netcentral-.*.jar/netcentral-'$NC_VERSION'.jar/g' /etc/systemd/system/netcentral.service
      sudo systemctl daemon-reload
      sudo systemctl start netcentral
      echo "Net Central restarted."
    fi
  else
    echo "Environment variables have been added to /etc/environment.  Make sure to reboot or run 'source /etc/environment' to add them into any new terminal."
    echo "You can run Net Central using the  'java -jar $NC_INSTALLATION_DIR/netcentral-$NC_VERSION.jar' command"
  fi

  # cleanup environment
  NC_VERSION=
  NC_INSTALL_SERVICES=
  NC_UPGRADE=
elif [ "$NC_OS" = "Darwin" ]; then
  echo "This is a Mac Machine - not yet supported"
  exit 1
else
  echo "Unsupported OS -" $NC_OS
  exit 1
fi

echo "Net Central installation complete."