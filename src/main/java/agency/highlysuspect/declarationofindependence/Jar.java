package agency.highlysuspect.declarationofindependence;

import org.objectweb.asm.*;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Jar implements Closeable {
	public Jar(ZipInputStream zin, String path, Jar parent) {
		this.zin = zin;
		this.path = path;
		this.parent = parent;
	}
	
	public Jar(ZipInputStream zin, String path) {
		this(zin, path, null);
	}
	
	final ZipInputStream zin;
	final String path;
	final Jar parent;
	
	boolean parsed = false;
	
	final Set<String> definedClasses = new HashSet<>();
	final Set<String> usedClasses = new HashSet<>();
	final List<ModMetadata> metadata = new ArrayList<>(1);
	
	private final List<Jar> containedJars = new ArrayList<>();
	
	@Override
	public void close() throws IOException {
		zin.close();
	}
	
	public Set<String> getAllModids() {
		if(!parsed) throw new IllegalStateException("can't access modids before parsing jar!");
		return metadata.stream().flatMap(mm -> mm.getModIds().stream()).collect(Collectors.toSet());
	}
	
	public Set<String> getAllDeps() {
		if(!parsed) throw new IllegalStateException("can't access deps before parsing jar!");
		//nested jars should depend on their parent, parents should depend on all their nested jars
		Set<String> result = new HashSet<>(findAllDepsHere());
		findAllDepsDown(result);
		findAllDepsUp(result);
		return result;
	}
	
	private Set<String> findAllDepsHere() {
		return metadata.stream().flatMap(mm -> mm.getDeps().stream()).collect(Collectors.toSet());
	}
	
	private void findAllDepsDown(Set<String> addTo) {
		containedJars.forEach(jar -> {
			addTo.addAll(jar.findAllDepsHere());
			jar.findAllDepsDown(addTo);
		});
	}
	
	private void findAllDepsUp(Set<String> addTo) {
		if(parent != null) {
			addTo.addAll(parent.getAllModids());
			parent.findAllDepsUp(addTo);
		}
	}
	
	public boolean isMod() {
		return !metadata.isEmpty();
	}
	
	public void filterNonMods() {
		containedJars.removeIf(jar -> !jar.isMod());
		containedJars.forEach(Jar::filterNonMods);
	}
	
	public void accept(Consumer<Jar> action) {
		action.accept(this);
		for(Jar jar : containedJars) jar.accept(action);
	}
	
	void parse(Consumer<Jar> nestedJarConsumer) throws IOException {
		if(parsed) return;
		
		ZipEntry e;
		while((e = zin.getNextEntry()) != null) {
			if(e.isDirectory()) {
				zin.closeEntry();
				continue;
			}
			
			//class
			if(e.getName().endsWith(".class") && !e.getName().endsWith("module-info.class")) {
				ClassReader reader = new ClassReader(zin.readAllBytes());
				reader.accept(new ClsVisitor(), 0);
				zin.closeEntry();
				continue;
			}
			
			//nested jar
			if(e.getName().endsWith(".jar")) {
				Jar subJar = new Jar(new NonClosingZipInputStream(zin), this.path + "!" + e.getName(), this);
				subJar.parse(nestedJarConsumer); //recurse
				
				this.containedJars.add(subJar); //keep track of this nested jar
				nestedJarConsumer.accept(subJar); //tell super about this nested jar
				zin.closeEntry();
				continue;
			}
			
			//metadata
			if(e.getName().equals("fabric.mod.json")) {
				FabricModMetadata mm = FabricModMetadata.parse(zin);
				if(mm != null) metadata.add(mm);
				zin.closeEntry();
				continue;
			}
		}
		
		//you're trivially allowed to use classes from your own mod
		//so just don't worry about them
		usedClasses.removeAll(definedClasses);
		
		parsed = true;
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
	
	class ClsVisitor extends ClassVisitor implements Opcodes {
		public ClsVisitor() {
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
