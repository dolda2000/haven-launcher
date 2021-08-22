/*
 *  This file is part of the Haven Java Launcher.
 *  Copyright (C) 2019 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven.launcher;

import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.net.*;

public class Config {
    public static final int MAJOR_VERSION = 1;
    public static final int MINOR_VERSION = 2;
    public final Collection<Resource> classpath = new ArrayList<>();
    public final Collection<Resource> include = new ArrayList<>();
    public final Collection<String> jvmargs = new ArrayList<>();
    public final Collection<String> cmdargs = new ArrayList<>();
    public final Collection<URI> included = new HashSet<>();
    public final Collection<NativeLib> libraries = new ArrayList<>();
    public final Map<String, String> sysprops = new HashMap<>();
    public Resource chain = null;
    public String mainclass = null;
    public Resource execjar = null;
    public String title = null;
    public Resource splashimg = null, icon = null;
    public int heapsize = 0;

    public static class Environment {
	public static final URI opaque = URI.create("urn:nothing");
	public Collection<Validator> val = Collections.emptyList();
	public Map<String, String> par = Collections.emptyMap();
	public URI rel = opaque;

	public Environment val(Collection<Validator> val) {this.val = val; return(this);}
	public Environment par(Map<String, String> par) {this.par = par; return(this);}
	public Environment rel(URI rel) {this.rel = rel; return(this);}

	public static Environment from(Resource res) {
	    return(new Environment().val(res.val).rel(res.uri));
	}
    }

    public static int iparcmp(String a, String b) {
	int x, y;
	try {x = Integer.parseInt(a);} catch(NumberFormatException e) {x = Integer.MIN_VALUE;}
	try {y = Integer.parseInt(b);} catch(NumberFormatException e) {y = Integer.MIN_VALUE;}
	return((x < y) ? -1 : (x > y) ? 1 : 0);
    }

    public static List<?> verparse(String ver) {
	List<Object> ret = new ArrayList<>();
	int p = 0;
	while(p < ver.length()) {
	    char c = ver.charAt(p++);
	    if((c >= '0') && (c <= '9')) {
		int n = c - '0';
		while(p < ver.length()) {
		    c = ver.charAt(p);
		    if(!((c >= '0') && (c <= '9')))
			break;
		    p++;
		    n = (n * 10) + (c - '0');
		}
		ret.add(n);
	    } else {
		StringBuilder buf = new StringBuilder();
		buf.append(c);
		while(p < ver.length()) {
		    c = ver.charAt(p);
		    if((c >= '0') && (c <= '9'))
			break;
		    p++;
		    buf.append(c);
		}
		ret.add(buf.toString());
	    }
	}
	return(ret);
    }

    public static int vparcmp(String a, String b) {
	List<?> x = verparse(a), y = verparse(b);
	int p = 0;
	while((p < x.size()) && (p < y.size())) {
	    Object j = x.get(p), k = y.get(p);
	    p++;
	    if((j instanceof Integer) && (k instanceof String)) {
		return(-1);
	    } else if((j instanceof String) && (k instanceof Integer)) {
		return(1);
	    } else if((j instanceof Integer) && (k instanceof Integer)) {
		int c = ((Integer)j).compareTo((Integer)k);
		if(c != 0) return(c);
	    } else if((j instanceof String) && (k instanceof String)) {
		int c = ((String)j).compareTo((String)k);
		if(c != 0) return(c);
	    }
	}
	if(x.size() > p)
	    return(1);
	else if(y.size() > p)
	    return(-1);
	return(0);
    }

    public static String expand(String s, Environment env) {
	StringBuilder buf = new StringBuilder();
	int p = 0;
	while(p < s.length()) {
	    char c = s.charAt(p++);
	    if(c == '$') {
		if(p >= s.length())
		    throw(new RuntimeException("unexpected expansion at end-of-line: " + s));
		char x = s.charAt(p++);
		if(x == '$') {
		    buf.append('$');
		} else if(x == '{') {
		    int p2 = s.indexOf('}', p);
		    if(p2 < 0)
			throw(new RuntimeException("unterminated parameter expansion: " + s));
		    String par = s.substring(p, p2);
		    p = p2 + 1;
		    if(par.startsWith("p:")) {
			buf.append(System.getProperty(par.substring(2), ""));
		    } else {
			buf.append(env.par.getOrDefault(par, ""));
		    }
		    /*
		} else if(x == 's') {
		    char d = s.charAt(p++);
		    int p2 = s.indexOf(d, p), p3 = s.indexOf(d, p2 + 1), p4 = s.indexOf(d, p3 + 1);
		    if((p2 < 0) || (p3 < 0) || (p4 < 0))
			throw(new RuntimeException("unterminated substitution expansion: " + s));
		    String pat = s.substring(p, p2)
		    p = p4 + 1;
		    */
		} else {
		    throw(new RuntimeException("unknown expansion `" + x + "': " + s));
		}
	    } else {
		buf.append(c);
	    }
	}
	return(buf.toString());
    }

    private void when(String[] words, Environment env) {
	int a = 1;
	while(true) {
	    if(a >= words.length)
		throw(new RuntimeException("unterminated `when' stanza: " + Arrays.asList(words)));
	    String w = words[a++];
	    if(w.equals(":")) {
		break;
	    } else if(w.equals("!")) {
		if(a >= words.length) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(!expand(words[a++], env).equals(""))
		    return;
	    } else if(w.equals("==")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(!expand(words[a++], env).equals(expand(words[a++], env)))
		    return;
	    } else if(w.equals("!=")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(expand(words[a++], env).equals(expand(words[a++], env)))
		    return;
	    } else if(w.startsWith("~=")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		int fl = 0;
		if(w.indexOf('i') >= 0)
		    fl |= Pattern.CASE_INSENSITIVE;
		if(!Pattern.compile(words[a++], fl).matcher(expand(words[a++], env)).matches())
		    return;
	    } else if(w.equals(">")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(iparcmp(expand(words[a++], env), expand(words[a++], env)) <= 0) return;
	    } else if(w.equals(">=")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(iparcmp(expand(words[a++], env), expand(words[a++], env)) < 0) return;
	    } else if(w.equals("<")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(iparcmp(expand(words[a++], env), expand(words[a++], env)) >= 0) return;
	    } else if(w.equals("<=")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(iparcmp(expand(words[a++], env), expand(words[a++], env)) > 0) return;
	    } else if(w.equals(".>")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(vparcmp(expand(words[a++], env), expand(words[a++], env)) <= 0) return;
	    } else if(w.equals(".>=")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(vparcmp(expand(words[a++], env), expand(words[a++], env)) < 0) return;
	    } else if(w.equals(".<")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(vparcmp(expand(words[a++], env), expand(words[a++], env)) >= 0) return;
	    } else if(w.equals(".<=")) {
		if(a >= words.length - 1) throw(new RuntimeException("unexpected `when' operator at end-of-line: " + Arrays.asList(words)));
		if(vparcmp(expand(words[a++], env), expand(words[a++], env)) > 0) return;
	    } else {
		if(expand(w, env).equals(""))
		    return;
	    }
	}
	command(Arrays.copyOfRange(words, a, words.length), env);
    }

    public void command(String[] words, Environment env) {
	    if((words == null) || (words.length < 1))
		return;
	    switch(words[0]) {
	    case "require": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: require MAJOR.MINOR"));
		int maj, min;
		try {
		    int p = words[1].indexOf('.');
		    if(p < 0)
			throw(new RuntimeException("usage: require MAJOR.MINOR"));
		    maj = Integer.parseInt(words[1].substring(0, p));
		    min = Integer.parseInt(words[1].substring(p + 1));
		} catch(NumberFormatException e) {
		    throw(new RuntimeException("usage: require MAJOR.MINOR", e));
		}
		if((maj != MAJOR_VERSION) || (min > MINOR_VERSION))
		    throw(new RuntimeException(String.format("invalid version of launcher; launch file requires %d.%d, this is %d.%d",
							     maj, min, MAJOR_VERSION, MINOR_VERSION)));
		break;
	    }
	    case "rel": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: rel URI"));
		try {
		    env.rel(new URI(expand(words[1], env)));
		} catch(URISyntaxException e) {
		    throw(new RuntimeException("usage: rel URL", e));
		}
		break;
	    }
	    case "validate": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: validate VALIDATOR..."));
		Collection<Validator> nval = new ArrayList<>();
		for(int i = 1; i < words.length; i++) {
		    Validator v = Validator.parse(expand(words[i], env));
		    if(v != null)
			nval.add(v);
		}
		env.val = nval;
		break;
	    }
	    case "title": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: title TITLE"));
		title = expand(words[1], env);
		break;
	    }
	    case "splash-image": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: splash-image URL"));
		try {
		    splashimg = new Resource(env.rel.resolve(new URI(expand(words[1], env))), env.val);
		} catch(URISyntaxException e) {
		    throw(new RuntimeException("usage: splash-image URL", e));
		}
		break;
	    }
	    case "icon": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: icon URL"));
		try {
		    icon = new Resource(env.rel.resolve(new URI(expand(words[1], env))), env.val);
		} catch(URISyntaxException e) {
		    throw(new RuntimeException("usage: icon URL", e));
		}
		break;
	    }
	    case "chain": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: chain URL"));
		try {
		    chain = new Resource(env.rel.resolve(new URI(expand(words[1], env))), env.val);
		} catch(URISyntaxException e) {
		    throw(new RuntimeException("usage: chain URL", e));
		}
		break;
	    }
	    case "main-class": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: main-class CLASS-NAME"));
		mainclass = expand(words[1], env);
		break;
	    }
	    case "exec-jar": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: exec-jar URL"));
		try {
		    execjar = new Resource(env.rel.resolve(new URI(expand(words[1], env))), env.val);
		} catch(URISyntaxException e) {
		    throw(new RuntimeException("usage: exec-jar URL", e));
		}
		break;
	    }
	    case "include": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: include URL"));
		try {
		    include.add(new Resource(env.rel.resolve(new URI(expand(words[1], env))), env.val));
		} catch(URISyntaxException e) {
		    throw(new RuntimeException("usage: include URL", e));
		}
		break;
	    }
	    case "class-path": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: classpath URL"));
		try {
		    classpath.add(new Resource(env.rel.resolve(new URI(expand(words[1], env))), env.val));
		} catch(URISyntaxException e) {
		    throw(new RuntimeException("usage: classpath URL", e));
		}
		break;
	    }
	    case "property": {
		if(words.length < 3)
		    throw(new RuntimeException("usage: property NAME VALUE"));
		sysprops.put(expand(words[1], env), expand(words[2], env));
		break;
	    }
	    case "heap-size": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: heap-size MBYTES"));
		try {
		    heapsize = Integer.parseInt(expand(words[1], env));
		} catch(NumberFormatException e) {
		    throw(new RuntimeException("usage: heap-size MBYTES", e));
		}
		break;
	    }
	    case "jvm-arg": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: jvm-arg ARG..."));
		for(int i = 1; i < words.length; i++)
		    jvmargs.add(expand(words[i], env));
		break;
	    }
	    case "arguments": {
		if(words.length < 2)
		    throw(new RuntimeException("usage: arguments ARG..."));
		for(int i = 1; i < words.length; i++)
		    cmdargs.add(expand(words[i], env));
		break;
	    }
	    case "native-lib": {
		if(words.length < 4)
		    throw(new RuntimeException("usage: native-lib OS ARCH URL"));
		try {
		    Pattern os = Pattern.compile(words[1], Pattern.CASE_INSENSITIVE);
		    Pattern arch = Pattern.compile(words[2], Pattern.CASE_INSENSITIVE);
		    Resource lib = new Resource(env.rel.resolve(new URI(expand(words[3], env))), env.val);
		    libraries.add(new NativeLib(os, arch, lib));
		} catch(PatternSyntaxException | URISyntaxException e) {
		    throw(new RuntimeException("usage: native-lib OS ARCH URL", e));
		}
		break;
	    }
	    case "set": {
		if(words.length < 3)
		    throw(new RuntimeException("usage: set VARIABLE VALUE"));
		Map<String, String> par = new HashMap<>(env.par);
		par.put(expand(words[1], env), expand(words[2], env));
		env.par(par);
		break;
	    }
	    case "when": {
		when(words, env);
		break;
	    }
	    }
    }

    public void read(Reader in, Environment env) throws IOException {
	BufferedReader fp = new BufferedReader(in);
	for(String ln = fp.readLine(); ln != null; ln = fp.readLine()) {
	    if((ln.length() > 0) && (ln.charAt(0) == '#'))
		continue;
	    command(Utils.splitwords(ln), env);
	}
    }
}
