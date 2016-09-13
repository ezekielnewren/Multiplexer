package com.github.ezekielnewren.net.multiplexer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.LinkedList;

public class MuxDriver {

	private static final long TIMEOUT = Long.MAX_VALUE;

	public static void main(String[] args) throws Exception {
		simple();
		
	}
	
	public static void complex() throws Exception {
		LinkedList<Thread> tlist = new LinkedList<Thread>();
		
		ByteArrayCircularBuffer near = new ByteArrayCircularBuffer(600);
		ByteArrayCircularBuffer far = new ByteArrayCircularBuffer(323);

		int[] chans = new int[100];
		for (int i=0; i<chans.length; i++) chans[i] = i;
		
		Multiplexer clientMux = new Multiplexer(near.getInputStream(), far.getOutputStream());
		clientMux.segregator.handle.setName("--clientSide--segregator");
		Multiplexer serverMux = new Multiplexer(far.getInputStream(), near.getOutputStream(), chans);
		serverMux.segregator.handle.setName("--serverSide--segregator");
		
		int actors = 4;
		
		final Channel[] io = new Channel[actors];
		final String[] name = {
			"alice",
			"bob",
			"chuck",
			"HOST",
		};
		int i=0;
		
		tlist.add(new Actor(serverMux, name[i], i++) {
			public void task() throws Exception {
				Channel alice = smux.accept(17, 101, TIMEOUT);
				Channel bob = smux.accept(47, 50, TIMEOUT);
				Channel chuck = smux.accept(99, 8192, TIMEOUT);
				
				Thread.sleep(1);
				
				alice.close();
				bob.close();
				chuck.close();
			}
		}.start());
		
		tlist.add(new Actor(clientMux, name[i], i++) {
			public void task() throws IOException {
				io[id] = cmux.connect(17, 100, TIMEOUT);
				
				io[id].close();
			}
		}.start());
		
		tlist.add(new Actor(clientMux, name[i], i++) {
			public void task() throws IOException {
				io[id] = cmux.connect(47, 100, TIMEOUT);
				
				
				
				io[id].close();
			}
		}.start());
		
		tlist.add(new Actor(clientMux, name[i], i++) {
			public void task() throws IOException {
				io[id] = cmux.connect(99, 100, TIMEOUT);
				
				
				
				io[id].close();
			}
		}.start());
		
		
		
		
		
		
		
		
		
		
		
		Iterator<Thread> it = tlist.iterator();
		while (it.hasNext()) {
			it.next().join();
		}
	}
	
	public static void simple() throws Exception {
		String ip = "localhost";
		int port = 8888;
		ServerSocket ss = new ServerSocket(port);
		final Socket client = new Socket(ip, port);
		final Socket server = ss.accept();

		final int channel = 17;
		final int bufferSize = 23;
		
		
		new Thread(new Runnable() {
			public void run() {
				try {
					ClientMultiplexer m = new Multiplexer(client.getInputStream(), client.getOutputStream());
					
					Channel cm = m.connect(channel, bufferSize);
					
					DataInputStream in = new DataInputStream(cm.getInputStream());
					DataOutputStream out = new DataOutputStream(cm.getOutputStream());
					
					String original = "hello world";
					
					out.writeUTF(original);
					String deserialized = in.readUTF();
					
					System.out.println(original+"=="+deserialized+(original.equals(deserialized)));
					
					cm.close();
					
					m.close();
					
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).start();
		
		new Thread(new Runnable() {
			public void run() {
				try {
					ServerMultiplexer m = new Multiplexer(server.getInputStream(), server.getOutputStream(), channel);
					
					Channel cm = m.accept(channel, bufferSize);
					
					DataInputStream in = new DataInputStream(cm.getInputStream());
					DataOutputStream out = new DataOutputStream(cm.getOutputStream());
					
					String data = in.readUTF();
					out.writeUTF(data);
					
					cm.close();
					
					m.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).start();
		
		
	}
	
	
	static abstract class Actor implements Runnable {

		Thread handle;
		Multiplexer mux;
		ClientMultiplexer cmux;
		ServerMultiplexer smux;
		int id;
		
		public Actor(Multiplexer m, String name, int id) {
			handle = new Thread(this, name);
			handle.setDaemon(true);
			mux = m;
			cmux = mux;
			smux = mux;
			this.id = id;
		}
		
		public abstract void task() throws Exception;
		
		@Override
		public void run() {
			try {
				task();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		public Thread start() {
			handle.start();
			return handle;
		}
		
	}
	
	public static String getState(Multiplexer home, int channel) {
		if (home==null) return null;
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
