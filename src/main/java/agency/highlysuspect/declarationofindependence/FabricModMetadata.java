package agency.highlysuspect.declarationofindependence;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FabricModMetadata implements ModMetadata {
	public FabricModMetadata(Set<String> modids, Set<String> deps) {
		this.modids = modids;
		this.deps = deps;
	}
	
	final Set<String> modids;
	final Set<String> deps;
	
	@Override
	public Set<String> getModIds() {
		return modids;
	}
	
	@Override
	public Set<String> getDeps() {
		return deps;
	}
	
	public static FabricModMetadata parse(InputStream in) {
		JsonObject fmj = new Gson().fromJson(new InputStreamReader(in), JsonObject.class);
		JsonElement modidE = fmj.get("id");
		if(modidE == null || !modidE.isJsonPrimitive()) return null;
		
		Set<String> modids = new HashSet<>();
		Set<String> deps = new HashSet<>();
		
		modids.add(modidE.getAsString());
		
		//"provides" aliases
		JsonElement providesE = fmj.get("provides");
		if(providesE != null) {
			JsonArray provides = providesE.getAsJsonArray();
			modids.addAll(provides.asList().stream().map(JsonElement::getAsString).toList());
		}
		
		//deps
		JsonElement dependsE = fmj.get("depends");
		if(dependsE != null) {
			JsonObject depends = dependsE.getAsJsonObject();
			deps.addAll(depends.keySet());
		}
		
		List.of("minecraft", "java", "fabricloader").forEach(deps::remove); //dgaf bro!!
		
		return new FabricModMetadata(modids, deps);
	}
}
