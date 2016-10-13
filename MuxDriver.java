//package com.github.ezekielnewren.net.multiplexer;
//
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.io.InputStream;
//import java.io.OutputStream;
//import java.io.PrintStream;
//import java.lang.reflect.Field;
//import java.net.ServerSocket;
//import java.net.Socket;
//
//public class MuxDriver {
//
//	static FileOutputStream fos;
//	static PrintStream logOut;
//	static boolean DEBUG = System.console()==null&&true;
//	
//	static {
//		try {
//			fos = new FileOutputStream("MuxDriver.log");
//			logOut = System.out;
//		} catch (IOException ioe) {
//			ioe.printStackTrace();
//			System.exit(1);
//		}
//	}
//	
//	public static void log(String message) {
//		if (!DEBUG) return;
//		logOut.println("["+Thread.currentThread().getName()+"]: "+message);
//	}
//	
//	public static void main(String[] args) throws Exception {
//		
//	}
//	
//	public static void test() throws Exception {
//		log("------------------------------------------");
//		
//		final ByteArrayCircularBuffer clientCB = new ByteArrayCircularBuffer(5000);
//		final ByteArrayCircularBuffer serverCB = new ByteArrayCircularBuffer(5000);
//		
//		int port = 3243;
//		ServerSocket ss = new ServerSocket(port);
//		
//		Socket tcpclient = new Socket("localhost", port);
//		Socket tcpserver = ss.accept();
//		
//		boolean network = false;
//		
//		final InputStream cis = network?tcpclient.getInputStream():clientCB.getInputStream();
//		final OutputStream cos = network?tcpclient.getOutputStream():serverCB.getOutputStream();
//		
//		final InputStream sis = network?tcpserver.getInputStream():serverCB.getInputStream();
//		final OutputStream sos = network?tcpserver.getOutputStream():clientCB.getOutputStream();
//		
//		final ClientMultiplexer csup = new Multiplexer(cis, cos);
//		final ServerMultiplexer ssup = new Multiplexer(sis, sos, 13036);
//		
//		final int channel = 15157;
//		
//		Thread clientThread = new Thread(new Runnable() {
//			public void run() {
//				try {
//					Channel cm = csup.connect(channel, 49772);
//					
//					cm.close();
//				} catch (IOException e) {
//					e.printStackTrace();
//				}
//			}
//		});
//		
//		Thread serverThread = new Thread(new Runnable() {
//			public void run() {
//				try {
//					Channel cm = ssup.accept(channel, 13036, true);
//					
//					cm.close();
//				} catch (IOException e) {
//					e.printStackTrace();
//				}
//			}
//		});
//		
//		clientThread.setName("--client--");clientThread.start();
//		serverThread.setName("--server--");serverThread.start();
//		
//		clientThread.join();
//		serverThread.join();
//		
//	}
//	
//	public static String getState(Multiplexer home, int channel) {
//		if (home==null) return null;
//		Field[] field = home.getClass().getDeclaredFields();
//		for (int i=0; i<field.length; i++) {
//			try {
//				String var = field[i].getName();
//				if (var.startsWith("STATE_")&&home.getCP(channel).state == field[i].getLong(home)) {
//					return field[i].getName();
//				}
//			} catch (Exception e) {
//				e.printStackTrace();
//			}
//		}
//		return "STATE_UNKNOWN";
//	}
//
//}
