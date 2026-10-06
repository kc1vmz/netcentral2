# Net Central

## What is this?

Net Central allows you to maintain situational awareness using APRS in emergency and non-emergency situations.  
  
Using an APRS net, you can coordinate and track all participants. Adding objects and providing well-known reporting information, you will enrich the APRS ecosystem with important near-time information.  

## How APRS nets work
  
Start a net and let people know about it. Nets can be announced as bulletins when started.  

Operators can then participate by checking in and out of a net by sending APRS messages to the callsign of the net.  

  
List of valid commands sent as APRS messages to the net callsign:

CI - Check in to net

CO - Check out of net

I - Send net info (name, voice frequency, description)

S status - report your status so other participants know what you are doing

STG - report your status color as green.

STY - report your status color as yellow.

STR - report your status color as red.

T callsign - set tactical callsign for net

F frequency - report your voice freqency that you are monitoring

X n - report your transmit power n in watts

E (BATTERY, COMMERCIAL, GENERATOR, NONE, SOLAR) - report your electrical power type

B (BATTERY, COMMERCIAL, GENERATOR, NONE, SOLAR) - report your backup electrical power type

Y (APPLIANCE, BASE, HANDHELD, INTERNET, MOBILE, OTHER) - report your radio style

M - send message to all participants in net

MC - send message to only net control

R n - replay last n messages sent to the net (n is a number)

L - list net participants, their callsigns, status, location, and voice freqency monitored

P callsign - ping callsign to test connectivity

O callsign - get known operational status of a station by its callsign

Q - send a question to all participants in net

A n - answer question n sent to the net

QL - send a list of unanswered questions

QR n - Report on question n and its answers

INV callsign[,callsign2,...] - invite one or more callsigns to a net  

NCI - toggle notification of all check-ins and check-outs (default OFF)

H - display help message of all commands

  
CI, CO, T, F, X, E, B, NCI and Y can all be combined in a single message (ex: CI X 50 E COMM B SO NCI)

  
You must be a registered user on Net Central to start and close nets, but any operator with APRS can participate!


## Build notes

Net Central is a Maven-based build.  

From the root directory, run "mvn clean package".  This will build all java components.

You may also use your favorite IDE that supports maven projects, such as VS Code.  


## Installation notes

Each release in Github has a netcentral_installer.sh / netcentral_installer.bat file  (also found under the installers directory).

Run the operating system specific version to download, install and configure Net Central properly.

  

### Operating System specifics

#### Linux

##### Port number usage
If you attempt to change port numbers other than the defaults, remember that Linux restricts the use of port numbers below 1024 to root without extra effort.  

##### Firewall settings

Ensure that port 8080 is open on the server's firewall. Failure to open these ports will prevent external UI and API access.  

#### Windows

##### Firewall settings

Ensure that port 8080 is open on the server's firewall. Failure to open these ports will prevent external UI and API access.  


### Java

Java 21 JDK is preferred for build, and a JRE based on Java 21 for execution.  

Obtain the Java 21 JDK for your specific platform and build using maven or your favorite IDE supporting maven projects.


## Running Net Central

Net Central is composed of a single JAR file that contains all of the required sofware.


### Running Net Central Server

To run the JAR file, specify:  
    java netcentral-2.0.1.jar


## License
  
This software is licensed under GPL v3.  For more information, see LICENSE file.  