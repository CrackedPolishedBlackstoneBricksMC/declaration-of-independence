package agency.highlysuspect.declarationofindependence;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.objectweb.asm.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DeclarationOfIndependence {
	public static void main(String[] args) throws Exception {
		
		List<Mod> mods = new ArrayList<>(args.length);
		
		try {
			for(String arg : args) {
				ZipFile zf = new ZipFile(Paths.get(arg).toAbsolutePath().toFile());
				Mod mod = modOrNull(zf);
				
				if(mod == null) {
					System.out.println("not a mod: " + zf.getName());
					zf.close();
					continue;
				}
				
				mods.add(mod);
			}
			
			//parse all mods
			for(Mod mod : mods) mod.parse();
			
			//who defines what?
			Map<String, List<Mod>> whoDefinesWhat = new HashMap<>();
			for(Mod mod : mods)
				for(String def : mod.definedClasses)
					whoDefinesWhat.computeIfAbsent(def, __ -> new ArrayList<>(2)).add(mod);
			
			//check that all usages are declared
			for(Mod mod : mods)
				for(String use : mod.usedClasses)
					for(Mod definingMod : whoDefinesWhat.getOrDefault(use, List.of()))
						if(mod.deps.contains(definingMod.modid)) {
//							System.out.println("mod " + mod.modid + " uses class " + use + " from " + definingMod.modid);
						} else {
							System.out.println("UNDECLARED mod " + mod.modid + " uses class " + use + " from " + definingMod.modid);
						}
			
			//report results
			//for(Mod mod : mods) mod.show();
		} finally {
			for(Mod mod : mods) {
				mod.close();
			}
		}
	}
	
	private static Mod modOrNull(ZipFile zf) throws IOException {
		ZipEntry fmjEntry = zf.getEntry("fabric.mod.json");
		if(fmjEntry == null) return null;
		
		try(Reader fmjStreamReader = new InputStreamReader(zf.getInputStream(fmjEntry), StandardCharsets.UTF_8)) {
			JsonObject fmj = new Gson().fromJson(fmjStreamReader, JsonObject.class);
//			System.out.println(fmj);
			JsonElement modidE = fmj.get("id");
			if(modidE == null || !modidE.isJsonPrimitive()) return null;
			
			Set<String> deps = new HashSet<>();
			JsonElement dependsE = fmj.get("depends");
			if(dependsE != null) {
				JsonObject depends = dependsE.getAsJsonObject();
				deps.addAll(depends.keySet());
			}
			
			String modid = modidE.getAsString();
			return new Mod(modid, deps, zf);
		}
	}
}

class Mod implements Closeable {
	public Mod(String modid, Set<String> deps, ZipFile zf) {
		this.modid = modid;
		this.deps = deps;
		this.zf = zf;
	}
	
	final String modid;
	final Set<String> deps;
	final ZipFile zf;
	final Set<String> definedClasses = new HashSet<>();
	final Set<String> usedClasses = new HashSet<>();
	
	@Override
	public void close() throws IOException {
		zf.close();
	}
	
	void parse() throws IOException {
		System.out.println("parsing " + zf.getName());
		
		for(ZipEntry e : new Enumeratorable<>(zf.entries())) {
			if(e.getName().endsWith(".class") && !e.getName().endsWith("module-info.class")) {
				try(InputStream in = zf.getInputStream(e)) {
					ClassReader reader = new ClassReader(in.readAllBytes());
					reader.accept(new Visitor(), 0);
				}
			}
		}
		
		//if you define a class yourself, you're allowed to use it
		usedClasses.removeAll(definedClasses);
	}
	
	void show() {
		for(String def : definedClasses) System.out.println("Mod " + modid + " defines " + def);
		for(String use : usedClasses) System.out.println("Mod " + modid + " uses " + use);
		System.out.println();
	}
	
	private void useDesc(String desc) {
		int l = 0;
		while((l = desc.indexOf('L', l)) != -1) {
			int semi = desc.indexOf(';', l);
			if(semi == -1) throw new IllegalArgumentException(desc);
			String aawawaw = desc.substring(l + 1, semi);
			//System.out.println("DESC " + aawawaw);
			usedClasses.add(aawawaw);
			l = semi;
		}
	}
	
	class Visitor extends ClassVisitor implements Opcodes {
		public Visitor() {
			super(ASM9);
		}
		
		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			definedClasses.add(name);
			usedClasses.add(superName);
			if(interfaces != null) Collections.addAll(usedClasses, interfaces);
		}
		
		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			useDesc(descriptor);
			return null;
		}
		
		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			useDesc(descriptor);
			return new MthVisitor();
		}
	}
	
	class MthVisitor extends MethodVisitor implements Opcodes {
		public MthVisitor() {
			super(ASM9);
		}
		
		@Override
		public void visitTypeInsn(int opcode, String type) {
			usedClasses.add(type);
		}
		
		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
			usedClasses.add(owner);
			useDesc(descriptor);
		}
		
		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
			usedClasses.add(owner);
			useDesc(descriptor);
		}
		
		@Override
		public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
			useDesc(descriptor);
			useDesc(bootstrapMethodHandle.getDesc());
			//todo args?
		}
		
		@Override
		public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
			useDesc(descriptor);
		}
		
		@Override
		public void visitLdcInsn(Object value) {
			if(value instanceof Type t && t.getSort() == Type.OBJECT) useDesc(t.getDescriptor());
		}
	}
}

class Enumeratorable<T> implements Iterable<T> {
	public Enumeratorable(Enumeration<T> e) {
		this.e = e;
	}
	
	private final Enumeration<T> e;
	
	@Override
	public Iterator<T> iterator() {
		return e.asIterator();
	}
}
