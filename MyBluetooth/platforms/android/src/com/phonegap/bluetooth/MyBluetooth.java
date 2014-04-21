package com.phonegap.bluetooth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import org.apache.cordova.DroidGap;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

public class MyBluetooth {
	
	private DroidGap mGap;
	private BluetoothAdapter btAdapter;
	private int REQUEST_ENABLE_BT = 0;
	private int REQUEST_VISIBLE_BT = 1;
	private BroadcastReceiver br;
	static int sdk = Integer.parseInt(Build.VERSION.SDK);
	private static final UUID MY_UUID = UUID
			.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private ArrayList<BluetoothDevice> btdDiscovered = new ArrayList<BluetoothDevice>();
	private AcceptThread accept;
	private ArrayList<BluetoothSocket> sockets = new ArrayList<BluetoothSocket>();

	public MyBluetooth(DroidGap gap) {
		mGap = gap;
	}

	public void turnOnBlueTooth() {
		btAdapter = BluetoothAdapter.getDefaultAdapter();

		if (btAdapter == null) {
			// Device does not support Bluetooth
			System.out.println("bluetooth isn't supported");
		}
		if (!btAdapter.isEnabled()) {
			// Request to turn on the bluetooth
			Intent enableBtIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_ENABLE);
			mGap.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
		} else {

			// Discover all devices,
			Intent discoveryMode = new Intent(
					BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			discoveryMode.putExtra(btAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			mGap.startActivityForResult(discoveryMode, REQUEST_VISIBLE_BT);

			// Start thread ( using for accepting outcoming bluetooth
			// connection, only with paired connection)
			accept = new AcceptThread();
			accept.start();
		}
	}

	// Get all available devices
	public void getDevices() {
		// If the button is pushed before the bluetooth is turned on
		if (btAdapter == null) {
			btAdapter = BluetoothAdapter.getDefaultAdapter();
		}

		IntentFilter iF = new IntentFilter(BluetoothDevice.ACTION_FOUND);
		br = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();

				// When discovery finds a device
				if (BluetoothDevice.ACTION_FOUND.equals(action)) {
					// Get the BluetoothDevice object from the Intent
					BluetoothDevice device = intent
							.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					btdDiscovered.add(device);
					JSONObject jo = new JSONObject();
					try {
						jo.put("deviceName", device.getName());
						jo.put("deviceAddress", device.getAddress());
						System.out.println(jo.toString());

						// Call addDeviceToList javascript function with the
						// string parameter
						mGap.sendJavascript("addDeviceToList(" + jo.toString()
								+ ");");
					} catch (JSONException e) {
						e.printStackTrace();
					}
				}
			}
		};
		mGap.registerReceiver(br, iF);

