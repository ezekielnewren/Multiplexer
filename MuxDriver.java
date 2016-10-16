package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import misc.Lib;

public class MuxDriver {

	public static void main(String[] args) throws Exception {
		
		final ByteArrayCircularBuffer clientWindow = new ByteArrayCircularBuffer(0x1000);
		final ByteArrayCircularBuffer serverWindow = new ByteArrayCircularBuffer(0x1000);
		
		final Thread[] worker = new Thread[2];
		
		worker[0] = new Thread(new Runnable() {
			public void run() {
				try {
					ClientMultiplexer client = new Multiplexer(clientWindow.getInputStream(), serverWindow.getOutputStream());
				
					StreamChannel c = client.connectStreamChannel(131, 0x8000, Long.MAX_VALUE);
					
					c.close();
					
					client.close();
					
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
		worker[0].setDaemon(true);
		worker[0].setName("consumer");
		
		worker[1] = new Thread(new Runnable() {
			public void run() {
				try {
					ServerMultiplexer server = new Multiplexer(serverWindow.getInputStream(), clientWindow.getOutputStream(), 131);
					
					StreamChannel s = server.acceptStreamChannel(131, 0x8000, Long.MAX_VALUE, false);
					
					s.close();
					
					server.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				
			}
		});
		worker[1].setDaemon(true);
		worker[1].setName("producer");
		
		
		worker[0].start();
		worker[1].start();
		
		
		worker[0].join();
		worker[1].join();
		
		
	}
	
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

}
