//This work is licensed under a GNU General Public License. Visit http://gnu.org/licenses/gpl-3.0-standalone.html for details.
//Genetic Control System Server. Copyright (©) 2016, Pecacheu (Bryce Peterson, bbryce.com)

package com.pecacheu.genctrl;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.OverlayLayout;
import javax.swing.WindowConstants;

public class Main extends JPanel {
	static final String MSG_BADGE = ColorCodes.parse(":cyan:[GenCtrl] :reset:");//(char)27+"[36m[GenCtrl] "+(char)27+"[0m";
	static final String ERR_BADGE = ColorCodes.parse(MSG_BADGE+":red:");//MSG_BADGE+(char)27+"[1;41m ";
	static final int PORT = 50542, BCAST_PORT = 50541;
	static final int GEN_DELAY = 10000;
	
	//Server Vars:
	private static final Scanner console = new Scanner(System.in);
	static volatile ServerSocket server = null;
	static volatile ChuList<Client> clients = new ChuList<Client>();
	static volatile TreeMap<String,Status> status = new TreeMap<String,Status>();
	static volatile ChuList<Status> pauseBreak = new ChuList<Status>();
	static volatile boolean dFlag = false, addPauseFlag = false;
	static volatile Thread cmdThread, cliThread, pingThread;
	
	static ChuList<GUIBar> bars = new ChuList<GUIBar>();
	static JFrame window; static JPanel pane;
	
	//Gen Vars:
	static volatile int genFlag = 0;
	static final Object genSync = new Object();
	private static boolean fRun = true;
	private static int swapRun = 0;
	private static final String fk1 = "_FAKE #1", fk2 = "_FAKE #2", fk3 = "_FAKE #3";
	private static int[][] vars;
	
	public static void main(String[] args) {
		if(loadConfig()) {
			new Thread(() -> { sleep(500); initThreads(); initGUI(); }).start();
			while(true) if(dFlag) { runDisable(); break; }
		}
	}
	
	public static void initThreads() {
		dbg("Starting Genetic Control..."); if(!startServer()) { disableServer(); return; }
		
		cmdThread = new Thread(() -> { while(true) try { //Read console commands:
			String cmdLine = waitForCmdLine(); //Wait for new line.
			if(cmdLine != null) {
				dbg("Commnad: "+cmdLine);
				if(cmdLine.equalsIgnoreCase("exit")) disableServer(); //Exit command.
				if(cmdLine.equalsIgnoreCase("run")) checkRun(false); //Run command.
			}
		} catch(Exception e) { err("Console thread error",e); }});
		
		cliThread = new Thread(() -> { while(true) try { //Connect clients:
			waitForClient();
		} catch(Exception e) { err("Client thread error",e); }});
		
		pingThread = new Thread(() -> { while(true) try { //Send keep-alive pings and broadcast packets:
			synchronized(genSync) {
				ChuIterator<Client> it = clients.chuIterator();
				while(it.hasNext()) {
					Client cli = it.next(); if(cli == null) continue;
					if(!cli.pingLoop()) { dbg("Connection to '"+cli.name+"' timed out!"); cli.close(); }
				}
				if(!addPauseFlag) broadcast();
				if(genFlag > 0) { genFlag--; if(genFlag <= 0) { genFlag = 0; checkRun(false); }}
			}
			sleep(10);
		} catch(Exception e) { err("Ping thread error",e); }});
		
		cmdThread.start(); cliThread.start(); pingThread.start(); //Start threads.
		dbg("Threads initialized.");
	}
	
