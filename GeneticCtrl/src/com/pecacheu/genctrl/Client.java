package com.pecacheu.genctrl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class Client {
	public volatile String name = null;
	public Status pbStat = null;
	public volatile Status alt = null;
	
	private Socket socket;
	private BufferedReader in;
	private PrintWriter out;
	private ClientReader reader;
	private Thread thread;
	
	volatile int pingTime = 0, pingSendTime = 0;
	
	Client(Socket s) throws Exception {
		socket = s; //socket.setTcpNoDelay(true);
		
		in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		out = new PrintWriter(socket.getOutputStream(), true);
		reader = new ClientReader(this);
		
		thread = new Thread(() -> { while(true) try { //Connect clients:
			String line = readLine(); if(line.length() > 0) { pingTime = 0; reader.read(line.charAt(0), line.substring(1)); }
		} catch(Exception e) { Main.err("Client '"+name+"' thread error",e); }});
		thread.start();
	}
	
	private String readLine() {
		try { String str = in.readLine(); return str!=null?str:""; }
		catch(Exception e) { Main.err("Client '"+name+"' read failed! Disconnecting client..."); close(); }
		return "";
	}
	
	public synchronized void sendMsg(String msg) { out.println(msg); }
	
	public void close() { close(false); }
	public void close(boolean noEvent) { synchronized(Main.genSync) {
		Main.clients.remove(this);
		try { thread.stop(); if(!noEvent) reader.removed(); socket.close(); }
		catch(Exception e) { Main.err("Could not close socket!",e); }
	}}
	
	public boolean pingLoop() {
		if(pingSendTime >= 200) sendPing();
		if(pingTime > 400) return false;
		pingTime++; pingSendTime++; return true;
	}
	
	public void resetPing() { pingTime = 0; }
	private void sendPing() { pingSendTime = 0; sendMsg("P"); }
}