		// if the device is already looking for devices, restart the discovering
		// action
		if (btAdapter.isDiscovering())
			btAdapter.cancelDiscovery();
		btAdapter.startDiscovery();
	}

	public void stopSearchingDevices() {
		if (br != null) {
			btAdapter.cancelDiscovery();
			mGap.unregisterReceiver(br);
		}
	}

	// Called when a message in the chat is received.
	public void receiveMessage(String m) {

		mGap.sendJavascript("receiveMessage('" + m + "');");
	}

	// Called to send a message to all opened sockets
	public void sendMessageToSocket(String message) {

		for (BluetoothSocket bs : sockets) {
			try {
				OutputStream os = bs.getOutputStream();
				OutputStreamWriter out = new OutputStreamWriter(os);

				out.write(message);
				out.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void connect(String name, String macAddress) {

		BluetoothDevice d = null;
		for (BluetoothDevice de : btdDiscovered) {
			if (de != null && de.getName() != null && de.getAddress() != null)
				if (de.getName().equals(name)
						&& de.getAddress().equals(macAddress)) {
					d = de;
					break;
				}
		}

		// Stop listening to outcoming connection
		if (accept != null)
			accept.cancel();

		// Connect to the device
		ConnectThread c = new ConnectThread(d);
		c.start();

		// Listening from the opened socket
		listen l = new listen(c.mmSocket);
		l.start();

	}

	private class AcceptThread extends Thread {
		private final BluetoothServerSocket mmServerSocket;

		@SuppressLint("NewApi")
		public AcceptThread() {
			// Use a temporary object that is later assigned to mmServerSocket,
			// because mmServerSocket is final
			BluetoothServerSocket tmp = null;
			try {
				// MY_UUID is the app's UUID string, also used by the client
				// code
				// tmp =
				// btAdapter.listenUsingRfcommWithServiceRecord("test",MY_UUID);

				tmp = btAdapter.listenUsingInsecureRfcommWithServiceRecord(
						"HelloWorld", MY_UUID);
			} catch (IOException e) {
				e.printStackTrace();
			}
			mmServerSocket = tmp;
		}

		public void run() {
			BluetoothSocket socket = null;
			// Keep listening until exception occurs or a socket is returned
			while (true) {
				try {
					socket = mmServerSocket.accept();

					// if a socket is opened
					if (socket != null) {
						System.out.println("running && accept");

						sockets.add(socket);

						listen l = new listen(socket);
						l.start();
					}

				} catch (IOException e) {
					e.printStackTrace();
					break;
				}
			}
		}

		public void cancel() {
			try {
				mmServerSocket.close();
				interrupt();
			} catch (IOException e) {
			}
		}
	}

	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;

		@SuppressLint("NewApi")
		public ConnectThread(BluetoothDevice device) {
			// Use a temporary object that is later assigned to mmSocket,
			// because mmSocket is final
			BluetoothSocket tmp = null;

			System.out.println("connect" + device.getName());

			// Get a BluetoothSocket to connect with the given BluetoothDevice
			try {
				if (sdk != 10) {
					tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
				} else {
					Set<BluetoothDevice> bondedDevices = btAdapter
							.getBondedDevices();
					Class<?> btDeviceInstance = Class
							.forName(BluetoothDevice.class.getCanonicalName());
					Method removeBondMethod = btDeviceInstance
							.getMethod("removeBond");
					String currentMac = device.getAddress();
					
					for (BluetoothDevice bluetoothDevice : bondedDevices) {
						String mac = bluetoothDevice.getAddress();
						
						if (mac.equals(currentMac)) {
							removeBondMethod.invoke(bluetoothDevice);
							break;
						}
					}

					tmp = device
							.createInsecureRfcommSocketToServiceRecord(MY_UUID);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			mmSocket = tmp;
		}

		public void run() {
			// Cancel discovery because it will slow down the connection
			if (btAdapter.isDiscovering())
				btAdapter.cancelDiscovery();

			try {
				// Connect the device through the socket. This will block
				// until it succeeds or throws an exception
				mmSocket.connect();
				
				System.out.println("connected");
				if (mmSocket != null) {
					// Add the socket to the list of opened sockets
					sockets.add(mmSocket);
				}

			} catch (IOException connectException) {
				connectException.printStackTrace();
				// Unable to connect; close the socket and get out
				try {
					mmSocket.close();
				} catch (IOException closeException) {
					closeException.printStackTrace();
				}
				return;
			}
		}
	}

	private class listen extends Thread {
		private BluetoothSocket sock;

		public listen(BluetoothSocket s) {
			sock = s;
		}

		public void run() {
			System.out.println("listening");
			int bufferSize = 1024;
			try {

				while (true) {
					
					byte[] buffer = new byte[bufferSize];
					InputStream i = sock.getInputStream();
					int readen = i.read(buffer);
					if (readen == -1) {
						System.out.println("No message");
						break;
					} else {
						int size = 0;
						while (size < buffer.length) {
							if (buffer[size] == 0) {
								break;
							}
							size++;
						}

						// Specify the appropriate encoding as the last argument
						String myString = new String(buffer, 0, size, "UTF-8");
						
						// Send the string to javascript
						receiveMessage(myString);

						System.out.println("Message recieved : " + myString);

					}
				}

			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}