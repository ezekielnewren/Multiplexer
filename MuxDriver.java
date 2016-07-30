package net.multiplexer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;

public class MuxDriver {

	static FileOutputStream fos;
	//static MultiOutputStream mos;
	static PrintStream logOut;
	static boolean DEBUG = System.console()==null&&true;
	
	public static Multiplexer home;
	public static int channel = 17;
	static Multiplexer client;
	static Multiplexer server;
	static Channel[] cm = new Channel[0x10000];
	
	static {
		try {
			fos = new FileOutputStream("MuxDriver.log");
			logOut = System.out;
		} catch (IOException ioe) {
			ioe.printStackTrace();
			System.exit(1);
		}
	}
	
	public static void log(String message) {
		if (!DEBUG) return;
		logOut.println("["+Thread.currentThread().getName()+"]: "+message);
	}
	
	static class data {
		
	}
	
	public static void main(String[] args) throws Exception {
		for (int i=0; i<1000; i++) {
			try {
				test();
			} catch (Exception e) {
				e.printStackTrace();
				break;
			}
		}
		
	}
	
	public static void test() throws Exception {
		final ByteArrayCircularBuffer clientCB = new ByteArrayCircularBuffer(5000);
		final ByteArrayCircularBuffer serverCB = new ByteArrayCircularBuffer(5000);
		
		int port = 3243;
		ServerSocket ss = new ServerSocket(port);
		
		Socket tcpclient = new Socket("localhost", port);
		Socket tcpserver = ss.accept();
		
		boolean network = false;
		
		final InputStream cis = network?tcpclient.getInputStream():clientCB.getInputStream();
		final OutputStream cos = network?tcpclient.getOutputStream():serverCB.getOutputStream();
		
		final InputStream sis = network?tcpserver.getInputStream():serverCB.getInputStream();
		final OutputStream sos = network?tcpserver.getOutputStream():clientCB.getOutputStream();
		
		
		
		Thread clientThread = new Thread() {
			public void run() {
				try {
					client = new Multiplexer(cis, cos);
					//client.getState(3);
					Channel cm = client.connect(channel, 600, Long.MAX_VALUE);
					
					cm.close();
					
					client.close(0);
				} catch (Exception ioe) {
					ioe.printStackTrace();
				}
			}
		};
		
		
		Thread serverThread = new Thread() {
			public void run() {
				try {
					//int channel = MuxDriver.channel;
					server = new Multiplexer(sis, sos, channel);
					Channel cm = server.accept(channel, 7, Long.MAX_VALUE, true);

					cm.close();
					
					server.close(0);
					
				} catch (Exception ioe) {
					ioe.printStackTrace();
				}
			}
		};

		Thread subProccess = new Thread(new Runnable() {
			public void run() {
				try {
					if (server!=null) {
						Channel cm = server.accept(3, 700, Long.MAX_VALUE, true);
					
						cm.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
		
		clientThread.setName("---clientSide---");
		subProccess.setName("---unwanted3rdParty---");
		serverThread.setName("---serverSide---");
		
		clientThread.start();
		//subProccess.start();
		serverThread.start();
		
		clientThread.join();
		subProccess.join();
		serverThread.join();
		
		ss.close();
		tcpclient.close();
		tcpserver.close();
	}
	
	public static String setHome() {
		String tmp = Thread.currentThread().getName();
		if (tmp.equals("---clientSide---")) {
			home = client;
		} else {
			home = server;
		}
		return tmp;
	}
	
	public static String getState(Multiplexer home, int channel) {
		Field[] field = home.getClass().getDeclaredFields();
		for (int i=0; i<field.length; i++) {
			try {
				String var = field[i].getName();
				if (var.startsWith("STATE_")&&home.getCP(channel).state == field[i].getLong(home)) {
					return field[i].getName();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return "STATE_UNKNOWN";
	}

}
