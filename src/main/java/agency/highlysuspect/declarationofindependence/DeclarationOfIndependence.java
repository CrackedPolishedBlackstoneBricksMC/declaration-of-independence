package agency.highlysuspect.declarationofindependence;

import com.google.gson.Gson;
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
		
		List<Mod> mods = new ArrayList<>(args.length);
		
		try {
			for(String arg : args) {
				Path path = Paths.get(arg);
				ZipInputStream zin = new ZipInputStream(Files.newInputStream(path));
				Mod mod = new Mod(zin, path.getFileName().toString(), null);
				mods.add(mod);
			}
			
			//parse all mods; "newMods" holds discovered nested jijs
			List<Mod> modsToParse = new ArrayList<>(mods);
			while(!modsToParse.isEmpty()) {
				System.out.println("parsing " + modsToParse.size() + " mods");
				List<Mod> newMods = new ArrayList<>();
				for(Mod mod : modsToParse) mod.parse(newMods::add);
				mods.addAll(newMods);
				modsToParse = newMods;
			}
			
			mods.removeIf(mod -> {
				if(mod.modid == null) {
					System.out.println(mod.path + " is not a mod");
					return true;
				}
				return false;
			});
			
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
		} finally {
			for(Mod mod : mods) {
				mod.close();
			}
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
	String modid;
	final Set<String> deps = new HashSet<>();
	final Set<String> definedClasses = new HashSet<>();
	final Set<String> usedClasses = new HashSet<>();
	
	@Override
	public void close() throws IOException {
		zin.close();
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
				continue;
			}
			
			//metadata
			if(e.getName().equals("fabric.mod.json")) {
				JsonObject fmj = new Gson().fromJson(new InputStreamReader(zin), JsonObject.class);
				JsonElement modidE = fmj.get("id");
				if(modidE == null || !modidE.isJsonPrimitive()) continue;
				modid = modidE.getAsString();
				
				JsonElement dependsE = fmj.get("depends");
				if(dependsE != null) {
					JsonObject depends = dependsE.getAsJsonObject();
					deps.addAll(depends.keySet());
				}
				
				System.out.println(path + " is " + modid + ", deps: " + String.join(", ", deps));
			}
		}
		
		//if you define a class yourself, you're allowed to use it
		usedClasses.removeAll(definedClasses);
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
