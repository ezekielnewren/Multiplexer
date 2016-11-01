package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.github.ezekielnewren.io.*;

import misc.Lib;

public class MuxDriver {

	public static void main(String[] args) throws Exception {
		//cbTest();
		
		muxTest();
		
		//multiThreadTest();
		
	}
	
	public static void muxTest() throws Exception {
		final ByteArrayCircularBuffer clientWindow = new ByteArrayCircularBuffer(0xffff);
		final ByteArrayCircularBuffer serverWindow = new ByteArrayCircularBuffer(0xffff);
		
		final Thread[] worker = new Thread[2];
		
		long total = 1500000000;
		
		long seed = 9857327476839L;
		
		
		worker[1] = new Thread(new Runnable() {
			public void run() {
				try {
					Multiplexer home = new Multiplexer(serverWindow.getInputStream(), clientWindow.getOutputStream(), 0);
					ServerMultiplexer server = home;

					StreamChannel stream = server.acceptStreamChannel(0, 0x8000, Long.MAX_VALUE);
					
//					MessageDigest md = MessageDigest.getInstance("MD5");
//					DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), md);
					
					long beg,time;
					
					beg = System.nanoTime();
					Lib.copy(stream.getInputStream(), null, new byte[8192], total);
					time = (System.nanoTime()-beg)/1000000;
					System.out.println(SPEED.rateOverTime(total, time).toString(true));
					
//					byte[] digest = md.digest();
//					
//					System.out.println("server: "+Lib.bytesToHex(digest));
					
					stream.close();
					
					server.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
				
			}
		});
		worker[1].setDaemon(true);
		worker[1].setName("--server--");
		
		worker[0] = new Thread(new Runnable() {
			public void run() {
				try {
					Multiplexer home = new Multiplexer(clientWindow.getInputStream(), serverWindow.getOutputStream());
					ClientMultiplexer client = home;
				
					StreamChannel c = client.connectStreamChannel(0, 27, Long.MAX_VALUE);
					
//					RandomInputStream ris = new RandomInputStream(new Random(seed));
//					MessageDigest md = MessageDigest.getInstance("MD5");
//					DigestInputStream dis = new DigestInputStream(ris, md);
					
					Lib.copy(null, c.getOutputStream(), new byte[65535], total);
					
//					byte[] digest = md.digest();
//					
//					System.out.println("client: "+Lib.bytesToHex(digest));
					
					
					c.close();
					
					client.close();
					
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		worker[0].setDaemon(true);
		worker[0].setName("--client--");
		
		
		
		
		worker[0].start();
		worker[1].start();
		
		
		worker[0].join();
		worker[1].join();
	}
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	public static void multiThreadTest() {
		
		String[] user = {"John", "Teresa", "Bob", "Bill", "Joe"};
		
		Thread[] t = new Thread[user.length];
		
		final Object lock = new Object();
		
		final AtomicBoolean x = new AtomicBoolean(false);
		
		for (int i=0; i<user.length; i++) {
			t[i] = new Thread(new Runnable(){

				@Override
				public void run() {
					try {
						synchronized(lock) {
							while (!x.get()) {
								lock.wait();
								
								Lib.doNothing();
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				
			});
			t[i].setName(user[i]);
			//t[i].setDaemon(true);
		}
		
		for (int i=0; i<user.length; i++) t[i].start();
		
		synchronized(lock) {
			
			x.set(true);
			lock.notifyAll();
			
			
		}
		
		
		
		
	}
	
	
	
	public static void cbTest() {
		try {
			// 58.181Gbps boundless
			// 24.739Gbps bounded
			
			byte[] cbuff = new byte[150000];
			ByteArrayCircularBuffer inst = new ByteArrayCircularBuffer(cbuff);
			byte[] data = "abcde".getBytes();
			
			int total = 10000;
			final long seed = 63873;
			final Random r = new Random(seed);
			
//			RandomInputStream ris = new RandomInputStream(r);
//			Lib.copy(ris, inst.getOutputStream(), new byte[8192], inst.getBufferSize());
			
			long millis = 3*1000;
			
			Scanner stdin = new Scanner(System.in);
			System.out.print("ready? y/n ");
			stdin.nextLine();

//			int count = 0;
//			
//			long beg,time;
//			beg = System.nanoTime();
//			while ( (time=(System.nanoTime()-beg)/1000000) < millis) {
//				inst.write(0x40);
//				inst.read();
//				count += 2;
//			}
//
//			System.out.println(SPEED.rateOverTime(count, time).toString(false));
//			
//			MessageDigest md = MessageDigest.getInstance("MD5");
//			DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), md);
//			
//			Lib.copy(ris, dos, new byte[8192], total);
//			dos.close();
//			
//			Lib.printBytesInHex(md.digest());
			
			
			final Thread[] worker = new Thread[2];
			
			worker[0] = new Thread(new Runnable() {
				public void run() {
					try {
						r.setSeed(seed);
						RandomInputStream ris = new RandomInputStream(r);
						
						//byte[] b = new byte[8192];
						
						long count = 0;
						
						long beg,time;
						beg = System.nanoTime();
						Lib.copy(null, inst.getOutputStream(), new byte[8192], count=8000000000L);
						inst.closeOutput();
						time = (System.nanoTime()-beg)/1000000;
						
						System.out.println(SPEED.rateOverTime(count, time).toString(true));
						
						
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
			worker[0].setDaemon(true);
			worker[0].setName("--client--");
			
			worker[1] = new Thread(new Runnable() {
				public void run() {
					try {
						
//						byte[] buff = new byte[8192];
//						long total = 0;
						
						Lib.copy(inst.getInputStream(), null);
						
						
					} catch (Exception e) {
						e.printStackTrace();
					}
					
				}
			});
			worker[1].setDaemon(true);
			worker[1].setName("--server--");
			
			
			worker[0].start();
			worker[1].start();
			
			
			worker[0].join();
			worker[1].join();			
			
			
			Lib.doNothing();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static String getState(Multiplexer home, int channel) {
		if (home==null) return null;
		Field[] field = home.getClass().getDeclaredFields();
		for (int i=0; i<field.length; i++) {
			try {
				String var = field[i].getName();
				if (var.startsWith("STATE_")&&home.getCM(channel).state == field[i].getLong(home)) {
					return field[i].getName();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return "STATE_UNKNOWN";
	}

}
