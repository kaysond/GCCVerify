# GCCVerify - A utility for verifying microcontroller-based Gamecube controller mods.
GCCVerify is a simple Java utility by http://github.com/kaysond that reads JSON-encoded parameters from a serial port, compares them to a manifest of permitted mods and thier values, then downloads the microcontroller program memory and compares against a local binary.

## Features
* Manifest file allows the user to easily adjust permitted firmwares, modifications, and mod values
* Automatic manifest updates from github repo
* Automatic firmware binary downloads from github repo
* Developers can easily add new firmwares by submitting github pull requests

## Run Requirements
* Java 8.0 or newer
* avrdude, libusb0.dll, and an avrdude.conf (included in release packages)

## Build Requirements
* [jssc 2.8.0](https://github.com/scream3r/java-simple-serial-connector)
* [gson 2.8.1](https://github.com/google/gson)
* JDK 1.8.0

## Command Line Options
* -d : Enables debug mode
* -o : Enables offline mode (does not attempt to update manifest or firmware binaries from github)

## Pull Requests
Pull requests welcome. To add your firmware to the manifest, please submit a pull request modifying only lib/manifest.json and adding only your firmware binary. Hash and filesize can be easily found by adding the firmware to your local manifest and enabling debug mode. Ensure the manifest timestamp is correctly updated (unix timestamp in seconds). You must include a link to your source code.

## Microcontroller parameter validation specification
On boot, the microcontroller serial port must be configured to 9600baud, 8 data bits, 1 stop bit, and no parity. No serial output can occur until 2 seconds has elapsed, or the magic string is received. Within the first 2 seconds of boot, the microcontroller must accept the magic string "GCCVerify". Upon receiving the string, the microcontroller should respond with a JSON-encoded object containing the following:

* name: String - the firmware name
* major_version: int - the firmware major version number
* minor_version: int - the firmware minor version number
* mods: mod[] - array of objects - any number of mod objects

mod:
* name: String - name of the modification
* enabled: bool - if the mod is active or not
* vals: int[] - values associated with the mod

See below for allowed mod names and their values.

## Mods
The following mods are supported:
