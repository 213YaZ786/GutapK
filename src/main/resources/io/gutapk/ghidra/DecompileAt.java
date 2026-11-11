// Writes the C of the function at one file offset of libil2cpp.so to a file.
// Args: <file offset in hex> <out file>. A failure is written as a first line
// starting with "ERROR " so GutapK can tell it from C.
// Ports:
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/Ghidra/Features/Decompiler/ghidra_scripts/ShowCCallsScript.java#setUpDecompiler
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/Ghidra/Features/Decompiler/ghidra_scripts/ShowCCallsScript.java#L147-L170
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/Ghidra/Features/Decompiler/src/main/java/ghidra/app/util/exporter/CppExporter.java#L547-L590
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/Ghidra/Features/Decompiler/src/main/java/ghidra/app/decompiler/component/DecompilerUtils.java#L44-L62
// https://github.com/Perfare/Il2CppDumper/blob/master/Il2CppDumper/ghidra_with_struct.py#get_addr
// Not ported: ShowCCallsScript run, analyzeFunction, printCall, toString (call sites, not needed),
// CppExporter export and the parallel queue (one function per run here)
// Not ported: decompileFunction (its one call is inlined in run, the error goes to the out file)
// Not ported: doWork (inlined in run, only the C text is kept)
// Not ported: DecompileOptions (the type is used as it is, not redefined)
// Not ported: PcodeOps (a CppExporter detail of its written file, not wanted)
// get_addr takes an RVA, GutapK holds file offsets: Ghidra's Memory maps them.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import ghidra.app.decompiler.*;
import ghidra.app.decompiler.component.DecompilerUtils;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;

public class DecompileAt extends GhidraScript {

	private Address get_addr(long offset) {
		List<Address> found = currentProgram.getMemory().locateAddressesForFileOffset(offset);
		return found.isEmpty() ? null : found.get(0);
	}

	private void write(Path out, String text) throws Exception {
		Files.writeString(out, text, StandardCharsets.UTF_8);
	}

	@Override
	public void run() throws Exception {
		String[] args = getScriptArgs();
		Path out = Path.of(args[1]);
		DecompInterface decomplib = setUpDecompiler(currentProgram);
		try {
			if (!decomplib.openProgram(currentProgram)) {
				write(out, "ERROR Decompile Error: " + decomplib.getLastMessage());
				return;
			}
			Address addr = get_addr(Long.parseLong(args[0], 16));
			if (addr == null) {
				write(out, "ERROR No address at file offset 0x" + args[0]);
				return;
			}
			Function func = getFunctionAt(addr);
			if (func == null) {
				func = getFunctionContaining(addr);
			}
			if (func == null) {
				write(out, "ERROR No Function at address " + addr);
				return;
			}
			DecompileResults dr =
				decomplib.decompileFunction(func, decomplib.getOptions().getDefaultTimeout(), monitor);
			String errorMessage = dr.getErrorMessage();
			if (!"".equals(errorMessage)) {
				write(out, "ERROR " + errorMessage);
				return;
			}
			DecompiledFunction decompiledFunction = dr.getDecompiledFunction();
			write(out, decompiledFunction.getC());
		}
		finally {
			decomplib.dispose();
		}
	}

	private DecompInterface setUpDecompiler(Program program) {
		DecompileOptions options = DecompilerUtils.getDecompileOptions(state.getTool(), program);
		DecompInterface decomplib = new DecompInterface();
		decomplib.setOptions(options);
		decomplib.toggleCCode(true);
		decomplib.toggleSyntaxTree(true);
		decomplib.setSimplificationStyle("decompile");
		return decomplib;
	}
}