	private static boolean loadConfig() {
		try {
			if(!ChuConf.confExists("config")) {
				ChuConf.unpack("config"+ChuConf.EXT, null);
				dbg("Saved example config. Please edit to match requirements."); return false;
			} else if(ChuConf.confExists("output")) {
				err("Output file 'output"+ChuConf.EXT+"' exists!"); return false;
			}
			
			ChuConf config = ChuConf.load("config"); Object vProp = config.getProp("vars");
			if(!(vProp instanceof ChuConfSection)) throw new Exception("Vars must be of type config section!");
			
			ChuIterator<Object> it = ((ChuConfSection)vProp).getPropList();
			vars = new int[it.list.size()][3];
			
			while(it.hasNext()) {
				Object prop = it.next();
				if(!(prop instanceof ChuList<?>)) throw new Exception("Vars entry must be list!");
				ChuList<?> list = (ChuList<?>)prop; int size = list.size();
				if(size != 2 && size != 3) throw new Exception("Vars entry must be list of length 2 or 3!");
				if(!(list.get(0) instanceof Integer)) throw new Exception("Vars entry must be list of Integer!");
				
				Integer min = (Integer)list.get(0), max = (Integer)list.get(1);
				if(min >= max) throw new Exception("Vars entry "+(it.index+1)+": Max ("+max+") must be greater than min ("+min+")!");
				
				vars[it.index][0] = min; vars[it.index][1] = max;
				vars[it.index][2] = (size==3)?(Integer)list.get(2):Integer.MIN_VALUE;
			}
		} catch(Exception e) { err("Could not load config",e); return false; }
		return true;
	}
	
	private static int bcastCount = 0;
	private static DatagramSocket bSocket = null;
	
	public static void broadcast() {
		if(bcastCount >= 300) { doBroadcast(); bcastCount = 0; }
		bcastCount++;
	}
	
	private static void doBroadcast() {
		try {
			if(bSocket == null) {
				bSocket = new DatagramSocket(); bSocket.setBroadcast(true);
			}
			byte[] bytes = {'G'}; bSocket.send(new DatagramPacket(bytes,
			bytes.length, InetAddress.getByName("255.255.255.255"), BCAST_PORT));
		} catch (Exception e) { err("Failed to send broadcast packet",e); }
	}
	
	public static boolean startServer() {
		dbg("Starting socket server on port "+PORT);
		try { server = new ServerSocket(PORT); }
		catch(Exception e) { err("Could not start server",e); return false; }
		return true;
	}
	
	public static void disableServer() { dFlag = true; }
	
	private static void runDisable() { synchronized(genSync) {
		dbg("Stopping threads...");
		cmdThread.stop(); cliThread.stop(); pingThread.stop(); console.close();
		if(server != null) try {
			dbg("Closing sockets...");
			ChuIterator<Client> it = clients.chuIterator(); while(it.hasNext()) it.next().close();
			dbg("Disabling server."); server.close(); server = null;
			if(window != null) { window.dispose(); window = null; }
		} catch(Exception e) { err("Could not close server",e); return; }
	}}
	
	public static void waitForClient() {
		if(server != null) try {
			dbg("Client thread: Waiting for clients...");
			Socket socket = server.accept(); //.accept() blocks until connection.
			synchronized(genSync) {
				if(addPauseFlag && pauseBreak.size() == 0) return;
				Client cli = new Client(socket); if(addPauseFlag) { cli.pbStat = pauseBreak.remove(0);
				cli.alt = cli.pbStat.cli.alt; cli.pbStat.cli = cli; } clients.add(cli);
				dbg("Client thread: New client connected!");
			}
		} catch(Exception e) { err("Error while opening client connection",e); }
	}
	
	public static String waitForCmdLine() {
		sleep(50); try { return console.nextLine(); } catch(Exception e) { return null; }
	}
	
	//------ Genetic Algorithm Code:
	
	//Extra 'imaginary' computers if there aren't enough clients:
	private static Status swapA = new Status(null,true),
	swapB = new Status(null,true), swapC = new Status(null,true);
	
	private static void initVars(Status stat) { //Set vars to initial state:
		for(int i=0,l=vars.length; i<l; i++) {
			int min = vars[i][0], max = vars[i][1], init = vars[i][2];
			stat.args.set(i, init==Integer.MIN_VALUE?rand(min, max):init);
		}
	}
	
	private static void doSwap(int num) {
		status.forEach((key, stat) -> { if(!stat.isFake && !stat.running) {
			dbg("doSwap Launcher, cli = '"+key+"'");
			if(!swapA.running && !swapA.hasRun) { swapA.cli = stat.cli; swapA.startSim(); dbg("Launch SWAPA"); }
			else if(num >= 2 && !swapB.running && !swapB.hasRun) { swapB.cli = stat.cli; swapB.startSim(); dbg("Launch SWAPB"); }
			else if(num >= 3 && !swapC.running && !swapC.hasRun) { swapC.cli = stat.cli; swapC.startSim(); dbg("Launch SWAPC"); }
		}});
	}
	
