package agency.highlysuspect.declarationofindependence;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DeclarationOfIndependence {
	public static void main(String[] args) throws Exception {
		
		List<Mod> jars = new ArrayList<>(args.length);
		
		try {
			for(String arg : args) {
				Path path = Paths.get(arg);
				ZipInputStream zin = new ZipInputStream(Files.newInputStream(path));
				Mod mod = new Mod(zin, path.getFileName().toString(), null);
				jars.add(mod);
			}
			
			//parse all mods; "newMods" holds discovered nested jijs
			List<Mod> jarsToParse = new ArrayList<>(jars);
			List<Mod> mods = new ArrayList<>();
			while(!jarsToParse.isEmpty()) {
				System.out.println("parsing " + jarsToParse.size() + " jars");
				List<Mod> newJars = new ArrayList<>();
				for(Mod mod : jarsToParse) {
					mod.parse(newJars::add);
					
					//was this actually a mod? (contained fmj)
					if(mod.modids.isEmpty()) {
						System.out.println(mod.path + " is not a known mod type");
					} else mods.add(mod);
				}
				jarsToParse = newJars;
			}
			
			for(Mod mod : mods) mod.addImplicitJijDeps();
			
			System.out.println("total mods: " + mods.size());
			
			//deduplicate
			Set<String> modids = new HashSet<>();
			for(Mod mod : mods) modids.addAll(mod.modids);
			//TODO pick the one with the highest version/or match fabric's resolver
			List<Mod> dedupeMods = new ArrayList<>(modids.size());
			for(String modid : modids) dedupeMods.add(mods.stream().filter(it -> it.modids.contains(modid)).findFirst().get());
			
			System.out.println("deduplicated: " + dedupeMods.size());
			
			//transitive deps (TODO this algorithm is trash lol)
			Map<String, Mod> modsById = new HashMap<>();
			for(Mod mod : mods) for(String id : mod.modids) modsById.put(id, mod);
			boolean didAnything;
			do {
				didAnything = false;
				System.out.println("transitive deps pass...");
				for(Mod mod : mods) {
					for(String dep : new HashSet<>(mod.deps)) {
						Mod depMod = modsById.get(dep);
						if(depMod == null) continue;
						
						for(String depModDep : depMod.deps) {
							if(mod.deps.add(depModDep)) {
								didAnything = true;
//								System.out.println("added transitive " + depModDep + " to " + mod.displayId());
							}
						}
						
						//also fill out aliases
						for(String depModAlias : depMod.modids) {
							if(mod.deps.add(depModAlias)) {
								didAnything = true;
//								System.out.println("added transitive " + depModAlias + " to " + mod.displayId());
							}
						}
					}
				}
			} while(didAnything);
			
			//who defines what?
			Map<String, List<Mod>> whoDefinesWhat = new HashMap<>();
			for(Mod mod : dedupeMods)
				for(String def : mod.definedClasses)
					whoDefinesWhat.computeIfAbsent(def, __ -> new ArrayList<>(2)).add(mod);
			
			System.out.println(whoDefinesWhat.keySet().size() + " total classes");
			
			//check that all usages are declared
			for(Mod mod : dedupeMods)
				for(String use : mod.usedClasses)
					for(Mod definingMod : whoDefinesWhat.getOrDefault(use, List.of()))
						for(String definingModId : definingMod.modids)
							if(mod.deps.contains(definingModId)) {
//							System.out.println("mod " + mod.modid + " uses class " + use + " from " + definingMod.modid);
						} else {
							System.out.println("UNDECLARED mod " + mod.displayId() + " uses class " + use + " from " + definingMod.displayId());
						}
		} finally {
			for(Mod mod : jars) mod.close();
		}
	}
}

class Mod implements Closeable {
	public Mod(ZipInputStream zin, String path, Mod parent) {
		this.zin = zin;
		this.path = path;
		this.parent = parent;
	}
	
	final ZipInputStream zin;
	final Mod parent;
	final String path;
	final Set<String> modids = new HashSet<>(); //including aliases / "provides"
	final Set<String> deps = new HashSet<>();
	final Set<String> definedClasses = new HashSet<>();
	final Set<String> usedClasses = new HashSet<>();
	
	private List<Mod> nestedMods = new ArrayList<>();
	
	@Override
	public void close() throws IOException {
		zin.close();
	}
	
	public String displayId() {
		return String.join(",", modids);
	}
	
	void parse(Consumer<Mod> moreMods) throws IOException {
		ZipEntry e;
		while((e = zin.getNextEntry()) != null) {
			if(e.isDirectory()) continue;
			
			//class
			if(e.getName().endsWith(".class") && !e.getName().endsWith("module-info.class")) {
				ClassReader reader = new ClassReader(zin.readAllBytes());
				reader.accept(new Visitor(), 0);
				continue;
			}
			
			//nested jar
			if(e.getName().endsWith(".jar")) {
				ZipInputStream sub = new ZipInputStream(zin);
				Mod subMod = new Mod(sub, this.path + "!" + e.getName(), this);
				moreMods.accept(subMod);
				subMod.parse(moreMods);
				nestedMods.add(subMod);
				continue;
			}
			
			//metadata
			if(e.getName().equals("fabric.mod.json")) {
				JsonObject fmj = new Gson().fromJson(new InputStreamReader(zin), JsonObject.class);
				JsonElement modidE = fmj.get("id");
				if(modidE == null || !modidE.isJsonPrimitive()) continue;
				modids.add(modidE.getAsString());
				
				JsonElement dependsE = fmj.get("depends");
				if(dependsE != null) {
					JsonObject depends = dependsE.getAsJsonObject();
					deps.addAll(depends.keySet());
				}
				
				JsonElement providesE = fmj.get("provides");
				if(providesE != null) {
					JsonArray provides = providesE.getAsJsonArray();
					modids.addAll(provides.asList().stream().map(JsonElement::getAsString).toList());
				}
				
//				System.out.println(path + " is " + modid + ", deps: " + String.join(", ", deps));
			}
		}
		
		//if you define a class yourself, you're allowed to use it
		usedClasses.removeAll(definedClasses);
	}
	
	void addImplicitJijDeps() {
		for(Mod nested : nestedMods) {
			if(nested.modids.isEmpty()) continue;
			System.out.println("implicit dep: " + nested.displayId() + " <-> " + displayId());
			nested.deps.addAll(modids);
			deps.addAll(nested.modids);
		}
	}
	
	private void useDesc(String desc) {
		int l = 0;
		while((l = desc.indexOf('L', l)) != -1) {
			int semi = desc.indexOf(';', l);
			if(semi == -1) throw new IllegalArgumentException(desc);
			usedClasses.add(desc.substring(l + 1, semi));
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
