package com.github.ezekielnewren.net.multiplexer;

public class FastQueue {

	private Node first;
	private Node last;
	private int length;
	
	private static class Node {
		long value;
		Node next;
		Node(long v) {
			value = v;
			next = null;
		}
	}
	
	public FastQueue() {
		first = null;
		last = null;
		length = 0;
	}
	
	public void clear() {
		first = last = null;
		length = 0;
	}
	
	public void add(long v) {
		Node n = new Node(v);
		if (first==null) {
			first = n;
			//last = n; // not necessary and is significantly slower
		} else if (first.next==null) {
			first.next = n;
			last = first.next;
		} else {
			last.next = n;
			last = last.next;
		}
		length++;
	}
	
	public void add(boolean v) {
		add(v?-1L:0);
	}
	
	public void add(double v) {
		add(Double.doubleToRawLongBits(v));
	}
	
	public long getFirstValue() {
		return first.value;
	}
	
	public long getLastValue() {
		return last.value;
	}
	
	public void setFirstValue(long val) {
		first.value = val;
	}
	
	public void setLastValue(long val) {
		last.value = val;
	}
	
	public long peek() {
		return first.value;
	}
	
	public long pop() {
		long tmp = first.value;
		first = first.next;
		length--;
		return tmp;
	}
	
	public double popDouble() {
		return Double.longBitsToDouble(pop());
	}
	
	public float popFloat() {
		return (float) popDouble();
	}
	
	public long popLong() {
		return pop();
	}
	
	public int popInt() {
		return (int) pop();
	}
	
	public short popShort() {
		return (short) pop();
	}
	
	public char popChar() {
		return (char) pop();
	}
	
	public byte popByte() {
		return (byte) pop();
	}
	
	public boolean popBoolean() {
		return pop()<0;
	}
	
	public void push(long val) {
		Node n = new Node(val);
		if (first == null) {
			first = n;
			last = first;
		} else  {
			n.next = first;
			first = n;
		}
	}
	
	public int size() {
		return length;
	}
	
}