	private static ChuList<Status> sortStats() {
		ChuList<String> keySet = new ChuList<String>(status.keySet());
		ChuList<Status> sorted = new ChuList<Status>();
		for(int f=0,a=status.size(); f<a; f++) {
			Iterator<String> it = keySet.iterator();
			Status best = null; String k="", bestK="";
			while(it.hasNext()) {
				k=it.next(); Status stat = status.get(k); if(best == null
				|| stat.result < best.result) { best = stat; bestK = k; }
			}
			keySet.remove(bestK); sorted.push(best);
		}
		return sorted;
	}
	
	private static boolean crCancel = false;
	public static void checkRun(boolean delay) { synchronized(genSync) {
		crCancel = status.isEmpty(); status.forEach((key, stat) -> {
			if(stat.running) { crCancel = true; return; }
		});
		if(!crCancel && pauseBreak.size() == 0) setupNextGen(delay);
		else dbg("GenRun: Not ready to run yet!");
	}}
	
	private static int sngCnt = 0;
	private static void setupNextGen(boolean delay) {
		if(!addPauseFlag) { addPauseFlag = true; dbg("Client Thread: Client ADD/REMOVE disabled."); }
		updateStatGUI(); dbg("GenRun: Setting up for next genrun...");
		//Count number of real clients (at least, ones with names)
		sngCnt = 0; status.forEach((key, stat) -> { if(!stat.isFake) sngCnt++; });
		if(sngCnt <= 3) { //Double with imaginary clients if 3 or less:
			int iNum = 4-sngCnt; status.put(fk1, swapA);
			if(iNum >= 2) status.put(fk2, swapB); else status.remove(fk2);
			if(iNum >= 3) status.put(fk3, swapC); else status.remove(fk3);
			if(fRun) initGen(); else if(swapRun < iNum) { doSwap(iNum); swapRun++; } else finishGen(delay);
		} else { status.remove(fk1); status.remove(fk2); status.remove(fk3); if(fRun) initGen(); else finishGen(delay); }
	}
	
	private static void initGen() {
		dbg("GenRun: Loading initial generation...");
		status.forEach((key, stat) -> { initVars(stat); if(!stat.isFake) stat.startSim(); });
		swapRun = 0; swapA.reset(); swapB.reset(); swapC.reset(); fRun = false;
	}
	
	private static void finishGen(boolean delayed) {
		if(delayed) {
			if(addPauseFlag) { addPauseFlag = false; dbg("Client Thread: Client ADD/REMOVE enabled."); }
			resultsGUI(); genFlag = GEN_DELAY/10;
		} else runNextGen();
	}
	
