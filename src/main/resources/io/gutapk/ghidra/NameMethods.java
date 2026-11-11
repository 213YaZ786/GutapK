// Names every IL2CPP method of GutapK's methods.tsv in the program, as
// Il2CppDumper's script names them from its script.json. Measurement only.
// Ports:
// https://github.com/Perfare/Il2CppDumper/blob/master/Il2CppDumper/ghidra_with_struct.py#get_addr
// https://github.com/Perfare/Il2CppDumper/blob/master/Il2CppDumper/ghidra_with_struct.py#set_name
// https://github.com/Perfare/Il2CppDumper/blob/master/Il2CppDumper/ghidra_with_struct.py#make_function
// https://github.com/Perfare/Il2CppDumper/blob/master/Il2CppDumper/ghidra_with_struct.py#L90-L98 (ScriptMethod names)
// https://github.com/Perfare/Il2CppDumper/blob/master/Il2CppDumper/ghidra_with_struct.py#L139-L146 (Addresses made functions)
// https://github.com/Perfare/Il2CppDumper/blob/master/Il2CppDumper/Outputs/StructGenerator.cs#L96 (typeName$$methodName)
// Not ported: set_type, set_sig (methods.tsv holds no C signatures nor il2cpp.h types),
// ScriptString, ScriptMetadata, ScriptMetadataMethod (methods.tsv holds no strings nor metadata),
// askFile (headless: the file comes as the script's argument)
// get_addr takes an RVA, methods.tsv holds file offsets: Ghidra's Memory maps them.
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;

public class NameMethods extends GhidraScript {

	private static final SourceType USER_DEFINED = SourceType.USER_DEFINED;

	private Address get_addr(long offset) {
		List<Address> found = currentProgram.getMemory().locateAddressesForFileOffset(offset);
		return found.isEmpty() ? null : found.get(0);
	}

	private void set_name(Address addr, String name) {
		try {
			name = name.replace(' ', '-');
			createLabel(addr, name, true, USER_DEFINED);
		}
		catch (Exception e) {
			println("set_name() Failed.");
		}
	}

	private void make_function(Address start) {
		Function func = getFunctionAt(start);
		if (func == null) {
			try {
				createFunction(start, null);
			}
			catch (Exception e) {
				println("Warning: Unable to create function");
			}
		}
	}

	@Override
	public void run() throws Exception {
		long t0 = System.nanoTime();
		List<String> lines = Files.readAllLines(Path.of(getScriptArgs()[0]), StandardCharsets.UTF_8);
		monitor.initialize(lines.size());
		monitor.setMessage("Methods");
		int named = 0;
		int missed = 0;
		for (String line : lines) {
			String[] f = line.split("\t");
			if (f.length < 7) {
				continue;
			}
			Address addr = get_addr(Long.parseLong(f[5], 16));
			if (addr == null) {
				missed++;
				continue;
			}
			String member = f[3];
			int paren = member.indexOf('(');
			String head = paren < 0 ? member : member.substring(0, paren).trim();
			String methodName = head.substring(head.lastIndexOf(' ') + 1);
			String typeName = f[1].isEmpty() ? f[2] : f[1] + "." + f[2];
			make_function(addr);
			set_name(addr, typeName + "$$" + methodName);
			named++;
			monitor.incrementProgress(1);
		}
		println("named " + named + ", not mapped " + missed + ", " + (System.nanoTime() - t0) / 1_000_000 + " ms");
	}
}
