/**
 * Verifier.java is a class for verifying the contents of a microcontroller-based Gamcube
 * controller firmware. The two main verification functions request JSON-encoded
 * parameters of the firmware and download the program memory. These are verified
 * against a user-specified JSON-encoded manifest file. Functions are included to
 * manage the manifest file, specifically to download the "official" manifest and
 * firmware binaries from the github repo.
 *
 * Contains the main class and some necessary objects
 *
 * Copyright (C) 2017 Aram Akhavan <kaysond@hotmail.com>
 * https://github.com/kaysond/GCCVerify
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package GCCVerify;

import jssc.*;
import java.nio.file.*;
import static java.nio.file.StandardCopyOption.*;
import java.io.*;
import java.net.URL;
import java.util.Scanner;
import java.util.Formatter;
import java.util.stream.Stream;
import static java.util.InputMismatchException.*;
import java.nio.channels.*;
import com.google.gson.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import GCCVerify.Manifest;
import GCCVerify.Manifest.FirmwareImage;

public class Verifier {

	public static enum Platform {ARDUINO};
	public static final String manifestURL = "https://raw.githubusercontent.com/kaysond/GCCVerify/master/build/lib/manifest.json";
	private static Manifest localManifest = new Manifest();
	private static Manifest remoteManifest = new Manifest();
	private static Manifest activeManifest = new Manifest();
	public static boolean debug = false;

	private SerialPort serialPort;
	private Platform platform;
	private String firmwareName;
	private int baudRate;
	private int dataBits;
	private int stopBits;
	private int parity;

	public Verifier(String portName) {
		serialPort = new SerialPort(portName);
		if ( !isManifestLoaded() ) {
			loadLocalManifest();
			useLocalManifest();
		}
	}

	public void selectPlatform(Platform platform) {
		switch (platform) {
			default:
				this.platform = platform;
				baudRate = SerialPort.BAUDRATE_9600;
				dataBits = SerialPort.DATABITS_8;
				stopBits = SerialPort.STOPBITS_1;
				parity = SerialPort.PARITY_NONE;
				break;
		}
	}

	public VerifyParamsResult verifyParams() {
		System.out.println("Verifying parameters of " + platform.name() + " on " + serialPort.getPortName());
		if ( !isManifestLoaded() ) {
			System.out.println("ERROR: Manifest is not loaded.");
			return new VerifyParamsResult(false, "");
		}
		firmwareName = "";
		try {
			serialPort.openPort();
			serialPort.setParams(baudRate, dataBits, stopBits, parity);
			if ( platform == Platform.ARDUINO ) {
				System.out.printf("Waiting for boot...%n");
				//Reset the system a few times (mainly for Arduino)
				for ( int i = 0; i < 2; i++ ) {
					serialPort.setDTR(false);
					serialPort.setDTR(true);
					serialPort.setRTS(false);
					serialPort.setRTS(true);
					Thread.sleep(250);
				}
				//Wait for bootloader (Nano takes ~920ms after the for loop finishes to boot, immediately do a Serial.begin() and Serial.println())
				Thread.sleep(1000);
			}

			//Send the string once every 0.25s for 1s until something shows up on the serial port
			System.out.printf("Requesting firmware parameters...%n");
			for ( int i = 0; i < 4; i++ ) {
				serialPort.writeString("GCCVerify");
				Thread.sleep(250);
				if ( serialPort.getInputBufferBytesCount() > 0 ) 
					break;
			}

			//Receive serial data until terminating "\r\n" or for up to 2s (2400 bytes at 9600baud)
			String firmwareJSON = "";
			long endTime = System.currentTimeMillis() + 2000;
			while ( System.currentTimeMillis() < endTime ) {
				if ( serialPort.getInputBufferBytesCount() > 0 ) {
					firmwareJSON += serialPort.readString();
					if ( firmwareJSON.indexOf("\r\n") > 0 )
						break;
				}
			}
			serialPort.closePort();
			System.out.printf("Received %d bytes. Parsing...%n", firmwareJSON.length());

			//Skip serial data up to the first "{" and drop anything after the "\r\n" if it exists
			int bracketIdx = firmwareJSON.indexOf("{");
			if ( bracketIdx > - 1)
				firmwareJSON = firmwareJSON.substring(bracketIdx);
			int termIdx = firmwareJSON.indexOf("\r\n");
			if ( termIdx > -1 )
				firmwareJSON = firmwareJSON.substring(0, termIdx);

			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			FirmwareParams firmwareParams = new FirmwareParams();
			try {
				if ( firmwareJSON.length() == 0 )
					throw new JsonSyntaxException("JSON string was empty");
				firmwareParams = gson.fromJson(firmwareJSON, FirmwareParams.class);
				if ( debug )
					System.out.println(gson.toJson(firmwareParams));
			} catch ( JsonSyntaxException e ) {
				System.out.println("Response is invalid JSON.");
				if ( debug )
					System.out.println(e.getMessage());
				return new VerifyParamsResult(false, "");
			}

			//Validate received JSON
			//Object must contain
			//name: String - the firmware name
			//major_version: int - the firmware major version number
			//minor_version: int - the firmware minor version number
			//mods: mod[] - array of objects representing firmware mods from stock operation, formatted as:

			//mod:
			//Object must contain
			//name: String - name of the modification
			//enabled: bool - if the mod is active or not
			//vals: int[] - values associated with the mod

			if ( firmwareParams.name == "" ) {
				System.out.println("Response did not contain firmware name.");
				return new VerifyParamsResult(false, "");
			}

			if ( firmwareParams.major_version < 0 ) {
				System.out.println("Response did not contain major version number.");
				return new VerifyParamsResult(false, "");
			}

			if ( firmwareParams.minor_version < 0 ) {
				System.out.println("Response did not contain minor version number.");
				return new VerifyParamsResult(false, "");
			}

			firmwareName = String.format("%s-%d.%d", firmwareParams.name, firmwareParams.major_version, firmwareParams.minor_version);
			System.out.printf("Detected firmware: %s%n", firmwareName);
			System.out.printf("Checking firmware mods...%n");
			boolean flagErr = false;
			String output = "";
			if ( firmwareParams.mods.length == 0 ) {
				System.out.println("Controller firmware reported no mods.");
			}
			else {
				for ( FirmwareMod mod : firmwareParams.mods ) {
					if ( mod.name.length() == 0 ) {
						return new VerifyParamsResult(true, String.format("--------------------------------%n" + 
																	      "|   --Firmware has no mods--   |%n" +
																	      "--------------------------------%n"));
					}
					//Find the mod in the manifest
					int modIdx = -1;
					for ( int i = 0; i < activeManifest.modSpecs.length; i++ ) {
						if ( activeManifest.modSpecs[i].name.equals(mod.name) ) {
							modIdx = i;
							break;
						}
					}
					if ( modIdx == -1 ) {
						flagErr = true;
						output += mod.toString(String.format("--------------------------------%n" + 
															 "|     **Unknown Mod Found**    |%n"), 
													  String.format("%n"));
					}
					else {
						boolean flagIllegalVal = false;
						Manifest.FirmwareModSpec modSpec = activeManifest.modSpecs[modIdx];
						if ( mod.enabled && !modSpec.permitted ) {
							flagErr = true;
							output += mod.toString(String.format("--------------------------------%n" + 
																 "|     **Illegal Mod Found**    |%n"), 
														  String.format("%n"));
						}
						else {
							for ( FirmwareModValue value : mod.values ) {
								int valIdx = -1;
								for ( int i = 0; i < modSpec.valueSpecs.length; i++ ) {
									if ( modSpec.valueSpecs[i].name.equals(value.name) ) {
										valIdx = i;
										break;
									}
								}
								if ( valIdx == -1 ) {
									flagErr = true;
									flagIllegalVal = true;
									output += mod.toString(String.format("--------------------------------%n" + 
																		 "| **Unknown Mod Value Found**  |%n"), 
								  							String.format("%n"));
								}
								else {
									Manifest.ModValueSpec valueSpec = modSpec.valueSpecs[valIdx];
									if ( value.value > valueSpec.maxVal || value.value < valueSpec.minVal ) {
										flagErr = true;
										flagIllegalVal = true;
										output += mod.toString(String.format("--------------------------------%n" + 
																			 "| **Illegal Mod Values Found** |%n"), 
									  							String.format("%n"));
										break;
									}
								}
							}
						}
						if ( !flagIllegalVal ) {
							output += mod.toString(String.format("--------------------------------%n" + 
																 "|         --Mod Info--         |%n"), 
								  							String.format("%n"));
						}
					}
				}
			}
			System.out.printf("Done.%n%n");
			return new VerifyParamsResult(!flagErr, output);

		} catch ( SerialPortException e ) {
			handleSerialPortException(e);
			return new VerifyParamsResult(false, "");
		} catch ( Exception e ) {
			System.out.printf("An unhandled exception occurred.%n%n");
			if ( debug ) {
				e.printStackTrace();
			}
			return new VerifyParamsResult(false, "");
		} //try

	} //verifyParams

	public boolean verifyFirmwareImage() {
		System.out.println("Verifying firmware image of " + platform.name() + " on " + serialPort.getPortName());
		if ( !isManifestLoaded() ) {
			System.out.println("ERROR: Manifest is not loaded.");
			return false;
		}
		if ( firmwareName == null || firmwareName.length() == 0 ) {
			System.out.println("ERROR: Firmware name not available. Verify firmware parameters first.");
			return false;
		}
		try {
			//Find the right firmware in the manifest
			int fwIdx = -1;
			for ( int i = 0; i < activeManifest.firmwareImages.length; i++ ) {
				if ( activeManifest.firmwareImages[i].name.equals(firmwareName) ) {
					fwIdx = i;
					break;
				}
			}
			if ( fwIdx == -1 ) {
				System.out.printf("Could not find firmware %s in manifest.%n%n", firmwareName);
				return false;
			}
			FirmwareImage libImg = activeManifest.firmwareImages[fwIdx];
			if ( !libImg.permitted ) {
				System.out.printf("Firmware %s is not permitted.%n%n", firmwareName);
				return false;
			}

			Path libFWPath = Paths.get("lib", firmwareName + ".hex");
			System.out.printf("Verifying library firmware image...%n");
			if ( !verifyLibFirmwareImage(libFWPath, libImg.hash) ) {
				System.out.printf("Could not verify controller firmware because library image does not match manifest.%n%n");
				return false;
			}
			System.out.printf("Done.%n%n");

			//Get the firmware from the controller
			if ( platform == Platform.ARDUINO ) {
				Path avrdude = Paths.get("bin", "avrdude.exe");
				Path avrdudeConf = Paths.get("etc", "avrdude.conf");
				if ( Files.isExecutable(avrdude) ) {
					if ( Files.isReadable(avrdudeConf) ) {
						System.out.println("Downloading controller firmware (this can take a while)...");
						String[] command = {avrdude.toString(), "-C" + avrdudeConf.toString(), "-v", "-patmega328p", "-carduino", "-P" + serialPort.getPortName(), "-Uflash:r:\"progmem.bin\":r", "-b57600"};
						ProcessBuilder pb = new ProcessBuilder(command);
						pb.redirectErrorStream(true);

						Process process = pb.start();
						
						Scanner s = new Scanner(process.getInputStream()).useDelimiter("\\A");
						String avrOut = s.hasNext() ? s.next() : "";

						if ( debug )
							System.out.printf("%s%n%n", avrOut);

						int exitVal = -1;
						try { //Shouldn't need the try since the scanner should block until the stream is done...
							exitVal = process.exitValue();
						} catch ( IllegalThreadStateException e ) {}

						if ( exitVal != 0 ) {
							process.destroy();
							throw new AVRDudeException("avrdude encountered an error. Command: " + String.join(" ", command) + "%navrdude output: %n" + avrOut);
						}
					}
					else {
						throw new FileNotFoundException("Could not find avrdude.conf. Please check the etc directory.");
					}
				}
				else {
					throw new FileNotFoundException("Could not find avrdude. Please check the bin directory.");
				}
			}

			System.out.printf("Comparing to firmware in library...%n");
			byte[] progmem = Files.readAllBytes(Paths.get("progmem.bin"));
			try ( Stream<String> lines = Files.lines(Paths.get("lib", firmwareName + ".hex")) ) {
				int i = 1;
  				for ( String line : (Iterable<String>)lines::iterator ) {
  					IntelHexRecord record = new IntelHexRecord(line);
  					if ( record.recordType == IntelHexRecord.DATA ) {
	  					if ( !record.checksumValid() ) {
	  						Files.delete(Paths.get("progmem.bin"));
	  						System.out.printf("Error in library firmware file. Bad checksum at line %d.%n", i);
	  					}
	  					if ( record.byteCount > 0 ) {
		  					//Check byte by byte
		  					for ( int j = 0; j < record.byteCount; j++ ) {
		  						if ( record.data[j] != progmem[record.address + j]) {
		  							Files.delete(Paths.get("progmem.bin"));
	  								System.out.printf("Controller firmware does not match firmware in library at byte %d.%n%n", record.address + j);
	  								return false;
		  						}
		  					}
		  				}
	  				}
  					i++;
				};
			} catch ( IOException e ) {
				System.out.printf("Could not open firmware file.%n");
				return false;
			}
			Files.delete(Paths.get("progmem.bin"));
			System.out.printf("Controller firmware matches %s in library.%n%n", firmwareName);
			return true;
		} catch ( FileNotFoundException e ) {
			System.out.println(e.getMessage());
			return false;
		} catch ( AVRDudeException e ) {
			System.out.println(e.getMessage());
			return false;
		} catch ( Exception e ) {
			if ( debug ) {
				e.printStackTrace();
			}
			System.out.printf("An unhandled exception occurred.%n");
			return false;
		}

	} //verifySoftwareImage()

	public static boolean loadRemoteManifest() {
		System.out.println("Loading remote manifest...");
		//Get the latest manifest
		try {
			remoteManifest.load(new URL(Verifier.manifestURL));
			System.out.printf("Done.%n%n");
			return true;
		} catch ( JsonSyntaxException e ) {
			System.out.printf("WARNING: Remote manifest could not be loaded because it is improperly formatted.%n%n");
			return false;
		} catch ( Exception e ) {
			System.out.printf("WARNING: Could not retrieve remote manifest.%n%n");
			return false;
		}
	}

	public static boolean loadLocalManifest() {
		System.out.println("Loading local manifest...");
		try {
			localManifest.load(Paths.get("lib", "manifest.json"));
			System.out.printf("Done.%n%n");
			return true;
		} catch ( JsonSyntaxException e ) {
			System.out.printf("WARNING: Local manifest could not be loaded because it is improperly formatted.%n%n");
			return false;
		} catch ( Exception e ) {
			if ( debug )
				System.out.println(e.toString());
			System.out.printf("WARNING: Could not retrieve local manifest.%n%n");
			return false;
		}

	}

	public static boolean updateLibFromManifest() {
		System.out.printf("Updating firmware images...%n");
		boolean flagErr = false;
		for ( Manifest.FirmwareImage img : activeManifest.firmwareImages ) {
			Path imgPath = Paths.get("lib", img.name + ".hex");
			if ( Files.isReadable(imgPath) ) {
				System.out.printf("%s found. Verifying...%n", img.name);
				//Get SHA2 hash
				if ( verifyLibFirmwareImage(imgPath, img.hash) ) {
					System.out.printf("Done.%n");
				}
				else {
					System.out.printf("%s does not match manifest.%n", img.name);
					if ( !downloadAndVerifyLibFirmwareImage(imgPath, img.url, img.hash) )
						flagErr = true;
				}
			}
			else {
				//Attempt to download
				System.out.printf("%s was not found.%n", img.name);
				if ( !downloadAndVerifyLibFirmwareImage(imgPath, img.url, img.hash) )
					flagErr = true;
			}

		}
		System.out.printf("%n");
		return !flagErr;
	}

	public static boolean downloadAndVerifyLibFirmwareImage(Path path, String url, String hash) {
		System.out.printf("Downloading image from %s...", url);
		if ( downloadLibFirmwareImage(path, url) ) {
			System.out.printf("Done.%nVerifying...");
			if( verifyLibFirmwareImage(path, hash) ) {
				System.out.printf("Done.%n");
				return true;
			}
			else {
				System.out.printf("Failed! Image does not match manifest.%n");
				return false;
			}
		}
		else {
			System.out.printf("Failed!%n");
			return false;
		}
	}

	public static boolean downloadLibFirmwareImage(Path path, String url) {
		try {
			ReadableByteChannel rbc = Channels.newChannel(new URL(url).openStream());
			FileOutputStream fos = new FileOutputStream(path.toString());
			fos.getChannel().transferFrom(rbc, 0, 1000000); //1MB max
			rbc.close();
			fos.close();
			return true;
		} catch ( Exception e ) {
			return false;
		}
	}

	public static boolean verifyLibFirmwareImage(Path path, String hash) {
		try {
			DigestInputStream dis = new DigestInputStream(Files.newInputStream(path), MessageDigest.getInstance("SHA-256"));
			BufferedInputStream bis = new BufferedInputStream(dis);
			int i;
			while ((i = bis.read()) != -1) {}
			bis.close();
			String fileHash = bytesToHex(dis.getMessageDigest().digest());
			if ( debug )
				System.out.printf("File Hash: %s | Expected Hash: %s|%n", fileHash, hash);
			if ( !(hash.equals(fileHash)) ) {
				return false;
			}
		} catch ( IOException e ) {
			System.out.printf("IO error while verifying image. Image may not be correct.");
			return false;
		} catch ( NoSuchAlgorithmException e ) {
			System.out.printf("SHA256 hash algorithm is not available on your system. Image may not be correct.");
			return false;
		}

		return true;
	}

	private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

	public static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}

	public static void saveRemoteManifestToLocal() {
		System.out.printf("Updating manifest with remote copy...%n");
		try {
			localManifest = remoteManifest;
			Path manifestPath = Paths.get("lib", "manifest.json");
			Path oldManifestPath = Paths.get("lib","manifest_old.json");
			if ( Files.isReadable(manifestPath) )
				Files.copy(manifestPath, oldManifestPath, REPLACE_EXISTING);
			try {
				Gson gson = new GsonBuilder().setPrettyPrinting().create();
				Files.write(manifestPath, gson.toJson(localManifest).getBytes());
				System.out.printf("Done.%n%n");
			} catch ( IOException e ) {
				if ( debug )
					System.out.println(e.toString());
				System.out.printf("WARNING: Could not write to local manifest file.%n%n");
			}
		} catch ( IOException e ) {
			if ( debug )
				System.out.println(e.toString());
			System.out.printf("WARNING: Could not back up existing manifest. Did not write local manifest file.%n%n");
		}
	}

	public static void useLocalManifest() {
		activeManifest = localManifest;
	}

	public static void useRemoteManifest() {
		activeManifest = remoteManifest;
	}

	public static boolean isRemoteManifestNewer() {
		return remoteManifest.timestamp > localManifest.timestamp ? true : false;
	}

	public static boolean isManifestLoaded() {
		return activeManifest.timestamp != 0;
	}

	public void handleSerialPortException (SerialPortException e) {
		switch ( e.getExceptionType() ) {
			case SerialPortException.TYPE_PORT_BUSY:
				System.out.println("Serial port is busy. Please check USB connection and exit all serial monitoring terminals.");
				break;
			case SerialPortException.TYPE_PORT_NOT_FOUND:
				System.out.println("Serial port could not be found. Please check USB connection.");
				break;
			default:
				System.out.println("An unhandled serial port exception occurred: " + e.getMessage());
		}
		if ( serialPort.isOpened() ) {
			try {
				if ( !serialPort.closePort() )
					System.out.println("Warning: could not close serial port. Restart may be required if port remains busy.");
			} catch ( SerialPortException e2 ) {
				System.out.println("Warning: could not close serial port. Restart may be required if port remains busy.");
			}
		}
	}

	public class VerifyParamsResult {
		public boolean succeeded;
		public String output;

		public VerifyParamsResult(boolean succeeded, String output) {
			this.succeeded = succeeded;
			this.output = output;
		}
	}

	class FirmwareParams {
		public String name = "";
		public int major_version = -1;
		public int minor_version = -1;
		public FirmwareMod[] mods = new FirmwareMod[]{};
	}

	class FirmwareMod {
		public String name = "";
		public boolean enabled;
		public FirmwareModValue[] values = new FirmwareModValue[]{};

		public String toString(String prefix, String suffix) {
			Formatter formatter = new Formatter(new StringBuilder());
			if ( prefix.length() > 0 )
				formatter.format("%s", prefix);
			formatter.format("--------------------------------%n");
			formatter.format("|  Name:                       |%n");
			formatter.format("|    %-20s      |%n", name);
			formatter.format("|%30s|%n", "");
			formatter.format("|  Enabled: %-19s|%n", (enabled ? "Yes" : "No"));
			formatter.format("|%30s|%n", "");
			formatter.format("|  Values:                     |%n");
			for ( FirmwareModValue value : values ) {
				formatter.format("|    %-26s|%n", new Formatter().format("%s: %d", value.name, value.value).toString());
			}
			formatter.format("--------------------------------%n");
			if ( suffix.length() > 0 )
				formatter.format("%s", suffix);

			return formatter.toString();
		}

	}

	class FirmwareModValue {
		public String name = "";
		public int value = Integer.MAX_VALUE; //This should trip any value check if the firmware does not properly respond with a value
	}

	class IntelHexRecord {
		public static final int DATA = 0;
		public static final int END_OF_FILE = 1;

		//Ints to avoid sign issues
		public int byteCount = 0;
		public int address = 0;
		public int recordType = DATA;
		public byte[] data;
		public int checksum = 0;

		public IntelHexRecord(String record) {
			if ( record.length() >= 11 && record.charAt(0) == ':') {
				byteCount = Integer.parseInt(record.substring(1,3), 16);
				address = Integer.parseInt(record.substring(3,7), 16);
				recordType = (byte) (Integer.parseInt(record.substring(7,9), 16) & 0xFF);
				if ( byteCount > 0 ) {
					data = new byte[byteCount];
					for ( int i = 0; i < byteCount; i++ ) {
						data[i] = (byte) (Integer.parseInt(record.substring(2*i + 9, 2*i + 9 + 2), 16) & 0xFF);
					}
				}
				checksum = Integer.parseInt(record.substring(9 + byteCount*2,9 + byteCount*2 + 2), 16);
			}
		}

		public boolean checksumValid() {
			int sum = byteCount + (address & 0xFF) + ((address & 0xFF00) >> 8) + recordType;
			if ( data != null ) {
				for ( byte datum : data ) {
					sum += ((int) datum & 0xFF);
				}
			}
			sum = (~sum + 1) & 0xFF;
			if ( sum == checksum ) 
				return true;
			else
				return false;
		}
	}

} //GCCVerify

@SuppressWarnings("serial")
class AVRDudeException extends Exception {
	public AVRDudeException(String message) {
		super(message);
	}
}