	private static void runNextGen() {
		synchronized(System.out) { System.out.println();
		dbg("GenRun: Calculating next generation..."); }
		
		//Sort to find best & worst performers:
		ChuList<Status> sorted = sortStats();
		
		Status best = sorted.get(0), worst = sorted.get(sorted.length-1);
		dbg("GenRun: Best performer was '"+best.cli.name+"' with score of "+best.result+".");
		dbg("GenRun: Worst performer was '"+worst.cli.name+"' with score of "+worst.result+".");
		
		//Crossover phase:
		int skip = (int)Math.ceil(sorted.length/2.f)-1, out=0;
		ChuList<Integer> bArgs = best.args;
		
		for(int i=1,l=sorted.length-skip; i<l; i++) {
			int cPoint = rand(0,vars.length-1); ChuList<Integer> args = sorted.get(i).args,
			argsA = args.subList(0, cPoint).addAll(bArgs.subList(cPoint, bArgs.size())),
			argsB = bArgs.subList(0, cPoint).addAll(args.subList(cPoint, args.size()));
			
			if(argsA.size() != 3 || argsB.size() != 3) {
				err("\n\nLENGTH IS WRONG! Generating Debug Report...\n");
				dbg("bArgs: "+bArgs.toString()+", cPoint: "+cPoint+", args: "+args.toString());
				dbg("argsA: "+args.subList(0, cPoint).toString()+" + "+bArgs.subList(cPoint, bArgs.size()).toString());
				dbg("args(0, "+cPoint+") & bArgs("+cPoint+", "+bArgs.size()+")");
				dbg("argsB: "+bArgs.subList(0, cPoint).toString()+" + "+args.subList(cPoint, args.size()).toString());
				dbg("bArgs(0, "+cPoint+") & args("+cPoint+", "+args.size()+")");
			} else {
				dbg("GenRun: Cut point "+cPoint+" produced children "+argsA.toString()+" and "+argsB.toString());
			}
			
			sorted.get(out).args = argsA; out++;
			if(out<sorted.length) { sorted.get(out).args = argsB; out++; } else dbg("Odd client count, skipped last child.");
		}
		
		//Mutation phase:
		/*for(int i=0,l=sorted.length; i<l; i++) for(int f=0,a=vars.length; f<a; f++) {
			if(Math.random()>=0.8) {
				int num = rand(vars[f][0], vars[f][1]);
				dbg("GenRun: '"+sorted.get(i).cli.name+"' had mutation on item "+f+" to "+num);
				sorted.get(i).args.set(f, num);
			}
		}*/
		
		//Mutation phase v2:
		for(int i=0,l=sorted.length; i<l; i++) {
			ChuList<Integer> args = sorted.get(i).args;
			for(int f=0,a=vars.length; f<a; f++) if(Math.random()>=0.8) {
				int min = vars[f][0], max = vars[f][1], maxChg = (int)Math
				.round((max-min)/1.5), num = args.get(f)+rand(-maxChg, maxChg);
				if(num < min) num = min; if(num > max) num = max; args.set(f, num);
				dbg("GenRun: '"+sorted.get(i).cli.name+"' had mutation on item "+f+" to "+num);
			}
		}
		
		//Start sim for next generation:
		dbg("GenRun: Launching generation...");
		status.forEach((key, stat) -> { if(!stat.isFake) stat.startSim(); });
		swapRun = 0; swapA.reset(); swapB.reset(); swapC.reset();
	}
	
	//------ GUI Interface Code:
	
