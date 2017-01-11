package com.pecacheu.genctrl;

public class ColorCodes {
	private static boolean ENABLED = !System.getProperty("os.name").startsWith("Win");
	
	private static final String[] NAMES = {
		"reset",
		"black",
		"red",
		"green",
		"yellow",
		"blue",
		"purple",
		"cyan",
		"white"
	};
	
	private static final String[] CODES = {
		"0",
		"30;40;1",
		"31;40;1",
		"32;40;1",
		"33;40;1",
		"34;40;1",
		"35;40;1",
		"36;40;1",
		"37;40;1"
	};
	
	public static String parse(String s) {
		String[] sl = s.split(":"); String sr = sl[0];
		for(int i=1,l=sl.length; i<l; i++) {
			String code = getCharCode(sl[i]);
			sr += (code!=null)?(ENABLED?code:""):sl[i];
		}
		return sr;
	}
	
	private static String getCharCode(String comp) {
		for(int i=0,l=NAMES.length; i<l; i++) {
			if(NAMES[i].equalsIgnoreCase(comp)) return "\033["+CODES[i]+"m";
		}
		return null;
	}
}