	public static void initGUI() {
		window = new JFrame("Genetic Supercomputer Control");
		window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		window.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent windowEvent) {
				window = null; disableServer();
			}
		});
		pane = new JPanel(); window.add(pane);
		pane.setLayout(new OverlayLayout(pane));
		pane.setBackground(Color.BLACK);
		window.setMinimumSize(new Dimension(640, 480));
		window.pack(); window.setVisible(true);
		updateStatGUI();
	}
	
	public static void updateStatGUI() { synchronized(genSync) { if(window != null) {
		int cLen = clients.size(); if(bars.size() != cLen || resGUI) {
			bars.forEach((bar) -> { bar.remove(); }); bars.clear();
			resGUI = false; float cl = cLen+1; for(int h=1; h<=cLen; h++) {
				bars.add(new GUIBar(pane, new Point(20, (int)(h/cl*100.f)), ""));
			}
		}
		for(int i=0,l=bars.size(); i<l; i++) {
			GUIBar bar = bars.get(i); String name = clients.get(i).name;
			if(name != null && status.containsKey(name)) {
				Status stat = status.get(name);
				if(stat.running) {
					bar.label = name+", "+stat.args.toString()+", Running...";
					bar.progress = stat.status; bar.setForeground(null);
				} else if(stat.cli.alt != null) {
					Status alt = stat.cli.alt; if(alt.running) {
						bar.label = name+", "+alt.args.toString()+", Alt Running...";
						bar.progress = alt.status; bar.setForeground(GUIBar.DARK_BLUE);
					} else {
						bar.label = name+", Alt Stop"; bar.progress = 100;
						bar.setForeground(Color.DARK_GRAY);
					}
				} else {
					bar.label = name+", Idle"; bar.progress = 100;
					bar.setForeground(Color.DARK_GRAY);
				}
			} else {
				bar.label = "[LOADING]"; bar.progress = 0;
			}
		}
		window.revalidate(); window.repaint();
	}}}
	
	private static boolean resGUI = false;
	public static void resultsGUI() { synchronized(genSync) { if(window != null) {
		bars.forEach((bar) -> { bar.remove(); }); bars.clear();
		
		ChuList<Status> sorted = sortStats();
		int /*best = sorted.get(0).result,*/ worst = sorted.get(sorted.length-1).result;
		ChuList<String> results = new ChuList<String>(sorted.size());
		
		int sLen = sorted.length; float cl = sLen+1; for(int i=0; i<sLen; i++) {
			Status stat = sorted.get(i);
			
			//Get name and arguments:
			String name = stat.isFake?(stat.equals(swapA)?"SWAPA":(stat.equals(swapB)?"SWAPB":(stat.equals(swapC)?"SWAPC":"???"))):stat.cli.name;
			String args = stat.args.toString(), label = name+(stat.isFake?" (run by "+stat.cli.name+")":"")+", "+args+", Result: "+stat.result;
			
			//Add to results list:
			results.push(name+" -- "+args+" -> "+stat.result);
			
			//Display in GUI window:
			GUIBar bar = new GUIBar(pane, new Point(20, (int)((i+1)/cl*100.f)), label);
			bar.progress = Math.round(map(stat.result, worst, 0/*best*/, 0, 100));
			bar.setBackground(GUIBar.ORANGE); bar.setForeground(GUIBar.CYAN); bars.add(bar);
		}
		window.revalidate(); window.repaint(); resGUI = true;
		writeResults(results);
	}}}
	
	private static int genNum = 0;
	private static void writeResults(ChuList<String> results) { synchronized(genSync) {
		genNum++; dbg("Writing generation "+genNum+" to results log.");
		try {
			ChuConf output; //Open/create file:
			if(ChuConf.confExists("output")) output = ChuConf.load("output");
			else output = new ChuConf();
			//Write data:
			ChuConfSection data = new ChuConfSection();
			for(int i=results.size()-1; i>=0; i--) data.addProp(results.get(i));
			//results.forEach((res) -> { data.addProp(res); });
			//String[] resData = res.split(Pattern.quote("{*}"));
			//ChuConf.KEY_CHECK.matcher(resData[0]).replaceAll("_");
			output.setSection("gen"+genNum, data); output.save("output");
		} catch(Exception e) { err("Error while writing results file",e); }
	}}
	
	//------ Useful Functions:
	public static void err(String msg) {
		synchronized(System.out) { System.err.println(ERR_BADGE+"Error: "+msg); }
	} public static void err(String msg, Exception e) {
		synchronized(System.out) { System.err.println(ERR_BADGE+msg+":"); e.printStackTrace(); }
	} public static void err(Exception e) {
		synchronized(System.out) { System.err.println(ERR_BADGE+"Error:"); e.printStackTrace(); }
	}
	
	public static void dbg(String str) {
		synchronized(System.out) { System.out.println(MSG_BADGE+str); }
	}
	
	public static boolean sleep(long millis) {
		try { Thread.sleep(millis); } catch(InterruptedException e) { return false; }
		return true;
	}
	
	public static float map(float input, float minIn, float maxIn, float minOut, float maxOut) {
		return ((input-minIn)/(maxIn-minIn)*(maxOut-minOut))+minOut;
	}
	
	//Emulate JavaScript's fromCharCode Function:
	public static String fromCharCode(int... codePoints) {
		return new String(codePoints, 0, codePoints.length);
	}
	
	//From JS Utils:
	public static int rand(int min, int max) { return (int)Math.floor(Math.random()*(max-min+1)+min); }
}

class ClientReader {
	private Client cli;
	
	ClientReader(Client c) {
		cli = c; Main.updateStatGUI();
	}
	
	public void read(char cmd, String line) { synchronized(Main.genSync) {
		//TODO FOR DEBUG:
		//Main.dbg("LINE FROM "+cli.name+": "+cmd+"."+line);
		if(cmd == 'N') { //NAME command:
			if(cli.name != null) {
				Main.err("Client Reader: Client '"+cli.name+"' attempted to change it's name!");
			} else if(Main.status.containsKey(line)) {
				Main.err("Duplicate client name '"+line+"'! Disconnecting client...");
				try { cli.close(); } catch(Exception e) { Main.err(e); }
			} else if(!Main.addPauseFlag) { //Normal Connect & Name:
				Main.status.put(line, new Status(cli)); cli.name = line;
				Main.dbg("Client Reader: New client's name is '"+line+"'");
				Main.updateStatGUI();
			} else if(cli.pbStat != null) { //Reconnect & Rename:
				Main.status.put(line, cli.pbStat);
				Main.dbg("Client Reader: Reconnected '"+line+"' as '"+line+"'.");
				if(cli.alt != null && cli.alt.running) {
					Main.dbg("Re-starting ALT simulation..."); cli.alt.cli = cli; cli.alt.startSim();
				} else if(cli.pbStat.running) {
					Main.dbg("Re-starting saved simulation..."); cli.pbStat.startSim();
				} else Main.dbg("No saved simulations to start.");
				cli.name = line; Main.updateStatGUI();
			} else cli.close(true);
		} else if(cmd == 'U') { //STATUS UPDATE command:
			//Main.dbg("Client Reader: Client '"+cli.name+"' progress: "+line);
			if(cli.alt != null) cli.alt.setStatus(line); else Main.status.get(cli.name).setStatus(line);
		} else if(cmd == 'R') { //RESULT command:
			Main.dbg("Client Reader: Client '"+cli.name+"': Sim finished! Result was "+line);
			if(cli.alt != null) cli.alt.setResult(line); else Main.status.get(cli.name).setResult(line);
		}
	}}
	
	public void removed() {
		if(Main.addPauseFlag) {
			Main.err("Client Thread: Client '"+cli.name+"' disconnected with add/remove locked!");
			Main.dbg("Please reconnect to continue."); Main.pauseBreak.push(Main.status.remove(cli.name));
		} else {
			if(cli.name != null) Main.status.remove(cli.name); Main.updateStatGUI();
		}
	}
}

class Status {
	public Client cli;
	public ChuList<Integer> args = new ChuList<Integer>();
	public Integer status = 0, result = 0;
	public boolean running = false, hasRun = false, isFake = false;
	
	Status(Client c) { cli = c; }
	Status(Client c, boolean f) { cli = c; isFake = f; }
	
	public void startSim() {
		reset(); running = true; cli.sendMsg('S'+args.join(","));
		if(isFake) cli.alt = this;
	}
	
	public void setStatus(String s) { try {
		status = new Integer(s); result = 0; Main.updateStatGUI();
	} catch(NumberFormatException e) {}}
	
	public void setResult(String s) { try {
		result = new Integer(s); status = 100; running = false; hasRun = true;
		Main.checkRun(true);
	} catch(NumberFormatException e) {}}
	
	public void reset() {
		result = 0; status = 0; running = false; hasRun = false;
		if(isFake && cli != null) cli.alt = null;
	}
}

class GUIBar extends JComponent {
	private JComponent parent;
	private volatile Point pos;
	
	public volatile int progress = 0;
	public volatile String label;
	
	//Custom Colors:
	public static final Color DARK_GREEN = new Color(20, 200, 20),
	DARK_BLUE = new Color(20, 50, 120),
	CYAN = new Color(0,115,255),
	ORANGE = new Color(230,126,0);//255,140,0
	
	//Draw Properties:
	private static final int BAR_HEIGHT = 60,
	BORDER = 5, ROUND = 10;//, OFFSET = 24;
	
	GUIBar(JComponent p, Point n, String l) {
		p.add(this); parent = p; label = l;
		super.setForeground(DARK_GREEN);
		super.setBackground(Color.YELLOW);
		setPosition(n);
	}
	
	public void setPosition(Point n) {
		pos = n;
	} public void setPosition(int x, int y) {
		setPosition(new Point(x, y));
	}
	
	@Override
	public void setForeground(Color c) {
		super.setForeground(c==null?DARK_GREEN:c);
	}
	@Override
	public void setBackground(Color c) {
		super.setBackground(c==null?Color.YELLOW:c);
	}
	
	public void remove() {
		parent.remove(this);
	}
	
	@Override
	protected void paintComponent(Graphics g) {
		Dimension pSize = parent.getSize();
		int height = (int)Math.floor(pSize.height*(pos.y/100.f))-(BAR_HEIGHT/2),
		maxWidth = pSize.width-(pos.x*2)-(BORDER*2),
		width = (int)Math.floor(maxWidth*(progress/100.f));
		
		g.setColor(super.getBackground()); g.setFont(new Font("Arial", Font.BOLD, 24));
		g.drawString(label, pos.x+8, height-6);
		g.fillRoundRect(pos.x, height, pSize.width-(pos.x*2), BAR_HEIGHT, ROUND, ROUND);
		
		g.setColor(super.getForeground());
		g.fillRoundRect(pos.x+BORDER, height+BORDER, width, BAR_HEIGHT-(BORDER*2), ROUND, ROUND);
	}
}